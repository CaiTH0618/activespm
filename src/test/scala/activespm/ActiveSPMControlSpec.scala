package activespm

import chisel3._
import chisel3.util.{Enum, log2Ceil}
import chiseltest._
import chiseltest.simulator.{VerilatorBackendAnnotation, VerilatorFlags}
import firrtl2.options.TargetDirAnnotation
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import org.scalatest.flatspec.AnyFlatSpec

import java.util.concurrent.atomic.AtomicInteger

private class ActiveSPMControlHarness(
  params: ActiveSPMParams,
  sequences: Seq[Seq[Pattern]])(implicit p: Parameters) extends LazyModule {

  private val control = LazyModule(new ActiveSPMControl(params))
  private val xbar = TLXbar()
  private val pushers = sequences.zipWithIndex.map { case (patterns, index) =>
    LazyModule(new TLPatternPusher(s"control-test-master-$index", patterns))
  }

  control.node := xbar
  pushers.foreach(pusher => xbar := pusher.node)

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val run = IO(Input(Vec(sequences.size, Bool())))
    val finished = IO(Output(Vec(sequences.size, Bool())))

    val requestReady = IO(Input(Bool()))
    val requestValid = IO(Output(Bool()))
    val requestDirection = IO(Output(UInt(1.W)))
    val requestExternalAddress = IO(Output(UInt(64.W)))
    val requestLocalOffset = IO(Output(UInt(64.W)))
    val requestByteCount = IO(Output(UInt(64.W)))

    val completionValid = IO(Input(Bool()))
    val completionError = IO(Input(ActiveSPMErrorCode()))
    val completionBytes = IO(Input(UInt(64.W)))
    val progressBusy = IO(Input(Bool()))
    val progressBytes = IO(Input(UInt(64.W)))

    pushers.zipWithIndex.foreach { case (pusher, index) =>
      pusher.module.io.run := run(index)
      finished(index) := pusher.module.io.done
    }

    control.module.dma.request.ready := requestReady
    requestValid := control.module.dma.request.valid
    requestDirection := control.module.dma.request.bits.transferDirection.asUInt
    requestExternalAddress := control.module.dma.request.bits.externalAddress
    requestLocalOffset := control.module.dma.request.bits.localOffset
    requestByteCount := control.module.dma.request.bits.byteCount

    control.module.dma.completion.valid := completionValid
    control.module.dma.completion.bits.errorCode := completionError
    control.module.dma.completion.bits.bytesCompleted := completionBytes
    control.module.dma.progress.busy := progressBusy
    control.module.dma.progress.bytesCompleted := progressBytes
  }
}

private case class MMIOTransfer(
  direction: ActiveSPMDirection.Type,
  externalAddress: BigInt,
  localOffset: BigInt,
  byteCount: BigInt,
  expectedError: ActiveSPMErrorCode.Type = ActiveSPMErrorCode.none,
  expectedBytesCompleted: BigInt = -1)

private class MMIOTLResponseFault(faultAt: Int, denied: Boolean, corrupt: Boolean)
    (implicit p: Parameters) extends LazyModule {
  val node = TLAdapterNode(managerFn = port => port.v2copy(
    slaves = port.slaves.map(_.v2copy(mayDenyGet = true, mayDenyPut = true))))

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _) = node.in.head
    val (out, _) = node.out.head
    out <> in

    private val responseCount = RegInit(0.U(16.W))
    private val inject = out.d.valid && responseCount === faultAt.U
    in.d.bits.denied := out.d.bits.denied || (inject && denied.B)
    in.d.bits.corrupt := out.d.bits.corrupt || (inject && corrupt.B) ||
      (inject && denied.B && out.d.bits.opcode === TLMessages.AccessAckData)
    when(out.d.fire) { responseCount := responseCount + 1.U }
  }
}

