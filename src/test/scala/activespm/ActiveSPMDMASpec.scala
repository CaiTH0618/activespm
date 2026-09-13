package activespm

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.{VerilatorBackendAnnotation, VerilatorFlags}
import firrtl2.options.TargetDirAnnotation
import freechips.rocketchip.diplomacy.{AddressSet, IdRange}
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import org.scalatest.flatspec.AnyFlatSpec
import java.util.concurrent.atomic.AtomicInteger

private case class DMATransfer(
  direction: ActiveSPMDirection.Type,
  externalAddress: BigInt,
  localOffset: BigInt,
  byteCount: BigInt,
  expectedError: ActiveSPMErrorCode.Type = ActiveSPMErrorCode.none,
  expectedBytesCompleted: BigInt = -1)

private class ActiveSPMDMAPatternHarness(
  params: ActiveSPMParams,
  externalBeatBytes: Int,
  transfer: DMATransfer,
  externalInitial: Seq[BigInt],
  externalExpected: Seq[BigInt],
  localInitial: Seq[BigInt],
  localExpected: Seq[BigInt])(implicit p: Parameters) extends LazyModule {

  private val externalBase = params.externalMemoryRanges.head.base
  private val externalRAM = LazyModule(new TLRAM(
    params.externalMemoryRanges.head,
    cacheable = false,
    executable = false,
    atomics = false,
    beatBytes = externalBeatBytes))
  private val scratchpad = LazyModule(new ActiveSPMScratchpad(params))
  private val dma = LazyModule(new ActiveSPMDMA(params))

  private def patterns(base: BigInt, beatBytes: Int, words: Seq[BigInt], read: Boolean): Seq[Pattern] =
    words.zipWithIndex.map { case (word, index) =>
      val address = base + index * beatBytes
      if (read) ReadExpectPattern(address, log2Ceil(beatBytes), word)
      else WritePattern(address, log2Ceil(beatBytes), word)
    }

  private val externalInit = LazyModule(new TLPatternPusher(
    "external-init", patterns(externalBase, externalBeatBytes, externalInitial, read = false)))
  private val externalVerify = LazyModule(new TLPatternPusher(
    "external-verify", patterns(externalBase, externalBeatBytes, externalExpected, read = true)))
  private val localInit = LazyModule(new TLPatternPusher(
    "local-init", patterns(params.scratchpadAddress.base, params.spadBeatBytes, localInitial, read = false)))
  private val localVerify = LazyModule(new TLPatternPusher(
    "local-verify", patterns(params.scratchpadAddress.base, params.spadBeatBytes, localExpected, read = true)))

  private val externalXbar = TLXbar()
  externalRAM.node := externalXbar
  externalXbar := externalInit.node
  externalXbar := externalVerify.node
  externalXbar := TLDelayer(0.1) := dma.externalNode

  private val globalXbar = TLXbar()
  scratchpad.globalNode := globalXbar
  globalXbar := localInit.node
  globalXbar := localVerify.node
  scratchpad.localNode := TLDelayer(0.1) := dma.localNode

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val start = IO(Input(Bool()))
    val finished = IO(Output(Bool()))

    private val sInit :: sRequest :: sWait :: sVerify :: sDone :: Nil = Enum(5)
    private val state = RegInit(sInit)
    private val completionDelay = RegInit(0.U(4.W))
    private val sawBusy = RegInit(false.B)

    externalInit.module.io.run := state === sInit && start
    localInit.module.io.run := state === sInit && start
    externalVerify.module.io.run := state === sVerify
    localVerify.module.io.run := state === sVerify

    dma.module.control.request.valid := state === sRequest
    dma.module.control.request.bits.transferDirection := transfer.direction
    dma.module.control.request.bits.externalAddress := transfer.externalAddress.U
    dma.module.control.request.bits.localOffset := transfer.localOffset.U
    dma.module.control.request.bits.byteCount := transfer.byteCount.U
    dma.module.control.completion.ready := state === sWait && completionDelay === 7.U

    when(dma.module.control.progress.busy) { sawBusy := true.B }

    when(state === sInit && externalInit.module.io.done && localInit.module.io.done) {
      state := sRequest
    }
    when(state === sRequest && dma.module.control.request.fire) {
      state := sWait
    }
    when(state === sWait && completionDelay =/= 7.U) {
      completionDelay := completionDelay + 1.U
    }
    when(state === sWait && dma.module.control.completion.valid) {
      assert(dma.module.control.completion.bits.errorCode === transfer.expectedError)
      val expectedBytes = if (transfer.expectedBytesCompleted >= 0)
        transfer.expectedBytesCompleted else transfer.byteCount
      assert(dma.module.control.completion.bits.bytesCompleted === expectedBytes.U)
      when(completionDelay =/= 7.U) {
        assert(!dma.module.control.request.ready,
          "ActiveSPM DMA accepted a request while completion was backpressured")
      }
    }
    when(state === sWait && dma.module.control.completion.fire) {
      if (transfer.byteCount > 0 && transfer.expectedError == ActiveSPMErrorCode.none) {
        assert(sawBusy, "ActiveSPM nonzero valid DMA request never reported busy")
      }
      state := sVerify
    }
    when(state === sVerify && externalVerify.module.io.done && localVerify.module.io.done) {
      state := sDone
    }
    finished := state === sDone
  }
}

