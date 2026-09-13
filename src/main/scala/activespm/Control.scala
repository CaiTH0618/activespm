package activespm

import chisel3._
import freechips.rocketchip.regmapper.RegField
import freechips.rocketchip.resources.SimpleDevice
import freechips.rocketchip.tilelink.{TLAdapterNode, TLRegisterNode}
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.ValName
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}

/** MMIO/control shell for ActiveSPM.
  *
  * This development stage exposes reserved, zero-reading register fields only.
  * It deliberately emits no DMA request.
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

    dma.request.valid := false.B
    dma.request.bits.transferDirection := ActiveSPMDirection.load
    dma.request.bits.externalAddress := 0.U
    dma.request.bits.localOffset := 0.U
    dma.request.bits.byteCount := 0.U
    dma.completion.ready := true.B

    registerNode.regmap(
      ActiveSPMRegisters.commandOffset -> Seq(RegField(64)),
      ActiveSPMRegisters.externalAddressOffset -> Seq(RegField(64)),
      ActiveSPMRegisters.localOffsetOffset -> Seq(RegField(64)),
      ActiveSPMRegisters.byteCountOffset -> Seq(RegField(64)),
      ActiveSPMRegisters.statusOffset -> Seq(RegField(64)),
      ActiveSPMRegisters.bytesCompletedOffset -> Seq(RegField(64)),
      ActiveSPMRegisters.errorCodeOffset -> Seq(RegField(64)))
  }
}
