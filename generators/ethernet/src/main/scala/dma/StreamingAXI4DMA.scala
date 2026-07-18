package rivet.dma

import chisel3._
import chisel3.util.{Cat, Decoupled, log2Ceil, Queue}
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.axi4stream.{
  AXI4StreamIdentityNode,
  DMARequest,
  SimpleDMARequest
}
import freechips.rocketchip.diplomacy.{AddressSet, IdRange}
import freechips.rocketchip.regmapper.{RegField, RegWriteFn}
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}

/** Converts a long DMA descriptor into AXI-sized burst descriptors. */
private[dma] class DescriptorBurstSplitter(
    addressWidth: Int,
    descriptorLengthWidth: Int,
    burstLengthWidth: Int,
    beatBytes: Int)
    extends Module {
  require(descriptorLengthWidth >= burstLengthWidth)

  private val maximumBurstLength = (BigInt(1) << burstLengthWidth) - 1
  private val burstBeats = maximumBurstLength + 1
  private val burstIndexWidth = math.max(1, descriptorLengthWidth - burstLengthWidth)

  val input = IO(Flipped(Decoupled(DMARequest(addressWidth, descriptorLengthWidth))))
  val output = IO(Decoupled(SimpleDMARequest(addressWidth, burstLengthWidth)))

  private val burstIndex = RegInit(0.U(burstIndexWidth.W))
  private val cycleIndex = RegInit(0.U(addressWidth.W))
  private val finalBurst = burstIndex >= (input.bits.length >> burstLengthWidth).asUInt
  private val finalCycle = cycleIndex >= input.bits.cycles

  output.bits.baseAddress := input.bits.baseAddress + burstIndex * (beatBytes * burstBeats).U
  output.bits.length := Mux(
    finalBurst,
    input.bits.length - burstIndex * burstBeats.U,
    maximumBurstLength.U)
  output.bits.fixedAddress := input.bits.fixedAddress
  output.valid := input.valid
  input.ready := finalBurst && finalCycle && output.ready

  when(output.fire) {
    burstIndex := Mux(finalBurst, 0.U, burstIndex + 1.U)
    cycleIndex := Mux(finalBurst, cycleIndex + 1.U, cycleIndex)
  }
  when(input.fire) {
    burstIndex := 0.U
    cycleIndex := 0.U
  }
}

