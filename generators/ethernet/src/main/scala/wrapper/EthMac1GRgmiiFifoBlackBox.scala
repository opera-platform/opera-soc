package rivet.wrapper

import chisel3._
import chisel3.experimental.{IntParam, StringParam}
import chisel3.util.HasBlackBoxResource
import rivet.parameters.EthRgmiiParams

/**
 * BlackBox for the Forencich `eth_mac_1g_rgmii_fifo` Verilog module. `desiredName` pins the
 * emitted instance name to the Verilog module, and the IO field names mirror the Verilog ports exactly.
 */
class EthMac1GRgmiiFifoBlackBox(params: EthRgmiiParams)
    extends BlackBox(
      Map(
        "TARGET" -> StringParam(params.target),
        "IODDR_STYLE" -> StringParam(params.ioddrStyle),
        "CLOCK_INPUT_STYLE" -> StringParam(params.clockInputStyle), // BUFG
        "USE_CLK90" -> StringParam(params.useClk90),
        "AXIS_DATA_WIDTH" -> IntParam(params.axisDataWidth),
        "AXIS_KEEP_ENABLE" -> IntParam(params.axisKeepEnable),
        "AXIS_KEEP_WIDTH" -> IntParam(params.axisKeepWidth),
        "ENABLE_PADDING" -> IntParam(params.enablePadding),
        "MIN_FRAME_LENGTH" -> IntParam(params.minFrameLength),
        "TX_FIFO_DEPTH" -> IntParam(params.txFifoDepth),
        "TX_FIFO_RAM_PIPELINE" -> IntParam(params.txFifoRamPipeline),
        "TX_FRAME_FIFO" -> IntParam(params.txFrameFifo),
        "TX_DROP_OVERSIZE_FRAME" -> IntParam(params.txDropOversizeFrame),
        "TX_DROP_BAD_FRAME" -> IntParam(params.txDropBadFrame),
        "TX_DROP_WHEN_FULL" -> IntParam(params.txDropWhenFull),
        "RX_FIFO_DEPTH" -> IntParam(params.rxFifoDepth),
        "RX_FIFO_RAM_PIPELINE" -> IntParam(params.rxFifoRamPipeline),
        "RX_FRAME_FIFO" -> IntParam(params.rxFrameFifo),
        "RX_DROP_OVERSIZE_FRAME" -> IntParam(params.rxDropOversizeFrame),
        "RX_DROP_BAD_FRAME" -> IntParam(params.rxDropBadFrame),
        "RX_DROP_WHEN_FULL" -> IntParam(params.rxDropWhenFull)
      )
    )
    with HasBlackBoxResource {
  override def desiredName: String = "eth_mac_1g_rgmii_fifo"

  val io = IO(new Bundle {
    val gtx_clk = Input(Clock())
    val gtx_clk90 = Input(Clock())
    val gtx_rst = Input(Bool())
    val logic_clk = Input(Clock())
    val logic_rst = Input(Bool())

    val tx_axis_tdata = Input(UInt(params.axisDataWidth.W))
    val tx_axis_tkeep = Input(UInt(params.axisKeepWidth.W))
    val tx_axis_tvalid = Input(Bool())
    val tx_axis_tready = Output(Bool())
    val tx_axis_tlast = Input(Bool())
    val tx_axis_tuser = Input(Bool())

    val rx_axis_tdata = Output(UInt(params.axisDataWidth.W))
    val rx_axis_tkeep = Output(UInt(params.axisKeepWidth.W))
    val rx_axis_tvalid = Output(Bool())
    val rx_axis_tready = Input(Bool())
    val rx_axis_tlast = Output(Bool())
    val rx_axis_tuser = Output(Bool())

    val rgmii_rx_clk = Input(Clock())
    val rgmii_rxd = Input(UInt(4.W))
    val rgmii_rx_ctl = Input(Bool())
    val rgmii_tx_clk = Output(Clock())
    val rgmii_txd = Output(UInt(4.W))
    val rgmii_tx_ctl = Output(Bool())

    val tx_error_underflow = Output(Bool())
    val tx_fifo_overflow = Output(Bool())
    val tx_fifo_bad_frame = Output(Bool())
    val tx_fifo_good_frame = Output(Bool())
    val rx_error_bad_frame = Output(Bool())
    val rx_error_bad_fcs = Output(Bool())
    val rx_fifo_overflow = Output(Bool())
    val rx_fifo_bad_frame = Output(Bool())
    val rx_fifo_good_frame = Output(Bool())
    val speed = Output(UInt(2.W))

    val cfg_ifg = Input(UInt(8.W))
    val cfg_tx_enable = Input(Bool())
    val cfg_rx_enable = Input(Bool())
  })

  // Verilog dependency tree.
  addResource("axis_gmii_rx.v")
  addResource("axis_gmii_tx.v")
  addResource("eth_mac_1g.v")
  addResource("eth_mac_1g_rgmii.v")
  addResource("eth_mac_1g_rgmii_fifo.v")
  addResource("iddr.v")
  addResource("lfsr.v")
  addResource("oddr.v")
  addResource("rgmii_phy_if.v")
  addResource("ssio_ddr_in.v")
  addResource("axis_adapter.v")
  addResource("axis_async_fifo.v")
  addResource("axis_async_fifo_adapter.v")
}
