// OPERA DSP chain register map and helper functions.

#ifndef SOFTWARE_OPERA_DSP_OPERA_DSP_REGS_H_
#define SOFTWARE_OPERA_DSP_OPERA_DSP_REGS_H_

#include "mmio_config.h"

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define OPERA_DSP_NUM_POINTS 256
#define OPERA_DSP_MAX_SAMPLES 4096
#define OPERA_DSP_FFT_SIZE_LOG2_VALUE 8
#define OPERA_DSP_FFT_STAGE_MASK 0xffU

// Scratchpad layout (64 KiB MBUS scratchpad at 0x08000000):
//   input:  0x08000000 .. 0x08004000  (4096 samples x 4 B = 16 KiB max)
//   output: 0x08004000 .. 0x0800C000  (4096 bins x 8 B CFAR words = 32 KiB max)
#define OPERA_DSP_SCRATCH_INPUT_BASE 0x08000000UL
#define OPERA_DSP_SCRATCH_OUTPUT_BASE 0x08004000UL

#define OPERA_DSP_OUT_BYTES_PER_BIN 8

#define OPERA_DSP_CSR_STRIDE 8

#define DMA_ENABLE (OPERA_DSP_DMA_BASE + OPERA_DSP_CSR_STRIDE * 0)
#define DMA_IDLE (OPERA_DSP_DMA_BASE + OPERA_DSP_CSR_STRIDE * 1)
#define DMA_WATCHDOG (OPERA_DSP_DMA_BASE + OPERA_DSP_CSR_STRIDE * 2)
#define DMA_INT (OPERA_DSP_DMA_BASE + OPERA_DSP_CSR_STRIDE * 3)
#define DMA_S2M_BASE (OPERA_DSP_DMA_BASE + OPERA_DSP_CSR_STRIDE * 4)
#define DMA_S2M_LENGTH (OPERA_DSP_DMA_BASE + OPERA_DSP_CSR_STRIDE * 5)
#define DMA_S2M_CYCLES (OPERA_DSP_DMA_BASE + OPERA_DSP_CSR_STRIDE * 6)
#define DMA_S2M_FIXED (OPERA_DSP_DMA_BASE + OPERA_DSP_CSR_STRIDE * 7)
#define DMA_S2M_TRIGGER (OPERA_DSP_DMA_BASE + OPERA_DSP_CSR_STRIDE * 8)
#define DMA_M2S_BASE (OPERA_DSP_DMA_BASE + OPERA_DSP_CSR_STRIDE * 9)
#define DMA_M2S_LENGTH (OPERA_DSP_DMA_BASE + OPERA_DSP_CSR_STRIDE * 10)
#define DMA_M2S_CYCLES (OPERA_DSP_DMA_BASE + OPERA_DSP_CSR_STRIDE * 11)
#define DMA_M2S_FIXED (OPERA_DSP_DMA_BASE + OPERA_DSP_CSR_STRIDE * 12)
#define DMA_M2S_TRIGGER (OPERA_DSP_DMA_BASE + OPERA_DSP_CSR_STRIDE * 13)

#define WINDOWING_SIZE (OPERA_DSP_WINDOWING_BASE + OPERA_DSP_CSR_STRIDE * 0)
#define WINDOWING_CTRL (OPERA_DSP_WINDOWING_BASE + OPERA_DSP_CSR_STRIDE * 1)

#define FFT_SIZE_LOG2 (OPERA_DSP_FFT_BASE + OPERA_DSP_CSR_STRIDE * 0)
#define FFT_DIV_BY_2 (OPERA_DSP_FFT_BASE + OPERA_DSP_CSR_STRIDE * 1)
#define FFT_DIRECTION (OPERA_DSP_FFT_BASE + OPERA_DSP_CSR_STRIDE * 2)
#define FFT_LOAD_CFG (OPERA_DSP_FFT_BASE + OPERA_DSP_CSR_STRIDE * 3)
#define FFT_OVERFLOW (OPERA_DSP_FFT_BASE + OPERA_DSP_CSR_STRIDE * 4)
#define FFT_DRAIN_ON_LAST (OPERA_DSP_FFT_BASE + OPERA_DSP_CSR_STRIDE * 5)

