package rivet.mdio

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.regmapper._
import freechips.rocketchip.resources._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._

trait HasMdioIO {
  def io: MdioIO
}

/**
 * Memory-mapped MDIO master peripheral.
 *
 * Wraps mdio_master.v and exposes a tiny TileLink CSR interface so software can read/write the Ethernet PHY's management registers.
 * Used to bring up the RTL8211E: read PHY ID / link status, enable RGMII internal RX/TX delay, restart auto-negotiation.
 *
 * CSR map (relative to csrAddress):
 *   0x00 (W,16): cmd_data   -- write data for the next WRITE transaction (held)
 *   0x04 (W,12): trigger    -- {reg_addr[4:0], phy_addr[4:0], opcode[1:0]}; writing this issues
 *                              the transaction (opcode 0b10=READ, 0b01=WRITE).
 *   0x08 (R):    status     -- bit0 = busy, bit1 = read-data valid
 *   0x0C (R,16): rdata      -- 16-bit result of the last READ (reading pops it)
 *   0x10 (W,8):  prescale   -- MDC divider; MDC = clk/(2*(prescale+1)). Default 0xFF.
 */
class MdioTL(csrAddress: AddressSet, beatBytes: Int)(implicit p: Parameters) extends LazyModule {
  val devname = "tlEthMdio"
  val devcompat = Seq("rivet", "eth-mdio")
  val device = new SimpleDevice(devname, devcompat) {
    override def describe(resources: ResourceBindings): Description = {
      val Description(name, mapping) = super.describe(resources)
      Description(name, mapping)
    }
  }
  val node = TLRegisterNode(address = Seq(csrAddress), device = device, beatBytes = beatBytes)

  override lazy val module = new MdioImpl
  class MdioImpl extends LazyModuleImp(this) with HasMdioIO {
    val io = IO(new MdioIO())
    val master = Module(new MdioMasterBlackBox())
    master.io.clk := clock
    master.io.rst := reset.asBool

    // PHY pins
    io.mdc := master.io.mdc_o
    master.io.mdio_i := io.mdio_i
    io.mdio_o := master.io.mdio_o
    io.mdio_t := master.io.mdio_t

    // Held config registers.
    val cmdData = RegInit(0.U(16.W))
    val prescale = RegInit(0xff.U(8.W))
    master.io.cmd_data := cmdData
    master.io.prescale := prescale

    // Trigger: writing 0x04 pulses cmd_valid. RegField.w holds the access until cmd_ready, so
    // cmd_phy_addr/reg_addr/opcode stay stable on the accepting cycle.
    val trigger = Wire(DecoupledIO(UInt(12.W)))
    trigger.ready := master.io.cmd_ready
    master.io.cmd_valid := trigger.valid
    master.io.cmd_opcode := trigger.bits(1, 0)
    master.io.cmd_phy_addr := trigger.bits(6, 2)
    master.io.cmd_reg_addr := trigger.bits(11, 7)

    // Read-data pop: reading 0x0C returns data_out and acknowledges it via data_out_ready.
    val rdata = Wire(DecoupledIO(UInt(16.W)))
    rdata.valid := master.io.data_out_valid
    rdata.bits := master.io.data_out
    master.io.data_out_ready := rdata.ready

    node.regmap(
      0x00 -> Seq(RegField.w(16, cmdData)),
      0x04 -> Seq(RegField.w(12, trigger)),
      0x08 -> Seq(RegField.r(1, master.io.busy), RegField.r(1, master.io.data_out_valid)),
      0x0C -> Seq(RegField.r(16, RegReadFn(rdata))),
      0x10 -> Seq(RegField.w(8, prescale))
    )
  }
}
