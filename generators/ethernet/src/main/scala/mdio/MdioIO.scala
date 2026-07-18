package rivet.mdio

import chisel3._

// IO bundle for the MDIO master. MDIO is a bidirectional open-drain pin, exposed here as the tri-state triple (i/o/t). Field names mirror the pad signals.
class MdioIO extends Bundle {
  val mdc    = Output(Bool())  // management clock to the PHY (<= 2.5 MHz)
  val mdio_i = Input(Bool())   // MDIO value sampled from the pad
  val mdio_o = Output(Bool())  // MDIO value to drive onto the pad
  val mdio_t = Output(Bool())  // tri-state: 1 = release (Hi-Z), 0 = drive mdio_o
}
