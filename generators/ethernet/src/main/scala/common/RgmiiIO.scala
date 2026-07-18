package rivet.common

import chisel3._

// Field names mirror the RGMII/PHY signal names and become ChipTop port names referenced by the FPGA harness and XDC.
class RgmiiIO extends Bundle {
  val gtx_clk = Input(Clock())
  val gtx_clk90 = Input(Clock())
  val gtx_rst = Input(Bool())

  val phy = new RgmiiPhyIO()
}

class RgmiiPhyIO extends Bundle {
  val rgmii_rx_clk = Input(Clock())
  val rgmii_rxd = Input(Vec(4, Bool()))
  val rgmii_rx_ctl = Input(Bool())
  val rgmii_tx_clk = Output(Clock())
  val rgmii_txd = Output(Vec(4, Bool()))
  val rgmii_tx_ctl = Output(Bool())
  // Active-low PHY hardware reset.
  val phy_reset_n = Output(Bool())
}

trait HasEthRgmiiIO {
  def io: RgmiiIO
}
