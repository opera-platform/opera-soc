package chipyard

import org.chipsalliance.cde.config.Config

// Verilog RGMII Ethernet loopback with MAC TX connected to MAC RX in the testbench.
class EthernetRGMIILoopbackRocketConfig extends Config(
  new chipyard.harness.WithEthernetRGMIILoopback ++
  new rivet.wrapper.WithEthernetRGMIISim ++
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.AbstractConfig)

// Verilog GMII Ethernet loopback with MAC TX connected to MAC RX in the testbench.
class EthernetGMIILoopbackRocketConfig extends Config(
  new chipyard.harness.WithEthernetGMIILoopback ++
  new rivet.wrapper.WithEthernetGMIISim ++
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.AbstractConfig)

// Verilog 10G/XGMII Ethernet loopback with MAC TX connected to MAC RX in the testbench.
class EthernetXGMIILoopbackRocketConfig extends Config(
  new chipyard.harness.WithEthernetXGMIILoopback ++
  new rivet.wrapper.WithEthernetXGMIISim ++
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.AbstractConfig)

// Chisel-native RGMII Ethernet loopback using the same MMIO queues and testbench loopback.
class EthMac1GRgmiiLoopbackRocketConfig extends Config(
  new chipyard.harness.WithEthernetRGMIILoopback ++
  new rivet.WithEthernetRGMIISim ++
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.AbstractConfig)

// Chisel-native GMII Ethernet loopback using the same MMIO queues and testbench loopback.
class EthMac1GGmiiLoopbackRocketConfig extends Config(
  new chipyard.harness.WithEthernetGMIILoopback ++
  new rivet.WithEthernetGMIISim ++
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.AbstractConfig)
