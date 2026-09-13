package activespm

import chisel3._
import freechips.rocketchip.diplomacy.{RegionType, TransferSizes}
import freechips.rocketchip.resources.MemoryDevice
import freechips.rocketchip.tilelink.{TLBundle, TLManagerNode, TLSlaveParameters, TLSlavePortParameters}
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.ValName
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}

/** Non-functional scratchpad shell defining global and DMA-local manager ports. */
class ActiveSPMScratchpad(params: ActiveSPMParams)(implicit p: Parameters) extends LazyModule {
  private val device = new MemoryDevice

  private def manager(name: String) = TLManagerNode(Seq(TLSlavePortParameters.v1(
    managers = Seq(TLSlaveParameters.v1(
      address = Seq(params.scratchpadAddress),
      resources = device.reg,
      regionType = RegionType.IDEMPOTENT,
      executable = false,
      supportsGet = TransferSizes(1, params.beatBytes),
      supportsPutFull = TransferSizes(1, params.beatBytes),
      supportsPutPartial = TransferSizes(1, params.beatBytes),
      fifoId = Some(0)).v2copy(name = Some(name))),
    beatBytes = params.beatBytes,
    minLatency = 1)))(ValName(name))

  val globalNode = manager(params.scratchpadNodeName)
  val localNode = manager(params.localNodeName)

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    private def tieOffManager(tl: TLBundle, portName: String): Unit = {
      tl.a.ready := false.B
      tl.d.valid := false.B
      tl.d.bits := 0.U.asTypeOf(tl.d.bits)
      tl.b.valid := false.B
      tl.b.bits := 0.U.asTypeOf(tl.b.bits)
      tl.c.ready := true.B
      tl.e.ready := true.B
      assert(!tl.a.valid, s"$portName accessed before the ActiveSPM scratchpad is implemented")
    }

    tieOffManager(globalNode.in.head._1, params.scratchpadNodeName)
    tieOffManager(localNode.in.head._1, params.localNodeName)
  }
}
