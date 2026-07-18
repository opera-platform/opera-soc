package rivet.mdio

import chisel3._
import chisel3.util.HasBlackBoxResource

// BlackBox for the unparameterized `mdio_master.v` Verilog module.
class MdioMasterBlackBox extends BlackBox with HasBlackBoxResource {
  override def desiredName: String = "mdio_master"

  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())

    val cmd_phy_addr = Input(UInt(5.W))
    val cmd_reg_addr = Input(UInt(5.W))
    val cmd_data     = Input(UInt(16.W))
    val cmd_opcode   = Input(UInt(2.W))
    val cmd_valid    = Input(Bool())
    val cmd_ready    = Output(Bool())

    val data_out       = Output(UInt(16.W))
    val data_out_valid = Output(Bool())
    val data_out_ready = Input(Bool())

    val mdc_o  = Output(Bool())
    val mdio_i = Input(Bool())
    val mdio_o = Output(Bool())
    val mdio_t = Output(Bool())

    val busy = Output(Bool())

    val prescale = Input(UInt(8.W))
  })

  // Verilog dependency tree.
  addResource("mdio_master.v")
}
