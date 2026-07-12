#include <stdint.h>
#include <stdio.h>
#include "mmio.h"
#include "opera_dsp_chain_golden.h"

#define SCRATCH_BASE 0x08000000UL
#define SCRATCH_SIZE 0x10000UL
#define GUARD_WORDS 4
#define CSR_STRIDE 8

#define DMA_BASE 0x10050000UL
#define WINDOWING_BASE 0x10051000UL
#define FFT_BASE 0x10053000UL
#define MAG_BASE 0x10054000UL
#define CFAR_BASE 0x10055000UL

#define DMA_ENABLE (DMA_BASE + CSR_STRIDE * 0)
#define DMA_IDLE (DMA_BASE + CSR_STRIDE * 1)
#define DMA_WATCHDOG (DMA_BASE + CSR_STRIDE * 2)
#define DMA_INT (DMA_BASE + CSR_STRIDE * 3)
#define DMA_S2M_BASE (DMA_BASE + CSR_STRIDE * 4)
#define DMA_S2M_LENGTH (DMA_BASE + CSR_STRIDE * 5)
#define DMA_S2M_CYCLES (DMA_BASE + CSR_STRIDE * 6)
#define DMA_S2M_FIXED (DMA_BASE + CSR_STRIDE * 7)
#define DMA_S2M_TRIGGER (DMA_BASE + CSR_STRIDE * 8)
#define DMA_M2S_BASE (DMA_BASE + CSR_STRIDE * 9)
#define DMA_M2S_LENGTH (DMA_BASE + CSR_STRIDE * 10)
#define DMA_M2S_CYCLES (DMA_BASE + CSR_STRIDE * 11)
#define DMA_M2S_FIXED (DMA_BASE + CSR_STRIDE * 12)
#define DMA_M2S_TRIGGER (DMA_BASE + CSR_STRIDE * 13)

#define WINDOWING_SIZE (WINDOWING_BASE + CSR_STRIDE * 0)
#define WINDOWING_CTRL (WINDOWING_BASE + CSR_STRIDE * 1)

#define FFT_SIZE_LOG2 (FFT_BASE + CSR_STRIDE * 0)
#define FFT_DIV_BY_2 (FFT_BASE + CSR_STRIDE * 1)
#define FFT_DIRECTION (FFT_BASE + CSR_STRIDE * 2)
#define FFT_LOAD_CFG (FFT_BASE + CSR_STRIDE * 3)
#define FFT_OVERFLOW (FFT_BASE + CSR_STRIDE * 4)
#define FFT_DRAIN_ON_LAST (FFT_BASE + CSR_STRIDE * 5)

#define MAG_SELECT (MAG_BASE + CSR_STRIDE * 0)

/* 
 * Register offsets (index * beatBytes) from opera-dsp cfar CFARRegs.scala.
 */
#define CFAR_REG_FFT_SIZE (CFAR_BASE + CSR_STRIDE * 0)
#define CFAR_REG_THRESHOLD_SCALE (CFAR_BASE + CSR_STRIDE * 1)
#define CFAR_REG_PEAK_GROUPING (CFAR_BASE + CSR_STRIDE * 2)
#define CFAR_REG_MODE (CFAR_BASE + CSR_STRIDE * 3)
#define CFAR_REG_REFERENCE_CELLS (CFAR_BASE + CSR_STRIDE * 4)
#define CFAR_REG_GUARD_CELLS (CFAR_BASE + CSR_STRIDE * 5)
#define CFAR_REG_NOISE_DIV_SHIFT (CFAR_BASE + CSR_STRIDE * 6)
#define CFAR_REG_LOG_MODE (CFAR_BASE + CSR_STRIDE * 9)
#define CFAR_REG_EDGE_POLICY (CFAR_BASE + CSR_STRIDE * 10)
#define CFAR_REG_LOAD_CFG (CFAR_BASE + CSR_STRIDE * 11)