#define MAG_SELECT (OPERA_DSP_MAG_BASE + OPERA_DSP_CSR_STRIDE * 0)

/* Register offsets (index * beatBytes) from opera-dsp cfar CFARRegs.scala; the
 * names match tests/opera-dsp-chain.c. */
#define CFAR_REG_FFT_SIZE (OPERA_DSP_CFAR_BASE + OPERA_DSP_CSR_STRIDE * 0)
#define CFAR_REG_THRESHOLD_SCALE (OPERA_DSP_CFAR_BASE + OPERA_DSP_CSR_STRIDE * 1)
#define CFAR_REG_PEAK_GROUPING (OPERA_DSP_CFAR_BASE + OPERA_DSP_CSR_STRIDE * 2)
#define CFAR_REG_MODE (OPERA_DSP_CFAR_BASE + OPERA_DSP_CSR_STRIDE * 3)
#define CFAR_REG_REFERENCE_CELLS (OPERA_DSP_CFAR_BASE + OPERA_DSP_CSR_STRIDE * 4)
#define CFAR_REG_GUARD_CELLS (OPERA_DSP_CFAR_BASE + OPERA_DSP_CSR_STRIDE * 5)
#define CFAR_REG_NOISE_DIV_SHIFT (OPERA_DSP_CFAR_BASE + OPERA_DSP_CSR_STRIDE * 6)
#define CFAR_REG_LOG_MODE (OPERA_DSP_CFAR_BASE + OPERA_DSP_CSR_STRIDE * 9)
#define CFAR_REG_EDGE_POLICY (OPERA_DSP_CFAR_BASE + OPERA_DSP_CSR_STRIDE * 10)
#define CFAR_REG_LOAD_CFG (OPERA_DSP_CFAR_BASE + OPERA_DSP_CSR_STRIDE * 11)

/* CFAR output word layout, fixed by maxFftSize = 256 in OperaDspChain.scala:
 * [63:41] threshold Q9.14 | [40:9] cut Q18.14 | [8:1] fft_bin | [0] peak.
 * Field parameters match the golden header (opera_dsp_chain_golden.h). */
#define CFAR_MAX_FFT_SIZE 256
#define CFAR_FFT_BIN_BITS 8
#define CFAR_CUT_SHIFT 9
#define CFAR_THR_SHIFT 41
#define CFAR_THR_BITS 23

#define DMA_INT_READ_DONE 0x01
#define DMA_INT_READ_WATCHDOG 0x02
#define DMA_INT_READ_ERROR 0x04
#define DMA_INT_WRITE_DONE 0x08
#define DMA_INT_WRITE_WATCHDOG 0x10
#define DMA_INT_WRITE_ERROR 0x20
#define DMA_ERROR_MASK \
  (DMA_INT_READ_WATCHDOG | DMA_INT_READ_ERROR | DMA_INT_WRITE_WATCHDOG | DMA_INT_WRITE_ERROR)

/* Unpack helpers for the 64-bit CFAR output word; kept textually identical to tests/opera-dsp-chain.c so the two never drift. */
static inline int cfar_peak(uint64_t w)
{
  return (int)(w & 1u);
}

static inline unsigned cfar_bin(uint64_t w)
{
  return (unsigned)((w >> 1) & ((1u << CFAR_FFT_BIN_BITS) - 1u));
}

static inline uint32_t cfar_cut_raw(uint64_t w)
{
  return (uint32_t)(w >> CFAR_CUT_SHIFT);
}

static inline int64_t cfar_thr_raw(uint64_t w)
{
  return (int64_t)w >> CFAR_THR_SHIFT; /* sign-extends: threshold sits at the top */
}

static inline void opera_w64(uintptr_t a, uint64_t v) { *(volatile uint64_t *)a = v; }
static inline uint64_t opera_r64(uintptr_t a) { return *(volatile uint64_t *)a; }

static inline void opera_fence_rw(void) {
  __asm__ volatile("fence rw, rw" ::: "memory");
}

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // SOFTWARE_OPERA_DSP_OPERA_DSP_REGS_H_