private class ActiveSPMMMIOPatternHarness(
  params: ActiveSPMParams,
  externalBeatBytes: Int,
  transfer: MMIOTransfer,
  externalInitial: Seq[BigInt],
  externalExpected: Seq[BigInt],
  localInitial: Seq[BigInt],
  localExpected: Seq[BigInt],
  fault: Option[(Int, Boolean, Boolean)])(implicit p: Parameters) extends LazyModule {

  private val externalBase = params.externalMemoryRanges.head.base
  private val control = LazyModule(new ActiveSPMControl(params))
  private val dma = LazyModule(new ActiveSPMDMA(params))
  private val scratchpad = LazyModule(new ActiveSPMScratchpad(params))
  private val externalRAM = LazyModule(new TLRAM(
    params.externalMemoryRanges.head,
    cacheable = false,
    executable = false,
    atomics = false,
    beatBytes = externalBeatBytes))

  private def memoryPatterns(
    base: BigInt,
    beatBytes: Int,
    words: Seq[BigInt],
    read: Boolean): Seq[Pattern] = words.zipWithIndex.map { case (word, index) =>
    val address = base + index * beatBytes
    if (read) ReadExpectPattern(address, log2Ceil(beatBytes), word)
    else WritePattern(address, log2Ceil(beatBytes), word)
  }

  private val externalInit = LazyModule(new TLPatternPusher(
    "mmio-external-init", memoryPatterns(externalBase, externalBeatBytes, externalInitial, read = false)))
  private val externalVerify = LazyModule(new TLPatternPusher(
    "mmio-external-verify", memoryPatterns(externalBase, externalBeatBytes, externalExpected, read = true)))
  private val localInit = LazyModule(new TLPatternPusher(
    "mmio-local-init", memoryPatterns(
      params.scratchpadAddress.base, params.spadBeatBytes, localInitial, read = false)))
  private val localVerify = LazyModule(new TLPatternPusher(
    "mmio-local-verify", memoryPatterns(
      params.scratchpadAddress.base, params.spadBeatBytes, localExpected, read = true)))

  private val commandValue = BigInt(1) |
    (if (transfer.direction == ActiveSPMDirection.store) BigInt(2) else BigInt(0))
  private val commandPatterns = Seq(
    WritePattern(params.controlAddress.base + ActiveSPMRegisters.externalAddressOffset,
      3, transfer.externalAddress),
    WritePattern(params.controlAddress.base + ActiveSPMRegisters.localOffsetOffset,
      3, transfer.localOffset),
    WritePattern(params.controlAddress.base + ActiveSPMRegisters.byteCountOffset,
      3, transfer.byteCount),
    WritePattern(params.controlAddress.base + ActiveSPMRegisters.commandOffset,
      3, commandValue))
  private val expectedBytes = if (transfer.expectedBytesCompleted >= 0)
    transfer.expectedBytesCompleted else transfer.byteCount
  private val expectedStatus = if (transfer.expectedError == ActiveSPMErrorCode.none) 2 else 4
  private val expectedError = transfer.expectedError.litValue
  private val statusPatterns = Seq(
    ReadExpectPattern(params.controlAddress.base + ActiveSPMRegisters.statusOffset,
      3, expectedStatus),
    ReadExpectPattern(params.controlAddress.base + ActiveSPMRegisters.bytesCompletedOffset,
      3, expectedBytes),
    ReadExpectPattern(params.controlAddress.base + ActiveSPMRegisters.errorCodeOffset,
      3, expectedError),
    WritePattern(params.controlAddress.base + ActiveSPMRegisters.statusOffset,
      3, expectedStatus & 6),
    ReadExpectPattern(params.controlAddress.base + ActiveSPMRegisters.statusOffset,
      3, 0),
    ReadExpectPattern(params.controlAddress.base + ActiveSPMRegisters.errorCodeOffset,
      3, 0))
  private val command = LazyModule(new TLPatternPusher("mmio-command", commandPatterns))
  private val status = LazyModule(new TLPatternPusher("mmio-status", statusPatterns))

  private val controlXbar = TLXbar()
  control.node := TLDelayer(0.1) := controlXbar
  controlXbar := command.node
  controlXbar := status.node

  private val externalXbar = TLXbar()
  externalRAM.node := externalXbar
  externalXbar := externalInit.node
  externalXbar := externalVerify.node
  fault match {
    case Some((at, denied, corrupt)) =>
      val injector = LazyModule(new MMIOTLResponseFault(at, denied, corrupt))
      externalXbar := injector.node := TLDelayer(0.1) := dma.externalNode
    case None =>
      externalXbar := TLDelayer(0.1) := dma.externalNode
  }

  private val globalXbar = TLXbar()
  scratchpad.globalNode := globalXbar
  globalXbar := localInit.node
  globalXbar := localVerify.node
  scratchpad.localNode := TLDelayer(0.1) := dma.localNode

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val start = IO(Input(Bool()))
    val finished = IO(Output(Bool()))

    control.module.dma.request <> dma.module.control.request
    control.module.dma.completion <> dma.module.control.completion
    control.module.dma.progress := dma.module.control.progress

    private val sInit :: sCommand :: sWait :: sVerify :: sDone :: Nil = Enum(5)
    private val state = RegInit(sInit)
    private val completionSeen = RegInit(false.B)

    externalInit.module.io.run := state === sInit && start
    localInit.module.io.run := state === sInit && start
    command.module.io.run := state === sCommand
    status.module.io.run := state === sVerify
    externalVerify.module.io.run := state === sVerify
    localVerify.module.io.run := state === sVerify

    when(control.module.dma.completion.fire) {
      completionSeen := true.B
    }
    when(state === sInit && externalInit.module.io.done && localInit.module.io.done) {
      state := sCommand
    }
    when(state === sCommand && command.module.io.done) {
      state := Mux(completionSeen || control.module.dma.completion.fire, sVerify, sWait)
    }
    when(state === sWait && completionSeen) {
      state := sVerify
    }
    when(state === sVerify && status.module.io.done &&
      externalVerify.module.io.done && localVerify.module.io.done) {
      state := sDone
    }
    finished := state === sDone
  }
}