#define DMA_INT_READ_DONE 0x01
#define DMA_INT_READ_WATCHDOG 0x02
#define DMA_INT_READ_ERROR 0x04
#define DMA_INT_WRITE_DONE 0x08
#define DMA_INT_WRITE_WATCHDOG 0x10
#define DMA_INT_WRITE_ERROR 0x20

#define DMA_ERROR_MASK \
  (DMA_INT_READ_WATCHDOG | DMA_INT_READ_ERROR | DMA_INT_WRITE_WATCHDOG | DMA_INT_WRITE_ERROR)

#define MAX_MISMATCH_PRINTS 16

/* Memory layout inside the 64 KiB MBUS scratchpad, derived from the golden header:
 * input samples (uint32), then two guarded uint64 output regions for the determinism check.
 * Everything is 64-byte aligned.
 */
#define ALIGN64(x) (((x) + 63UL) & ~63UL)
#define INPUT_BYTES ALIGN64((unsigned long)INPUT_WORDS * 4UL)
#define STORAGE_BYTES ALIGN64(((unsigned long)OUTPUT_WORDS + 2UL * GUARD_WORDS) * 8UL)
#define FIRST_STORAGE_OFFSET INPUT_BYTES
#define SECOND_STORAGE_OFFSET (INPUT_BYTES + STORAGE_BYTES)

_Static_assert(INPUT_WORDS % 2 == 0, "M2S length needs an even 32-bit sample count");
_Static_assert(SCRATCH_BASE % 8 == 0 && FIRST_STORAGE_OFFSET % 8 == 0, "DMA base alignment");
_Static_assert(INPUT_BYTES + 2UL * STORAGE_BYTES <= SCRATCH_SIZE,
               "input + two output regions must fit the 64 KiB scratchpad");

/* CFAR output word:
 * [63:CFAR_THR_SHIFT] threshold | [CFAR_THR_SHIFT-1:CFAR_CUT_SHIFT] cut | [CFAR_CUT_SHIFT-1:1] fft_bin | [0] peak (see DSP_PLAN.md section 3).
 */
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

static inline void fence_rw(void)
{
  asm volatile("fence rw, rw" ::: "memory");
}

#ifndef OPERA_DSP_CHAIN_INPUT_PRELOADED
static void fill_input(volatile uint32_t *input)
{
  for (int i = 0; i < INPUT_WORDS; i++) {
    input[i] = opera_dsp_chain_input[i];
  }
}
#endif

static volatile uint64_t *output_payload(volatile uint64_t *storage)
{
  return storage + GUARD_WORDS;
}

static void prepare_output_region(volatile uint64_t *storage)
{
  const uint64_t guard = 0xa5a5a5a500000000ULL;

  for (int i = 0; i < GUARD_WORDS; i++) {
    storage[i] = guard | (uint64_t)i;
  }
  for (int i = 0; i < OUTPUT_WORDS; i++) {
    output_payload(storage)[i] = 0xdeaddead00000000ULL | (uint64_t)i;
  }
  for (int i = 0; i < GUARD_WORDS; i++) {
    output_payload(storage)[OUTPUT_WORDS + i] = guard | (uint64_t)(0x100 + i);
  }
}

/* READ_DONE/WRITE_DONE pulse at the end of every <= 256-beat sub-burst, 
 * and the remaining CSRs track only the current sub-burst.
 * Completion of the whole descriptor therefore requires:
 * both done bits seen, engine idle, nothing remaining, and the final output word
 * actually landed in memory (it still holds its sentinel until the last beat).
 */
