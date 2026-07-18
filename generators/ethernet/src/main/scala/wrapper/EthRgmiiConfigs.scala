package rivet.wrapper

import freechips.rocketchip.diplomacy.AddressSet
import org.chipsalliance.cde.config.Config

import rivet.common.{EthernetRGMIIKey, EthernetRGMIIParams}
import rivet.parameters.EthRgmiiParams

/**
 * Mixin to add the Verilog Forencich RGMII Ethernet MAC to a config.
 */
// DOC include start: WithEthernetRGMII
class WithEthernetRGMII
    extends Config((site, here, up) => { case EthernetRGMIIKey =>
      Some(
        EthernetRGMIIParams(
          dmaAddress      = AddressSet(0x10060000L, 0xfff),
          frontendAddress = AddressSet(0x10061000L, 0xfff),
          ethAddress      = AddressSet(0x10062000L, 0xfff),
          ethParams    = EthRgmiiParams()
        )
      )
    })
// DOC include end: WithEthernetRGMII

/**
 * Simulation variant of [[WithEthernetRGMII]].
 *
 * Pair with `chipyard.harness.WithEthernetRGMIILoopback`, which drives gtx_clk/gtx_clk90 and loops
 * the RGMII TX pins back into the RX pins so the SoC can stream to itself in sim.
 */
class WithEthernetRGMIISim
    extends Config((site, here, up) => { case EthernetRGMIIKey =>
      Some(
        EthernetRGMIIParams(
          dmaAddress      = AddressSet(0x10060000L, 0xfff),
          frontendAddress = AddressSet(0x10061000L, 0xfff),
          ethAddress      = AddressSet(0x10062000L, 0xfff),
          ethParams    = EthRgmiiParams(target = "SIM")
        )
      )
    })
