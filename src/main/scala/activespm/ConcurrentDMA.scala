package activespm

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.{BufferParams, IdRange}
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.ValName
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}

/** Unchanged single-command control interface, with optional transaction concurrency.
  * Only the external-memory master gains source IDs; the SRAM/control slave
  * interfaces and the software-visible command format remain unchanged.
  */
class ActiveSPMDMA(params: ActiveSPMParams)(implicit p: Parameters) extends LazyModule {
  private val externalClient = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(
    TLMasterParameters.v1(name = params.dmaNodeName,
      sourceId = IdRange(0, params.dmaMaxInFlight), visibility = params.externalMemoryRanges)))))
  private val localClients = Seq.tabulate(params.dmaLocalPorts) { lane =>
    TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1(
      name = params.localLaneName(lane), sourceId = IdRange(0, params.dmaLocalInFlight),
      visibility = Seq(params.scratchpadAddress))))))
  }
  private def boundary(name: String, client: TLOutwardNode): TLIdentityNode = {
    val node = TLIdentityNode()(ValName(name))
    val depth = if (params.dmaMaxInFlight > 1) 2 else 1
    node := TLBuffer(a = BufferParams(depth, false, false), b = BufferParams.none,
      c = BufferParams.none, d = BufferParams(depth, false, false), e = BufferParams.none) := client
    node
  }
  val externalNode = boundary(params.dmaNodeName, externalClient)
  val localNodes = localClients.zipWithIndex.map { case (client, lane) =>
    boundary(params.localLaneName(lane), client)
  }
  val localNode = localNodes.head

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val control = IO(new ActiveSPMDMAControlIO)
    val desc = Option.when(params.descQueueDepth > 0)(IO(new ActiveSPMDeSCIO(params)))
    private val (external, externalEdge) = externalClient.out.head
    private val locals = localClients.map(_.out.head)
    private val byte = Module(new ActiveSPMByteDMA(params, externalEdge, locals.head._2))

    if (params.dmaMaxInFlight == 1) {
      control <> byte.io.control
      external <> byte.io.external
      locals.head._1 <> byte.io.local
    } else {
      require(externalEdge.bundle.dataBits == 512)
      require(locals.forall(_._2.bundle.dataBits == 256))
      val fast = Module(new ActiveSPMConcurrentDMA(params, externalEdge, locals.map(_._2)))
      val idle :: runningFast :: startTail :: runningByte :: complete :: Nil = Enum(5)
      val state = RegInit(idle)
      val useFast = RegInit(false.B)
      val saved = Reg(new ActiveSPMDMARequest)
      val bulkBytes = Reg(UInt(64.W))
      val baseCompleted = RegInit(0.U(64.W))
      val resultBytes = RegInit(0.U(64.W))
      val resultError = RegInit(ActiveSPMErrorCode.none)

      val localEnd = Cat(0.U(1.W), control.request.bits.localOffset) +& control.request.bits.byteCount
      val externalEnd = Cat(0.U(1.W), control.request.bits.externalAddress) +& control.request.bits.byteCount
      val lastExternal = externalEnd(63, 0) - 1.U
      val externalLegal = params.externalMemoryRanges.map(r =>
        r.contains(control.request.bits.externalAddress) && r.contains(lastExternal)).reduce(_ || _)
      val eligible = control.request.bits.localOffset(5, 0) === 0.U &&
        control.request.bits.externalAddress(5, 0) === 0.U && control.request.bits.byteCount >= 64.U &&
        !localEnd(64) && !externalEnd(64) && localEnd <= params.scratchpadSize.U && externalLegal
      val bulk = control.request.bits.byteCount & ~63.U(64.W)
      val fastCanYield = fast.quiescent.getOrElse(true.B)
      // The byte engine owns the shared TL ports only after accepted fast
      // traffic has drained. Unsent bursts can stay buffered while it runs.
      val wantsByte = state === startTail || state === runningByte ||
        (state === idle && control.request.valid && !eligible)
      desc.foreach { port =>
        fast.io.desc.get <> port
        fast.pause.get := wantsByte
      }

      fast.io.control.request.bits := control.request.bits
      fast.io.control.request.bits.byteCount := bulk
      fast.io.control.request.valid := state === idle && control.request.valid && eligible
      byte.io.control.request.bits := control.request.bits
      byte.io.control.request.valid := state === idle && control.request.valid && !eligible && fastCanYield
      when(state === startTail) {
        byte.io.control.request.bits := saved
        byte.io.control.request.bits.externalAddress := saved.externalAddress + bulkBytes
        byte.io.control.request.bits.localOffset := saved.localOffset + bulkBytes
        byte.io.control.request.bits.byteCount := saved.byteCount - bulkBytes
        byte.io.control.request.valid := fastCanYield
      }
      control.request.ready := state === idle && Mux(eligible,
        fast.io.control.request.ready, byte.io.control.request.ready && fastCanYield)
      fast.io.control.completion.ready := state === runningFast
      byte.io.control.completion.ready := state === runningByte
      control.completion.valid := state === complete
      control.completion.bits.errorCode := resultError
      control.completion.bits.bytesCompleted := resultBytes
      control.progress.busy := state =/= idle && state =/= complete
      control.progress.bytesCompleted := Mux(state === complete, resultBytes,
        baseCompleted + Mux(state === runningFast, fast.io.control.progress.bytesCompleted,
          Mux(state === runningByte, byte.io.control.progress.bytesCompleted, 0.U)))

      when(control.request.fire) {
        saved := control.request.bits
        bulkBytes := bulk
        baseCompleted := 0.U
        resultBytes := 0.U
        resultError := ActiveSPMErrorCode.none
        useFast := eligible
        state := Mux(eligible, runningFast, runningByte)
      }
      when(fast.io.control.completion.fire) {
        resultBytes := fast.io.control.completion.bits.bytesCompleted
        resultError := fast.io.control.completion.bits.errorCode
        when(fast.io.control.completion.bits.errorCode === ActiveSPMErrorCode.none && saved.byteCount =/= bulkBytes) {
          baseCompleted := fast.io.control.completion.bits.bytesCompleted
          useFast := false.B
          state := startTail
        }.otherwise { state := complete }
      }
      when(state === startTail && byte.io.control.request.fire) { state := runningByte }
      when(byte.io.control.completion.fire) {
        resultBytes := baseCompleted + byte.io.control.completion.bits.bytesCompleted
        resultError := byte.io.control.completion.bits.errorCode
        state := complete
      }
      when(control.completion.fire) { state := idle }

      val fastOwnsPorts = if (desc.isDefined) state =/= runningByte else useFast
      def select(tl: TLBundle, high: TLBundle, low: Option[TLBundle]): Unit = {
        tl.a.valid := Mux(fastOwnsPorts, high.a.valid, low.map(_.a.valid).getOrElse(false.B))
        tl.a.bits := Mux(fastOwnsPorts, high.a.bits, low.map(_.a.bits).getOrElse(0.U.asTypeOf(tl.a.bits)))
        high.a.ready := fastOwnsPorts && tl.a.ready
        high.d.valid := fastOwnsPorts && tl.d.valid
        high.d.bits := tl.d.bits
        tl.d.ready := Mux(fastOwnsPorts, high.d.ready, low.map(_.d.ready).getOrElse(true.B))
        high.b.valid := false.B
        high.b.bits := 0.U.asTypeOf(high.b.bits)
        high.c.ready := true.B
        high.e.ready := true.B
        low.foreach { l =>
          l.a.ready := !fastOwnsPorts && tl.a.ready
          l.d.valid := !fastOwnsPorts && tl.d.valid
          l.d.bits := tl.d.bits
          l.b.valid := false.B
          l.b.bits := 0.U.asTypeOf(l.b.bits)
          l.c.ready := true.B
          l.e.ready := true.B
        }
        tl.b.ready := true.B
        tl.c.valid := false.B
        tl.c.bits := 0.U.asTypeOf(tl.c.bits)
        tl.e.valid := false.B
        tl.e.bits := 0.U.asTypeOf(tl.e.bits)
      }
      select(external, fast.io.external, Some(byte.io.external))
      locals.zipWithIndex.foreach { case ((tl, _), lane) =>
        select(tl, fast.io.local(lane), if (lane == 0) Some(byte.io.local) else None)
      }
    }
  }
}

