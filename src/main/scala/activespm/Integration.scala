package activespm

import freechips.rocketchip.subsystem.{BaseSubsystem, CBUS, SBUS}
import freechips.rocketchip.tilelink._
import org.chipsalliance.diplomacy.ValName
import org.chipsalliance.diplomacy.lazymodule.LazyModule

/** Optionally attach every configured ActiveSPM instance to a subsystem. */
trait CanHavePeripheryActiveSPM { this: BaseSubsystem =>
  private val activeSPMParams = p(ActiveSPMKey)
  ActiveSPMParams.validateInstances(activeSPMParams)

  val activeSPMs = activeSPMParams.map { params =>
    val sbus = locateTLBusWrapper(SBUS)
    val cbus = locateTLBusWrapper(CBUS)
    val domain = sbus.generateSynchronousDomain.suggestName(s"activespm_domain_${params.id}")
    val activeSPM = domain { LazyModule(new ActiveSPM(params)) }
    activeSPM.suggestName(s"activespm_${params.id}")

    val controlXing = domain.crossIn(activeSPM.controlNode)(ValName(params.controlNodeName))(params.controlXType)
    cbus.coupleTo(params.controlNodeName) {
      controlXing := TLFragmenter(ActiveSPMRegisters.accessBytes, cbus.blockBytes) := TLWidthWidget(cbus.beatBytes) := _
    }
    sbus.coupleTo(params.scratchpadNodeName) {
      activeSPM.scratchpadNode :=
        TLFIFOFixer() := TLFragmenter(params.spadBeatBytes, sbus.blockBytes) :=
          TLWidthWidget(sbus.beatBytes) := _
    }
    sbus.coupleFrom(params.dmaNodeName) { _ := activeSPM.dmaNode }

    activeSPM
  }
}
