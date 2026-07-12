package chipyard.example.operadsp

import java.nio.file.{Path, Paths}

import chisel3.util.log2Ceil
import fixedpoint.FixedPoint
import opera.cfar.{CFAREdgePolicy, CFARModel, CFARParams}
import opera.common.ArithmeticUtils
import opera.fft.ModelUtils.RawComplex
import opera.fft.{BitReverseUtils, FFTModel, FFTParams, ModelUtils}
import opera.logmagnitude.LogJPLSquared

import chipyard.example.operadsp.DspChainTestUtils.{
  packComplex,
  readInputText,
  roundHalfUp,
  roundTowardZero,
  wrapSigned
}

/** Implements the bit-exact software model for the complete OPERA DSP chain. */
private[operadsp] object DspChainModel {
  final case class Options(
      numPoints: Int = 1024,
      frames: Int = 3,
      amplitudeRaw: Int = 64,
      cfarScaleRaw: Int = 49152,
      cfarRef: Int = 16,
      cfarGuard: Int = 4,
      cfarShift: Int = 4,
      cfarMode: Int = 0,
      cfarEdgePolicy: Int = CFAREdgePolicy.OneSidedAverage,
      peakGrouping: Int = 0,
      toneAmplitudeRaw: Int = 512,
      toneBins: Seq[Int] = Seq(37, 123, 211),
      inputText: Option[Path] = None,
      expectedHex: Option[Path] = None,
      out: Path = Paths.get("tests/build/generated/opera_dsp_chain_golden.h"))

  final case class GoldenData(
      input: Vector[BigInt],
      expectedMag: Vector[BigInt],
      expectedCfar: Vector[BigInt])

  private object WindowModel extends opera.windowing.TestUtils
  private object MagnitudeModel extends opera.logmagnitude.TestUtils

  /** Evaluates windowing, FFT, log magnitude, and CFAR for every input frame. */
  def generate(chainParams: OperaDspChainParams, options: Options): GoldenData = {
    val fftParams = staticFftParams(OperaDspChainParamsFactory.fft(chainParams))
    val inputFormat = FFTModel.inputFormat(fftParams)
    val cfarParams = OperaDspChainParamsFactory.cfar(chainParams)
    validateHardwareLimits(cfarParams, options)

    val frames = buildInputFrames(chainParams, inputFormat, options)
    val inputWords = frames.flatten.map(packComplex(_, inputFormat.width))
    val magnitudeFrames = frames.map { frame =>
      expectedMagnitudeFrame(chainParams, fftParams, frame)
    }
    val cfarWords = magnitudeFrames.flatMap { magnitudes =>
      expectedCfarFrame(cfarParams, chainParams, magnitudes, options)
    }
    GoldenData(inputWords, magnitudeFrames.flatten, cfarWords)
  }

  /** Ensures runtime CFAR geometry does not exceed elaborated hardware limits. */
  private def validateHardwareLimits(cfarParams: CFARParams[FixedPoint], options: Options): Unit = {
    require(
      options.cfarRef <= cfarParams.maxReferenceCells,
      "cfarRef exceeds hardware maxReferenceCells")
    require(
      options.cfarGuard <= cfarParams.maxGuardCells,
      "cfarGuard exceeds hardware maxGuardCells")
  }

  /** Loads exact samples when provided, otherwise creates deterministic stimulus frames. */
  private def buildInputFrames(
      chainParams: OperaDspChainParams,
      inputFormat: ModelUtils.FixedFormat,
      options: Options): Vector[Vector[RawComplex]] = options.inputText match {
    case Some(path) => readInputText(path, inputFormat, chainParams.numPoints)
    case None =>
      Vector.tabulate(options.frames) { frameIndex =>
        stimulusFrame(inputFormat, chainParams.numPoints, frameIndex, options)
      }
  }

  /** Produces the natural-order log-magnitude bins consumed by CFAR. */
  private def expectedMagnitudeFrame(
      chainParams: OperaDspChainParams,
      fftParams: FFTParams,
      frame: Vector[RawComplex]): Vector[BigInt] = {
    val outputFormat = FFTModel.fftOutputFormat(fftParams)
    val windowed = applyWindow(chainParams, frame)
    val fftCore = FFTModel(fftParams, windowed).checkedFrame(chainParams.numPoints)
    val fftNatural = BitReverseUtils.bitReverse(fftCore)
    fftNatural.map { sample =>
      applyLogMagnitude(chainParams, sample.map(outputFormat.wrap))
    }
  }

  /** Computes and packs one bit-exact CFAR output frame. */
  private def expectedCfarFrame(
      cfarParams: CFARParams[FixedPoint],
      chainParams: OperaDspChainParams,
      magnitudes: Vector[BigInt],
      options: Options): Vector[BigInt] = {
    val logMagnitudeParams = OperaDspChainParamsFactory.logMagnitude(chainParams)
    val binaryPoint = logMagnitudeParams.outputType.binaryPoint.get
    require(
      cfarParams.scaleType.binaryPoint.get == binaryPoint,
      "scale and log-magnitude binary points must match")
    val lsb = math.pow(2.0, binaryPoint.toDouble)
    val cfarMode = CFARModel.caModes.find(_.value == options.cfarMode).getOrElse {
      throw new IllegalArgumentException(s"unsupported CA-CFAR mode ${options.cfarMode}")
    }
    val expected = CFARModel.expectedFrame(
      params = cfarParams,
      data = magnitudes.map(_.toDouble / lsb),
      cfarMode = cfarMode,
      thresholdScale = options.cfarScaleRaw.toDouble / lsb,
      logMode = true,
      referenceCells = options.cfarRef,
      guardCells = options.cfarGuard,
      noiseDivShift = options.cfarShift,
      edgePolicy = options.cfarEdgePolicy,
      peakGrouping = options.peakGrouping != 0)
    packCfarFrame(cfarParams, expected)
  }

  /** Packs model CFAR fields into the hardware's 64-bit output layout. */
  private def packCfarFrame(
      cfarParams: CFARParams[FixedPoint],
      expected: Seq[CFARModel.ExpectedBin]): Vector[BigInt] = {
    val binBits = log2Ceil(cfarParams.maxFftSize)
    val cutShift = 1 + binBits
    val thresholdShift = cutShift + 32
    val thresholdMask = (BigInt(1) << (64 - thresholdShift)) - 1
    val cutMask = (BigInt(1) << 32) - 1
    val binMask = (BigInt(1) << binBits) - 1

    expected.zipWithIndex.map { case (bin, index) =>
      ((bin.threshold.raw & thresholdMask) << thresholdShift) |
        ((bin.cut.raw & cutMask) << cutShift) |
        ((BigInt(index) & binMask) << 1) |
        (if (bin.peak) BigInt(1) else BigInt(0))
    }.toVector
  }

  /** Disables runtime FFT controls to match the statically configured hardware chain. */
  private def staticFftParams(params: FFTParams): FFTParams =
    params.copy(
      runTime = false,
      divBy2Reg = false,
      directionReg = false,
      drainOnLastReg = false)

  /** Creates a chirp floor plus deterministic tones that produce definite CFAR peaks. */
  private def stimulusFrame(
      format: ModelUtils.FixedFormat,
      numPoints: Int,
      frameIndex: Int,
      options: Options): Vector[RawComplex] = {
    Vector.tabulate(numPoints) { sample =>
      val chirpPhase = 2.0 * math.Pi *
        (sample * sample + (frameIndex + 1) * (frameIndex + 3) * sample).toDouble /
        numPoints.toDouble
      val chirpReal = math.cos(chirpPhase) * options.amplitudeRaw.toDouble
      val chirpImag = math.sin(chirpPhase) * options.amplitudeRaw.toDouble
      val (real, imag) = options.toneBins.foldLeft((chirpReal, chirpImag)) {
        case ((realSum, imagSum), bin) =>
          val phase = 2.0 * math.Pi * bin.toDouble * sample.toDouble / numPoints.toDouble
          (
            realSum + math.cos(phase) * options.toneAmplitudeRaw.toDouble,
            imagSum + math.sin(phase) * options.toneAmplitudeRaw.toDouble)
      }
      RawComplex(format.wrap(roundHalfUp(real)), format.wrap(roundHalfUp(imag)))
    }
  }

  /** Applies the configured fixed-point window to one complex input frame. */
  private def applyWindow(
      chainParams: OperaDspChainParams,
      frame: Vector[RawComplex]): Vector[RawComplex] = {
    val params = OperaDspChainParamsFactory.windowing(chainParams)
    val inputWidth = params.inputType.getWidth / 2
    val outputWidth = params.outputType.getWidth / 2
    val inputBinPoint = params.inputType.real.binaryPoint.get
    val outputBinPoint = params.outputType.real.binaryPoint.get
    val coefficientBinPoint = params.coeffType.binaryPoint.get
    val outputFormat = ModelUtils.FixedFormat(outputWidth, outputBinPoint)
    val window = params.windowFunc.function.getOrElse {
      throw new IllegalArgumentException("OperaDsp chain golden model requires a window")
    }
    require(window.length == frame.length, "window and frame lengths must match")

    frame.zip(window).map { case (sample, coefficient) =>
      val (real, imag) = WindowModel.windowModel(
        inputData = packComplex(sample, inputWidth),
        coefficient = coefficient,
        inputWidth = inputWidth,
        inputBinPoint = inputBinPoint,
        outputBinPoint = outputBinPoint,
        coeffBinPoint = coefficientBinPoint,
        trimType = params.trimType)
      RawComplex(outputFormat.wrap(real), outputFormat.wrap(imag))
    }
  }

  /** Converts one complex FFT bin into the chain's fixed-point log magnitude. */
  private def applyLogMagnitude(chainParams: OperaDspChainParams, sample: RawComplex): BigInt = {
    val params = OperaDspChainParamsFactory.logMagnitude(chainParams)
    require(params.magType == LogJPLSquared, "golden model requires LogJPLSquared")
    val inputWidth = params.inputType.getWidth / 2
    val inputBinPoint = params.inputType.real.binaryPoint.get
    val logInputBinPoint = params.realType.get.binaryPoint.get
    val outputWidth = params.outputType.getWidth
    val outputBinPoint = params.outputType.binaryPoint.get

    val jpl = MagnitudeModel.jplModel(
      real = ArithmeticUtils.toSignedNBits(sample.real, inputWidth).toLong,
      imag = ArithmeticUtils.toSignedNBits(sample.imag, inputWidth).toLong,
      inputBinPoint = inputBinPoint,
      outputBinPoint = logInputBinPoint,
      trimType = params.trimType)
    val logarithm = MagnitudeModel.logModel(
      data = jpl,
      inputBinPoint = inputBinPoint,
      lutTableWidth = params.lutTableWidth.get,
      outputBinPoint = outputBinPoint,
      lutTableSize = params.lutTableSize.get,
      trimType = params.trimType)
    wrapSigned(roundTowardZero(logarithm * math.pow(2.0, outputBinPoint)), outputWidth)
  }
}