static int poll_dma_done(volatile uint64_t *output)
{
  const uint64_t done_mask = DMA_INT_READ_DONE | DMA_INT_WRITE_DONE;
  const uint64_t last_sentinel = 0xdeaddead00000000ULL | (uint64_t)(OUTPUT_WORDS - 1);
  const long timeout = 200000L + 100L * OUTPUT_WORDS;
  const long stall_limit = 10000; /* iterations (~4 MMIO reads each) with frozen state */
  uint64_t prev_sig = ~0ULL;
  long stall_iters = 0;
  uint64_t status = 0, idle = 0, s2m = 0, m2s = 0;

  for (long i = 0; i < timeout; i++) {
    status = reg_read64(DMA_INT);

    if (status & DMA_ERROR_MASK) {
      printf("opera-dsp-chain: DMA error status=0x%lx\n", (unsigned long)status);
      return 1;
    }
    idle = reg_read64(DMA_IDLE) & 1UL;
    s2m = reg_read64(DMA_S2M_TRIGGER);
    m2s = reg_read64(DMA_M2S_TRIGGER);
    if ((status & done_mask) == done_mask && idle == 1 && s2m == 0 && m2s == 0) {
      fence_rw();
      if (output[OUTPUT_WORDS - 1] != last_sentinel) {
        return 0;
      }
    }

    /* Stall detector: all observable DMA state frozen for stall_limit polls. */
    uint64_t sig = (status << 56) ^ (idle << 48) ^ (s2m << 24) ^ m2s;
    if (sig == prev_sig) {
      if (++stall_iters >= stall_limit) {
        break;
      }
    } else {
      prev_sig = sig;
      stall_iters = 0;
    }
  }

  fence_rw();
  {
    int landed = 0;
    int first_gap = -1;
    for (int i = 0; i < OUTPUT_WORDS; i++) {
      if (output[i] != (0xdeaddead00000000ULL | (uint64_t)i)) {
        landed++;
      } else if (first_gap < 0) {
        first_gap = i;
      }
    }
    printf("opera-dsp-chain: DMA %s: status=0x%lx idle=%lu s2m_remaining=%lu m2s_remaining=%lu "
           "landed=%d/%d first_gap=%d\n",
           stall_iters >= stall_limit ? "STALLED" : "timed out",
           (unsigned long)status, (unsigned long)idle, (unsigned long)s2m, (unsigned long)m2s,
           landed, OUTPUT_WORDS, first_gap);
  }
  return 1;
}

static int write_verify(unsigned long addr, uint64_t value, uint64_t mask, const char *name)
{
  uint64_t actual;

  reg_write64(addr, value);
  actual = reg_read64(addr) & mask;
  if (actual != (value & mask)) {
    printf("opera-dsp-chain: %s CSR readback failed: wrote 0x%lx read 0x%lx\n",
           name, (unsigned long)(value & mask), (unsigned long)actual);
    return 1;
  }
  return 0;
}

static int configure_cfar(void)
{
  int errors = 0;

  /* Values come from the golden header so hardware and expectations cannot drift. */
  errors += write_verify(CFAR_REG_FFT_SIZE, NUM_POINTS, 0x7ff, "cfar fft_size");
  errors += write_verify(CFAR_REG_THRESHOLD_SCALE, CFAR_SCALE_RAW,
                         (1UL << CFAR_THR_BITS) - 1UL, "cfar threshold_scale");
  errors += write_verify(CFAR_REG_PEAK_GROUPING, CFAR_PEAK_GROUPING, 0x1, "cfar peak_grouping");
  errors += write_verify(CFAR_REG_MODE, CFAR_MODE, 0x3, "cfar mode");
  errors += write_verify(CFAR_REG_REFERENCE_CELLS, CFAR_REF_CELLS, 0x1f, "cfar reference_cells");
  errors += write_verify(CFAR_REG_GUARD_CELLS, CFAR_GUARD_CELLS, 0x7, "cfar guard_cells");
  errors += write_verify(CFAR_REG_NOISE_DIV_SHIFT, CFAR_NOISE_DIV_SHIFT, 0x7,
                         "cfar noise_div_shift");
  errors += write_verify(CFAR_REG_LOG_MODE, CFAR_LOG_MODE, 0x1, "cfar log_mode");
  errors += write_verify(CFAR_REG_EDGE_POLICY, CFAR_EDGE_POLICY, 0x3, "cfar edge_policy");
  reg_write64(CFAR_REG_LOAD_CFG, 1); /* write-only pulse */

  return errors != 0;
}