private class ActiveSPMMMIOPatternTop(
  params: ActiveSPMParams,
  externalBeatBytes: Int,
  transfer: MMIOTransfer,
  externalInitial: Seq[BigInt],
  externalExpected: Seq[BigInt],
  localInitial: Seq[BigInt],
  localExpected: Seq[BigInt],
  fault: Option[(Int, Boolean, Boolean)],
  topName: String)(implicit p: Parameters) extends Module {

  override val desiredName = topName
  val start = IO(Input(Bool()))
  val finished = IO(Output(Bool()))
  private val harness = Module(LazyModule(new ActiveSPMMMIOPatternHarness(
    params, externalBeatBytes, transfer, externalInitial, externalExpected,
    localInitial, localExpected, fault)).module)
  harness.start := start
  finished := harness.finished
}

class ActiveSPMControlSpec extends AnyFlatSpec with ChiselScalatestTester {
  private implicit val p: Parameters = Parameters.empty
  private val nextTopId = new AtomicInteger
  private val controlBase = BigInt(0x10050000L)
  private val externalBase = BigInt(0x80000000L)
  private val memoryBytes = 64

  private val params = ActiveSPMParams(
    id = 0,
    controlAddress = AddressSet(controlBase, 0xfffL),
    scratchpadAddress = AddressSet(0x70000000L, 0xfffL),
    spadBeatBytes = 8,
    nBanks = 4,
    externalMemoryRanges = Seq(AddressSet(externalBase, 0xfffL)))

  private def write(offset: Int, data: BigInt): Pattern =
    WritePattern(controlBase + offset, 3, data)

  private def read(offset: Int, data: BigInt): Pattern =
    ReadExpectPattern(controlBase + offset, 3, data)

  private def annotations(topName: String) = Seq(
    VerilatorBackendAnnotation,
    TargetDirAnnotation(s"generators/activespm/target/chiseltest/$topName"),
    VerilatorFlags(Seq("--output-split", "100000000", "--output-split-cfuncs", "100000000")))

