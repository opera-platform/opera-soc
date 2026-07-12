package chipyard

import chipyard.example.operadsp.{OperaDspChainParams, WithOperaDspChain}
import org.chipsalliance.cde.config.Config

// DSP chain with a single Rocket core, used for testing the DSP chain in isolation
class OperaDspRocketConfig extends Config(
  new WithOperaDspChain(OperaDspChainParams(numPoints = 256)) ++
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.AbstractConfig
)

// DSP chain with Ethernet loopback and a single Rocket core, used for testing the DSP chain with network connectivity
class OperaDspEthLoopbackRocketConfig extends Config(
  new chipyard.harness.WithEthernetRGMIILoopback ++
  new rivet.wrapper.WithEthernetRGMIISim ++
  new WithOperaDspChain(OperaDspChainParams(numPoints = 256)) ++
  new freechips.rocketchip.rocket.WithCFlushEnabled ++
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.AbstractConfig
)
