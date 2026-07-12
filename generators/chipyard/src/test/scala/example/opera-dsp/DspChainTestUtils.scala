package chipyard.example.operadsp

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import chisel3.util.log2Ceil
import opera.fft.ModelUtils
import opera.fft.ModelUtils.RawComplex

import chipyard.example.operadsp.DspChainModel.{GoldenData, Options}

/** Provides file, formatting, and fixed-point helpers for the full-chain test. */
private[operadsp] object DspChainTestUtils {
  /** Reads decimal complex samples using the same Q-format conversion as the FPGA host tool. */
  def readInputText(
      path: Path,
      format: ModelUtils.FixedFormat,
      numPoints: Int): Vector[Vector[RawComplex]] = {
    val samples = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
      .linesIterator
      .zipWithIndex
      .flatMap { case (line, zeroBasedLine) =>
        parseInputLine(path, line, zeroBasedLine + 1, format)
      }
      .toVector
    require(samples.nonEmpty, s"input file is empty: $path")
    require(
      samples.size % numPoints == 0,
      s"input sample count ${samples.size} is not a multiple of $numPoints")
    samples.grouped(numPoints).map(_.toVector).toVector
  }

  /** Writes the C header consumed by the full bare-metal DSP-chain regression. */
  def writeHeader(
      path: Path,
      chainParams: OperaDspChainParams,
      options: Options,
      golden: GoldenData): Unit = {
    val words = new StringBuilder
    appendHeaderPreamble(words, chainParams, golden)
    appendCfarLayout(words, chainParams)
    appendCfarConfiguration(words, options)
    appendArray(words, "opera_dsp_chain_input", golden.input)
    words ++= "\n"
    appendArray(words, "opera_dsp_chain_expected_mag", golden.expectedMag)
    words ++= "\n"
    appendArray64(words, "opera_dsp_chain_expected_cfar", golden.expectedCfar)
    words ++= "\n#endif\n"
    writeText(path, words.result())
  }

  /** Writes packed 64-bit CFAR words for the FPGA-result comparison utility. */
  def writeExpectedHex(path: Path, values: Seq[BigInt]): Unit = {
    val mask = (BigInt(1) << 64) - 1
    val contents = values.map { value =>
      val hex = (value & mask).toString(16)
      ("0" * (16 - hex.length)) + hex
    }.mkString("", "\n", "\n")
    writeText(path, contents)
  }

  /** Packs real and imaginary lanes into the hardware's complex-word layout. */
  def packComplex(sample: RawComplex, laneWidth: Int): BigInt = {
    val mask = (BigInt(1) << laneWidth) - 1
    ((sample.real & mask) << laneWidth) | (sample.imag & mask)
  }

  /** Rounds a floating-point model value with the hardware model's half-up rule. */
  def roundHalfUp(value: Double): BigInt =
    BigDecimal(value).setScale(0, BigDecimal.RoundingMode.HALF_UP).toBigInt

  /** Truncates a floating-point model value toward zero. */
  def roundTowardZero(value: Double): BigInt = BigInt(value.toLong)

  /** Reinterprets a raw value as a signed integer of the requested width. */
  def wrapSigned(raw: BigInt, width: Int): BigInt = {
    val mask = (BigInt(1) << width) - 1
    val sign = BigInt(1) << (width - 1)
    val masked = raw & mask
    if ((masked & sign) != 0) masked - (BigInt(1) << width) else masked
  }

  /** Returns true when a size is supported by the radix-2-squared FFT model. */
  def isPowerOfFour(value: Int): Boolean =
    value >= 4 && (value & (value - 1)) == 0 && log2Ceil(value) % 2 == 0

  /** Parses one optional `real imag` input line and quantizes it to the model format. */
  private def parseInputLine(
      path: Path,
      line: String,
      lineNumber: Int,
      format: ModelUtils.FixedFormat): Option[RawComplex] = {
    val body = line.takeWhile(_ != '#').trim
    if (body.isEmpty) {
      None
    } else {
      val fields = body.split("\\s+")
      require(fields.length == 2, s"malformed sample at $path:$lineNumber")
      Some(RawComplex(
        quantizeInput(path, lineNumber, fields(0), format),
        quantizeInput(path, lineNumber, fields(1), format)))
    }
  }

  /** Quantizes one decimal component and checks that it fits the configured input width. */
  private def quantizeInput(
      path: Path,
      lineNumber: Int,
      text: String,
      format: ModelUtils.FixedFormat): BigInt = {
    val scale = math.pow(2.0, format.binaryPoint.toDouble)
    val minimum = -(BigInt(1) << (format.width - 1))
    val maximum = (BigInt(1) << (format.width - 1)) - 1
    val raw = roundHalfUp(text.toDouble * scale)
    require(raw >= minimum && raw <= maximum, s"input range error at $path:$lineNumber: $text")
    format.wrap(raw)
  }

  /** Appends header guards and FFT/frame geometry. */
  private def appendHeaderPreamble(
      builder: StringBuilder,
      chainParams: OperaDspChainParams,
      golden: GoldenData): Unit = {
    val numPoints = chainParams.numPoints
    builder ++= "#ifndef OPERA_DSP_CHAIN_GOLDEN_H\n"
    builder ++= "#define OPERA_DSP_CHAIN_GOLDEN_H\n\n"
    builder ++= "#include <stdint.h>\n\n"
    builder ++= s"#define NUM_POINTS $numPoints\n"
    builder ++= s"#define NUM_FRAMES ${golden.input.size / numPoints}\n"
    builder ++= "#define INPUT_WORDS (NUM_POINTS * NUM_FRAMES)\n"
    builder ++= "/* One 64-bit CFAR word per bin. */\n"
    builder ++= "#define OUTPUT_WORDS INPUT_WORDS\n"
    builder ++= s"#define FFT_SIZE_LOG2_VALUE ${log2Ceil(numPoints)}\n"
    builder ++= "#define FFT_STAGE_MASK ((1U << FFT_SIZE_LOG2_VALUE) - 1U)\n\n"
  }

  /** Appends the packed CFAR word geometry. */
  private def appendCfarLayout(
      builder: StringBuilder,
      chainParams: OperaDspChainParams): Unit = {
    val maximumFftSize = OperaDspChainParamsFactory.cfarMaxFftSize(chainParams)
    val binBits = log2Ceil(maximumFftSize)
    builder ++= "/* CFAR output: threshold | cut | fft_bin | peak. */\n"
    builder ++= s"#define CFAR_MAX_FFT_SIZE $maximumFftSize\n"
    builder ++= s"#define CFAR_FFT_BIN_BITS $binBits\n"
    builder ++= s"#define CFAR_CUT_SHIFT ${1 + binBits}\n"
    builder ++= s"#define CFAR_THR_SHIFT ${1 + binBits + 32}\n"
    builder ++= s"#define CFAR_THR_BITS ${64 - (1 + binBits + 32)}\n\n"
  }

  /** Appends the runtime CFAR settings used to create the expected output. */
  private def appendCfarConfiguration(builder: StringBuilder, options: Options): Unit = {
    builder ++= "/* CFAR configuration used by the expected model and hardware. */\n"
    builder ++= s"#define CFAR_SCALE_RAW ${options.cfarScaleRaw}\n"
    builder ++= s"#define CFAR_REF_CELLS ${options.cfarRef}\n"
    builder ++= s"#define CFAR_GUARD_CELLS ${options.cfarGuard}\n"
    builder ++= s"#define CFAR_NOISE_DIV_SHIFT ${options.cfarShift}\n"
    builder ++= s"#define CFAR_MODE ${options.cfarMode}\n"
    builder ++= s"#define CFAR_EDGE_POLICY ${options.cfarEdgePolicy}\n"
    builder ++= s"#define CFAR_PEAK_GROUPING ${options.peakGrouping}\n"
    builder ++= "#define CFAR_LOG_MODE 1\n\n"
  }

  /** Appends a C uint32_t array with four words per source line. */
  private def appendArray(builder: StringBuilder, name: String, values: Seq[BigInt]): Unit = {
    appendFormattedArray(builder, "uint32_t", name, values, cUInt32)
  }

  /** Appends a C uint64_t array with four words per source line. */
  private def appendArray64(builder: StringBuilder, name: String, values: Seq[BigInt]): Unit = {
    appendFormattedArray(builder, "uint64_t", name, values, cUInt64)
  }

  /** Formats an integer sequence as a static C array. */
  private def appendFormattedArray(
      builder: StringBuilder,
      elementType: String,
      name: String,
      values: Seq[BigInt],
      format: BigInt => String): Unit = {
    builder ++= s"static const $elementType $name[] = {\n"
    values.zipWithIndex.grouped(4).foreach { group =>
      builder ++= "  "
      builder ++= group.map { case (value, _) => format(value) }.mkString(", ")
      if (group.lastOption.exists { case (_, index) => index != values.size - 1 }) {
        builder ++= ","
      }
      builder ++= "\n"
    }
    builder ++= "};\n"
  }

  /** Formats a raw word as an unsigned 32-bit C literal. */
  private def cUInt32(value: BigInt): String = {
    val unsigned = (value & ((BigInt(1) << 32) - 1)).toLong
    f"0x$unsigned%08xU"
  }

  /** Formats a raw word as an unsigned 64-bit C literal. */
  private def cUInt64(value: BigInt): String = {
    val unsigned = value & ((BigInt(1) << 64) - 1)
    f"0x${unsigned.toLong}%016xULL"
  }

  /** Creates parent directories and writes UTF-8 test output. */
  private def writeText(path: Path, contents: String): Unit = {
    val parent = path.toAbsolutePath.getParent
    if (parent != null) {
      Files.createDirectories(parent)
    }
    Files.write(path, contents.getBytes(StandardCharsets.UTF_8))
  }
}
