package activespm

import chisel3._
import freechips.rocketchip.diplomacy.{RegionType, TransferSizes}
import freechips.rocketchip.resources.MemoryDevice
import freechips.rocketchip.tilelink.{TLAdapterNode, TLArbiter, TLRAM, TLSlaveParameters, TLXbar}
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.ValName
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}

/** Banked storage shared by the system-facing and DMA-local TileLink paths. */
class ActiveSPMScratchpad(params: ActiveSPMParams)(implicit p: Parameters) extends LazyModule {
  private val device = new MemoryDevice

  private def aggregatedManager(name: String) = TLSlaveParameters.v1(
    address = Seq(params.scratchpadAddress),
    resources = device.reg("mem"),
    regionType = RegionType.IDEMPOTENT,
    executable = false,
    supportsGet = TransferSizes(1, params.spadBeatBytes),
    supportsPutFull = TransferSizes(1, params.spadBeatBytes),
    supportsPutPartial = TransferSizes(1, params.spadBeatBytes),
    fifoId = None).v2copy(name = Some(name))

  private def aggregateNode(name: String) = TLAdapterNode(
    clientFn = identity,
    managerFn = port => port.v2copy(slaves = Seq(aggregatedManager(name))))(ValName(name))

  val globalNode = aggregateNode(params.scratchpadNodeName)
  val localNode = aggregateNode(params.localNodeName)

  private val bankXbar = TLXbar(TLArbiter.roundRobin)
  bankXbar := globalNode
  bankXbar := localNode

  private val banks = params.bankAddressSets.zipWithIndex.map { case (address, bankId) =>
    val bank = LazyModule(new TLRAM(
      address = address,
      cacheable = false,
      executable = false,
      atomics = false,
      beatBytes = params.spadBeatBytes,
      devOverride = Some(device)))
    bank.suggestName(s"activespm_spad_${params.id}_bank_$bankId")
    bank.node := bankXbar
    bank
  }

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    private def connectMetadataAdapter(node: TLAdapterNode): Unit = {
      (node.in zip node.out).foreach { case ((in, _), (out, _)) => out <> in }
    }

    connectMetadataAdapter(globalNode)
    connectMetadataAdapter(localNode)
  }
}
