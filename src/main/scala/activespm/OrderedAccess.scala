package activespm

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.IdRange
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}

/** Global SRAM ingress: accept requests ahead of bank execution and return
  * their responses in order. Bank A channels have no additional queues, so
  * downstream A handshakes execute physical SRAM operations in ingress order.
  * The fixed-latency, single-port TLRAM banks preserve read/write dependencies.
  * DMA clients have independent paths and take part in per-bank arbitration.
  */
private[activespm] class ActiveSPMOrderedAccess(depth: Int)(implicit p: Parameters) extends LazyModule {
  require(isPow2(depth) && depth >= 2)
  val node = TLAdapterNode(
    clientFn = cp => cp.v1copy(clients = Seq(TLMasterParameters.v1(
      name = "activespm-ordered-global", sourceId = IdRange(0, depth), requestFifo = true))),
    managerFn = mp => mp.v1copy(managers = mp.managers.map(_.v1copy(fifoId = Some(0)))))

  lazy val module = new LazyModuleImp(this) {
    val (in, edgeIn) = node.in.head
    val (out, edgeOut) = node.out.head
    require(edgeIn.bundle.dataBits == edgeOut.bundle.dataBits)
    require(edgeOut.manager.maxTransfer <= edgeOut.manager.beatBytes,
      "Ordered SRAM ingress expects already-fragmented single-beat requests")
    val requests = Module(new Queue(chiselTypeOf(in.a.bits), depth, pipe = true))
    requests.io.enq <> in.a
    val bits = log2Ceil(depth)
    val tail = RegInit(0.U(bits.W))
    val head = RegInit(0.U(bits.W))
    val occupied = RegInit(0.U(log2Ceil(depth + 1).W))
    val allocated = RegInit(VecInit(Seq.fill(depth)(false.B)))
    val ready = RegInit(VecInit(Seq.fill(depth)(false.B)))
    val sources = Reg(Vec(depth, UInt(edgeIn.bundle.sourceBits.W)))
    val responses = Reg(Vec(depth, chiselTypeOf(out.d.bits)))

    out.a.valid := requests.io.deq.valid && occupied < depth.U
    out.a.bits := requests.io.deq.bits
    out.a.bits.source := tail
    requests.io.deq.ready := out.a.ready && occupied < depth.U
    out.d.ready := true.B // Every issued request has an exclusively reserved response slot.
    in.d.valid := ready(head)
    in.d.bits := responses(head)
    in.d.bits.source := sources(head)

    when(out.a.fire) {
      assert(!allocated(tail))
      allocated(tail) := true.B
      ready(tail) := false.B
      sources(tail) := requests.io.deq.bits.source
      tail := tail + 1.U
    }
    when(out.d.fire) {
      val slot = out.d.bits.source(bits - 1, 0)
      assert(allocated(slot) && !ready(slot), "Ordered SRAM response has no reserved slot")
      responses(slot) := out.d.bits
      ready(slot) := true.B
    }
    when(in.d.fire) {
      allocated(head) := false.B
      ready(head) := false.B
      head := head + 1.U
    }
    when(out.a.fire =/= in.d.fire) {
      occupied := Mux(out.a.fire, occupied + 1.U, occupied - 1.U)
    }
    assert(occupied <= depth.U)
    in.b.valid := false.B
    in.b.bits := 0.U.asTypeOf(in.b.bits)
    in.c.ready := true.B
    in.e.ready := true.B
    out.b.ready := true.B
    out.c.valid := false.B
    out.c.bits := 0.U.asTypeOf(out.c.bits)
    out.e.valid := false.B
    out.e.bits := 0.U.asTypeOf(out.e.bits)
  }
}