  private def words(bytes: Seq[Int], beatBytes: Int): Seq[BigInt] =
    bytes.grouped(beatBytes).map { beat =>
      beat.zipWithIndex.foldLeft(BigInt(0)) { case (word, (byte, lane)) =>
        word | (BigInt(byte & 0xff) << (8 * lane))
      }
    }.toSeq

  private def runMMIOTransfer(
    externalBeatBytes: Int,
    direction: ActiveSPMDirection.Type,
    externalAddress: BigInt,
    localOffset: BigInt,
    byteCount: BigInt,
    expectedError: ActiveSPMErrorCode.Type = ActiveSPMErrorCode.none,
    fault: Option[(Int, Boolean, Boolean)] = None): Unit = {
    val externalInitial = Seq.tabulate(memoryBytes)(i => (0x29 + i * 11) & 0xff)
    val localInitial = Seq.tabulate(memoryBytes)(i => (0xd3 - i * 7) & 0xff)
    val externalExpected = externalInitial.toArray
    val localExpected = localInitial.toArray
    if (expectedError == ActiveSPMErrorCode.none && byteCount > 0) {
      val externalOffset = (externalAddress - externalBase).toInt
      if (direction == ActiveSPMDirection.load) {
        Array.copy(externalInitial.toArray, externalOffset,
          localExpected, localOffset.toInt, byteCount.toInt)
      } else {
        Array.copy(localInitial.toArray, localOffset.toInt,
          externalExpected, externalOffset, byteCount.toInt)
      }
    }

    val transfer = MMIOTransfer(
      direction, externalAddress, localOffset, byteCount,
      expectedError, if (expectedError == ActiveSPMErrorCode.none) byteCount else 0)
    val topName = s"ActiveSPMMMIOPatternTop${nextTopId.getAndIncrement()}"
    test(new ActiveSPMMMIOPatternTop(
      params, externalBeatBytes, transfer,
      words(externalInitial, externalBeatBytes),
      words(externalExpected.toIndexedSeq, externalBeatBytes),
      words(localInitial, params.spadBeatBytes),
      words(localExpected.toIndexedSeq, params.spadBeatBytes),
      fault,
      topName)).withAnnotations(annotations(topName)) { dut =>
        dut.start.poke(true.B)
        var cycles = 0
        while (!dut.finished.peek().litToBoolean && cycles < 30000) {
          dut.clock.step()
          cycles += 1
        }
        assert(dut.finished.peek().litToBoolean,
          s"MMIO-driven DMA transfer timed out after $cycles cycles")
      }
  }

  private def initialize(dut: ActiveSPMControlHarness#Impl, sequenceCount: Int): Unit = {
    for (index <- 0 until sequenceCount) dut.run(index).poke(false.B)
    dut.requestReady.poke(false.B)
    dut.completionValid.poke(false.B)
    dut.completionError.poke(ActiveSPMErrorCode.none)
    dut.completionBytes.poke(0.U)
    dut.progressBusy.poke(false.B)
    dut.progressBytes.poke(0.U)
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
  }

