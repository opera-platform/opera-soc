package rivet.common

import chisel3._
import chisel3.util.{log2Ceil, DecoupledIO}

/**
 * Shared CSR / bring-up helpers for the Ethernet MAC wrappers.
 */
object EthMacCsr {

  /**
   * Latch single-cycle event pulses into a sticky field and clear the whole field when the returned status source is read.
   */
  def stickyEvents(pulses: UInt): DecoupledIO[UInt] = {
    val width = pulses.getWidth
    val events = RegInit(0.U(width.W))
    val status = Wire(DecoupledIO(UInt(width.W)))
    status.valid := true.B
    status.bits := events
    events := Mux(status.ready, 0.U, events) | pulses
    status
  }

  /**
   * Active-low PHY hardware reset, generated in the `clk` domain.
   * Held low while `rst` is high and for `holdMs` milliseconds after it deasserts, then released.
   * The external PHY needs a long, clean reset pulse before it will train the link.
   */
  def phyResetActiveLow(clk: Clock, rst: Bool, freqHz: Double, holdMs: Double): Bool = {
    val resetCycles = (freqHz * holdMs / 1000.0).toInt
    withClockAndReset(clk, rst.asAsyncReset) {
      val count = RegInit(0.U(log2Ceil(resetCycles + 1).W))
      val done = count === resetCycles.U
      when(!done) { count := count + 1.U }
      done
    }
  }
}