/** Bridges AXI4-Stream traffic to AXI4 memory with optional frame-aware receive completion. */
class StreamingAXI4DMA(
    id: IdRange = IdRange(0, 1),
    aligned: Boolean = false,
    s2mTerminateOnLast: Boolean = false)
    extends LazyModule()(Parameters.empty) {

  val streamNode = AXI4StreamIdentityNode()
  val axiNode = AXI4MasterNode(Seq(AXI4MasterPortParameters(Seq(AXI4MasterParameters(
    name = name,
    id = id,
    aligned = aligned,
    maxFlight = Some(2))))))

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (streamInput, streamInputParameters) = streamNode.in.head
    val (streamOutput, streamOutputParameters) = streamNode.out.head
    val (axi, axiParameters) = axiNode.out.head

    require(streamInputParameters.bundle.hasData)
    require(!streamInputParameters.bundle.hasKeep)
    require(
      streamInputParameters.bundle.n * 8 == axiParameters.bundle.dataBits,
      s"stream is ${streamInputParameters.bundle.n * 8} bits, AXI is " +
        s"${axiParameters.bundle.dataBits} bits")

    val addressWidth: Int = axiParameters.bundle.addrBits
    val lengthWidth: Int = axiParameters.bundle.lenBits
    val burstBeats: Int = 1 << lengthWidth
    val dataWidth: Int = axiParameters.bundle.dataBits
    val beatBytes: Int = dataWidth / 8

    val enable = IO(Input(Bool()))
    val idle = IO(Output(Bool()))
    val watchdogInterval = IO(Input(UInt(64.W)))

    val readComplete = IO(Output(Bool()))
    val readWatchdog = IO(Output(Bool()))
    val readError = IO(Output(Bool()))
    val writeComplete = IO(Output(Bool()))
    val writeWatchdog = IO(Output(Bool()))
    val writeError = IO(Output(Bool()))

    val streamToMemoryRequest = IO(Flipped(Decoupled(
      DMARequest(addressWidth, addressWidth))))
    val streamToMemoryLengthRemaining = IO(Output(UInt(lengthWidth.W)))
    val streamToMemoryCompletedBeats = IO(Output(UInt((lengthWidth + 1).W)))
    val streamToMemoryCompletedLast = IO(Output(Bool()))
    val streamToMemoryQueueCount = IO(Output(UInt()))

    val memoryToStreamRequest = IO(Flipped(Decoupled(
      DMARequest(addressWidth, addressWidth))))
    val memoryToStreamLengthRemaining = IO(Output(UInt(lengthWidth.W)))
    val memoryToStreamQueueCount = IO(Output(UInt()))

    val arprot = IO(Input(UInt(AXI4Parameters.protBits.W)))
    val awprot = IO(Input(UInt(AXI4Parameters.protBits.W)))
    val arcache = IO(Input(UInt(AXI4Parameters.cacheBits.W)))
    val awcache = IO(Input(UInt(AXI4Parameters.cacheBits.W)))

    private val streamToMemoryQueue = Module(new Queue(
      DMARequest(addressWidth, addressWidth), 8))
    streamToMemoryQueue.io.enq <> streamToMemoryRequest
    streamToMemoryQueueCount := streamToMemoryQueue.io.count

    private val streamToMemorySplitter = Module(new DescriptorBurstSplitter(
      addressWidth,
      addressWidth,
      lengthWidth,
      beatBytes))
    streamToMemorySplitter.input <> streamToMemoryQueue.io.deq

    private val memoryToStreamQueue = Module(new Queue(
      DMARequest(addressWidth, addressWidth), 8))
    memoryToStreamQueue.io.enq <> memoryToStreamRequest
    memoryToStreamQueueCount := memoryToStreamQueue.io.count

    private val memoryToStreamSplitter = Module(new DescriptorBurstSplitter(
      addressWidth,
      addressWidth,
      lengthWidth,
      beatBytes))
    memoryToStreamSplitter.input <> memoryToStreamQueue.io.deq

    private val reading = RegInit(false.B)
    private val writing = RegInit(false.B)
    private val writeResponsePending = RegInit(false.B)
    private val readDescriptor = Reg(SimpleDMARequest(addressWidth, lengthWidth))
    private val writeDescriptor = Reg(SimpleDMARequest(addressWidth, lengthWidth))
    private val readBeatCounter = Reg(UInt(lengthWidth.W))
    private val writeBeatCounter = Reg(UInt(lengthWidth.W))
    private val readWatchdogCounter = RegInit(0.U(64.W))
    private val writeWatchdogCounter = RegInit(0.U(64.W))

    memoryToStreamLengthRemaining := Mux(
      reading,
      readDescriptor.length - readBeatCounter,
      0.U)
    streamToMemoryLengthRemaining := Mux(
      writing,
      writeDescriptor.length - writeBeatCounter,
      0.U)

    readComplete := false.B
    readWatchdog := readWatchdogCounter > watchdogInterval
    readError := false.B
    writeComplete := false.B
    writeWatchdog := writeWatchdogCounter > watchdogInterval
    writeError := false.B

    when(reading) {
      readWatchdogCounter := readWatchdogCounter + 1.U
    }
    when(writing || writeResponsePending) {
      writeWatchdogCounter := writeWatchdogCounter + 1.U
    }

    private val readBuffer = Module(new Queue(chiselTypeOf(axi.r.bits), burstBeats))
    readBuffer.io.enq <> axi.r
    readBuffer.io.deq.ready := streamOutput.ready
    streamOutput.valid := readBuffer.io.deq.valid
    streamOutput.bits.data := readBuffer.io.deq.bits.data
    streamOutput.bits.strb := ((BigInt(1) << streamOutputParameters.bundle.n) - 1).U
    streamOutput.bits.last := readBuffer.io.deq.bits.last

    private val readSpaceAvailable =
      readBuffer.io.count <= (burstBeats - 1).U - memoryToStreamSplitter.output.bits.length
    axi.ar.valid := !reading && enable && memoryToStreamSplitter.output.valid && readSpaceAvailable
    memoryToStreamSplitter.output.ready :=
      !reading && enable && axi.ar.ready && readSpaceAvailable
    axi.ar.bits.addr := memoryToStreamSplitter.output.bits.baseAddress
    axi.ar.bits.burst := !memoryToStreamSplitter.output.bits.fixedAddress
    axi.ar.bits.cache := arcache
    axi.ar.bits.id := id.start.U
    axi.ar.bits.len := memoryToStreamSplitter.output.bits.length
    axi.ar.bits.lock := 0.U
    axi.ar.bits.prot := arprot
    axi.ar.bits.size := log2Ceil(beatBytes).U

    when(memoryToStreamSplitter.output.fire) {
      reading := true.B
      readDescriptor := memoryToStreamSplitter.output.bits
      readBeatCounter := 0.U
      readWatchdogCounter := 0.U
    }
    when(axi.r.fire) {
      readBeatCounter := readBeatCounter + 1.U
      when(axi.r.bits.last || readBeatCounter >= readDescriptor.length) {
        readComplete := true.B
        reading := false.B
      }
      readError := axi.r.bits.resp =/= AXI4Parameters.RESP_OKAY
    }

    private val writeBuffer = Module(new Queue(streamInput.bits.cloneType, burstBeats))
    private val writeLastPending = RegInit(false.B)
    private val writeLastBeats = RegInit(0.U((lengthWidth + 1).W))
    private val writeBurstEndedFrame = RegInit(false.B)
    private val writeCompletedBeats = RegInit(0.U((lengthWidth + 1).W))
    private val writeCompletedLast = RegInit(false.B)

    if (s2mTerminateOnLast) {
      val acceptingStream = !writing && !writeLastPending
      writeBuffer.io.enq.valid := streamInput.valid && acceptingStream
      writeBuffer.io.enq.bits := streamInput.bits
      streamInput.ready := writeBuffer.io.enq.ready && acceptingStream
      when(writeBuffer.io.enq.fire && streamInput.bits.last) {
        writeLastPending := true.B
        writeLastBeats := writeBuffer.io.count +& 1.U
      }
    } else {
      writeBuffer.io.enq <> streamInput
    }

    streamToMemoryCompletedBeats := writeCompletedBeats
    streamToMemoryCompletedLast := writeCompletedLast

    private val programmedWriteBeats = streamToMemorySplitter.output.bits.length +& 1.U
    private val writeEndsAtLast = if (s2mTerminateOnLast) {
      writeLastPending && writeLastBeats <= programmedWriteBeats
    } else {
      false.B
    }
    private val actualWriteLength = Mux(
      writeEndsAtLast,
      (writeLastBeats - 1.U)(lengthWidth - 1, 0),
      streamToMemorySplitter.output.bits.length)
    private val writeDataAvailable =
      writeBuffer.io.count > streamToMemorySplitter.output.bits.length || writeEndsAtLast

    axi.aw.valid := !writing && !writeResponsePending && enable &&
      streamToMemorySplitter.output.valid && writeDataAvailable
    streamToMemorySplitter.output.ready := !writing && !writeResponsePending && enable &&
      axi.aw.ready && writeDataAvailable
    axi.aw.bits.addr := streamToMemorySplitter.output.bits.baseAddress
    axi.aw.bits.burst := !streamToMemorySplitter.output.bits.fixedAddress
    axi.aw.bits.cache := awcache
    axi.aw.bits.id := id.start.U
    axi.aw.bits.len := actualWriteLength
    axi.aw.bits.lock := 0.U
    axi.aw.bits.prot := awprot
    axi.aw.bits.size := log2Ceil(beatBytes).U

    axi.w.bits.data := writeBuffer.io.deq.bits.data
    axi.w.bits.strb := writeBuffer.io.deq.bits.makeStrb
    axi.w.bits.last := writeBeatCounter >= writeDescriptor.length
    axi.w.valid := writing && writeBuffer.io.deq.valid
    writeBuffer.io.deq.ready := axi.w.ready && writing

    when(streamToMemorySplitter.output.fire) {
      if (s2mTerminateOnLast) {
        assert(
          streamToMemorySplitter.output.bits.length === streamToMemoryQueue.io.deq.bits.length,
          "tlast-terminated S2M descriptors must fit in one AXI burst")
        assert(
          streamToMemoryQueue.io.deq.bits.cycles === 0.U,
          "tlast-terminated S2M descriptors do not support repeated cycles")
      }
      writing := true.B
      writeDescriptor := streamToMemorySplitter.output.bits
      writeDescriptor.length := actualWriteLength
      writeBurstEndedFrame := writeEndsAtLast
      writeBeatCounter := 0.U
      writeWatchdogCounter := 0.U
    }

    when(axi.w.fire) {
      writeBeatCounter := writeBeatCounter + 1.U
      when(axi.w.bits.last) {
        writing := false.B
        writeResponsePending := true.B
        when(writeBurstEndedFrame) {
          writeLastPending := false.B
          writeLastBeats := 0.U
        }
      }
    }

    axi.b.ready := true.B
    when(axi.b.fire) {
      writeResponsePending := false.B
      writeComplete := true.B
      writeError := axi.b.bits.resp =/= AXI4Parameters.RESP_OKAY
      writeCompletedBeats := writeDescriptor.length +& 1.U
      writeCompletedLast := writeBurstEndedFrame
    }

    idle := !reading && !writing && !writeResponsePending &&
      writeBuffer.io.count === 0.U && readBuffer.io.count === 0.U
  }
}

