package activespm

import chisel3._
import freechips.rocketchip.diplomacy.IdRange
import freechips.rocketchip.tilelink.{TLBundle, TLClientNode, TLMasterParameters, TLMasterPortParameters}
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.ValName
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}

/** Non-functional DMA shell defining ActiveSPM's two TileLink client ports. */
class ActiveSPMDMA(params: ActiveSPMParams)(implicit p: Parameters) extends LazyModule {
  val externalNode = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1(
    name = params.dmaNodeName,
    sourceId = IdRange(0, 1),
    visibility = params.externalMemoryRanges)))))(ValName(params.dmaNodeName))

  val localNode = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1(
    name = params.localNodeName,
    sourceId = IdRange(0, 1),
    visibility = Seq(params.scratchpadAddress))))))(ValName(params.localNodeName))

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val control = IO(new ActiveSPMDMAControlIO)

    control.request.ready := false.B
    control.completion.valid := false.B
    control.completion.bits.errorCode := ActiveSPMErrorCode.none
    control.completion.bits.bytesCompleted := 0.U
    control.progress.busy := false.B
    control.progress.bytesCompleted := 0.U

    private def tieOffClient(tl: TLBundle): Unit = {
      tl.a.valid := false.B
      tl.a.bits := 0.U.asTypeOf(tl.a.bits)
      tl.c.valid := false.B
      tl.c.bits := 0.U.asTypeOf(tl.c.bits)
      tl.e.valid := false.B
      tl.e.bits := 0.U.asTypeOf(tl.e.bits)
      tl.b.ready := true.B
      tl.d.ready := true.B
    }

    tieOffClient(externalNode.out.head._1)
    tieOffClient(localNode.out.head._1)
  }
}