private class ActiveSPMDMAPatternTop(
  params: ActiveSPMParams,
  externalBeatBytes: Int,
  transfer: DMATransfer,
  externalInitial: Seq[BigInt],
  externalExpected: Seq[BigInt],
  localInitial: Seq[BigInt],
  localExpected: Seq[BigInt],
  topName: String)(implicit p: Parameters) extends Module {

  override val desiredName = topName
  val start = IO(Input(Bool()))
  val finished = IO(Output(Bool()))
  private val harness = Module(LazyModule(new ActiveSPMDMAPatternHarness(
    params, externalBeatBytes, transfer, externalInitial, externalExpected,
    localInitial, localExpected)).module)
  harness.start := start
  finished := harness.finished
}

private class TLResponseFault(faultAt: Int, denied: Boolean, corrupt: Boolean)
    (implicit p: Parameters) extends LazyModule {
  val node = TLAdapterNode(managerFn = port => port.v2copy(
    slaves = port.slaves.map(_.v2copy(mayDenyGet = true, mayDenyPut = true))))
  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _) = node.in.head
    val (out, _) = node.out.head
    out <> in

    val responseCount = RegInit(0.U(16.W))
    val inject = out.d.valid && responseCount === faultAt.U
    in.d.bits.denied := out.d.bits.denied || (inject && denied.B)
    in.d.bits.corrupt := out.d.bits.corrupt || (inject && corrupt.B) ||
      (inject && denied.B && out.d.bits.opcode === TLMessages.AccessAckData)
    when(out.d.fire) { responseCount := responseCount + 1.U }
  }
}