/** Adds a memory-mapped control and status register interface to the streaming DMA. */
class StreamingAXI4DMAWithCSR(
    csrAddress: AddressSet,
    beatBytes: Int = 4,
    id: IdRange = IdRange(0, 1),
    aligned: Boolean = false,
    s2mTerminateOnLast: Boolean = false)
    extends LazyModule()(Parameters.empty) { outer =>

  val dma = LazyModule(new StreamingAXI4DMA(id, aligned, s2mTerminateOnLast))
  val axiMasterNode = dma.axiNode
  val axiSlaveNode = AXI4RegisterNode(address = csrAddress, beatBytes = beatBytes)
  val streamNode = dma.streamNode

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    private val dmaModule = outer.dma.module
    private val enableRegister = RegInit(false.B)
    private val watchdogRegister = RegInit(0.U((beatBytes * 8).W))
    private val interruptRegister = RegInit(0.U(6.W))

    dmaModule.enable := enableRegister
    dmaModule.watchdogInterval := watchdogRegister.pad(64)

    when(dmaModule.readComplete) {
      interruptRegister := interruptRegister | 1.U
    }
    when(dmaModule.readWatchdog) {
      interruptRegister := interruptRegister | 2.U
    }
    when(dmaModule.readError) {
      interruptRegister := interruptRegister | 4.U
    }
    when(dmaModule.writeComplete) {
      interruptRegister := interruptRegister | 8.U
    }
    when(dmaModule.writeWatchdog) {
      interruptRegister := interruptRegister | 16.U
    }
    when(dmaModule.writeError) {
      interruptRegister := interruptRegister | 32.U
    }

    private val streamToMemoryBits = dmaModule.streamToMemoryRequest.bits
    private val memoryToStreamBits = dmaModule.memoryToStreamRequest.bits
    private val streamToMemoryBase = RegInit(0.U(streamToMemoryBits.addrWidth.W))
    private val streamToMemoryLength = RegInit(0.U(streamToMemoryBits.addrWidth.W))
    private val streamToMemoryCycles = RegInit(0.U(streamToMemoryBits.addrWidth.W))
    private val streamToMemoryFixed = RegInit(false.B)
    private val memoryToStreamBase = RegInit(0.U(memoryToStreamBits.addrWidth.W))
    private val memoryToStreamLength = RegInit(0.U(memoryToStreamBits.addrWidth.W))
    private val memoryToStreamCycles = RegInit(0.U(memoryToStreamBits.addrWidth.W))
    private val memoryToStreamFixed = RegInit(false.B)

    streamToMemoryBits.baseAddress := streamToMemoryBase
    streamToMemoryBits.length := streamToMemoryLength
    streamToMemoryBits.cycles := streamToMemoryCycles
    streamToMemoryBits.fixedAddress := streamToMemoryFixed
    memoryToStreamBits.baseAddress := memoryToStreamBase
    memoryToStreamBits.length := memoryToStreamLength
    memoryToStreamBits.cycles := memoryToStreamCycles
    memoryToStreamBits.fixedAddress := memoryToStreamFixed

    private val arprot = RegInit(0.U(AXI4Parameters.protBits.W))
    private val awprot = RegInit(0.U(AXI4Parameters.protBits.W))
    private val arcache = RegInit(
      AXI4Parameters.CACHE_MODIFIABLE | AXI4Parameters.CACHE_BUFFERABLE)
    private val awcache = RegInit(
      AXI4Parameters.CACHE_MODIFIABLE | AXI4Parameters.CACHE_BUFFERABLE)

    dmaModule.arprot := arprot
    dmaModule.awprot := awprot
    dmaModule.arcache := arcache
    dmaModule.awcache := awcache

    axiSlaveNode.regmap(
      axiSlaveNode.beatBytes * 0 -> Seq(RegField(1, enableRegister)),
      axiSlaveNode.beatBytes * 1 -> Seq(RegField.r(1, dmaModule.idle)),
      axiSlaveNode.beatBytes * 2 -> Seq(RegField(beatBytes * 8, watchdogRegister)),
      axiSlaveNode.beatBytes * 3 -> Seq(RegField(6, interruptRegister)),
      axiSlaveNode.beatBytes * 4 -> Seq(RegField(beatBytes * 8, streamToMemoryBase)),
      axiSlaveNode.beatBytes * 5 -> Seq(RegField(beatBytes * 8, streamToMemoryLength)),
      axiSlaveNode.beatBytes * 6 -> Seq(RegField(beatBytes * 8, streamToMemoryCycles)),
      axiSlaveNode.beatBytes * 7 -> Seq(RegField(1, streamToMemoryFixed)),
      axiSlaveNode.beatBytes * 8 -> Seq(RegField(
        beatBytes * 8,
        dmaModule.streamToMemoryLengthRemaining,
        RegWriteFn { (valid, _) =>
          dmaModule.streamToMemoryRequest.valid := valid
          dmaModule.streamToMemoryRequest.ready
        })),
      axiSlaveNode.beatBytes * 9 -> Seq(RegField(beatBytes * 8, memoryToStreamBase)),
      axiSlaveNode.beatBytes * 10 -> Seq(RegField(beatBytes * 8, memoryToStreamLength)),
      axiSlaveNode.beatBytes * 11 -> Seq(RegField(beatBytes * 8, memoryToStreamCycles)),
      axiSlaveNode.beatBytes * 12 -> Seq(RegField(1, memoryToStreamFixed)),
      axiSlaveNode.beatBytes * 13 -> Seq(RegField(
        beatBytes * 8,
        dmaModule.memoryToStreamLengthRemaining,
        RegWriteFn { (valid, _) =>
          dmaModule.memoryToStreamRequest.valid := valid
          dmaModule.memoryToStreamRequest.ready
        })),
      axiSlaveNode.beatBytes * 14 -> Seq(RegField(AXI4Parameters.protBits, arprot)),
      axiSlaveNode.beatBytes * 15 -> Seq(RegField(AXI4Parameters.protBits, awprot)),
      axiSlaveNode.beatBytes * 16 -> Seq(RegField(AXI4Parameters.cacheBits, arcache)),
      axiSlaveNode.beatBytes * 17 -> Seq(RegField(AXI4Parameters.cacheBits, awcache)),
      axiSlaveNode.beatBytes * 18 -> Seq(RegField.r(
        dmaModule.lengthWidth + 2,
        Cat(
          dmaModule.streamToMemoryCompletedLast,
          dmaModule.streamToMemoryCompletedBeats))))
  }
}