static int configure_dsp(void)
{
  reg_write64(DMA_ENABLE, 0);
  reg_write64(DMA_INT, 0);
  reg_write64(DMA_WATCHDOG, 1000000);

  reg_write64(WINDOWING_SIZE, NUM_POINTS);
  reg_write64(WINDOWING_CTRL, 1);

  reg_write64(FFT_SIZE_LOG2, FFT_SIZE_LOG2_VALUE);
  reg_write64(FFT_DIV_BY_2, FFT_STAGE_MASK);
  reg_write64(FFT_DIRECTION, 1);
  reg_write64(FFT_LOAD_CFG, 1);
  reg_write64(FFT_OVERFLOW, FFT_STAGE_MASK);
  reg_write64(FFT_DRAIN_ON_LAST, 1);

  reg_write64(MAG_SELECT, 1);

  if ((reg_read64(FFT_DRAIN_ON_LAST) & 1UL) != 1UL) {
    printf("opera-dsp-chain: FFT drain_on_last CSR readback failed\n");
    return 1;
  }
  if ((reg_read64(MAG_SELECT) & 1UL) != 1UL) {
    printf("opera-dsp-chain: log-magnitude select CSR readback failed\n");
    return 1;
  }

  return configure_cfar();
}

static int run_dma(volatile uint32_t *input, volatile uint64_t *output, const char *label)
{
  uint64_t overflow;

  reg_write64(DMA_ENABLE, 0);
  reg_write64(DMA_INT, 0);
  reg_write64(FFT_OVERFLOW, FFT_STAGE_MASK);

  if ((reg_read64(DMA_IDLE) & 1UL) != 1UL) {
    printf("opera-dsp-chain: %s DMA not idle before trigger\n", label);
    return 1;
  }

  /* Lengths count 8-byte beats minus one: the input packs two 32-bit samples per beat, the output is one 64-bit CFAR word per bin. */
  reg_write64(DMA_S2M_BASE, (uintptr_t)output);
  reg_write64(DMA_S2M_LENGTH, OUTPUT_WORDS - 1);
  reg_write64(DMA_S2M_CYCLES, 0);
  reg_write64(DMA_S2M_FIXED, 0);

  reg_write64(DMA_M2S_BASE, (uintptr_t)input);
  reg_write64(DMA_M2S_LENGTH, INPUT_WORDS / 2 - 1);
  reg_write64(DMA_M2S_CYCLES, 0);
  reg_write64(DMA_M2S_FIXED, 0);

  reg_write64(DMA_ENABLE, 1);
  reg_write64(DMA_S2M_TRIGGER, 1); /* arm the output side before data can arrive */
  reg_write64(DMA_M2S_TRIGGER, 1);

  if (poll_dma_done(output) != 0) {
    printf("opera-dsp-chain: %s DMA did not complete\n", label);
    return 1;
  }
  fence_rw();

  overflow = reg_read64(FFT_OVERFLOW) & FFT_STAGE_MASK;
  if (overflow != 0) {
    printf("opera-dsp-chain: %s FFT overflow status=0x%lx\n",
           label, (unsigned long)overflow);
    reg_write64(FFT_OVERFLOW, overflow);
  }

  return 0;
}

/* Every output word carries its bin index,
 * so any dropped or duplicated beat anywhere in the chain breaks the sequence exactly where it happened.
 */
