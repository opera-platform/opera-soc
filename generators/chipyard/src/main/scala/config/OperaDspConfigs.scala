package chipyard

import chipyard.example.operadsp.{OperaDspChainParams, WithOperaDspChain}
import org.chipsalliance.cde.config.Config

class OperaDspRocketConfig extends Config(
  new WithOperaDspChain(OperaDspChainParams(numPoints = 16)) ++
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.AbstractConfig
)
