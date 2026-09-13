package activespm

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

  scratchpad.localNode := dma.localNode

  lazy val module = new LazyModuleImp(this) {
    control.module.dma.request <> dma.module.control.request
    control.module.dma.completion <> dma.module.control.completion
    control.module.dma.progress := dma.module.control.progress
  }
}
