package chipyard.example.operadsp

import chisel3._
import chisel3.util.log2Ceil
import dsptools.numbers.{Convergent, DspComplex}
import fixedpoint._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.axi4stream._
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.subsystem.{BaseSubsystem, FBUS, PBUS, TLBusWrapperLocation}
import freechips.rocketchip.tilelink._
import opera.cfar.{CFAREdgePolicy, CFARParams, CFARTL, CFARType}
import opera.fft.{DIF, FFTParams, FFTTL, Radix22}
import opera.logmagnitude.{LogJPLSquared, LogMagnitudeParams, MagnitudeTL}
import opera.windowing.{HammingWindow, WindowingParams, WindowingTL}
import org.chipsalliance.cde.config.{Config, Field, Parameters}
import org.chipsalliance.diplomacy.lazymodule._
import rivet.dma.StreamingAXI4DMAWithCSR

case class OperaDspChainParams(
    numPoints:             Int = 256,
    streamBytes:           Int = 8,
    dmaAddress:            AddressSet = AddressSet(0x10050000L, 0xfff),
    windowingAddress:      AddressSet = AddressSet(0x10051000L, 0xfff),
    windowingRamAddress:   AddressSet = AddressSet(0x10052000L, 0xfff),
    fftAddress:            AddressSet = AddressSet(0x10053000L, 0xfff),
    logMagnitudeAddress:   AddressSet = AddressSet(0x10054000L, 0xfff),
    cfarAddress:           AddressSet = AddressSet(0x10055000L, 0xfff)) {
  require(numPoints > 0, "numPoints must be positive")
  require((numPoints & (numPoints - 1)) == 0, s"numPoints must be a power of two, got $numPoints")
  require(streamBytes == 8, s"DMA stream must match the 64-bit front bus, got $streamBytes bytes")
}

case class OperaDspChainAttachParams(
    slaveWhere:  TLBusWrapperLocation = PBUS,
    masterWhere: TLBusWrapperLocation = FBUS)

case object OperaDspChainKey extends Field[Option[OperaDspChainParams]](None)
case object OperaDspChainAttachKey extends Field[OperaDspChainAttachParams](OperaDspChainAttachParams())

object OperaDspChainParamsFactory {
  def complexQ2p14: DspComplex[FixedPoint] =
    DspComplex(FixedPoint(16.W, 14.BP))

  def windowing(params: OperaDspChainParams): WindowingParams[FixedPoint] =
    WindowingParams.fixed(
      inputType   = complexQ2p14,
      outputType  = complexQ2p14,
      coeffType   = FixedPoint(16.W, 14.BP),
      numPoints   = params.numPoints,
      runTime     = true,
      windowFunc  = HammingWindow(params.numPoints, periodic = true),
      constWindow = true,
      trimType    = Convergent
    )

  def fft(params: OperaDspChainParams): FFTParams = {
    val stages = log2Ceil(params.numPoints)
    FFTParams(
      fftSize       = params.numPoints,
      twiddleType   = complexQ2p14,
      inDataType    = complexQ2p14,
      decimation    = DIF,
      sdfRadix      = Radix22,
      growEnable    = Seq.fill(stages)(false),
      runTime       = true,
      divBy2        = Seq.fill(stages)(true),
      divBy2Reg     = true,
      directionReg  = true,
      overflowReg   = true,
      numAddPipes   = 1,
      numMulPipes   = 1,
      useBitReverse = true,
      drainOnLastReg = true
    )
  }

  def cfarMaxFftSize(params: OperaDspChainParams): Int = math.max(256, params.numPoints)

  def cfar(params: OperaDspChainParams): CFARParams[FixedPoint] = {
    val maxFftSize = cfarMaxFftSize(params)
    // Packed output beat is Cat(threshold, cut, fftBin, peak); size threshold so it is exactly 64 bits.
    val thresholdWidth = 64 - 32 - 1 - log2Ceil(maxFftSize)
    require(thresholdWidth >= 16, s"threshold too narrow for maxFftSize=$maxFftSize")
    CFARParams.fixed(
      inputType         = FixedPoint(32.W, 14.BP),  // must equal log-magnitude output width (hardware assert)
      thresholdType     = FixedPoint(thresholdWidth.W, 14.BP),
      scaleType         = FixedPoint(thresholdWidth.W, 14.BP),
      cfarType          = CFARType.CellAveraging,
      maxReferenceCells = 16,
      maxGuardCells     = 4,
      maxFftSize        = maxFftSize,
      sendCut           = true,
      logMode           = true,
      runtimeLogMode    = true,
      edgePolicy        = CFAREdgePolicy.OneSidedAverage,
      runtimeEdgePolicy = true,
      addPipeStages     = 1,
      mulPipeStages     = 1
    )
  }

  def logMagnitude(params: OperaDspChainParams): LogMagnitudeParams[FixedPoint] =
    LogMagnitudeParams.fixed(
      inputType     = complexQ2p14,
      realType      = Some(FixedPoint(32.W, 14.BP)),
      outputType    = FixedPoint(32.W, 14.BP),
      magType       = LogJPLSquared,
      lutTableSize  = Some(10),
      lutTableWidth = Some(12),
      addPipeRegs   = true,
      mulPipeRegs   = true,
      trimType      = Convergent
    )
}

class AXI4StreamFrameLastInserter(numPoints: Int) extends LazyModule()(Parameters.empty) {
  require(numPoints > 0, "numPoints must be positive")

  val streamNode = AXI4StreamIdentityNode()

