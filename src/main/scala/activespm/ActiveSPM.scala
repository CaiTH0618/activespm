package activespm

import chisel3._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}

/** Top-level ActiveSPM composition shell. */
class ActiveSPM(val params: ActiveSPMParams)(implicit p: Parameters) extends LazyModule {
  private val control = LazyModule(new ActiveSPMControl(params))
  private val dma = LazyModule(new ActiveSPMDMA(params))
  private val scratchpad = LazyModule(new ActiveSPMScratchpad(params))

  val controlNode = control.node
  val scratchpadNode = scratchpad.globalNode
  val dmaNode = dma.externalNode

  scratchpad.localNodes.zip(dma.localNodes).foreach { case (sink, source) => sink := source }

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val desc = dma.module.desc.map(port => IO(chiselTypeOf(port)))
    desc.zip(dma.module.desc).foreach { case (port, inner) => inner <> port }
    control.module.dma.request <> dma.module.control.request
    control.module.dma.completion <> dma.module.control.completion
    control.module.dma.progress := dma.module.control.progress
  }
}
