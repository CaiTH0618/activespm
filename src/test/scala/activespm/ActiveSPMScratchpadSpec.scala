package activespm

import chisel3._
import chiseltest._
import chiseltest.simulator.{VerilatorBackendAnnotation, VerilatorFlags}
import freechips.rocketchip.diplomacy.{AddressSet, RegionType, TransferSizes}
import freechips.rocketchip.tilelink._
import firrtl2.options.TargetDirAnnotation
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import org.scalatest.flatspec.AnyFlatSpec
import java.util.concurrent.atomic.AtomicInteger

private case class MaskedWritePattern(address: BigInt, size: Int, data: BigInt, mask: BigInt) extends Pattern {
  def bits(edge: TLEdgeOut): (Bool, TLBundleA) =
    edge.Put(0.U, address.U, size.U, data.U, mask.U)
}

private class ActiveSPMScratchpadPatternHarness(
  params: ActiveSPMParams,
  globalPattern: Seq[Pattern],
  localPattern: Seq[Pattern],
  globalFirst: Boolean,
  concurrent: Boolean)(implicit p: Parameters) extends LazyModule {

  private val scratchpad = LazyModule(new ActiveSPMScratchpad(params))
  private val global = LazyModule(new TLPatternPusher("global-test-master", globalPattern))
  private val local = LazyModule(new TLPatternPusher("local-test-master", localPattern))

  scratchpad.globalNode := TLDelayer(0.15) := global.node
  scratchpad.localNode := TLDelayer(0.15) := local.node

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val start = IO(Input(Bool()))
    val finished = IO(Output(Bool()))

    private def validateManager(edge: TLEdgeIn, expectedName: String): Unit = {
      val managers = edge.manager.managers
      require(managers.size == 1, s"$expectedName must be one aggregated manager")
      val manager = managers.head
      require(manager.name == expectedName, s"expected manager $expectedName, got ${manager.name}")
      require(manager.address == Seq(params.scratchpadAddress))
      require(manager.regionType == RegionType.IDEMPOTENT)
      require(!manager.executable)
      require(manager.fifoId.isEmpty)
      require(manager.supportsGet == TransferSizes(1, params.spadBeatBytes))
      require(manager.supportsPutFull == TransferSizes(1, params.spadBeatBytes))
      require(manager.supportsPutPartial == TransferSizes(1, params.spadBeatBytes))
      require(manager.supportsAcquireT.none && manager.supportsAcquireB.none)
      require(manager.supportsArithmetic.none && manager.supportsLogical.none && manager.supportsHint.none)
    }

    validateManager(scratchpad.globalNode.in.head._2, params.scratchpadNodeName)
    validateManager(scratchpad.localNode.in.head._2, params.localNodeName)

    val globalRun = if (concurrent || globalFirst) start else local.module.io.done
    val localRun = if (concurrent || !globalFirst) start else global.module.io.done
    global.module.io.run := globalRun
    local.module.io.run := localRun
    finished := global.module.io.done && local.module.io.done
  }
}

private class ActiveSPMScratchpadPatternTop(
  params: ActiveSPMParams,
  globalPattern: Seq[Pattern],
  localPattern: Seq[Pattern],
  globalFirst: Boolean,
  concurrent: Boolean,
  topName: String)(implicit p: Parameters) extends Module {

  override val desiredName = topName

  val start = IO(Input(Bool()))
  val finished = IO(Output(Bool()))
  private val harness = Module(LazyModule(new ActiveSPMScratchpadPatternHarness(
    params, globalPattern, localPattern, globalFirst, concurrent)).module)
  harness.start := start
  finished := harness.finished
}

class ActiveSPMScratchpadSpec extends AnyFlatSpec with ChiselScalatestTester {
  private val nextTopId = new AtomicInteger
  private implicit val p: Parameters = Parameters.empty
  private val beatBytes = 8
  private val beatSize = 3
  private val base = BigInt(0x70000000L)

  private def params(nBanks: Int = 4): ActiveSPMParams = ActiveSPMParams(
    id = 0,
    controlAddress = AddressSet(0x10050000L, 0xfffL),
    scratchpadAddress = AddressSet(base, 0xfffL),
    spadBeatBytes = beatBytes,
    nBanks = nBanks,
    externalMemoryRanges = Seq(AddressSet(0x80000000L, 0xffffL)))