private class ActiveSPMDMAControlHarness(
  params: ActiveSPMParams,
  externalBeatBytes: Int,
  externalFault: Option[(Int, Boolean, Boolean)] = None,
  localFault: Option[(Int, Boolean, Boolean)] = None)(implicit p: Parameters) extends LazyModule {
  private val dma = LazyModule(new ActiveSPMDMA(params))
  private val externalRAM = LazyModule(new TLRAM(
    AddressSet(params.externalMemoryRanges.head.base, 0x1fffL),
    cacheable = false,
    executable = false,
    atomics = false,
    beatBytes = externalBeatBytes))
  private val scratchpad = LazyModule(new ActiveSPMScratchpad(params))
  private val unusedGlobal = LazyModule(new TLPatternPusher(
    "unused-global", Seq(ReadPattern(params.scratchpadAddress.base, log2Ceil(params.spadBeatBytes)))))

  externalFault match {
    case Some((at, denied, corrupt)) =>
      val injector = LazyModule(new TLResponseFault(at, denied, corrupt))
      externalRAM.node := injector.node := dma.externalNode
    case None => externalRAM.node := dma.externalNode
  }
  localFault match {
    case Some((at, denied, corrupt)) =>
      val injector = LazyModule(new TLResponseFault(at, denied, corrupt))
      scratchpad.localNode := injector.node := dma.localNode
    case None => scratchpad.localNode := dma.localNode
  }
  scratchpad.globalNode := unusedGlobal.node

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val control = IO(new ActiveSPMDMAControlIO)
    unusedGlobal.module.io.run := false.B

    private val externalMasters = dma.externalNode.out.head._2.master.masters
    private val localMasters = dma.localNode.out.head._2.master.masters
    require(externalMasters.size == 1)
    require(externalMasters.head.name == params.dmaNodeName)
    require(externalMasters.head.sourceId == IdRange(0, 1))
    require(externalMasters.head.visibility == params.externalMemoryRanges)
    require(localMasters.size == 1)
    require(localMasters.head.name == params.localNodeName)
    require(localMasters.head.sourceId == IdRange(0, 1))
    require(localMasters.head.visibility == Seq(params.scratchpadAddress))

    dma.module.control.request.valid := control.request.valid
    dma.module.control.request.bits := control.request.bits
    control.request.ready := dma.module.control.request.ready
    control.completion.valid := dma.module.control.completion.valid
    control.completion.bits := dma.module.control.completion.bits
    dma.module.control.completion.ready := control.completion.ready
    control.progress := dma.module.control.progress
  }
}

private class ActiveSPMDMAControlTop(
  params: ActiveSPMParams,
  externalBeatBytes: Int,
  externalFault: Option[(Int, Boolean, Boolean)],
  localFault: Option[(Int, Boolean, Boolean)],
  topName: String)(implicit p: Parameters) extends Module {
  override val desiredName = topName
  val control = IO(new ActiveSPMDMAControlIO)
  private val harness = Module(LazyModule(new ActiveSPMDMAControlHarness(
    params, externalBeatBytes, externalFault, localFault)).module)
  harness.control <> control
}

class ActiveSPMDMASpec extends AnyFlatSpec with ChiselScalatestTester {
  private implicit val p: Parameters = Parameters.empty
  private val nextTopId = new AtomicInteger
  private val externalBase = BigInt(0x80000000L)
  private val localBase = BigInt(0x70000000L)
  private val memoryBytes = 128

  private def params: ActiveSPMParams = ActiveSPMParams(
    id = 0,
    controlAddress = AddressSet(0x10050000L, 0xfffL),
    scratchpadAddress = AddressSet(localBase, 0xfffL),
    spadBeatBytes = 8,
    nBanks = 4,
    externalMemoryRanges = Seq(AddressSet(externalBase, 0xfffL)))

  private def words(bytes: Seq[Int], beatBytes: Int): Seq[BigInt] =
    bytes.grouped(beatBytes).map { beat =>
      beat.zipWithIndex.foldLeft(BigInt(0)) { case (word, (byte, lane)) =>
        word | (BigInt(byte & 0xff) << (8 * lane))
      }
    }.toSeq

