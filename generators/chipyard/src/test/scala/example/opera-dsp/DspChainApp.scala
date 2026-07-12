package chipyard.example.operadsp

import java.nio.file.Paths

import opera.cfar.CFAREdgePolicy

import chipyard.example.operadsp.DspChainModel.Options

/** Generates bit-exact input and expected-output data for the full DSP-chain test. */
object DspChainApp {
  private val VALUE_OPTIONS = Set(
    "--num-points",
    "--frames",
    "--amplitude-raw",
    "--cfar-scale-raw",
    "--cfar-ref",
    "--cfar-guard",
    "--cfar-shift",
    "--cfar-mode",
    "--cfar-edge-policy",
    "--peak-grouping",
    "--tone-amplitude-raw",
    "--tone-bins",
    "--input-text",
    "--expected-hex",
    "--out")

  /** Runs the golden model and writes the requested test artifacts. */
  def main(args: Array[String]): Unit = {
    val options = parseArgs(args)
    val chainParams = OperaDspChainParams(numPoints = options.numPoints)
    val golden = DspChainModel.generate(chainParams, options)
    DspChainTestUtils.writeHeader(options.out, chainParams, options, golden)
    options.expectedHex.foreach { path =>
      DspChainTestUtils.writeExpectedHex(path, golden.expectedCfar)
    }
  }

  /** Parses and validates command-line options for golden-data generation. */
  private def parseArgs(args: Array[String]): Options = {
    var options = Options()
    var index = 0

    while (index < args.length) {
      args(index) match {
        case "--help" | "-h" =>
          printUsage()
          sys.exit(0)
        case option if VALUE_OPTIONS.contains(option) =>
          require(index + 1 < args.length, s"missing value for $option")
          options = updateOption(options, option, args(index + 1))
          index += 2
        case other =>
          throw new IllegalArgumentException(s"unknown argument: $other")
      }
    }

    validateOptions(options)
    options
  }

  /** Applies one validated command-line key/value pair to the options. */
  private def updateOption(
      options: Options,
      option: String,
      value: String): Options = option match {
    case "--num-points" => options.copy(numPoints = value.toInt)
    case "--frames" => options.copy(frames = value.toInt)
    case "--amplitude-raw" => options.copy(amplitudeRaw = value.toInt)
    case "--cfar-scale-raw" => options.copy(cfarScaleRaw = value.toInt)
    case "--cfar-ref" => options.copy(cfarRef = value.toInt)
    case "--cfar-guard" => options.copy(cfarGuard = value.toInt)
    case "--cfar-shift" => options.copy(cfarShift = value.toInt)
    case "--cfar-mode" => options.copy(cfarMode = value.toInt)
    case "--cfar-edge-policy" => options.copy(cfarEdgePolicy = value.toInt)
    case "--peak-grouping" => options.copy(peakGrouping = value.toInt)
    case "--tone-amplitude-raw" => options.copy(toneAmplitudeRaw = value.toInt)
    case "--tone-bins" => options.copy(toneBins = parseToneBins(value))
    case "--input-text" => options.copy(inputText = Some(Paths.get(value)))
    case "--expected-hex" => options.copy(expectedHex = Some(Paths.get(value)))
    case "--out" => options.copy(out = Paths.get(value))
  }

  /** Converts a comma-separated tone-bin list into bin indices. */
  private def parseToneBins(value: String): Seq[Int] =
    value.split(',').filter(_.nonEmpty).map(_.trim.toInt).toSeq

  /** Rejects options that cannot be represented by the configured DSP chain. */
  private def validateOptions(options: Options): Unit = {
    validateFrameOptions(options)
    validateCfarOptions(options)
    require(options.toneAmplitudeRaw >= 0, "toneAmplitudeRaw must be non-negative")
    options.toneBins.foreach { bin =>
      require(
        bin >= 0 && bin < options.numPoints,
        s"tone bin $bin outside 0..${options.numPoints - 1}")
    }
  }

  /** Validates FFT geometry and stimulus sizing. */
  private def validateFrameOptions(options: Options): Unit = {
    require(options.numPoints > 0, "numPoints must be positive")
    require(
      DspChainTestUtils.isPowerOfFour(options.numPoints),
      "Radix22 model requires a power-of-four size")
    require(options.frames > 0, "frames must be positive")
    require(options.amplitudeRaw > 0, "amplitudeRaw must be positive")
  }

  /** Validates the runtime CFAR configuration used by the model and hardware. */
  private def validateCfarOptions(options: Options): Unit = {
    require(options.cfarScaleRaw >= 0, "cfarScaleRaw must be non-negative")
    require(options.cfarRef > 0, "cfarRef must be positive")
    require(options.cfarGuard > 0, "cfarGuard must be positive")
    require(options.cfarShift >= 0, "cfarShift must be non-negative")
    require(Set(0, 1, 2).contains(options.cfarMode), "cfarMode must be 0, 1, or 2")
    require(CFAREdgePolicy.isValid(options.cfarEdgePolicy), "invalid cfarEdgePolicy")
    require(Set(0, 1).contains(options.peakGrouping), "peakGrouping must be 0 or 1")
    require(
      options.numPoints > 2 * (options.cfarRef + options.cfarGuard) + 1,
      s"CFAR window must fit inside a ${options.numPoints}-point frame")
  }

  /** Prints the supported golden-generator command-line options. */
  private def printUsage(): Unit = {
    println(
      "Usage: DspChainApp " +
        "[--num-points 1024] [--frames 3] [--amplitude-raw 64] " +
        "[--cfar-scale-raw 49152] [--cfar-ref 16] [--cfar-guard 4] [--cfar-shift 4] " +
        "[--cfar-mode 0] [--cfar-edge-policy 1] [--peak-grouping 0] " +
        "[--tone-amplitude-raw 512] [--tone-bins 37,123,211] " +
        "[--input-text software/opera-dsp/pc/tx_file.txt] [--expected-hex expected.hex] " +
        "[--out tests/build/generated/opera_dsp_chain_golden.h]")
  }
}
