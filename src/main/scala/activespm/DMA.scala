package activespm

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.{BufferParams, IdRange}
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.ValName
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}

/** Byte-accurate, single-request DMA between external memory and the local scratchpad.
  *
  * Each TileLink interface has one source ID. A source Get and a destination Put may
  * be outstanding at the same time, with a bounded byte FIFO performing width and
  * byte-lane realignment between the two interfaces.
  */
class ActiveSPMDMA(params: ActiveSPMParams)(implicit p: Parameters) extends LazyModule {
  private val externalClientNode = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1(
    name = params.dmaNodeName,
    sourceId = IdRange(0, 1),
    visibility = params.externalMemoryRanges)))))(ValName(params.dmaNodeName))

  private val localClientNode = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1(
    name = params.localNodeName,
    sourceId = IdRange(0, 1),
    visibility = Seq(params.scratchpadAddress))))))(ValName(params.localNodeName))

  // Register both request and response data at the DMA boundary. Besides
  // providing explicit timing cut points around the byte realigner, these
  // queues make every transaction accepted by the DMA irrevocable while it is
  // waiting to reach the connected manager.
  private def registeredClientBoundary(name: String, client: TLOutwardNode): TLIdentityNode = {
    val boundary = TLIdentityNode()(ValName(name))
    boundary := TLBuffer(
      a = BufferParams(1, flow = false, pipe = false),
      b = BufferParams.none,
      c = BufferParams.none,
      d = BufferParams(1, flow = false, pipe = false),
      e = BufferParams.none) := client
    boundary
  }

  val externalNode = registeredClientBoundary(params.dmaNodeName, externalClientNode)
  val localNode = registeredClientBoundary(params.localNodeName, localClientNode)

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val control = IO(new ActiveSPMDMAControlIO)

    private val (external, externalEdge) = externalClientNode.out.head
    private val (local, localEdge) = localClientNode.out.head
    private val externalBeatBytes = external.params.dataBits / 8
    private val localBeatBytes = local.params.dataBits / 8
    private val maxBeatBytes = externalBeatBytes max localBeatBytes
    private val bufferBytes = 2 * maxBeatBytes
    private val bufferBits = 8 * bufferBytes
    private val countBits = log2Ceil(bufferBytes + 1)
    private val byteCountBits = log2Ceil(maxBeatBytes + 1)

    require(isPow2(externalBeatBytes), s"ActiveSPM external beat bytes must be a power of two, got $externalBeatBytes")
    require(localBeatBytes == params.spadBeatBytes,
      s"ActiveSPM local DMA width $localBeatBytes must equal spadBeatBytes ${params.spadBeatBytes}")

    private val sIdle :: sActive :: sComplete :: Nil = Enum(3)
    private val state = RegInit(sIdle)
    private val direction = Reg(ActiveSPMDirection())
    private val sourceAddress = Reg(UInt(64.W))
    private val sourceRemaining = Reg(UInt(64.W))
    private val destinationAddress = Reg(UInt(64.W))
    private val destinationRemaining = Reg(UInt(64.W))
    private val requestByteCount = Reg(UInt(64.W))

    private val byteBuffer = RegInit(0.U(bufferBits.W))
    private val bufferedBytes = RegInit(0.U(countBits.W))
    private val bytesCompleted = RegInit(0.U(64.W))
    private val completionError = RegInit(ActiveSPMErrorCode.none)
    private val fatalError = RegInit(false.B)

    private val externalOutstanding = RegInit(false.B)
    private val localOutstanding = RegInit(false.B)
    private val readBytes = Reg(UInt(byteCountBits.W))
    private val readLane = Reg(UInt(log2Ceil(maxBeatBytes).W))
    private val writeBytes = Reg(UInt(byteCountBits.W))

    private val isLoad = direction === ActiveSPMDirection.load
    private val sourceOutstanding = Mux(isLoad, externalOutstanding, localOutstanding)
    private val destinationOutstanding = Mux(isLoad, localOutstanding, externalOutstanding)

    control.request.ready := state === sIdle
    control.completion.valid := state === sComplete
    control.completion.bits.errorCode := completionError
    control.completion.bits.bytesCompleted := bytesCompleted
    control.progress.busy := state === sActive
    control.progress.bytesCompleted := bytesCompleted

    private def containsInterval(address: UInt, lastAddress: UInt): Bool =
      params.externalMemoryRanges.map { range =>
        range.contains(address) && range.contains(lastAddress)
      }.reduce(_ || _)

    private val requestLocalEnd = Cat(0.U(1.W), control.request.bits.localOffset) +&
      Cat(0.U(1.W), control.request.bits.byteCount)
    private val requestExternalEnd = Cat(0.U(1.W), control.request.bits.externalAddress) +&
      Cat(0.U(1.W), control.request.bits.byteCount)
    private val requestOverflows = requestLocalEnd(64) || requestExternalEnd(64)
    private val requestLocalInvalid = requestLocalEnd > params.scratchpadSize.U(65.W)
    private val requestExternalLast = requestExternalEnd(63, 0) - 1.U
    private val requestExternalInvalid = !containsInterval(
      control.request.bits.externalAddress, requestExternalLast)

    private def readChoice(
      edge: TLEdgeOut,
      address: UInt,
      beatBytes: Int): (Bool, UInt, UInt) = {
      val found = WireDefault(false.B)
      val lgSize = WireDefault(0.U(edge.bundle.sizeBits.W))
      val bytes = WireDefault(1.U(byteCountBits.W))
      val freeBytes = bufferBytes.U - bufferedBytes
      for (lg <- 0 to log2Ceil(beatBytes)) {
        val nBytes = 1 << lg
        val aligned = if (lg == 0) true.B else address(lg - 1, 0) === 0.U
        val candidate = aligned && sourceRemaining >= nBytes.U && freeBytes >= nBytes.U &&
          edge.manager.supportsGetSafe(address, lg.U)
        when(candidate) {
          found := true.B
          lgSize := lg.U
          bytes := nBytes.U
        }
      }
      (found, lgSize, bytes)
    }

    private case class WriteChoice(
      found: Bool,
      lgSize: UInt,
      envelopeAddress: UInt,
      bytes: UInt,
      mask: UInt,
      data: UInt,
      useFull: Bool)

    private def writeChoice(
      edge: TLEdgeOut,
      address: UInt,
      beatBytes: Int,
      sourceFinished: Bool): WriteChoice = {
      val found = WireDefault(false.B)
      val lgSize = WireDefault(0.U(edge.bundle.sizeBits.W))
      val envelopeAddress = WireDefault(address)
      val selectedBytes = WireDefault(1.U(byteCountBits.W))
      val selectedMask = WireDefault(0.U(beatBytes.W))
      val selectedData = WireDefault(0.U((beatBytes * 8).W))
      val selectedFull = WireDefault(false.B)
      val busLane = if (beatBytes == 1) 0.U else address(log2Ceil(beatBytes) - 1, 0)

      for (lg <- 0 to log2Ceil(beatBytes)) {
        val envelopeBytes = 1 << lg
        val envelopeMask = (envelopeBytes - 1).U(64.W)
        val base = address & ~envelopeMask
        val laneInEnvelope = if (lg == 0) 0.U else address(lg - 1, 0)
        val room = envelopeBytes.U - laneInEnvelope
        val requestedWide = Mux(destinationRemaining < room, destinationRemaining, room)
        val requested = requestedWide(byteCountBits - 1, 0)
        val chunk = Mux(bufferedBytes < requested, bufferedBytes, requested)
        val completeSpan = bufferedBytes >= requested || sourceFinished
        val maskWide = ((1.U((maxBeatBytes + 1).W) << chunk) - 1.U) << busLane
        val fullMask = edge.mask(base, lg.U)
        val mask = maskWide(beatBytes - 1, 0)
        val full = mask === fullMask
        val putFullLegal = full && edge.manager.supportsPutFullSafe(base, lg.U)
        val putPartialLegal = edge.manager.supportsPutPartialSafe(base, lg.U)
        val candidate = chunk =/= 0.U && completeSpan && (putFullLegal || putPartialLegal)
        when(candidate) {
          found := true.B
          lgSize := lg.U
          envelopeAddress := base
          selectedBytes := chunk
          selectedMask := mask
          selectedData := (byteBuffer << (busLane << 3))(beatBytes * 8 - 1, 0)
          selectedFull := putFullLegal
        }
      }
      WriteChoice(found, lgSize, envelopeAddress, selectedBytes,
        selectedMask, selectedData, selectedFull)
    }

    private val externalRead = readChoice(externalEdge, sourceAddress, externalBeatBytes)
    private val localRead = readChoice(localEdge, sourceAddress, localBeatBytes)
    private val sourceReadFound = Mux(isLoad, externalRead._1, localRead._1)
    private val sourceReadLgSize = Mux(isLoad, externalRead._2, localRead._2)
    private val sourceReadBytes = Mux(isLoad, externalRead._3, localRead._3)

    private val allSourceDataReturned = sourceRemaining === 0.U && !sourceOutstanding
    private val externalWrite = writeChoice(
      externalEdge, destinationAddress, externalBeatBytes, allSourceDataReturned)
    private val localWrite = writeChoice(
      localEdge, destinationAddress, localBeatBytes, allSourceDataReturned)
    private val destinationWriteFound = Mux(isLoad, localWrite.found, externalWrite.found)
    private val destinationWriteBytes = Mux(isLoad, localWrite.bytes, externalWrite.bytes)

    private val sourceCanIssue = state === sActive && !fatalError && !sourceOutstanding &&
      sourceRemaining =/= 0.U && sourceReadFound
    private val destinationCanIssue = state === sActive && !fatalError && !destinationOutstanding &&
      bufferedBytes =/= 0.U && destinationWriteFound

    private def defaultClient(tl: TLBundle): Unit = {
      tl.a.valid := false.B
      tl.a.bits := 0.U.asTypeOf(tl.a.bits)
      tl.c.valid := false.B
      tl.c.bits := 0.U.asTypeOf(tl.c.bits)
      tl.e.valid := false.B
      tl.e.bits := 0.U.asTypeOf(tl.e.bits)
      tl.b.ready := true.B
      tl.d.ready := false.B
    }
    defaultClient(external)
    defaultClient(local)

    private val externalGet = externalEdge.Get(0.U, sourceAddress, sourceReadLgSize)._2
    private val localGet = localEdge.Get(0.U, sourceAddress, sourceReadLgSize)._2
    private val externalPutFull = externalEdge.Put(0.U, externalWrite.envelopeAddress,
      externalWrite.lgSize, externalWrite.data)._2
    private val externalPutPartial = externalEdge.Put(0.U, externalWrite.envelopeAddress,
      externalWrite.lgSize, externalWrite.data, externalWrite.mask)._2
    private val localPutFull = localEdge.Put(0.U, localWrite.envelopeAddress,
      localWrite.lgSize, localWrite.data)._2
    private val localPutPartial = localEdge.Put(0.U, localWrite.envelopeAddress,
      localWrite.lgSize, localWrite.data, localWrite.mask)._2

    when(isLoad) {
      external.a.valid := sourceCanIssue
      external.a.bits := externalGet
      local.a.valid := destinationCanIssue
      local.a.bits := Mux(localWrite.useFull, localPutFull, localPutPartial)
    }.otherwise {
      local.a.valid := sourceCanIssue
      local.a.bits := localGet
      external.a.valid := destinationCanIssue
      external.a.bits := Mux(externalWrite.useFull, externalPutFull, externalPutPartial)
    }
    external.d.ready := externalOutstanding
    local.d.ready := localOutstanding

    private val sourceAFire = Mux(isLoad, external.a.fire, local.a.fire)
    private val destinationAFire = Mux(isLoad, local.a.fire, external.a.fire)
    private val sourceDFire = Mux(isLoad, external.d.fire, local.d.fire)
    private val destinationDFire = Mux(isLoad, local.d.fire, external.d.fire)
    private val sourceDInvalid = Mux(isLoad,
      external.d.bits.denied || external.d.bits.corrupt,
      local.d.bits.denied || local.d.bits.corrupt)
    private val destinationDInvalid = Mux(isLoad,
      local.d.bits.denied || local.d.bits.corrupt,
      external.d.bits.denied || external.d.bits.corrupt)
    private val responseError = (sourceDFire && sourceDInvalid) ||
      (destinationDFire && destinationDInvalid)

    private val sourceResponseData = WireDefault(0.U((maxBeatBytes * 8).W))
    when(isLoad) { sourceResponseData := external.d.bits.data }
      .otherwise { sourceResponseData := local.d.bits.data }
    private val incomingData = sourceResponseData >> (readLane << 3)
    private val enqueueBytes = Mux(sourceDFire && !sourceDInvalid && !fatalError, readBytes, 0.U)
    private val dequeueBytes = Mux(destinationAFire, destinationWriteBytes, 0.U)
    private val countAfterDequeue = bufferedBytes - dequeueBytes
    private val dataAfterDequeue = byteBuffer >> (dequeueBytes << 3)
    private val enqueuedData = Mux(enqueueBytes =/= 0.U, incomingData, 0.U)
    private val nextBuffer = dataAfterDequeue |
      (enqueuedData << (countAfterDequeue << 3))
    private val nextBufferedBytes = countAfterDequeue + enqueueBytes

    private val noReadChoice = state === sActive && !fatalError && !sourceOutstanding &&
      sourceRemaining =/= 0.U && (bufferBytes.U - bufferedBytes) =/= 0.U && !sourceReadFound
    private val writerMustProgress = bufferedBytes === bufferBytes.U || allSourceDataReturned
    private val noWriteChoice = state === sActive && !fatalError && !destinationOutstanding &&
      bufferedBytes =/= 0.U && writerMustProgress && !destinationWriteFound
    private val localConfigurationError = noReadChoice || noWriteChoice

    when(control.request.fire) {
      bytesCompleted := 0.U
      completionError := ActiveSPMErrorCode.none
      fatalError := false.B
      byteBuffer := 0.U
      bufferedBytes := 0.U
      externalOutstanding := false.B
      localOutstanding := false.B
      requestByteCount := control.request.bits.byteCount
      direction := control.request.bits.transferDirection

      when(control.request.bits.byteCount === 0.U) {
        state := sComplete
      }.elsewhen(requestOverflows) {
        completionError := ActiveSPMErrorCode.addressOverflow
        state := sComplete
      }.elsewhen(requestLocalInvalid) {
        completionError := ActiveSPMErrorCode.localRange
        state := sComplete
      }.elsewhen(requestExternalInvalid) {
        completionError := ActiveSPMErrorCode.externalRange
        state := sComplete
      }.otherwise {
        sourceAddress := Mux(control.request.bits.transferDirection === ActiveSPMDirection.load,
          control.request.bits.externalAddress,
          params.scratchpadAddress.base.U + control.request.bits.localOffset)
        destinationAddress := Mux(control.request.bits.transferDirection === ActiveSPMDirection.load,
          params.scratchpadAddress.base.U + control.request.bits.localOffset,
          control.request.bits.externalAddress)
        sourceRemaining := control.request.bits.byteCount
        destinationRemaining := control.request.bits.byteCount
        state := sActive
      }
    }

    when(state === sActive) {
      byteBuffer := nextBuffer
      bufferedBytes := nextBufferedBytes

      when(sourceAFire) {
        readBytes := sourceReadBytes
        readLane := Mux(isLoad,
          if (externalBeatBytes == 1) 0.U else sourceAddress(log2Ceil(externalBeatBytes) - 1, 0),
          if (localBeatBytes == 1) 0.U else sourceAddress(log2Ceil(localBeatBytes) - 1, 0))
        when(isLoad) { externalOutstanding := true.B }
          .otherwise { localOutstanding := true.B }
      }
      when(destinationAFire) {
        writeBytes := destinationWriteBytes
        destinationAddress := destinationAddress + destinationWriteBytes
        destinationRemaining := destinationRemaining - destinationWriteBytes
        when(isLoad) { localOutstanding := true.B }
          .otherwise { externalOutstanding := true.B }
      }
      when(external.d.fire) { externalOutstanding := false.B }
      when(local.d.fire) { localOutstanding := false.B }

      when(sourceDFire && !sourceDInvalid && !fatalError) {
        sourceAddress := sourceAddress + readBytes
        sourceRemaining := sourceRemaining - readBytes
      }
      when(destinationDFire && !destinationDInvalid) {
        bytesCompleted := bytesCompleted + writeBytes
      }

      when(responseError || localConfigurationError) {
        fatalError := true.B
        completionError := ActiveSPMErrorCode.tileLink
        byteBuffer := 0.U
        bufferedBytes := 0.U
      }

      val externalDrained = !externalOutstanding || external.d.fire
      val localDrained = !localOutstanding || local.d.fire
      when(fatalError && externalDrained && localDrained) {
        state := sComplete
      }.elsewhen(!fatalError && sourceRemaining === 0.U && destinationRemaining === 0.U &&
        bufferedBytes === 0.U && !externalOutstanding && !localOutstanding) {
        assert(bytesCompleted === requestByteCount,
          "ActiveSPM successful DMA completion must acknowledge every destination byte")
        state := sComplete
      }

      assert(nextBufferedBytes <= bufferBytes.U, "ActiveSPM DMA byte buffer overflow")
      assert(dequeueBytes <= bufferedBytes, "ActiveSPM DMA byte buffer underflow")
      assert(bytesCompleted <= requestByteCount, "ActiveSPM DMA bytesCompleted exceeds request length")
    }

    when(state === sComplete && control.completion.fire) {
      state := sIdle
      completionError := ActiveSPMErrorCode.none
      bytesCompleted := 0.U
    }

    when(external.d.valid) {
      assert(externalOutstanding, "ActiveSPM external D response without an outstanding request")
      assert(external.d.bits.source === 0.U, "ActiveSPM external D response used an unexpected source ID")
    }
    when(local.d.valid) {
      assert(localOutstanding, "ActiveSPM local D response without an outstanding request")
      assert(local.d.bits.source === 0.U, "ActiveSPM local D response used an unexpected source ID")
    }
    when(sourceDFire) {
      val opcode = Mux(isLoad, external.d.bits.opcode, local.d.bits.opcode)
      assert(opcode === TLMessages.AccessAckData,
        "ActiveSPM DMA source transaction received a non-AccessAckData response")
    }
    when(destinationDFire) {
      val opcode = Mux(isLoad, local.d.bits.opcode, external.d.bits.opcode)
      assert(opcode === TLMessages.AccessAck,
        "ActiveSPM DMA destination transaction received a non-AccessAck response")
    }
  }
}