  private def runTransfer(
    externalBeatBytes: Int,
    direction: ActiveSPMDirection.Type,
    externalOffset: Int,
    localOffset: Int,
    count: Int): Unit = {
    val externalInitial = Seq.tabulate(memoryBytes)(i => (0x31 + i * 7) & 0xff)
    val localInitial = Seq.tabulate(memoryBytes)(i => (0xc7 - i * 5) & 0xff)
    val externalExpected = externalInitial.toArray
    val localExpected = localInitial.toArray
    if (direction == ActiveSPMDirection.load) {
      Array.copy(externalInitial.toArray, externalOffset, localExpected, localOffset, count)
    } else {
      Array.copy(localInitial.toArray, localOffset, externalExpected, externalOffset, count)
    }
    val transfer = DMATransfer(
      direction, externalBase + externalOffset, localOffset, count)
    val topName = s"ActiveSPMDMAPatternTop${nextTopId.getAndIncrement()}"
    test(new ActiveSPMDMAPatternTop(
      params, externalBeatBytes, transfer,
      words(externalInitial, externalBeatBytes), words(externalExpected.toIndexedSeq, externalBeatBytes),
      words(localInitial, 8), words(localExpected.toIndexedSeq, 8), topName))
      .withAnnotations(Seq(
        VerilatorBackendAnnotation,
        TargetDirAnnotation(s"generators/activespm/target/chiseltest/$topName"),
        VerilatorFlags(Seq(
          "--output-split", "100000000", "--output-split-cfuncs", "100000000")))) { dut =>
        dut.start.poke(true.B)
        var cycles = 0
        while (!dut.finished.peek().litToBoolean && cycles < 20000) {
          dut.clock.step()
          cycles += 1
        }
        assert(dut.finished.peek().litToBoolean, s"DMA transfer timed out after $cycles cycles")
      }
  }

  private def annotations(topName: String) = Seq(
    VerilatorBackendAnnotation,
    TargetDirAnnotation(s"generators/activespm/target/chiseltest/$topName"),
    VerilatorFlags(Seq("--output-split", "100000000", "--output-split-cfuncs", "100000000")))

