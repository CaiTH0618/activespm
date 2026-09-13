package activespm

import chisel3._
import chisel3.util.Decoupled
import scala.collection.immutable.ListMap

/** DMA direction. The values are part of the software-visible ABI. */
object ActiveSPMDirection extends ChiselEnum {
  val load, store = Value
}

/** DMA completion error. The values are part of the software-visible ABI. */
object ActiveSPMErrorCode extends ChiselEnum {
  val none, busy, localRange, addressOverflow, externalRange, tileLink = Value
}

/** A command produced by the control block and consumed by the DMA.
  *
  * The producer must keep every field stable while `valid` is asserted and
  * `ready` is deasserted.
  */
class ActiveSPMDMARequest extends Record {
  private val directionField = ActiveSPMDirection()
  private val externalAddressField = UInt(64.W)
  private val localOffsetField = UInt(64.W)
  private val byteCountField = UInt(64.W)

  // Data already reserves `direction` for Chisel's binding metadata, so the
  // Scala accessor is disambiguated while the hardware field remains exactly
  // `direction` in this explicitly named Record.
  val elements = ListMap(
    "direction" -> directionField,
    "externalAddress" -> externalAddressField,
    "localOffset" -> localOffsetField,
    "byteCount" -> byteCountField)

  def transferDirection: ActiveSPMDirection.Type = directionField
  def externalAddress: UInt = externalAddressField
  def localOffset: UInt = localOffsetField
  def byteCount: UInt = byteCountField
}

/** Final result produced by the DMA and consumed by the control block.
  *
  * `errorCode == ActiveSPMErrorCode.none` denotes successful completion. The
  * producer must retain the result until the Decoupled handshake completes.
  */
class ActiveSPMDMACompletion extends Bundle {
  val errorCode = ActiveSPMErrorCode()
  val bytesCompleted = UInt(64.W)
}

/** Live DMA state sampled by the control block without a handshake. */
class ActiveSPMDMAProgress extends Bundle {
  val busy = Bool()
  val bytesCompleted = UInt(64.W)
}

/** Control-side view of the control-to-DMA interface. */
class ActiveSPMControlDMAIO extends Bundle {
  val request = Decoupled(new ActiveSPMDMARequest)
  val completion = Flipped(Decoupled(new ActiveSPMDMACompletion))
  val progress = Input(new ActiveSPMDMAProgress)
}

/** DMA-side view of the control-to-DMA interface. */
class ActiveSPMDMAControlIO extends Bundle {
  val request = Flipped(Decoupled(new ActiveSPMDMARequest))
  val completion = Decoupled(new ActiveSPMDMACompletion)
  val progress = Output(new ActiveSPMDMAProgress)
}

/** Software-visible register offsets and bit positions. */
object ActiveSPMRegisters {
  val accessBytes = 8

  val commandOffset = 0x00
  val externalAddressOffset = 0x08
  val localOffsetOffset = 0x10
  val byteCountOffset = 0x18
  val statusOffset = 0x20
  val bytesCompletedOffset = 0x28
  val errorCodeOffset = 0x30

  val commandStartBit = 0
  val commandDirectionBit = 1

  val statusBusyBit = 0
  val statusDoneBit = 1
  val statusErrorBit = 2
}