/** One 64-byte slot per external source ID. Data is a flop array, not SRAM.
  * Read responses may return out of order. Two local issuers each have their
  * own tags and can sustain one 32-byte request per cycle on distinct banks.
  */
private[activespm] class ActiveSPMConcurrentDMA(
  params: ActiveSPMParams, externalEdge: TLEdgeOut, localEdges: Seq[TLEdgeOut]
)(implicit p: Parameters) extends Module {
  private val descEnabled = params.descQueueDepth > 0
  val io = IO(new Bundle {
    val control = new ActiveSPMDMAControlIO
    val external = new TLBundle(externalEdge.bundle)
    val local = MixedVec(localEdges.map(e => new TLBundle(e.bundle)))
    val desc = Option.when(descEnabled)(new ActiveSPMDeSCIO(params))
  })
  val pause = Option.when(descEnabled)(IO(Input(Bool())))
  val quiescent = Option.when(descEnabled)(IO(Output(Bool())))
  private val n = params.dmaMaxInFlight
  private val indexBits = log2Ceil(n)
  private val localN = params.dmaLocalInFlight
  private val localBits = log2Ceil(localN)
  private val idle :: active :: complete :: Nil = Enum(3)
  private val state = RegInit(idle)
  private val load = Reg(Bool())
  private val externalBase = Reg(UInt(64.W))
  private val localBase = Reg(UInt(64.W))
  private val length = Reg(UInt(64.W))
  private val nextOffset = RegInit(0.U(64.W))
  private val allocatePtr = RegInit(0.U(indexBits.W))
  private val externalPtr = RegInit(0.U(indexBits.W))
  private val retirePtr = RegInit(0.U(indexBits.W))
  private val occupancy = RegInit(0.U(log2Ceil(n + 1).W))
  private val allocated = RegInit(VecInit(Seq.fill(n)(false.B)))
  private val offsets = Reg(Vec(n, UInt(64.W)))
  private val externalIssued = RegInit(VecInit(Seq.fill(n)(false.B)))
  private val externalPending = RegInit(VecInit(Seq.fill(n)(false.B)))
  private val externalDone = RegInit(VecInit(Seq.fill(n)(false.B)))
  private val returned = RegInit(VecInit(Seq.fill(n)(false.B)))
  private val localIssued = Seq.fill(2)(RegInit(VecInit(Seq.fill(n)(false.B))))
  private val localDone = Seq.fill(2)(RegInit(VecInit(Seq.fill(n)(false.B))))
  private val destinationAcked = Seq.fill(2)(Reg(Vec(n, Bool())))
  // Exactly n * 2 * 256 bits. Never reset or shift the entire data window.
  private val data = Seq.fill(2)(Reg(Vec(n, UInt(256.W))))
  private val localPtr = Seq.fill(2)(RegInit(0.U(indexBits.W)))
  private val localTag = Seq.fill(2)(RegInit(0.U(localBits.W)))
  private val localBusy = Seq.fill(2)(RegInit(VecInit(Seq.fill(localN)(false.B))))
  private val localSlot = Seq.fill(2)(Reg(Vec(localN, UInt(indexBits.W))))
  private val fatal = RegInit(false.B)
  private val error = RegInit(ActiveSPMErrorCode.none)
  private val completed = RegInit(0.U(64.W))
  private val prefixBlocked = RegInit(false.B)
  private val descriptors = Option.when(descEnabled)(Reg(Vec(n, new ActiveSPMDeSCBurst(params))))
  private val decodeClass = Option.when(descEnabled)(Reg(Vec(n, Bool())))
  private val failed = Option.when(descEnabled)(Reg(Vec(n, Bool())))
  private val poisoned = VecInit((0 until n).map { slot =>
    failed.fold(fatal)(_(slot) || (fatal && !decodeClass.get(slot)))
  })

  private def isDecode(slot: UInt): Bool = decodeClass.fold(false.B)(_(slot))
  private def isLoad(slot: UInt): Bool = descriptors.fold(load)(d => !d(slot).store)
  private def externalAddress(slot: UInt): UInt =
    descriptors.fold(externalBase + offsets(slot))(_(slot).externalAddress)
  private def localAddress(slot: UInt): UInt =
    descriptors.fold(localBase + offsets(slot))(d => params.scratchpadAddress.base.U + d(slot).localOffset)
  private def slotFinished(slot: UInt): Bool =
    externalDone(slot) && localDone.map(_(slot)).reduce(_ && _)
  private def slotDrained(slot: UInt): Bool =
    !externalPending(slot) && !(0 until 2).map(lane => localIssued(lane)(slot) &&
      !localDone(lane)(slot)).reduce(_ || _)

  io.control.request.ready := state === idle
  io.control.completion.valid := state === complete
  io.control.completion.bits.errorCode := error
  io.control.completion.bits.bytesCompleted := completed
  io.control.progress.busy := state === active

  def defaults(tl: TLBundle): Unit = {
    tl.a.valid := false.B
    tl.a.bits := 0.U.asTypeOf(tl.a.bits)
    tl.b.ready := true.B
    tl.c.valid := false.B
    tl.c.bits := 0.U.asTypeOf(tl.c.bits)
    tl.e.valid := false.B
    tl.e.bits := 0.U.asTypeOf(tl.e.bits)
    tl.d.ready := (if (descEnabled) true.B else state === active)
  }
  defaults(io.external)
  io.local.foreach(defaults)
  val running = state === active && !fatal
  val allocate = WireDefault(running && nextOffset < length && occupancy < n.U)
  val allocateSlot = WireDefault(allocatePtr)
  val retireSlot = WireDefault(retirePtr)
  val retire = WireDefault(state === active && allocated(retirePtr) &&
    (slotFinished(retirePtr) || (fatal && slotDrained(retirePtr))))
  val commandHead = WireDefault(retirePtr)
  val commandHeadValid = WireDefault(allocated(retirePtr))
  // Include the acknowledged low half of an unfinished LOAD at the head.
  // Retiring a partial or failed burst fixes the final prefix at that gap.
  val headBytes = Mux(commandHeadValid && !prefixBlocked && destinationAcked(0)(commandHead),
    Mux(destinationAcked(1)(commandHead), 64.U, 32.U), 0.U)
  val completedWithHead = completed + headBytes
  io.control.progress.bytesCompleted := Mux(state === active, completedWithHead, completed)
  val issueSlot = WireDefault(externalPtr)
  val localIssueSlot = WireDefault(VecInit(localPtr))
  val localCanIssue = WireDefault(VecInit((0 until 2).map { lane =>
    val slot = localPtr(lane)
    running && allocated(slot) && !localIssued(lane)(slot) && !localBusy(lane)(localTag(lane)) &&
      (!load || returned(slot))
  }))
  val storeReady = localDone.map(_(externalPtr)).reduce(_ && _)
  io.external.a.valid := running && allocated(externalPtr) && !externalIssued(externalPtr) && (load || storeReady)
  io.external.a.bits := Mux(isLoad(issueSlot),
    externalEdge.Get(issueSlot, externalAddress(issueSlot), 6.U)._2,
    externalEdge.Put(issueSlot, externalAddress(issueSlot), 6.U,
      Cat(data(1)(issueSlot), data(0)(issueSlot)))._2)

  val externalBad = io.external.d.fire && (io.external.d.bits.denied || io.external.d.bits.corrupt)
  val localBad = io.local.map(l => l.d.fire && (l.d.bits.denied || l.d.bits.corrupt))
  val externalResponseSlot = io.external.d.bits.source(indexBits - 1, 0)
  val localResponseSlots = io.local.zipWithIndex.map { case (tl, lane) =>
    localSlot(lane)(tl.d.bits.source(localBits - 1, 0))
  }
  val externalResponseClass = isDecode(externalResponseSlot)
  val localResponseClasses = localResponseSlots.map(isDecode)
  val commandFailed = (externalBad && !externalResponseClass) ||
    localBad.zip(localResponseClasses).map { case (bad, decoder) => bad && !decoder }.reduce(_ || _)
  val externalWriteAck = io.external.d.fire && !externalBad && !isLoad(externalResponseSlot)
  val localWriteAcks = io.local.zip(localResponseSlots).zip(localBad).map { case ((tl, slot), bad) =>
    tl.d.fire && !bad && isLoad(slot)
  }
  def acknowledgedBytes(decoding: Bool): UInt = {
    val loadBytes = localWriteAcks.zip(localResponseClasses).map { case (ack, decoder) =>
      Mux(ack && decoder === decoding, 32.U(7.W), 0.U(7.W))
    }.reduce(_ +& _)
    val storeBytes = Mux(externalWriteAck && externalResponseClass === decoding, 64.U(8.W), 0.U(8.W))
    loadBytes + storeBytes
  }
  val drained = !externalPending.reduce(_ || _) && !localBusy.map(_.reduce(_ || _)).reduce(_ || _)
  val commandDrained = WireDefault(occupancy === 0.U && drained)
  val advanceOffset = WireDefault(allocate)

  io.desc.foreach { port =>
    val paused = pause.get
    val admission = Module(new ActiveSPMDeSCAdmission(n))
    admission.io.configure := port.configure
    admission.io.pause := paused
    admission.io.responseClass.valid := io.external.d.fire
    admission.io.responseClass.bits := externalResponseClass
    admission.io.completed(0) := acknowledgedBytes(false.B)
    admission.io.completed(1) := acknowledgedBytes(true.B)
    port.inFlightBursts := admission.io.inFlightBursts
    port.completedBytes := admission.io.completedBytes

    val commandBursts = Module(new Queue(new ActiveSPMDeSCBurst(params), params.descQueueDepth, hasFlush = true))
    commandBursts.io.flush.get := fatal
    commandBursts.io.enq.valid := running && nextOffset < length
    commandBursts.io.enq.bits.externalBlockAddress := (externalBase + nextOffset) >> 6
    commandBursts.io.enq.bits.scratchpadBlockOffset := (localBase - params.scratchpadAddress.base.U + nextOffset) >> 6
    commandBursts.io.enq.bits.store := !load
    advanceOffset := commandBursts.io.enq.fire
    val decoder = Module(new Queue(new ActiveSPMDeSCBurst(params), params.descQueueDepth))
    val firstByte = port.decode.bits.externalAddress
    val lastByte = Cat(port.decode.bits.externalBlockAddress, 63.U(6.W))
    val validRange = params.externalMemoryRanges.map(r => r.contains(firstByte) && r.contains(lastByte))
      .reduce(_ || _) && port.decode.bits.localOffset <= (params.scratchpadSize - 64).U
    decoder.io.enq.valid := port.decode.valid && validRange
    decoder.io.enq.bits := port.decode.bits
    port.decode.ready := !validRange || decoder.io.enq.ready
    val decoderError = RegInit(false.B)
    when((port.decode.fire && !validRange) || (externalBad && externalResponseClass) ||
      localBad.zip(localResponseClasses).map { case (bad, isDecoder) => bad && isDecoder }.reduce(_ || _)) {
      decoderError := true.B
    }
    port.decodeError := decoderError

    // A decoder can use up to half of the shared slots, including any stores
    // being prepared in SRAM. This leaves slots for inference even at w = 0.
    val decoderSlots = RegInit(0.U(log2Ceil(n + 1).W))
    val free = ~allocated.asUInt
    val preferDecoder = RegInit(false.B)
    val commandCanAllocate = commandBursts.io.deq.valid && !fatal
    val decodeCanAllocate = decoder.io.deq.valid && decoderSlots < (n / 2).U && admission.io.decoderEnabled
    val allocateDecoder = decodeCanAllocate && (!commandCanAllocate || preferDecoder)
    allocate := !paused && free.orR && (commandCanAllocate || decodeCanAllocate)
    allocateSlot := PriorityEncoder(free)
    commandBursts.io.deq.ready := allocate && !allocateDecoder
    decoder.io.deq.ready := allocate && allocateDecoder
    // Shared slot IDs need not follow address order. Record the order of
    // MMIO-command bursts independently of the decoder's slot lifetime.
    val commandOrder = Module(new Queue(UInt(indexBits.W), n))
    commandOrder.io.enq.valid := commandBursts.io.deq.fire
    commandOrder.io.enq.bits := allocateSlot
    commandOrder.io.deq.ready := retire && !isDecode(retireSlot)
    commandHead := commandOrder.io.deq.bits
    commandHeadValid := commandOrder.io.deq.valid
    when(commandOrder.io.enq.valid) { assert(commandOrder.io.enq.ready) }
    when(allocate) {
      preferDecoder := !allocateDecoder
      descriptors.get(allocateSlot) := Mux(allocateDecoder, decoder.io.deq.bits, commandBursts.io.deq.bits)
      decodeClass.get(allocateSlot) := allocateDecoder
      failed.get(allocateSlot) := false.B
    }
    val takesDecoderSlot = allocate && allocateDecoder
    val freesDecoderSlot = retire && isDecode(retireSlot)
    when(takesDecoderSlot =/= freesDecoderSlot) {
      decoderSlots := Mux(takesDecoderSlot, decoderSlots + 1.U, decoderSlots - 1.U)
    }

    // Search independently for each class and each local lane. A decoder
    // waiting for a grant cannot block inference's issue or slot reclamation.
    def nextReady(ready: Seq[Bool], from: UInt): UInt = {
      val above = VecInit(ready.zipWithIndex.map { case (valid, i) => valid && i.U >= from }).asUInt
      PriorityEncoder(Mux(above.orR, above, VecInit(ready).asUInt))
    }
    val issueHeads = RegInit(VecInit(Seq.fill(2)(0.U(indexBits.W))))
    val externalReady = (0 until n).map(i => allocated(i) && !externalIssued(i) && !poisoned(i) &&
      (isLoad(i.U) || localDone.map(_(i)).reduce(_ && _)))
    for (c <- 0 until 2) {
      val ready = externalReady.zipWithIndex.map { case (valid, slot) => valid && isDecode(slot.U).asUInt === c.U }
      admission.io.candidates(c).valid := ready.reduce(_ || _)
      admission.io.candidates(c).bits := nextReady(ready, issueHeads(c))
    }
    io.external.a.valid := admission.io.issue.valid
    issueSlot := admission.io.issue.bits
    admission.io.issue.ready := io.external.a.ready
    when(io.external.a.fire) { issueHeads(isDecode(issueSlot).asUInt) := issueSlot + 1.U }
    when(externalBad) { failed.get(externalResponseSlot) := true.B }

    val localLocked = Seq.tabulate(2) { lane =>
      val locked = RegInit(false.B)
      val held = Reg(UInt(indexBits.W))
      val ready = (0 until n).map(i => allocated(i) && !localIssued(lane)(i) && !poisoned(i) &&
        Mux(isLoad(i.U), returned(i), !isDecode(i.U) || admission.io.decoderEnabled))
      val next = nextReady(ready, localPtr(lane))
      val tl = io.local(lane)
      localIssueSlot(lane) := Mux(locked, held, next)
      localCanIssue(lane) := locked || (!paused && !localBusy(lane)(localTag(lane)) && ready.reduce(_ || _))
      when(!locked && tl.a.valid && !tl.a.ready) { locked := true.B; held := next }
      when(tl.a.fire) { locked := false.B }
      when(localBad(lane)) { failed.get(localResponseSlots(lane)) := true.B }
      locked
    }

    val retireReady = (0 until n).map { i =>
      val slot = i.U
      val locked = (admission.io.locked && issueSlot === slot) ||
        localLocked.zipWithIndex.map { case (hold, lane) => hold && localIssueSlot(lane) === slot }.reduce(_ || _)
      val canRetireInOrder = isDecode(slot) || (commandHeadValid && commandHead === slot)
      val cancelled = poisoned(i) && slotDrained(slot)
      allocated(i) && canRetireInOrder && !locked && (cancelled || slotFinished(slot))
    }
    retire := retireReady.reduce(_ || _)
    retireSlot := nextReady(retireReady, retirePtr)
    quiescent.get := drained && !admission.io.locked && !localLocked.reduce(_ || _)
    commandDrained := !commandBursts.io.deq.valid && !commandHeadValid
  }

  when(io.control.request.fire) {
    load := io.control.request.bits.transferDirection === ActiveSPMDirection.load
    externalBase := io.control.request.bits.externalAddress
    localBase := params.scratchpadAddress.base.U + io.control.request.bits.localOffset
    length := io.control.request.bits.byteCount
    nextOffset := 0.U
    completed := 0.U
    prefixBlocked := false.B
    fatal := false.B
    error := ActiveSPMErrorCode.none
    if (!descEnabled) {
      allocatePtr := 0.U
      externalPtr := 0.U
      retirePtr := 0.U
      occupancy := 0.U
      allocated.foreach(_ := false.B)
      externalIssued.foreach(_ := false.B)
      externalPending.foreach(_ := false.B)
      externalDone.foreach(_ := false.B)
      returned.foreach(_ := false.B)
      for (lane <- 0 until 2) {
        localPtr(lane) := 0.U
        localTag(lane) := 0.U
        localBusy(lane).foreach(_ := false.B)
        localIssued(lane).foreach(_ := false.B)
        localDone(lane).foreach(_ := false.B)
      }
    }
    assert(io.control.request.bits.byteCount =/= 0.U && io.control.request.bits.byteCount(5, 0) === 0.U)
    assert(io.control.request.bits.externalAddress(5, 0) === 0.U && io.control.request.bits.localOffset(5, 0) === 0.U)
    state := active
  }
  when(allocate) {
    allocated(allocateSlot) := true.B
    if (!descEnabled) {
      offsets(allocateSlot) := nextOffset
      allocatePtr := allocatePtr + 1.U
    }
    externalIssued(allocateSlot) := false.B
    externalDone(allocateSlot) := false.B
    returned(allocateSlot) := false.B
    for (lane <- 0 until 2) {
      localIssued(lane)(allocateSlot) := false.B
      localDone(lane)(allocateSlot) := false.B
      destinationAcked(lane)(allocateSlot) := false.B
    }
  }
  when(advanceOffset) { nextOffset := nextOffset + 64.U }
  when(retire) {
    allocated(retireSlot) := false.B
    retirePtr := retireSlot + 1.U
  }
  when(allocate =/= retire) { occupancy := Mux(allocate, occupancy + 1.U, occupancy - 1.U) }
  when(io.external.a.fire) {
    externalIssued(issueSlot) := true.B
    externalPending(issueSlot) := true.B
    if (!descEnabled) externalPtr := externalPtr + 1.U
  }
  when(io.external.d.fire) {
    val slot = externalResponseSlot
    assert(externalPending(slot), "Concurrent DMA external response without reserved transaction")
    assert(io.external.d.bits.opcode === Mux(isLoad(slot), TLMessages.AccessAckData, TLMessages.AccessAck))
    externalPending(slot) := false.B
    externalDone(slot) := true.B
    when(externalWriteAck) { destinationAcked.foreach(_(slot) := true.B) }
    when(isLoad(slot) && !externalBad && !poisoned(slot)) {
      data(0)(slot) := io.external.d.bits.data(255, 0)
      data(1)(slot) := io.external.d.bits.data(511, 256)
      returned(slot) := true.B
    }
  }
  for (lane <- 0 until 2) {
    val tl = io.local(lane)
    val edge = localEdges(lane)
    val slot = localIssueSlot(lane)
    val tag = localTag(lane)
    val address = localAddress(slot) + (lane * 32).U
    tl.a.valid := localCanIssue(lane)
    tl.a.bits := Mux(isLoad(slot), edge.Put(tag, address, 5.U, data(lane)(slot))._2,
      edge.Get(tag, address, 5.U)._2)
    when(tl.a.fire) {
      localIssued(lane)(slot) := true.B
      localBusy(lane)(tag) := true.B
      localSlot(lane)(tag) := slot
      localTag(lane) := tag + 1.U
      localPtr(lane) := slot + 1.U
    }
    when(tl.d.fire) {
      val responseTag = tl.d.bits.source(localBits - 1, 0)
      val responseSlot = localResponseSlots(lane)
      assert(localBusy(lane)(responseTag), "Concurrent DMA local response without a tag")
      assert(tl.d.bits.opcode === Mux(isLoad(responseSlot), TLMessages.AccessAck, TLMessages.AccessAckData))
      localBusy(lane)(responseTag) := false.B
      localDone(lane)(responseSlot) := true.B
      when(localWriteAcks(lane)) { destinationAcked(lane)(responseSlot) := true.B }
      when(!isLoad(responseSlot) && !localBad(lane) && !poisoned(responseSlot)) {
        data(lane)(responseSlot) := tl.d.bits.data
      }
    }
  }
  when(state === active) {
    when(retire && !isDecode(retireSlot)) {
      completed := completedWithHead
      when(headBytes =/= 64.U) { prefixBlocked := true.B }
    }
    when(commandFailed) { fatal := true.B; error := ActiveSPMErrorCode.tileLink }
    when(fatal && commandDrained) { state := complete }
    when(!fatal && nextOffset === length && commandDrained) {
      assert(completed === length, "Concurrent DMA completed before every destination acknowledgment")
      state := complete
    }
    assert(occupancy <= n.U)
    assert(completedWithHead <= length)
  }
  when(io.control.completion.fire) { state := idle }
}
