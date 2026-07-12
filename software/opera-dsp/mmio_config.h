// MMIO base addresses for the OPERA DSP + Ethernet FPGA demo.

#ifndef SOFTWARE_OPERA_DSP_MMIO_CONFIG_H_
#define SOFTWARE_OPERA_DSP_MMIO_CONFIG_H_

#define ETHERNET_DMA_BASE 0x10060000UL
#define ETHERNET_FRONTEND_BASE 0x10061000UL
#define ETHERNET_CSR_BASE 0x10062000UL
#define ETHERNET_MDIO_BASE 0x10063000UL

#define ETH_DMA_TX_BUF 0x0800C000UL
#define ETH_DMA_RX_BUF 0x0800D000UL
#define ETH_MAX_FRAME 4096

#define OPERA_DSP_DMA_BASE 0x10050000UL
#define OPERA_DSP_WINDOWING_BASE 0x10051000UL
#define OPERA_DSP_FFT_BASE 0x10053000UL
#define OPERA_DSP_MAG_BASE 0x10054000UL
#define OPERA_DSP_CFAR_BASE 0x10055000UL

// CFAR runtime defaults.
// These must match the golden-model options in tests/CMakeLists.txt 
// (OPERA_DSP_GOLDEN_OPTIONS) so hardware, baremetal test, and firmware all run the same detector configuration.
#define OPERA_DSP_CFAR_SCALE_RAW 49152  // 3.0 in Q7.14
#define OPERA_DSP_CFAR_REF_CELLS 16
#define OPERA_DSP_CFAR_GUARD_CELLS 4
#define OPERA_DSP_CFAR_NOISE_DIV_SHIFT 4
#define OPERA_DSP_CFAR_MODE 0         // 0=CA, 1=GOCA, 2=SOCA
#define OPERA_DSP_CFAR_EDGE_POLICY 1  // one-sided average at frame edges
#define OPERA_DSP_CFAR_PEAK_GROUPING 0
#define OPERA_DSP_CFAR_LOG_MODE 1  // log-domain threshold (scale is added)

#endif  // SOFTWARE_OPERA_DSP_MMIO_CONFIG_H_
