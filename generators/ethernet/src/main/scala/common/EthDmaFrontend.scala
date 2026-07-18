package rivet.common

import chisel3._
import chisel3.util._
import freechips.rocketchip.amba.axi4stream._
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.regmapper._
import freechips.rocketchip.resources.SimpleDevice
import freechips.rocketchip.tilelink.TLRegisterNode
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._

/** Byte-wide frame stream used between the MAC and the frontend core. */
class EthDmaByte extends Bundle {
  val data = UInt(8.W)
  val last = Bool()
}

/** Eight-byte frame stream used between the frontend core and the DMA. */
class EthDmaBeat extends Bundle {
  val data = UInt(64.W)
  val last = Bool()
}

/**
 * Frame-aware bridge between a byte-wide Ethernet MAC stream and an eight-byte keep-less DMA stream.
 *
 * RX bytes are packed LSB-first and a short final beat is zero padded.
 * TX beats are unpacked LSB-first and trimmed using the queued true frame length;
 * the DMA's input `last` is intentionally not part of this core's TX interface.
 */
class EthDmaFrontendCore(maxFrameBytes: Int, rxLenDepth: Int, txLenDepth: Int) extends Module {
  require(maxFrameBytes > 0)
  require(maxFrameBytes % 8 == 0)
  require(maxFrameBytes <= 0xffff, "frame length must fit the 16-bit length CSRs")
  require(rxLenDepth > 0)
  require(txLenDepth > 0)

  // The DMA is armed before RX_LEN is known and terminates each S2M chunk on stream `last`.
  // This queue is therefore only elasticity between the MAC packer and the DMA's 256-beat write buffer; it need not retain a frame.
  private val rxBeatDepth = math.min(maxFrameBytes / 8, 64)
  private val rxBeatCountWidth = math.max(1, log2Ceil(rxBeatDepth + 1))
  private val rxCountWidth = math.max(1, log2Ceil(rxLenDepth + 1))
  private val txCountWidth = math.max(1, log2Ceil(txLenDepth + 1))

  val io = IO(new Bundle {
    val rxByte = Flipped(Decoupled(new EthDmaByte))
    val rxBeat = Decoupled(new EthDmaBeat)
    val rxLen = Decoupled(UInt(16.W))
    val rxLenCount = Output(UInt(rxCountWidth.W))
    val rxBeatCount = Output(UInt(rxBeatCountWidth.W))

    val txBeat = Flipped(Decoupled(UInt(64.W)))
    val txByte = Decoupled(new EthDmaByte)
    val txLen = Flipped(Decoupled(UInt(16.W)))
    val txSpace = Output(UInt(txCountWidth.W))
  })

  // RX length metadata stays in lockstep with final packed beats.
  // A full length queue therefore backpressures the final MAC byte instead of dropping it.
  val rxLenQueue = Module(new Queue(UInt(16.W), rxLenDepth))
  io.rxLen <> rxLenQueue.io.deq
  io.rxLenCount := rxLenQueue.io.count

  // Match the known-good software queues: a shallow asynchronous-read Queue.
  // The frame-aware DMA consumes it continuously.
  val rxBeatQueue = Module(new Queue(
    UInt(65.W),
    rxBeatDepth,
    useSyncReadMem = false)).suggestName("rxFrameBuffer")

  io.rxBeat.valid := rxBeatQueue.io.deq.valid
  io.rxBeat.bits.data := rxBeatQueue.io.deq.bits(63, 0)
  io.rxBeat.bits.last := rxBeatQueue.io.deq.bits(64)
  rxBeatQueue.io.deq.ready := io.rxBeat.ready
  io.rxBeatCount := rxBeatQueue.io.count

  val rxWord = RegInit(0.U(64.W))
  val rxLane = RegInit(0.U(3.W))
  val rxFrameBytes = RegInit(0.U(16.W))

