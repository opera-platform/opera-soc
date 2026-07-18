package chipyard

import org.chipsalliance.cde.config.Config

// Verilog RGMII Ethernet loopback with MAC TX connected to MAC RX in the testbench.
class EthernetRGMIILoopbackRocketConfig extends Config(
  new chipyard.harness.WithEthernetRGMIILoopback ++
  new rivet.wrapper.WithEthernetRGMIISim ++
  new freechips.rocketchip.rocket.WithCFlushEnabled ++
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.AbstractConfig)
