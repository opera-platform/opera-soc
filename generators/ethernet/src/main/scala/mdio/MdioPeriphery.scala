package rivet.mdio

import chisel3.IO
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.{Config, Field, Parameters}
import org.chipsalliance.diplomacy.lazymodule._

// EthernetMDIO Keys
case object EthernetMDIOKey extends Field[Option[EthernetMDIOParams]](None)
case object EthernetMDIOAttachKey extends Field[EthernetMDIOAttachParams](EthernetMDIOAttachParams())

case class EthernetMDIOAttachParams(
  slaveWhere: TLBusWrapperLocation = PBUS
)

case class EthernetMDIOParams(
  csrAddress: AddressSet
)

// Optionally attach the MDIO master to the peripheral bus and expose its MDC/MDIO IO.
trait CanHavePeripheryEthernetMDIO {
  this: BaseSubsystem =>
  private val portName = "EthernetMDIO"
  val ioMdio: Option[ModuleValue[MdioIO]] = p(EthernetMDIOKey) match {
    case Some(params) =>
      val manager = locateTLBusWrapper(p(EthernetMDIOAttachKey).slaveWhere)
      val domain = manager.generateSynchronousDomain.suggestName("mdio_domain")
      val mdioInner = domain {
        val mdio = LazyModule(new MdioTL(params.csrAddress, manager.beatBytes))
        manager.coupleTo(portName) {
          mdio.node := TLFIFOFixer() := TLFragmenter(manager.beatBytes, manager.blockBytes) := _
        }
        InModuleBody {
          val inner = IO(new MdioIO)
          inner <> mdio.module.io
          inner
        }
      }
      val mdioIO = InModuleBody {
        val ios = IO(new MdioIO()).suggestName("mdio_ios")
        ios <> mdioInner
        ios
      }
      Some(mdioIO)
    case None => None
  }
}

/**
 * Mixin to add the MDIO master to a config.
 * Sits at 0x1006_3000, after the Ethernet DMA, frontend, and MAC CSR pages.
 */
class WithEthernetMDIO
    extends Config((site, here, up) => { case EthernetMDIOKey =>
      Some(
        EthernetMDIOParams(
          csrAddress = AddressSet(0x10063000L, 0xff)
        )
      )
    })