  val rxShift = Cat(rxLane, 0.U(3.W))
  val rxInserted = rxWord | ((io.rxByte.bits.data.pad(64) << rxShift)(63, 0))
  val rxEmitsBeat = rxLane === 7.U || io.rxByte.bits.last
  val rxCanFinishFrame = !io.rxByte.bits.last || rxLenQueue.io.enq.ready
  val rxCanAccept = !rxEmitsBeat || (rxBeatQueue.io.enq.ready && rxCanFinishFrame)

  io.rxByte.ready := rxCanAccept

  val rxBeatEnqValid = io.rxByte.valid && rxEmitsBeat && rxCanFinishFrame
  rxBeatQueue.io.enq.valid := rxBeatEnqValid
  rxBeatQueue.io.enq.bits := Cat(io.rxByte.bits.last, rxInserted)

  rxLenQueue.io.enq.valid := io.rxByte.valid && io.rxByte.bits.last && rxBeatQueue.io.enq.ready
  rxLenQueue.io.enq.bits := rxFrameBytes + 1.U

  when(io.rxByte.fire) {
    val nextFrameBytes = rxFrameBytes + 1.U
    assert(io.rxByte.bits.last || nextFrameBytes < maxFrameBytes.U,
      "MAC emitted an RX frame larger than maxFrameBytes")
    when(rxEmitsBeat) {
      rxWord := 0.U
      rxLane := 0.U
      when(io.rxByte.bits.last) {
        rxFrameBytes := 0.U
      }.otherwise {
        rxFrameBytes := nextFrameBytes
      }
    }.otherwise {
      rxWord := rxInserted
      rxLane := rxLane + 1.U
      rxFrameBytes := nextFrameBytes
    }
  }

  val txLenQueue = Module(new Queue(UInt(16.W), txLenDepth))
  txLenQueue.io.enq.valid := io.txLen.valid
  txLenQueue.io.enq.bits := io.txLen.bits
  io.txLen.ready := txLenQueue.io.enq.ready
  io.txSpace := txLenDepth.U(txCountWidth.W) - txLenQueue.io.count

  val txActive = RegInit(false.B)
  val txRemaining = RegInit(0.U(16.W))
  val txLane = RegInit(0.U(3.W))

  // Hold DMA beats whenever no frame length is pending.
  // Once active, a beat is consumed only after all of its useful lanes have reached the MAC.
  io.txByte.valid := txActive && io.txBeat.valid
  io.txByte.bits.data := (io.txBeat.bits >> Cat(txLane, 0.U(3.W)))(7, 0)
  io.txByte.bits.last := txActive && txRemaining === 1.U

  val txFinishesBeat = txLane === 7.U || txRemaining === 1.U
  io.txBeat.ready := txActive && io.txByte.ready && txFinishesBeat
  txLenQueue.io.deq.ready := io.txByte.fire && txRemaining === 1.U

  when(!txActive && txLenQueue.io.deq.valid) {
    txActive := true.B
    txRemaining := txLenQueue.io.deq.bits
    txLane := 0.U
  }

  when(io.txByte.fire) {
    when(txRemaining === 1.U) {
      txActive := false.B
      txRemaining := 0.U
      txLane := 0.U
    }.otherwise {
      txRemaining := txRemaining - 1.U
      txLane := Mux(txLane === 7.U, 0.U, txLane + 1.U)
    }
  }
}