  private def issue(
    dut: ActiveSPMDMAControlTop,
    direction: ActiveSPMDirection.Type,
    externalAddress: BigInt,
    localOffset: BigInt,
    byteCount: BigInt,
    expectedError: ActiveSPMErrorCode.Type,
    expectedBytes: BigInt): Unit = {
    dut.control.request.bits.transferDirection.poke(direction)
    dut.control.request.bits.externalAddress.poke(externalAddress.U)
    dut.control.request.bits.localOffset.poke(localOffset.U)
    dut.control.request.bits.byteCount.poke(byteCount.U)
    dut.control.request.valid.poke(true.B)
    while (!dut.control.request.ready.peek().litToBoolean) { dut.clock.step() }
    dut.clock.step()
    dut.control.request.valid.poke(false.B)
    dut.control.completion.ready.poke(false.B)
    var cycles = 0
    while (!dut.control.completion.valid.peek().litToBoolean && cycles < 5000) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.control.completion.valid.peek().litToBoolean, s"DMA completion timed out after $cycles cycles")
    dut.control.completion.bits.errorCode.expect(expectedError)
    dut.control.completion.bits.bytesCompleted.expect(expectedBytes.U)
    dut.control.request.ready.expect(false.B)
    for (_ <- 0 until 4) {
      dut.control.completion.bits.errorCode.expect(expectedError)
      dut.control.completion.bits.bytesCompleted.expect(expectedBytes.U)
      dut.clock.step()
    }
    dut.control.completion.ready.poke(true.B)
    dut.clock.step()
    dut.control.completion.ready.poke(false.B)
    dut.control.request.ready.expect(true.B)
  }

  "ActiveSPMDMA" should "copy arbitrary byte-aligned data in both directions" in {
    runTransfer(8, ActiveSPMDirection.load, externalOffset = 3, localOffset = 11, count = 37)
    runTransfer(8, ActiveSPMDirection.store, externalOffset = 13, localOffset = 5, count = 41)
  }

  it should "realign data when external and scratchpad beat widths differ" in {
    runTransfer(16, ActiveSPMDirection.load, externalOffset = 15, localOffset = 7, count = 49)
    runTransfer(16, ActiveSPMDirection.store, externalOffset = 6, localOffset = 3, count = 53)
    runTransfer(4, ActiveSPMDirection.load, externalOffset = 3, localOffset = 6, count = 31)
    runTransfer(4, ActiveSPMDirection.store, externalOffset = 5, localOffset = 7, count = 29)
  }

  it should "handle zero-length requests without changing either memory" in {
    runTransfer(8, ActiveSPMDirection.load, externalOffset = 127, localOffset = 127, count = 0)
  }

  it should "reject invalid descriptors with deterministic error precedence" in {
    val validationParams = params.copy(externalMemoryRanges = Seq(
      AddressSet(externalBase, 0xffL), AddressSet(externalBase + 0x100, 0xffL)))
    val topName = s"ActiveSPMDMAControlTop${nextTopId.getAndIncrement()}"
    test(new ActiveSPMDMAControlTop(validationParams, 8, None, None, topName))
      .withAnnotations(annotations(topName)) { dut =>
        dut.control.request.valid.poke(false.B)
        dut.control.completion.ready.poke(false.B)
        dut.clock.step(2)
        issue(dut, ActiveSPMDirection.load, BigInt("ffffffffffffffff", 16),
          BigInt("ffffffffffffffff", 16), 0, ActiveSPMErrorCode.none, 0)
        issue(dut, ActiveSPMDirection.load, BigInt("ffffffffffffffff", 16),
          0, 2, ActiveSPMErrorCode.addressOverflow, 0)
        issue(dut, ActiveSPMDirection.load, externalBase,
          BigInt("ffffffffffffffff", 16), 2, ActiveSPMErrorCode.addressOverflow, 0)
        issue(dut, ActiveSPMDirection.load, externalBase,
          0xfff, 2, ActiveSPMErrorCode.localRange, 0)
        issue(dut, ActiveSPMDirection.load, externalBase + 0xf8,
          0, 16, ActiveSPMErrorCode.externalRange, 0)
        issue(dut, ActiveSPMDirection.load, externalBase - 1,
          0, 1, ActiveSPMErrorCode.externalRange, 0)

        dut.control.request.bits.transferDirection.poke(ActiveSPMDirection.load)
        dut.control.request.bits.externalAddress.poke(externalBase.U)
        dut.control.request.bits.localOffset.poke(0.U)
        dut.control.request.bits.byteCount.poke(64.U)
        dut.control.request.valid.poke(true.B)
        while (!dut.control.request.ready.peek().litToBoolean) { dut.clock.step() }
        dut.clock.step()
        dut.control.request.valid.poke(false.B)
        dut.control.progress.busy.expect(true.B)
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)
        dut.control.progress.busy.expect(false.B)
        dut.control.progress.bytesCompleted.expect(0.U)
        dut.control.completion.valid.expect(false.B)
        dut.control.request.ready.expect(true.B)
      }
  }

  it should "report source and destination TileLink failures" in {
    def runFault(
      direction: ActiveSPMDirection.Type,
      externalFault: Option[(Int, Boolean, Boolean)],
      localFault: Option[(Int, Boolean, Boolean)],
      expectedBytes: BigInt): Unit = {
      val topName = s"ActiveSPMDMAControlTop${nextTopId.getAndIncrement()}"
      test(new ActiveSPMDMAControlTop(
        params, 8, externalFault, localFault, topName)).withAnnotations(annotations(topName)) { dut =>
        dut.control.request.valid.poke(false.B)
        dut.control.completion.ready.poke(false.B)
        dut.clock.step(2)
        issue(dut, direction, externalBase, 0, 16,
          ActiveSPMErrorCode.tileLink, expectedBytes)
      }
    }

    runFault(ActiveSPMDirection.load,
      externalFault = Some((0, false, true)), localFault = None, expectedBytes = 0)
    runFault(ActiveSPMDirection.load,
      externalFault = Some((0, true, false)), localFault = None, expectedBytes = 0)
    runFault(ActiveSPMDirection.store,
      externalFault = Some((1, true, false)), localFault = None, expectedBytes = 8)
    runFault(ActiveSPMDirection.load,
      externalFault = None, localFault = Some((0, true, false)), expectedBytes = 0)
    runFault(ActiveSPMDirection.store,
      externalFault = None, localFault = Some((0, false, true)), expectedBytes = 0)
  }
}