  private def run(
    globalPattern: Seq[Pattern],
    localPattern: Seq[Pattern],
    nBanks: Int = 4,
    globalFirst: Boolean = true,
    concurrent: Boolean = false,
    resetDuringRun: Boolean = false): Unit = {

    val topName = s"ActiveSPMScratchpadPatternTop${nextTopId.getAndIncrement()}"
    test(new ActiveSPMScratchpadPatternTop(
      params(nBanks), globalPattern, localPattern, globalFirst, concurrent, topName))
      .withAnnotations(Seq(
        VerilatorBackendAnnotation,
        TargetDirAnnotation(s"generators/activespm/target/chiseltest/$topName"),
        VerilatorFlags(Seq("--output-split", "0", "--output-split-cfuncs", "0")))) { dut =>
        dut.start.poke(true.B)
        if (resetDuringRun) {
          dut.clock.step(5)
          dut.reset.poke(true.B)
          dut.clock.step(2)
          dut.reset.poke(false.B)
        }
        var cycles = 0
        while (!dut.finished.peek().litToBoolean && cycles < 10000) {
          dut.clock.step()
          cycles += 1
        }
        assert(dut.finished.peek().litToBoolean, s"scratchpad pattern timed out after $cycles cycles")
      }
  }

  private def masked(oldValue: BigInt, newValue: BigInt, mask: Int): BigInt =
    (0 until beatBytes).foldLeft(oldValue) { case (value, lane) =>
      if (((mask >> lane) & 1) == 0) value
      else {
        val laneMask = BigInt(0xff) << (8 * lane)
        (value & ~laneMask) | (newValue & laneMask)
      }
    }

  "ActiveSPMScratchpad" should "share full writes between global and local paths" in {
    val offsets = Seq(0x00, 0x08, 0x10, 0x18, 0x20, 0xff8)
    val values = offsets.indices.map(i => BigInt("1020304050607080", 16) + i)
    run(
      offsets.zip(values).map { case (offset, data) => WritePattern(base + offset, beatSize, data) },
      offsets.zip(values).map { case (offset, data) => ReadExpectPattern(base + offset, beatSize, data) })

    run(
      Seq(ReadExpectPattern(base + 0x80, beatSize, BigInt("8877665544332211", 16))),
      Seq(WritePattern(base + 0x80, beatSize, BigInt("8877665544332211", 16))),
      nBanks = 1,
      globalFirst = false)
  }

  it should "preserve every byte excluded by a partial-write mask" in {
    val oldValue = BigInt("1122334455667788", 16)
    val newValue = BigInt("aabbccddeeff0011", 16)
    val masks = Seq(0x01, 0x0f, 0xf0, 0xa5, 0xff)
    val addresses = masks.indices.map(i => base + 0x100 + i * beatBytes)
    val writes = addresses.zip(masks).flatMap { case (address, mask) => Seq(
      WritePattern(address, beatSize, oldValue),
      MaskedWritePattern(address, beatSize, newValue, mask))
    }
    val reads = addresses.zip(masks).map { case (address, mask) =>
      ReadExpectPattern(address, beatSize, masked(oldValue, newValue, mask))
    }
    run(reads, writes, globalFirst = false)
  }

  it should "complete concurrent traffic to distinct and contended banks" in {
    def traffic(firstOffset: Int, stride: Int, tag: Int): Seq[Pattern] =
      (0 until 12).flatMap { i =>
        val address = base + firstOffset + i * stride
        val data = (BigInt(tag) << 56) | i
        Seq(WritePattern(address, beatSize, data), ReadExpectPattern(address, beatSize, data))
      }

    run(traffic(0x000, 0x20, 1), traffic(0x008, 0x20, 2), concurrent = true)
    run(traffic(0x400, 0x40, 3), traffic(0x420, 0x40, 4), concurrent = true)
  }

  it should "return to an idle protocol state after reset during traffic" in {
    val global = (0 until 8).flatMap { i =>
      val address = base + i * 0x20
      Seq(WritePattern(address, beatSize, i + 1), ReadExpectPattern(address, beatSize, i + 1))
    }
    val local = (0 until 8).flatMap { i =>
      val address = base + 0x200 + i * 0x20
      Seq(WritePattern(address, beatSize, i + 17), ReadExpectPattern(address, beatSize, i + 17))
    }
    run(global, local, concurrent = true, resetDuringRun = true)
  }
}
