package rivet.common

import chisel3.IO
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.axi4stream._
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.{Field, Parameters}
import org.chipsalliance.diplomacy.lazymodule._

import rivet.dma.StreamingAXI4DMAWithCSR
import rivet.parameters.EthRgmiiParams
import rivet.wrapper.EthRgmiiTL

// EthernetRGMII Keys
case object EthernetRGMIIKey extends Field[Option[EthernetRGMIIParams]](None)
case object EthernetRGMIIAttachKey extends Field[EthernetRGMIIAttachParams](EthernetRGMIIAttachParams())

case class EthernetRGMIIAttachParams(
  slaveWhere: TLBusWrapperLocation = PBUS,
  masterWhere: TLBusWrapperLocation = FBUS
)

case class EthernetRGMIIParams(
  dmaAddress:      AddressSet = AddressSet(0x10060000L, 0xfff),
  frontendAddress: AddressSet = AddressSet(0x10061000L, 0xfff),
  ethAddress:      AddressSet = AddressSet(0x10062000L, 0xfff),
  maxFrameBytes:   Int = 4096,
  rxLenDepth:      Int = 32,
  txLenDepth:      Int = 8,
  ethParams:       EthRgmiiParams
)

// DOC include start: TLEthernetRGMII
class TLEthernetRGMII(params: EthernetRGMIIParams, beatBytes: Int) extends LazyModule()(Parameters.empty) {
  val bus = LazyModule(new TLXbar)
  val mem = Some(bus.node)
  val dma = LazyModule(new StreamingAXI4DMAWithCSR(
    csrAddress = params.dmaAddress, beatBytes = beatBytes, aligned = true,
    s2mTerminateOnLast = true))
  val frontend = LazyModule(new TLEthDmaFrontend(
    params.frontendAddress, beatBytes, params.maxFrameBytes, params.rxLenDepth, params.txLenDepth))
  frontend.mem.get := bus.node

  dma.streamNode := AXI4StreamBuffer() := frontend.rxOut
  frontend.txIn := AXI4StreamBuffer() := dma.streamNode

  val dmaCsrNode = dma.axiSlaveNode
  val dmaMemoryNode = dma.axiMasterNode

  val mac = LazyModule(new EthRgmiiTL(params.ethParams, csrAddress = params.ethAddress, beatBytes = beatBytes))
  mac.streamNode := frontend.txOut
  frontend.rxIn := mac.streamNode
  mac.mem.get := bus.node

  override lazy val module = new EthImpl
  class EthImpl extends LazyModuleImp(this) with HasEthRgmiiIO {
    val io: RgmiiIO = IO(new RgmiiIO())
    io <> mac.module.io
  }
}
// DOC include end: TLEthernetRGMII

// DOC include start: EthernetRGMII lazy trait
trait CanHavePeripheryEthernetRGMII {
  this: BaseSubsystem =>
  private val portName = "EthernetRGMII"
  val ioRgmii: Option[ModuleValue[RgmiiIO]] = p(EthernetRGMIIKey) match {
    case Some(params) =>
      val manager = locateTLBusWrapper(p(EthernetRGMIIAttachKey).slaveWhere)
      val client = locateTLBusWrapper(p(EthernetRGMIIAttachKey).masterWhere)
      val domain = manager.generateSynchronousDomain.suggestName("ethernet_domain")
      val (ethernet, ethIO) = domain {
        val eth = LazyModule(new TLEthernetRGMII(params = params, manager.beatBytes))
        val innerIO = InModuleBody {
          val rgmiiInner = IO(new RgmiiIO)
          rgmiiInner <> eth.module.io
          rgmiiInner
        }
        (eth, innerIO)
      }
      manager.coupleTo(portName) {
        ethernet.mem.get := TLFIFOFixer() := TLFragmenter(manager.beatBytes, manager.blockBytes) := _
      }
      manager.coupleTo(s"$portName-dma-csr") {
        ethernet.dmaCsrNode := AXI4Buffer() := TLToAXI4() :=
          TLFragmenter(manager.beatBytes, manager.blockBytes, holdFirstDeny = true) := _
      }
      client.coupleFrom(s"$portName-dma") {
        _ := TLFIFOFixer(TLFIFOFixer.all) := TLWidthWidget(8) := AXI4ToTL() :=
          AXI4UserYanker(Some(2)) := AXI4Fragmenter() := AXI4IdIndexer(1) := ethernet.dmaMemoryNode
      }
      // Expose the Ethernet IOs.
      val ethernetIO = InModuleBody {
        val ios = IO(new RgmiiIO())
        ios <> ethIO
        ios
      }
      // Return IO
      Some(ethernetIO)
    case None => None
  }
}
// DOC include end: EthernetRGMII lazy trait
