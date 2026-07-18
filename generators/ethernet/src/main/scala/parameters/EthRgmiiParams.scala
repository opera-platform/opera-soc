package rivet.parameters

case class EthRgmiiParams(
  target:              String = "XILINX",
  ioddrStyle:          String = "IODDR",
  clockInputStyle:     String = "BUFR",
  useClk90:            String = "TRUE",
  axisDataWidth:       Int = 8,
  axisKeepEnable:      Int = 0,
  axisKeepWidth:       Int = 1,
  enablePadding:       Int = 1,
  minFrameLength:      Int = 64,
  txFifoDepth:         Int = 4096,
  txFifoRamPipeline:   Int = 1,
  txFrameFifo:         Int = 1,
  txDropOversizeFrame: Int = 1,
  txDropBadFrame:      Int = 1,
  txDropWhenFull:      Int = 0,
  rxFifoDepth:         Int = 4096,
  rxFifoRamPipeline:   Int = 1,
  rxFrameFifo:         Int = 1,
  rxDropOversizeFrame: Int = 1,
  rxDropBadFrame:      Int = 1,
  rxDropWhenFull:      Int = 0
)