  lazy val module = new LazyModuleImp(this) {
    private val countWidth = math.max(1, log2Ceil(numPoints))
    private val sampleCount = RegInit(0.U(countWidth.W))
    private val lastSample = sampleCount === (numPoints - 1).U

    val (in, _) = streamNode.in.head
    val (out, _) = streamNode.out.head

    out.valid := in.valid
    in.ready := out.ready
    out.bits := in.bits
    out.bits.last := lastSample

    when(out.fire) {
      sampleCount := Mux(lastSample, 0.U, sampleCount + 1.U)
    }
  }
}

class TLOperaDspChain(params: OperaDspChainParams, controlBeatBytes: Int)(implicit p: Parameters)
    extends LazyModule()(p) {
  private val fftParams = OperaDspChainParamsFactory.fft(params)

  val dma = LazyModule(new StreamingAXI4DMAWithCSR(
    csrAddress = params.dmaAddress,
    beatBytes = controlBeatBytes,
    aligned = true
  ))
  val frameLast = LazyModule(new AXI4StreamFrameLastInserter(params.numPoints))
  val windowing = LazyModule(new WindowingTL(
    csrAddress = params.windowingAddress,
    ramAddress = params.windowingRamAddress,
    params = OperaDspChainParamsFactory.windowing(params),
    beatBytes = controlBeatBytes
  ))
  val fft = LazyModule(new FFTTL(
    address = params.fftAddress,
    params = fftParams,
    beatBytes = controlBeatBytes
  ))
  val logMagnitude = LazyModule(new MagnitudeTL(
    address = params.logMagnitudeAddress,
    params = OperaDspChainParamsFactory.logMagnitude(params),
    beatBytes = controlBeatBytes
  ))
  val cfar = LazyModule(new CFARTL(
    address = params.cfarAddress,
    params = OperaDspChainParamsFactory.cfar(params),
    beatBytes = controlBeatBytes
  ))

  // DMA runs 8-byte beats (stream width == AXI width); oneToN(2) splits each 64-bit memory word into two 32-bit samples (LSB half first) for the processing path.
  frameLast.streamNode := AXI4StreamBuffer() := AXI4StreamWidthAdapter.oneToN(2) := dma.streamNode
  windowing.streamNode := AXI4StreamBuffer() := frameLast.streamNode
  fft.streamNode := AXI4StreamBuffer() := windowing.streamNode
  logMagnitude.streamNode := AXI4StreamBuffer() := fft.streamNode
  cfar.streamNode := AXI4StreamBuffer() := logMagnitude.streamNode
  dma.streamNode := AXI4StreamBuffer() := cfar.streamNode

  val dmaCsrNode = dma.axiSlaveNode
  val dmaMemoryNode = dma.axiMasterNode
  val windowingMem = windowing.mem.get
  val fftMem = fft.mem.get
  val logMagnitudeMem = logMagnitude.mem.get
  val cfarMem = cfar.mem.get

  lazy val module = new LazyModuleImp(this)
}

trait CanHavePeripheryOperaDspChain { this: BaseSubsystem =>
  private val portName = "opera-dsp"

  val operaDspChain = p(OperaDspChainKey).map { params =>
    val attachParams = p(OperaDspChainAttachKey)
    val manager = locateTLBusWrapper(attachParams.slaveWhere)
    val client = locateTLBusWrapper(attachParams.masterWhere)
    val domain = manager.generateSynchronousDomain.suggestName("opera_dsp_domain")
    val chain = domain {
      LazyModule(new TLOperaDspChain(params, manager.beatBytes)(p))
    }

    manager.coupleTo(s"$portName-dma-csr") {
      chain.dmaCsrNode :=
        AXI4Buffer() :=
        TLToAXI4() :=
        TLFragmenter(manager.beatBytes, manager.blockBytes, holdFirstDeny = true) := _
    }
    manager.coupleTo(s"$portName-windowing") {
      chain.windowingMem := TLFIFOFixer() := TLFragmenter(manager.beatBytes, manager.blockBytes) := _
    }
    manager.coupleTo(s"$portName-fft") {
      chain.fftMem := TLFIFOFixer() := TLFragmenter(manager.beatBytes, manager.blockBytes) := _
    }
    manager.coupleTo(s"$portName-log-magnitude") {
      chain.logMagnitudeMem := TLFIFOFixer() := TLFragmenter(manager.beatBytes, manager.blockBytes) := _
    }
    manager.coupleTo(s"$portName-cfar") {
      chain.cfarMem := TLFIFOFixer() := TLFragmenter(manager.beatBytes, manager.blockBytes) := _
    }

    client.coupleFrom(s"$portName-dma") {
      // The TLFIFOFixer is required because AXI4ToTL recycles TL source IDs assuming FIFO-ordered responses; without it two outstanding bursts can complete out of order and re-use a live source.
      _ :=
        TLFIFOFixer(TLFIFOFixer.all) :=
        TLWidthWidget(params.streamBytes) :=
        AXI4ToTL() :=
        AXI4UserYanker(Some(2)) :=
        AXI4Fragmenter() :=
        AXI4IdIndexer(1) :=
        chain.dmaMemoryNode
    }

    chain
  }
}

class WithOperaDspChain(
    params: OperaDspChainParams = OperaDspChainParams(),
    attachParams: OperaDspChainAttachParams = OperaDspChainAttachParams()
) extends Config((site, here, up) => {
  case OperaDspChainKey => Some(params)
  case OperaDspChainAttachKey => attachParams
})
