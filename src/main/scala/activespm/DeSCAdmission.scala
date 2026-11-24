package activespm

import chisel3._
import chisel3.util._

/** One aligned 64-byte transfer. Addresses on this sideband are in 64-byte units. */
class ActiveSPMDeSCBurst(params: ActiveSPMParams) extends Bundle {
  private val externalBits = params.externalMemoryRanges.map(r => (r.base | r.mask).bitLength).max
  val externalBlockAddress = UInt((externalBits - 6).max(1).W)
  val scratchpadBlockOffset = UInt((log2Ceil(params.scratchpadSize) - 6).max(1).W)
  val store = Bool()

  def externalAddress: UInt = Cat(externalBlockAddress, 0.U(6.W))
  def localOffset: UInt = Cat(scratchpadBlockOffset, 0.U(6.W))
}

class ActiveSPMDeSCConfig(slots: Int) extends Bundle {
  val decoderWeight = UInt(4.W)
  // Class 0 is inference, class 1 is decoding.
  val burstLimit = Vec(2, UInt(log2Ceil(slots + 1).W))
  val byteLimit = Vec(2, UInt(log2Ceil(slots * 64 + 1).W))
}

class ActiveSPMDeSCIO(params: ActiveSPMParams) extends Bundle {
  val decode = Flipped(Decoupled(new ActiveSPMDeSCBurst(params)))
  val configure = Flipped(Valid(new ActiveSPMDeSCConfig(params.dmaMaxInFlight)))
  val inFlightBursts = Output(Vec(2, UInt(log2Ceil(params.dmaMaxInFlight + 1).W)))
  val completedBytes = Output(Vec(2, UInt(64.W)))
  val decodeError = Output(Bool())
}

/** Grant an eligible 64-byte burst at the DMA's external TileLink A interface.
  * Credits cover requests accepted by the outbound TLBuffer up to their D
  * response. A selected request remains stable during backpressure.
  */
private[activespm] class ActiveSPMDeSCAdmission(slots: Int) extends Module {
  val io = IO(new Bundle {
    val configure = Flipped(Valid(new ActiveSPMDeSCConfig(slots)))
    val candidates = Flipped(Vec(2, Valid(UInt(log2Ceil(slots).W))))
    val pause = Input(Bool())
    val issue = Decoupled(UInt(log2Ceil(slots).W))
    val locked = Output(Bool())
    val decoderEnabled = Output(Bool())
    val responseClass = Flipped(Valid(Bool()))
    val inFlightBursts = Output(Vec(2, UInt(log2Ceil(slots + 1).W)))
    val completed = Input(Vec(2, UInt(8.W)))
    val completedBytes = Output(Vec(2, UInt(64.W)))
  })

  private val countBits = log2Ceil(slots + 1)
  private val weight = RegInit(0.U(4.W))
  private val phase = RegInit(0.U(4.W))
  private val limit = RegInit(VecInit(Seq(slots.U(countBits.W), (slots / 2).U(countBits.W))))
  private val pending = RegInit(VecInit(Seq.fill(2)(0.U(countBits.W))))
  private val completed = RegInit(VecInit(Seq.fill(2)(0.U(64.W))))
  private val locked = RegInit(false.B)
  private val heldSlot = Reg(UInt(log2Ceil(slots).W))
  private val heldClass = Reg(Bool())
  private val heldPhaseValid = RegInit(false.B)
  private val heldPhase = Reg(UInt(4.W))

  val inference = io.candidates(0).valid && pending(0) < limit(0)
  val decoder = io.candidates(1).valid && pending(1) < limit(1) && weight =/= 0.U
  val both = inference && decoder
  val sum = phase +& weight
  val chooseDecoder = decoder && (!inference || sum(4))
  val chosenSlot = io.candidates(chooseDecoder.asUInt).bits
  val issuedClass = Mux(locked, heldClass, chooseDecoder)

  io.issue.valid := locked || (!io.pause && (inference || decoder))
  io.issue.bits := Mux(locked, heldSlot, chosenSlot)
  io.locked := locked
  io.decoderEnabled := weight =/= 0.U
  io.inFlightBursts := pending
  io.completedBytes := completed

  when(!locked && io.issue.valid && !io.issue.ready) {
    locked := true.B
    heldSlot := chosenSlot
    heldClass := chooseDecoder
    heldPhaseValid := both
    heldPhase := sum(3, 0)
  }
  when(io.issue.fire) {
    locked := false.B
    when(Mux(locked, heldPhaseValid, both)) {
      phase := Mux(locked, heldPhase, sum(3, 0))
    }
  }
  when(io.configure.valid) {
    weight := io.configure.bits.decoderWeight
    phase := 0.U
    heldPhaseValid := false.B
    for (c <- 0 until 2) {
      // At 64 bytes per burst, both credit limits can use the same counter.
      val burstsFromBytes = io.configure.bits.byteLimit(c) >> 6
      limit(c) := Mux(burstsFromBytes < io.configure.bits.burstLimit(c),
        burstsFromBytes, io.configure.bits.burstLimit(c))
    }
  }
  for (c <- 0 until 2) {
    val sent = io.issue.fire && issuedClass.asUInt === c.U
    val returned = io.responseClass.valid && io.responseClass.bits.asUInt === c.U
    when(sent =/= returned) {
      pending(c) := Mux(sent, pending(c) + 1.U, pending(c) - 1.U)
    }
    when(returned) { assert(pending(c) =/= 0.U) }
    completed(c) := completed(c) + io.completed(c)
  }
}
