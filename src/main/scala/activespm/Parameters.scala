package activespm

import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.prci.{ClockCrossingType, NoCrossing}
import org.chipsalliance.cde.config.{Config, Field}

/** Elaboration-time parameters for one ActiveSPM instance. */
case class ActiveSPMParams(
  id: Int,
  controlAddress: AddressSet,
  scratchpadAddress: AddressSet,
  beatBytes: Int,
  nBanks: Int,
  externalMemoryRanges: Seq[AddressSet],
  controlXType: ClockCrossingType = NoCrossing
) {
  private def isPowerOfTwo(value: BigInt): Boolean = value > 0 && (value & (value - 1)) == 0
  val scratchpadSize: BigInt = scratchpadAddress.mask + 1

  require(id >= 0, s"ActiveSPM instance ID must be non-negative, got $id")
  require(controlAddress.finite && controlAddress.contiguous,
    s"ActiveSPM control address must be a finite contiguous range, got $controlAddress")
  require(controlAddress.base % ActiveSPMRegisters.accessBytes == 0,
    s"ActiveSPM control base must be ${ActiveSPMRegisters.accessBytes}-byte aligned, got ${controlAddress.base}")
  require(controlAddress.contains(controlAddress.base + ActiveSPMRegisters.errorCodeOffset + ActiveSPMRegisters.accessBytes - 1),
    s"ActiveSPM control range must cover offsets through 0x${ActiveSPMRegisters.errorCodeOffset.toHexString}")
  require(scratchpadAddress.finite && scratchpadAddress.contiguous,
    s"ActiveSPM scratchpad address must be a finite contiguous range, got $scratchpadAddress")
  require(isPowerOfTwo(beatBytes), s"ActiveSPM beatBytes must be a power of two, got $beatBytes")
  require(isPowerOfTwo(nBanks), s"ActiveSPM nBanks must be a power of two, got $nBanks")
  require(isPowerOfTwo(scratchpadSize), s"ActiveSPM scratchpad size must be a power of two, got $scratchpadSize")
  require(scratchpadAddress.base % scratchpadSize == 0,
    s"ActiveSPM scratchpad base must be aligned to its size $scratchpadSize, got ${scratchpadAddress.base}")
  require(scratchpadSize >= BigInt(beatBytes) * nBanks,
    s"ActiveSPM scratchpad must contain at least one beat per bank")
  require(externalMemoryRanges.nonEmpty, "ActiveSPM requires at least one allowed external-memory range")
  externalMemoryRanges.foreach { range =>
    require(range.finite && range.contiguous,
      s"ActiveSPM external-memory ranges must be finite and contiguous, got $range")
  }

  val controlNodeName: String = s"activespm-ctrl[$id]"
  val scratchpadNodeName: String = s"activespm-spad[$id]"
  val dmaNodeName: String = s"activespm-dma[$id]"
  private[activespm] val localNodeName: String = s"activespm-local[$id]"
}

object ActiveSPMParams {
  /** Validate invariants that span more than one configured instance. */
  def validateInstances(params: Seq[ActiveSPMParams]): Unit = {
    require(params.map(_.id).distinct.size == params.size,
      s"ActiveSPM instance IDs must be unique, got ${params.map(_.id).mkString(", ")}")

    val publicRanges = params.flatMap { instance =>
      Seq(instance.controlNodeName -> instance.controlAddress, instance.scratchpadNodeName -> instance.scratchpadAddress)
    }
    publicRanges.combinations(2).foreach {
      case Seq((leftName, left), (rightName, right)) =>
        require(!left.overlaps(right), s"ActiveSPM public ranges overlap: $leftName $left and $rightName $right")
      case _ =>
    }
  }
}

/** Complete ordered list of ActiveSPM instances in a subsystem. */
case object ActiveSPMKey extends Field[Seq[ActiveSPMParams]](Nil)

/** Replace the subsystem's complete ActiveSPM instance list. */
class WithActiveSPM(params: Seq[ActiveSPMParams]) extends Config((site, here, up) => {
  case ActiveSPMKey => params
})
