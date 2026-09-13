package activespm

import chisel3._
import chisel3.util.Cat
import freechips.rocketchip.regmapper.{RegField, RegReadFn, RegWriteFn}
import freechips.rocketchip.resources.SimpleDevice
import freechips.rocketchip.tilelink.{TLAdapterNode, TLRegisterNode}
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.ValName
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}

/** Polling MMIO control block for ActiveSPM.
  *
  * Software-visible descriptor registers are snapshotted when START is written
  * while idle. The resulting DMA request is then held stable until accepted.
  * Completion and error status remain sticky until cleared by W1C writes or a
  * subsequent command is started.
  */
class ActiveSPMControl(params: ActiveSPMParams)(implicit p: Parameters) extends LazyModule {
  // DTS node names cannot contain the brackets required by the NoC mapping key.
  private val device = new SimpleDevice(s"activespm-ctrl-${params.id}", Seq("caitianhao,activespm-control"))

  private val registerNode = TLRegisterNode(
    address = Seq(params.controlAddress),
    device = device,
    beatBytes = ActiveSPMRegisters.accessBytes,
    undefZero = true)(ValName("registers"))

  // TLRegisterNode derives its manager name from the containing LazyModule.
  // Rename the exported manager explicitly so Constellation sees the stable key.
  val node = TLAdapterNode(
    clientFn = identity,
    managerFn = port => port.v2copy(
      slaves = port.slaves.map(_.v2copy(name = Some(params.controlNodeName)))))(ValName(params.controlNodeName))
  registerNode := node

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val dma = IO(new ActiveSPMControlDMAIO)

    // This adapter changes only negotiated metadata; the TileLink channels
    // themselves are an unmodified pass-through to the register node.
    (node.in zip node.out).foreach { case ((in, _), (out, _)) => out <> in }

    private val externalAddress = RegInit(0.U(64.W))
    private val localOffset = RegInit(0.U(64.W))
    private val byteCount = RegInit(0.U(64.W))

    private val activeDirection = RegInit(ActiveSPMDirection.load)
    private val activeExternalAddress = RegInit(0.U(64.W))
    private val activeLocalOffset = RegInit(0.U(64.W))
    private val activeByteCount = RegInit(0.U(64.W))
    private val requestPending = RegInit(false.B)
    private val requestAccepted = RegInit(false.B)

    private val busy = RegInit(false.B)
    private val done = RegInit(false.B)
    private val error = RegInit(false.B)
    private val finalBytesCompleted = RegInit(0.U(64.W))
    private val errorCode = RegInit(ActiveSPMErrorCode.none)

    private val commandWrite = WireDefault(false.B)
    private val commandData = WireDefault(0.U(64.W))
    private val statusWrite = WireDefault(false.B)
    private val statusData = WireDefault(0.U(64.W))

    private val commandWriteFn = RegWriteFn { (valid, data) =>
      commandWrite := valid
      commandData := data
      true.B
    }
    private val statusWriteFn = RegWriteFn { (valid, data) =>
      statusWrite := valid
      statusData := data
      true.B
    }

    dma.request.valid := requestPending
    dma.request.bits.transferDirection := activeDirection
    dma.request.bits.externalAddress := activeExternalAddress
    dma.request.bits.localOffset := activeLocalOffset
    dma.request.bits.byteCount := activeByteCount
    dma.completion.ready := true.B

    private val liveBytesCompleted = Mux(requestAccepted, dma.progress.bytesCompleted, 0.U)
    private val visibleBytesCompleted = Mux(busy, liveBytesCompleted, finalBytesCompleted)
    private val status = Cat(0.U(61.W), error, done, busy)

    when(dma.request.fire) {
      requestPending := false.B
      requestAccepted := true.B
    }

    // Track acknowledged destination progress while the command is active.
    when(busy && requestAccepted) {
      finalBytesCompleted := dma.progress.bytesCompleted
    }

    // STATUS.DONE and STATUS.ERROR are write-one-to-clear. New status events
    // below intentionally have priority over a simultaneous clear.
    when(statusWrite) {
      when(statusData(ActiveSPMRegisters.statusDoneBit)) {
        done := false.B
      }
      when(statusData(ActiveSPMRegisters.statusErrorBit)) {
        error := false.B
        errorCode := ActiveSPMErrorCode.none
      }
    }

    when(commandWrite && commandData(ActiveSPMRegisters.commandStartBit)) {
      when(busy) {
        // Reject the new command without disturbing the request in flight.
        error := true.B
        errorCode := ActiveSPMErrorCode.busy
      }.otherwise {
        activeDirection := commandData(ActiveSPMRegisters.commandDirectionBit)
          .asTypeOf(ActiveSPMDirection())
        activeExternalAddress := externalAddress
        activeLocalOffset := localOffset
        activeByteCount := byteCount
        requestPending := true.B
        requestAccepted := false.B
        busy := true.B
        done := false.B
        error := false.B
        finalBytesCompleted := 0.U
        errorCode := ActiveSPMErrorCode.none
      }
    }

    when(dma.completion.fire) {
      assert(busy && requestAccepted,
        "ActiveSPM control received a DMA completion without an accepted command")
      busy := false.B
      requestPending := false.B
      requestAccepted := false.B
      finalBytesCompleted := dma.completion.bits.bytesCompleted
      when(dma.completion.bits.errorCode === ActiveSPMErrorCode.none) {
        done := true.B
      }.otherwise {
        error := true.B
        errorCode := dma.completion.bits.errorCode
      }
    }

    when(busy && requestAccepted) {
      assert(dma.progress.bytesCompleted <= activeByteCount,
        "ActiveSPM DMA progress exceeds the active request length")
    }
    when(dma.completion.valid && busy && requestAccepted) {
      assert(dma.completion.bits.bytesCompleted <= activeByteCount,
        "ActiveSPM DMA completion exceeds the active request length")
    }

    // These registers deliberately occupy complete 64-bit fields. Software is
    // required to access the block with aligned 64-bit operations.
    private val externalAddressField = RegField(
      64, RegReadFn(externalAddress), RegWriteFn(externalAddress))
    private val localOffsetField = RegField(
      64, RegReadFn(localOffset), RegWriteFn(localOffset))
    private val byteCountField = RegField(
      64, RegReadFn(byteCount), RegWriteFn(byteCount))

    registerNode.regmap(
      ActiveSPMRegisters.commandOffset -> Seq(RegField.w(64, commandWriteFn)),
      ActiveSPMRegisters.externalAddressOffset -> Seq(externalAddressField),
      ActiveSPMRegisters.localOffsetOffset -> Seq(localOffsetField),
      ActiveSPMRegisters.byteCountOffset -> Seq(byteCountField),
      ActiveSPMRegisters.statusOffset -> Seq(
        RegField(64, RegReadFn(status), statusWriteFn)),
      ActiveSPMRegisters.bytesCompletedOffset -> Seq(
        RegField.r(64, RegReadFn(visibleBytesCompleted))),
      ActiveSPMRegisters.errorCodeOffset -> Seq(
        RegField.r(64, RegReadFn(errorCode.asUInt.pad(64)))))
  }
}
