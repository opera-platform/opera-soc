package rivet.wrapper

import chisel3._
import chisel3.util.Cat
import dspblocks.{DspBlock, HasCSR, TLHasCSR}
import freechips.rocketchip.amba.axi4stream._
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.regmapper._
import freechips.rocketchip.resources._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._

import rivet.common.{EthMacCsr, HasEthRgmiiIO, RgmiiIO}
import rivet.parameters.EthRgmiiParams

abstract class EthRgmiiBlock[D, U, E, O, B <: Data](params: EthRgmiiParams)(implicit p: Parameters)
    extends DspBlock[D, U, E, O, B]
    with HasCSR {

  val streamNode = AXI4StreamIdentityNode()

  override lazy val module = new EthImpl

  class EthImpl extends LazyModuleImp(this) with HasEthRgmiiIO {

    val in = streamNode.in.head._1
    val out = streamNode.out.head._1

    val io = IO(new RgmiiIO())
    val ethMac = Module(new EthMac1GRgmiiFifoBlackBox(params = params))

    ethMac.io.gtx_clk := io.gtx_clk
    ethMac.io.gtx_clk90 := io.gtx_clk90
    ethMac.io.gtx_rst := io.gtx_rst
    ethMac.io.logic_clk := clock
    ethMac.io.logic_rst := reset

    ethMac.io.tx_axis_tdata := in.bits.data
    ethMac.io.tx_axis_tkeep := in.bits.keep
    ethMac.io.tx_axis_tvalid := in.valid
    in.ready := ethMac.io.tx_axis_tready
    ethMac.io.tx_axis_tlast := in.bits.last
    ethMac.io.tx_axis_tuser := in.bits.user

    out.bits.data := ethMac.io.rx_axis_tdata
    out.bits.keep := ethMac.io.rx_axis_tkeep
    out.valid := ethMac.io.rx_axis_tvalid
    ethMac.io.rx_axis_tready := out.ready
    out.bits.last := ethMac.io.rx_axis_tlast
    out.bits.user := ethMac.io.rx_axis_tuser

    ethMac.io.rgmii_rx_clk := io.phy.rgmii_rx_clk
    ethMac.io.rgmii_rxd := io.phy.rgmii_rxd.asUInt
    ethMac.io.rgmii_rx_ctl := io.phy.rgmii_rx_ctl
    io.phy.rgmii_tx_clk := ethMac.io.rgmii_tx_clk
    io.phy.rgmii_txd := ethMac.io.rgmii_txd.asBools
    io.phy.rgmii_tx_ctl := ethMac.io.rgmii_tx_ctl

    // Active-low PHY hardware reset, held low for 10 ms after gtx_rst in the 125 MHz gtx_clk domain.
    io.phy.phy_reset_n := EthMacCsr.phyResetActiveLow(io.gtx_clk, io.gtx_rst, freqHz = 125000000.0, holdMs = 10.0)

    // Status: latch the single-cycle MAC event pulses sticky and clear on read (see EthMacCsr).
    val eventPulses = Cat(
      ethMac.io.rx_fifo_good_frame, // bit 8
      ethMac.io.rx_fifo_bad_frame,  // bit 7
      ethMac.io.rx_fifo_overflow,   // bit 6
      ethMac.io.rx_error_bad_fcs,   // bit 5
      ethMac.io.rx_error_bad_frame, // bit 4
      ethMac.io.tx_fifo_good_frame, // bit 3
      ethMac.io.tx_fifo_bad_frame,  // bit 2
      ethMac.io.tx_fifo_overflow,   // bit 1
      ethMac.io.tx_error_underflow  // bit 0
    )
    val statusRead = EthMacCsr.stickyEvents(eventPulses)

    val speedReg = RegInit(0.U(2.W))
    speedReg := ethMac.io.speed

    val cfgIfgReg = RegInit(12.U(8.W))
    val cfgTxEnableReg = RegInit(false.B)
    val cfgRxEnableReg = RegInit(false.B)
    ethMac.io.cfg_ifg := cfgIfgReg
    ethMac.io.cfg_tx_enable := cfgTxEnableReg
    ethMac.io.cfg_rx_enable := cfgRxEnableReg

    regmap(
      // 0x00: Status. bits[8:0] sticky event flags (read-to-clear), bits[10:9] live speed.
      0x00 -> Seq(
        RegField.r(9, RegReadFn(statusRead)),
        RegField.r(2, speedReg)
      ),
      // 0x04: Control
      0x04 -> Seq(
        RegField.w(8, cfgIfgReg),
        RegField.w(1, cfgTxEnableReg),
        RegField.w(1, cfgRxEnableReg)
      )
    )
  }
}

class EthRgmiiTL(params: EthRgmiiParams, csrAddress: AddressSet, beatBytes: Int)(implicit p: Parameters)
    extends EthRgmiiBlock[
      TLClientPortParameters,
      TLManagerPortParameters,
      TLEdgeOut,
      TLEdgeIn,
      TLBundle
    ](params)
    with TLHasCSR {
  val devname = "tlEthernetRGMII"
  val devcompat = Seq("rivet", "rgmii")
  val device = new SimpleDevice(devname, devcompat) {
    override def describe(resources: ResourceBindings): Description = {
      val Description(name, mapping) = super.describe(resources)
      Description(name, mapping)
    }
  }
  // make diplomatic TL node for regmap
  override val mem = Some(TLRegisterNode(address = Seq(csrAddress), device = device, beatBytes = beatBytes))
}