/** TileLink CSR wrapper and AXI4-Stream diplomatic shell for [[EthDmaFrontendCore]]. */
class TLEthDmaFrontend(
  csrAddress: AddressSet,
  beatBytes: Int,
  maxFrameBytes: Int = 4096,
  rxLenDepth: Int = 32,
  txLenDepth: Int = 8
) extends LazyModule()(Parameters.empty) {
  require(beatBytes == 8, "ethernet DMA frontend CSRs require 64-bit PBUS beats")

  val rxIn = AXI4StreamSlaveNode(AXI4StreamSlaveParameters())
  val rxOut = AXI4StreamMasterNode(AXI4StreamMasterParameters("ethRxDma", n = 8, u = 0))
  val txIn = AXI4StreamSlaveNode(AXI4StreamSlaveParameters())
  val txOut = AXI4StreamMasterNode(AXI4StreamMasterParameters("ethTxMac", n = 1, u = 1))

  private val device = new SimpleDevice("eth-dma-frontend", Seq("rivet,eth-dma-frontend"))
  val mem = Some(TLRegisterNode(address = Seq(csrAddress), device = device, beatBytes = beatBytes))

  override lazy val module = new LazyModuleImp(this) {
    require(rxIn.in.length == 1 && rxOut.out.length == 1)
    require(txIn.in.length == 1 && txOut.out.length == 1)

    val (macRx, macRxEdge) = rxIn.in.head
    val (dmaRx, dmaRxEdge) = rxOut.out.head
    val (dmaTx, dmaTxEdge) = txIn.in.head
    val (macTx, macTxEdge) = txOut.out.head

    require(macRxEdge.bundle.n == 1, s"MAC RX stream must be byte-wide, got ${macRxEdge.bundle.n}")
    require(dmaRxEdge.bundle.n == 8, s"DMA RX stream must be 8 bytes, got ${dmaRxEdge.bundle.n}")
    require(dmaTxEdge.bundle.n == 8, s"DMA TX stream must be 8 bytes, got ${dmaTxEdge.bundle.n}")
    require(macTxEdge.bundle.n == 1, s"MAC TX stream must be byte-wide, got ${macTxEdge.bundle.n}")
    require(!dmaRxEdge.bundle.hasKeep && !dmaTxEdge.bundle.hasKeep)

    val core = Module(new EthDmaFrontendCore(maxFrameBytes, rxLenDepth, txLenDepth))

    core.io.rxByte.valid := macRx.valid
    core.io.rxByte.bits.data := macRx.bits.data
    core.io.rxByte.bits.last := macRx.bits.last
    macRx.ready := core.io.rxByte.ready

    dmaRx.valid := core.io.rxBeat.valid
    dmaRx.bits := 0.U.asTypeOf(dmaRx.bits)
    dmaRx.bits.data := core.io.rxBeat.bits.data
    dmaRx.bits.last := core.io.rxBeat.bits.last
    core.io.rxBeat.ready := dmaRx.ready

    core.io.txBeat.valid := dmaTx.valid
    core.io.txBeat.bits := dmaTx.bits.data
    dmaTx.ready := core.io.txBeat.ready

    macTx.valid := core.io.txByte.valid
    macTx.bits := 0.U.asTypeOf(macTx.bits)
    macTx.bits.data := core.io.txByte.bits.data
    macTx.bits.last := core.io.txByte.bits.last
    macTx.bits.user := 0.U
    core.io.txByte.ready := macTx.ready

    // Reads always complete. An empty RX_LEN returns zero; a non-empty read pops exactly once when the register transaction completes.
    val rxLenRead = RegReadFn { ready: Bool =>
      val valid = core.io.rxLen.valid
      core.io.rxLen.ready := ready && valid
      (true.B, Mux(valid, Cat(valid, core.io.rxLen.bits), 0.U(17.W)))
    }

    // Present an always-ready register sink so a software write to a full TX length queue completes and is dropped, as documented.
    // Firmware checks TX_SPACE before every write.
    val txLenWrite = Wire(Decoupled(UInt(16.W)))
    txLenWrite.ready := true.B
    core.io.txLen.valid := txLenWrite.valid && core.io.txLen.ready
    core.io.txLen.bits := txLenWrite.bits

    val info = Cat(txLenDepth.U(8.W), rxLenDepth.U(8.W), maxFrameBytes.U(16.W))
    mem.get.regmap(
      0x00 -> Seq(RegField.r(17, rxLenRead)),
      0x08 -> Seq(RegField.r(core.io.rxLenCount.getWidth, core.io.rxLenCount)),
      0x10 -> Seq(RegField.w(16, txLenWrite)),
      0x18 -> Seq(RegField.r(core.io.txSpace.getWidth, core.io.txSpace)),
      0x20 -> Seq(RegField.r(32, info)),
      0x28 -> Seq(RegField.r(core.io.rxBeatCount.getWidth, core.io.rxBeatCount))
    )
  }
}