static int check_bin_sequence(const char *label, volatile uint64_t *output)
{
  int mismatches = 0;

  for (int i = 0; i < OUTPUT_WORDS; i++) {
    unsigned expected = (unsigned)(i % NUM_POINTS);
    unsigned actual = cfar_bin(output[i]);

    if (actual != expected) {
      if (mismatches < MAX_MISMATCH_PRINTS) {
        printf("opera-dsp-chain: %s bin sequence broken at frame=%d word=%d: "
               "expected bin=%u actual bin=%u (dropped/duplicated beat)\n",
               label, i / NUM_POINTS, i % NUM_POINTS, expected, actual);
      }
      mismatches++;
    }
  }

  if (mismatches != 0) {
    printf("opera-dsp-chain: %s bin sequence mismatches=%d of %d (DATA LOSS in chain)\n",
           label, mismatches, OUTPUT_WORDS);
    return 1;
  }
  return 0;
}

/* CUT == log-magnitude input to CFAR: a mismatch here means the bug is upstream of CFAR (input DMA path, windowing, FFT, or log-magnitude). */
static int check_cut(const char *label, volatile uint64_t *output)
{
  int mismatches = 0;

  for (int i = 0; i < OUTPUT_WORDS; i++) {
    uint32_t expected = opera_dsp_chain_expected_mag[i];
    uint32_t actual = cfar_cut_raw(output[i]);

    if (actual != expected) {
      if (mismatches < MAX_MISMATCH_PRINTS) {
        printf("opera-dsp-chain: %s CUT mismatch frame=%d bin=%d: "
               "expected=0x%x (%ld) actual=0x%x (%ld)\n",
               label, i / NUM_POINTS, i % NUM_POINTS,
               expected, (long)(int32_t)expected, actual, (long)(int32_t)actual);
      }
      mismatches++;
    }
  }

  if (mismatches != 0) {
    printf("opera-dsp-chain: %s CUT mismatches=%d of %d "
           "(bug UPSTREAM of CFAR: DMA-in/windowing/FFT/log-magnitude)\n",
           label, mismatches, OUTPUT_WORDS);
    return 1;
  }
  return 0;
}

/* Full-word compare; runs after bin and CUT pass, so a failure here isolates the threshold/peak fields, i.e. the CFAR block or its configuration. */
static int check_cfar_words(const char *label, volatile uint64_t *output)
{
  int mismatches = 0;

  for (int i = 0; i < OUTPUT_WORDS; i++) {
    uint64_t expected = opera_dsp_chain_expected_cfar[i];
    uint64_t actual = output[i];

    if (actual != expected) {
      if (mismatches < MAX_MISMATCH_PRINTS) {
        printf("opera-dsp-chain: %s CFAR mismatch frame=%d bin=%d: "
               "threshold expected=%ld actual=%ld peak expected=%d actual=%d "
               "(word expected=0x%lx actual=0x%lx)\n",
               label, i / NUM_POINTS, i % NUM_POINTS,
               (long)cfar_thr_raw(expected), (long)cfar_thr_raw(actual),
               cfar_peak(expected), cfar_peak(actual),
               (unsigned long)expected, (unsigned long)actual);
      }
      mismatches++;
    }
  }

  if (mismatches != 0) {
    printf("opera-dsp-chain: %s CFAR word mismatches=%d of %d "
           "(bug in CFAR block or its configuration)\n",
           label, mismatches, OUTPUT_WORDS);
    return 1;
  }
  return 0;
}

static int check_output_region(const char *label, volatile uint64_t *storage)
{
  const uint64_t guard = 0xa5a5a5a500000000ULL;
  volatile uint64_t *output = output_payload(storage);

  for (int i = 0; i < GUARD_WORDS; i++) {
    uint64_t expected = guard | (uint64_t)i;
    if (storage[i] != expected) {
      printf("opera-dsp-chain: %s pre-guard[%d] changed: 0x%lx != 0x%lx\n",
             label, i, (unsigned long)storage[i], (unsigned long)expected);
      return 1;
    }
  }

  for (int i = 0; i < OUTPUT_WORDS; i++) {
    uint64_t sentinel = 0xdeaddead00000000ULL | (uint64_t)i;
    if (output[i] == sentinel) {
      printf("opera-dsp-chain: %s output[%d] was not overwritten\n", label, i);
      return 1;
    }
  }

  for (int i = 0; i < GUARD_WORDS; i++) {
    uint64_t expected = guard | (uint64_t)(0x100 + i);
    if (output[OUTPUT_WORDS + i] != expected) {
      printf("opera-dsp-chain: %s post-guard[%d] changed: 0x%lx != 0x%lx\n",
             label, i, (unsigned long)output[OUTPUT_WORDS + i], (unsigned long)expected);
      return 1;
    }
  }

  return 0;
}