  private def runSequence(
    dut: ActiveSPMControlHarness#Impl,
    index: Int,
    timeout: Int = 2000): Unit = {
    dut.run(index).poke(true.B)
    var cycles = 0
    while (!dut.finished(index).peek().litToBoolean && cycles < timeout) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.finished(index).peek().litToBoolean,
      s"control MMIO sequence $index timed out after $cycles cycles")
    dut.run(index).poke(false.B)
    dut.clock.step()
  }

  "ActiveSPMControl" should "implement descriptor, request, sticky status, and W1C semantics" in {
    val firstExternal = BigInt(0x80000013L)
    val secondExternal = BigInt(0x80000022L)
    val sequences = Seq(
      Seq(
        read(ActiveSPMRegisters.commandOffset, 0),
        read(ActiveSPMRegisters.externalAddressOffset, 0),
        read(ActiveSPMRegisters.localOffsetOffset, 0),
        read(ActiveSPMRegisters.byteCountOffset, 0),
        read(ActiveSPMRegisters.statusOffset, 0),
        read(ActiveSPMRegisters.bytesCompletedOffset, 0),
        read(ActiveSPMRegisters.errorCodeOffset, 0)),
      Seq(
        write(ActiveSPMRegisters.externalAddressOffset, firstExternal),
        write(ActiveSPMRegisters.localOffsetOffset, 11),
        write(ActiveSPMRegisters.byteCountOffset, 19),
        write(ActiveSPMRegisters.commandOffset, 2),
        read(ActiveSPMRegisters.statusOffset, 0),
        write(ActiveSPMRegisters.commandOffset, 3),
        read(ActiveSPMRegisters.statusOffset, 1)),
      Seq(
        write(ActiveSPMRegisters.externalAddressOffset, secondExternal),
        write(ActiveSPMRegisters.localOffsetOffset, 7),
        write(ActiveSPMRegisters.byteCountOffset, 23),
        write(ActiveSPMRegisters.commandOffset, 3),
        read(ActiveSPMRegisters.externalAddressOffset, secondExternal),
        read(ActiveSPMRegisters.localOffsetOffset, 7),
        read(ActiveSPMRegisters.byteCountOffset, 23),
        read(ActiveSPMRegisters.statusOffset, 5),
        read(ActiveSPMRegisters.bytesCompletedOffset, 0),
        read(ActiveSPMRegisters.errorCodeOffset, 1)),
      Seq(
        read(ActiveSPMRegisters.statusOffset, 5),
        read(ActiveSPMRegisters.bytesCompletedOffset, 13),
        read(ActiveSPMRegisters.errorCodeOffset, 1)),
      Seq(
        read(ActiveSPMRegisters.statusOffset, 6),
        read(ActiveSPMRegisters.bytesCompletedOffset, 19),
        read(ActiveSPMRegisters.errorCodeOffset, 1),
        write(ActiveSPMRegisters.statusOffset, 2),
        read(ActiveSPMRegisters.statusOffset, 4),
        write(ActiveSPMRegisters.statusOffset, 4),
        read(ActiveSPMRegisters.statusOffset, 0),
        read(ActiveSPMRegisters.errorCodeOffset, 0)),
      Seq(
        write(ActiveSPMRegisters.commandOffset, 1),
        write(ActiveSPMRegisters.commandOffset, 1),
        read(ActiveSPMRegisters.statusOffset, 5),
        read(ActiveSPMRegisters.errorCodeOffset, 1)),
      Seq(
        read(ActiveSPMRegisters.statusOffset, 4),
        read(ActiveSPMRegisters.bytesCompletedOffset, 7),
        read(ActiveSPMRegisters.errorCodeOffset, 5)))

    val topName = s"ActiveSPMControlSpec${nextTopId.getAndIncrement()}"
    test(LazyModule(new ActiveSPMControlHarness(params, sequences)).module)
      .withAnnotations(annotations(topName)) { dut =>
        initialize(dut, sequences.size)
        runSequence(dut, 0)
        runSequence(dut, 1)

        dut.requestValid.expect(true.B)
        dut.requestDirection.expect(1.U)
        dut.requestExternalAddress.expect(firstExternal.U)
        dut.requestLocalOffset.expect(11.U)
        dut.requestByteCount.expect(19.U)

        runSequence(dut, 2)
        for (_ <- 0 until 5) {
          dut.requestValid.expect(true.B)
          dut.requestDirection.expect(1.U)
          dut.requestExternalAddress.expect(firstExternal.U)
          dut.requestLocalOffset.expect(11.U)
          dut.requestByteCount.expect(19.U)
          dut.clock.step()
        }

        dut.requestReady.poke(true.B)
        dut.clock.step()
        dut.requestReady.poke(false.B)
        dut.requestValid.expect(false.B)

        dut.progressBusy.poke(true.B)
        dut.progressBytes.poke(13.U)
        runSequence(dut, 3)

        dut.progressBusy.poke(false.B)
        dut.completionError.poke(ActiveSPMErrorCode.none)
        dut.completionBytes.poke(19.U)
        dut.completionValid.poke(true.B)
        dut.clock.step()
        dut.completionValid.poke(false.B)
        runSequence(dut, 4)

        runSequence(dut, 5)
        dut.requestValid.expect(true.B)
        dut.requestDirection.expect(0.U)
        dut.requestExternalAddress.expect(secondExternal.U)
        dut.requestLocalOffset.expect(7.U)
        dut.requestByteCount.expect(23.U)
        dut.requestReady.poke(true.B)
        dut.clock.step()
        dut.requestReady.poke(false.B)

        dut.completionError.poke(ActiveSPMErrorCode.tileLink)
        dut.completionBytes.poke(7.U)
        dut.completionValid.poke(true.B)
        dut.clock.step()
        dut.completionValid.poke(false.B)
        runSequence(dut, 6)
      }
  }

  it should "abort a pending request and clear all registers on reset" in {
    val sequences = Seq(
      Seq(
        write(ActiveSPMRegisters.externalAddressOffset, 0x80000040L),
        write(ActiveSPMRegisters.localOffsetOffset, 16),
        write(ActiveSPMRegisters.byteCountOffset, 32),
        write(ActiveSPMRegisters.commandOffset, 1)),
      Seq(
        read(ActiveSPMRegisters.externalAddressOffset, 0),
        read(ActiveSPMRegisters.localOffsetOffset, 0),
        read(ActiveSPMRegisters.byteCountOffset, 0),
        read(ActiveSPMRegisters.statusOffset, 0),
        read(ActiveSPMRegisters.bytesCompletedOffset, 0),
        read(ActiveSPMRegisters.errorCodeOffset, 0)))

    val topName = s"ActiveSPMControlSpec${nextTopId.getAndIncrement()}"
    test(LazyModule(new ActiveSPMControlHarness(params, sequences)).module)
      .withAnnotations(annotations(topName)) { dut =>
        initialize(dut, sequences.size)
        runSequence(dut, 0)
        dut.requestValid.expect(true.B)
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)
        dut.requestValid.expect(false.B)
        runSequence(dut, 1)
      }
  }

  it should "launch real load and store transfers through MMIO" in {
    runMMIOTransfer(8, ActiveSPMDirection.load,
      externalBase + 3, localOffset = 11, byteCount = 29)
    runMMIOTransfer(8, ActiveSPMDirection.store,
      externalBase + 13, localOffset = 5, byteCount = 31)
    runMMIOTransfer(16, ActiveSPMDirection.load,
      externalBase + 15, localOffset = 7, byteCount = 33)
  }

  it should "report zero-length and descriptor validation results through MMIO" in {
    runMMIOTransfer(8, ActiveSPMDirection.load,
      BigInt("ffffffffffffffff", 16),
      BigInt("ffffffffffffffff", 16), 0)
    runMMIOTransfer(8, ActiveSPMDirection.load,
      externalBase, localOffset = 0xfff, byteCount = 2,
      expectedError = ActiveSPMErrorCode.localRange)
    runMMIOTransfer(8, ActiveSPMDirection.load,
      BigInt("ffffffffffffffff", 16), localOffset = 0, byteCount = 2,
      expectedError = ActiveSPMErrorCode.addressOverflow)
    runMMIOTransfer(8, ActiveSPMDirection.load,
      externalBase - 1, localOffset = 0, byteCount = 1,
      expectedError = ActiveSPMErrorCode.externalRange)
  }

  it should "report a TileLink response failure through MMIO" in {
    runMMIOTransfer(8, ActiveSPMDirection.load,
      externalBase, localOffset = 0, byteCount = 16,
      expectedError = ActiveSPMErrorCode.tileLink,
      fault = Some((0, false, true)))
  }
}