static int check_matching_runs(volatile uint64_t *first, volatile uint64_t *second)
{
  for (int i = 0; i < OUTPUT_WORDS; i++) {
    if (first[i] != second[i]) {
      printf("opera-dsp-chain: repeated run mismatch at word %d: 0x%lx != 0x%lx\n",
             i, (unsigned long)second[i], (unsigned long)first[i]);
      return 1;
    }
  }

  return 0;
}

static int check_output(const char *label, volatile uint64_t *storage)
{
  volatile uint64_t *output = output_payload(storage);
  int failures = 0;

  /* Run every check group so one failure doesn't hide information from the others. */
  failures += check_bin_sequence(label, output);
  failures += check_cut(label, output);
  failures += check_cfar_words(label, output);
  failures += check_output_region(label, storage);
  return failures;
}

static uint32_t checksum_output(volatile uint64_t *output)
{
  uint32_t checksum = 0x811c9dc5U;

  for (int i = 0; i < OUTPUT_WORDS; i++) {
    checksum ^= (uint32_t)output[i];
    checksum *= 0x01000193U;
    checksum ^= (uint32_t)(output[i] >> 32);
    checksum *= 0x01000193U;
  }

  return checksum;
}

static int count_peaks(volatile uint64_t *output)
{
  int peaks = 0;

  for (int i = 0; i < OUTPUT_WORDS; i++) {
    peaks += cfar_peak(output[i]);
  }
  return peaks;
}

int main(void)
{
  volatile uint32_t *input = (volatile uint32_t *)SCRATCH_BASE;
  volatile uint64_t *first_storage = (volatile uint64_t *)(SCRATCH_BASE + FIRST_STORAGE_OFFSET);
  volatile uint64_t *second_storage = (volatile uint64_t *)(SCRATCH_BASE + SECOND_STORAGE_OFFSET);
  volatile uint64_t *first_output = output_payload(first_storage);
  volatile uint64_t *second_output = output_payload(second_storage);

#ifndef OPERA_DSP_CHAIN_INPUT_PRELOADED
  fill_input(input);
#endif
  prepare_output_region(first_storage);
  prepare_output_region(second_storage);
  fence_rw();

  if (configure_dsp() != 0) {
    return 1;
  }
  printf("opera-dsp-chain: configured points=%d frames=%d\n", NUM_POINTS, NUM_FRAMES);

  int failures = 0;

  if (run_dma(input, first_output, "first") != 0) {
    return 1;
  }
  printf("opera-dsp-chain: first DMA run complete\n");
  failures += check_output("first", first_storage);
  if (failures == 0) {
    printf("opera-dsp-chain: first run checks pass\n");
  }

  if (run_dma(input, second_output, "second") != 0) {
    return 1;
  }
  printf("opera-dsp-chain: second DMA run complete\n");
  failures += check_output("second", second_storage);
  failures += check_matching_runs(first_output, second_output);

  if (failures != 0) {
    printf("opera-dsp-chain: FAIL: %d check groups failed (frames=%d peaks=%d checksum=0x%x)\n",
           failures, NUM_FRAMES, count_peaks(first_output), checksum_output(first_output));
    return 1;
  }

  printf("opera-dsp-chain: pass frames=%d peaks=%d checksum=0x%x\n",
         NUM_FRAMES, count_peaks(first_output), checksum_output(first_output));
  return 0;
}
