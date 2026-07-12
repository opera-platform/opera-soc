// Bare-metal FPGA-side OPERA DSP service over raw Ethernet.

#include "eth.h"
#include "opera_dsp_regs.h"
#include "protocol.h"
#include "rtl8211e.h"

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

enum {
  kLinkPollMax = 1000000,
  kBytesPerWord = 4,
};

/* The response carries one 8-byte CFAR word per bin and must fit both the scratchpad output region and the ethernet transfer buffer. */
_Static_assert((uint64_t)OPERA_DSP_MAX_SAMPLES * OPERA_DSP_OUT_BYTES_PER_BIN <=
                   kEthernetMaxFileSize,
               "CFAR response (8 B/bin) exceeds the ethernet file size limit");
_Static_assert(OPERA_DSP_SCRATCH_OUTPUT_BASE - OPERA_DSP_SCRATCH_INPUT_BASE >=
                   (uint64_t)OPERA_DSP_MAX_SAMPLES * kBytesPerWord,
               "input samples overrun the scratchpad output region");

static const uint8_t kFpgaMac[kEthernetMacLen] = {0x02, 0x00, 0x00, 0x00, 0x00, 0x01};

static uint8_t file_buffer[kEthernetMaxFileSize];
static uint8_t rx_frame[kEthernetMaxFrameLen];
static uint8_t tx_frame[kEthernetMaxFrameLen];

static uint32_t read_le32(const uint8_t *buf) {
  return ((uint32_t)buf[0]) | ((uint32_t)buf[1] << 8) |
         ((uint32_t)buf[2] << 16) | ((uint32_t)buf[3] << 24);
}

static void write_le32(uint8_t *buf, uint32_t value) {
  buf[0] = (uint8_t)(value & 0xffu);
  buf[1] = (uint8_t)((value >> 8) & 0xffu);
  buf[2] = (uint8_t)((value >> 16) & 0xffu);
  buf[3] = (uint8_t)((value >> 24) & 0xffu);
}

static int build_frame(const uint8_t dst[kEthernetMacLen],
                       const ethernet_packet_header_t *header,
                       const uint8_t *data,
                       uint32_t data_len) {
  for (int i = 0; i < kEthernetMacLen; i++) {
    tx_frame[i] = dst[i];
    tx_frame[kEthernetMacLen + i] = kFpgaMac[i];
  }
  tx_frame[12] = (uint8_t)(kEthernetEthertype >> 8);
  tx_frame[13] = (uint8_t)(kEthernetEthertype & 0xffu);

  if (ethernet_encode_header(&tx_frame[kEthernetL2HeaderLen],
                             kEthernetHeaderLen + (size_t)data_len, header) < 0) {
    return -1;
  }
  if (data_len > 0) {
    memcpy(&tx_frame[kEthernetL2HeaderLen + kEthernetHeaderLen], data, data_len);
  }

  uint32_t payload_len = kEthernetHeaderLen + data_len;
  uint32_t padded_payload_len = payload_len;
  if (padded_payload_len < kEthernetMinPayloadLen) {
    padded_payload_len = kEthernetMinPayloadLen;
  }
  for (uint32_t i = payload_len; i < padded_payload_len; i++) {
    tx_frame[kEthernetL2HeaderLen + i] = 0;
  }
  return kEthernetL2HeaderLen + (int)padded_payload_len;
}

static int send_packet(const uint8_t dst[kEthernetMacLen],
                       const ethernet_packet_header_t *header,
                       const uint8_t *data,
                       uint32_t data_len) {
  int frame_len = build_frame(dst, header, data, data_len);
  if (frame_len <= 0) {
    printf("[opera-dsp] failed to build packet type=%u seq=%lu\n", header->type,
           (unsigned long)header->sequence);
    return -1;
  }

  if (eth_send_frame_bounded(tx_frame, frame_len) < 0) {
    printf("[opera-dsp] ethernet TX failed type=%u seq=%lu len=%d\n", header->type,
           (unsigned long)header->sequence, frame_len);
    return -1;
  }
  return 0;
}

static int send_control(const uint8_t dst[kEthernetMacLen],
                        uint8_t type,
                        uint8_t code,
                        uint32_t transfer_id,
                        uint32_t sequence) {
  ethernet_packet_header_t header = {
      .type = type,
      .code = code,
      .transfer_id = transfer_id,
      .sequence = sequence,
      .offset = 0,
      .length = 0,
      .crc32 = 0,
  };
  return send_packet(dst, &header, NULL, 0);
}

static int send_ack(const uint8_t dst[kEthernetMacLen],
                    uint32_t transfer_id,
                    uint8_t acked_type,
                    uint32_t sequence) {
  return send_control(dst, kEthernetPacketTypeAck, acked_type, transfer_id, sequence);
}

static int send_error(const uint8_t dst[kEthernetMacLen],
                      uint32_t transfer_id,
                      uint8_t code,
                      uint32_t sequence) {
  return send_control(dst, kEthernetPacketTypeError, code, transfer_id, sequence);
}

static int recv_protocol_packet(const uint8_t *expected_src,
                                uint8_t src[kEthernetMacLen],
                                ethernet_packet_header_t *header,
                                const uint8_t **payload,
                                uint32_t *payload_len) {
  while (true) {
    int len = eth_recv_frame(rx_frame, sizeof(rx_frame));
    if (len < 0) {
      printf("[opera-dsp] ethernet RX failed\n");
      return -1;
    }
    if (len > (int)sizeof(rx_frame)) {
      continue;
    }
    if (len < kEthernetL2HeaderLen + kEthernetHeaderLen) {
      continue;
    }

    uint16_t ethertype = (uint16_t)(((uint16_t)rx_frame[12] << 8) | rx_frame[13]);
    if (ethertype != kEthernetEthertype) {
      continue;
    }
    if (expected_src != NULL && memcmp(&rx_frame[6], expected_src, kEthernetMacLen) != 0) {
      continue;
    }
    if (ethernet_decode_header(&rx_frame[kEthernetL2HeaderLen],
                               (size_t)(len - kEthernetL2HeaderLen), header) < 0) {
      continue;
    }

    if (src != NULL) {
      memcpy(src, &rx_frame[6], kEthernetMacLen);
    }
    if (payload != NULL) {
      *payload = &rx_frame[kEthernetL2HeaderLen + kEthernetHeaderLen];
    }
    if (payload_len != NULL) {
      *payload_len = (uint32_t)(len - kEthernetL2HeaderLen - kEthernetHeaderLen);
    }
    return 0;
  }
}

static int wait_ack(const uint8_t peer_mac[kEthernetMacLen],
                    uint32_t transfer_id,
                    uint8_t acked_type,
                    uint32_t sequence) {
  while (true) {
    ethernet_packet_header_t header;
    if (recv_protocol_packet(peer_mac, NULL, &header, NULL, NULL) < 0) {
      return -1;
    }
    if (header.transfer_id != transfer_id) {
      continue;
    }
    if (header.type == kEthernetPacketTypeAck && header.code == acked_type &&
        header.sequence == sequence) {
      return 0;
    }
    if (header.type == kEthernetPacketTypeError) {
      printf("[opera-dsp] peer error code=%u seq=%lu\n", header.code,
             (unsigned long)header.sequence);
      return -1;
    }
  }
}

static int is_duplicate_data_packet(const ethernet_packet_header_t *header,
                                    const uint8_t *payload,
                                    uint32_t payload_len,
                                    uint32_t expected_sequence,
                                    uint32_t received,
                                    uint32_t file_size) {
  if (expected_sequence == 0 || header->sequence != expected_sequence - 1) {
    return 0;
  }
  if (header->length > kEthernetMaxChunkLen || header->length > payload_len) {
    return 0;
  }
  if (header->offset > file_size || header->length > file_size - header->offset) {
    return 0;
  }
  if (header->offset + header->length != received) {
    return 0;
  }
  if (ethernet_crc32(payload, header->length) != header->crc32) {
    return 0;
  }
  if (header->length > 0 &&
      memcmp(&file_buffer[header->offset], payload, header->length) != 0) {
    return 0;
  }
  return 1;
}

static int receive_file(uint8_t peer_mac[kEthernetMacLen],
                        uint32_t *transfer_id,
                        uint32_t *file_size,
                        uint32_t *file_crc32) {
  printf("[opera-dsp] waiting for START\n");

  while (true) {
    uint8_t src[kEthernetMacLen];
    ethernet_packet_header_t header;
    if (recv_protocol_packet(NULL, src, &header, NULL, NULL) < 0) {
      return -1;
    }

    if (header.type != kEthernetPacketTypeStart) {
      continue;
    }

    memcpy(peer_mac, src, kEthernetMacLen);
    *transfer_id = header.transfer_id;
    *file_size = header.length;
    *file_crc32 = header.crc32;

    if (*file_size > kEthernetMaxFileSize) {
      printf("[opera-dsp] reject oversized file size=%lu\n", (unsigned long)*file_size);
      (void)send_error(peer_mac, *transfer_id, kEthernetErrorCodeTooLarge, header.sequence);
      continue;
    }

    if (send_ack(peer_mac, *transfer_id, kEthernetPacketTypeStart, header.sequence) < 0) {
      return -1;
    }
    printf("[opera-dsp] START id=%lu size=%lu crc=0x%08lx\n",
           (unsigned long)*transfer_id, (unsigned long)*file_size,
           (unsigned long)*file_crc32);
    break;
  }

  uint32_t expected_sequence = 0;
  uint32_t received = 0;
  while (true) {
    const uint8_t *payload = NULL;
    uint32_t payload_len = 0;
    ethernet_packet_header_t header;
    if (recv_protocol_packet(peer_mac, NULL, &header, &payload, &payload_len) < 0) {
      return -1;
    }

    if (header.transfer_id != *transfer_id) {
      continue;
    }

    if (header.type == kEthernetPacketTypeStart) {
      if (send_ack(peer_mac, *transfer_id, kEthernetPacketTypeStart, header.sequence) < 0) {
        return -1;
      }
    } else if (header.type == kEthernetPacketTypeData) {
      if (is_duplicate_data_packet(&header, payload, payload_len, expected_sequence,
                                   received, *file_size)) {
        if (send_ack(peer_mac, *transfer_id, kEthernetPacketTypeData, header.sequence) < 0) {
          return -1;
        }
        continue;
      }

      if (header.sequence != expected_sequence || header.offset != received ||
          header.length > kEthernetMaxChunkLen || header.length > payload_len ||
          header.length > *file_size - received) {
        printf("[opera-dsp] DATA sequence/range error seq=%lu expected=%lu\n",
               (unsigned long)header.sequence, (unsigned long)expected_sequence);
        (void)send_error(peer_mac, *transfer_id, kEthernetErrorCodeSequence, header.sequence);
        return -1;
      }

      uint32_t chunk_crc32 = ethernet_crc32(payload, header.length);
      if (chunk_crc32 != header.crc32) {
        printf("[opera-dsp] DATA checksum error seq=%lu\n",
               (unsigned long)header.sequence);
        (void)send_error(peer_mac, *transfer_id, kEthernetErrorCodeChecksum, header.sequence);
        return -1;
      }

      if (header.length > 0) {
        memcpy(&file_buffer[received], payload, header.length);
      }
      received += header.length;
      if (send_ack(peer_mac, *transfer_id, kEthernetPacketTypeData, header.sequence) < 0) {
        return -1;
      }
      expected_sequence++;
    } else if (header.type == kEthernetPacketTypeEnd) {
      uint32_t actual_crc32 = ethernet_crc32(file_buffer, received);
      if (header.sequence != expected_sequence || header.offset != received ||
          header.length != *file_size || received != *file_size ||
          header.crc32 != *file_crc32 || actual_crc32 != *file_crc32) {
        printf("[opera-dsp] END verification error size=%lu received=%lu\n",
               (unsigned long)*file_size, (unsigned long)received);
        (void)send_error(peer_mac, *transfer_id, kEthernetErrorCodeChecksum, header.sequence);
        return -1;
      }

      if (send_ack(peer_mac, *transfer_id, kEthernetPacketTypeEnd, header.sequence) < 0) {
        return -1;
      }
      printf("[opera-dsp] receive complete bytes=%lu crc=0x%08lx\n",
             (unsigned long)received, (unsigned long)actual_crc32);
      return 0;
    } else if (header.type == kEthernetPacketTypeError) {
      printf("[opera-dsp] peer error while receiving code=%u seq=%lu\n", header.code,
             (unsigned long)header.sequence);
      return -1;
    } else {
      (void)send_error(peer_mac, *transfer_id, kEthernetErrorCodeMalformed, header.sequence);
      return -1;
    }
  }
}

static int send_file(const uint8_t peer_mac[kEthernetMacLen],
                     uint32_t transfer_id,
                     uint32_t file_size,
                     uint32_t file_crc32) {
  ethernet_packet_header_t header = {
      .type = kEthernetPacketTypeStart,
      .code = 0,
      .transfer_id = transfer_id,
      .sequence = 0,
      .offset = 0,
      .length = file_size,
      .crc32 = file_crc32,
  };

  printf("[opera-dsp] tx START bytes=%lu crc=0x%08lx\n", (unsigned long)file_size,
         (unsigned long)file_crc32);
  if (send_packet(peer_mac, &header, NULL, 0) < 0) {
    return -1;
  }
  if (wait_ack(peer_mac, transfer_id, kEthernetPacketTypeStart, 0) < 0) {
    return -1;
  }

  uint32_t sequence = 0;
  uint32_t offset = 0;
  while (offset < file_size) {
    uint32_t chunk_len = file_size - offset;
    if (chunk_len > kEthernetMaxChunkLen) {
      chunk_len = kEthernetMaxChunkLen;
    }

    header.type = kEthernetPacketTypeData;
    header.sequence = sequence;
    header.offset = offset;
    header.length = chunk_len;
    header.crc32 = ethernet_crc32(&file_buffer[offset], chunk_len);
    if (send_packet(peer_mac, &header, &file_buffer[offset], chunk_len) < 0) {
      return -1;
    }
    if (wait_ack(peer_mac, transfer_id, kEthernetPacketTypeData, sequence) < 0) {
      return -1;
    }

    offset += chunk_len;
    sequence++;
  }

  header.type = kEthernetPacketTypeEnd;
  header.sequence = sequence;
  header.offset = file_size;
  header.length = file_size;
  header.crc32 = file_crc32;
  if (send_packet(peer_mac, &header, NULL, 0) < 0) {
    return -1;
  }
  if (wait_ack(peer_mac, transfer_id, kEthernetPacketTypeEnd, sequence) < 0) {
    return -1;
  }

  printf("[opera-dsp] tx complete chunks=%lu\n", (unsigned long)sequence);
  return 0;
}

static int validate_and_copy_input(uint32_t file_size, uint32_t *sample_words) {
  if (file_size == 0 || (file_size % kBytesPerWord) != 0) {
    printf("[opera-dsp] input byte count must be nonzero and divisible by 4: %lu\n",
           (unsigned long)file_size);
    return kEthernetErrorCodeMalformed;
  }

  uint32_t samples = file_size / kBytesPerWord;
  if ((samples % OPERA_DSP_NUM_POINTS) != 0) {
    printf("[opera-dsp] samples=%lu is not a multiple of %d\n",
           (unsigned long)samples, OPERA_DSP_NUM_POINTS);
    return kEthernetErrorCodeMalformed;
  }
  if (samples > OPERA_DSP_MAX_SAMPLES) {
    printf("[opera-dsp] samples=%lu exceeds max=%d\n",
           (unsigned long)samples, OPERA_DSP_MAX_SAMPLES);
    return kEthernetErrorCodeTooLarge;
  }

  volatile uint32_t *input = (volatile uint32_t *)OPERA_DSP_SCRATCH_INPUT_BASE;
  for (uint32_t i = 0; i < samples; i++) {
    input[i] = read_le32(&file_buffer[i * kBytesPerWord]);
  }
  opera_fence_rw();

  *sample_words = samples;
  return 0;
}

#define OUTPUT_SENTINEL 0xdeaddead00000000ULL

/**
 * READ_DONE/WRITE_DONE pulse at the end of every <=256-beat sub-burst (the DMA's
 * DMASimplifier splits long descriptors),
 * and the remaining CSRs track only the current sub-burst.
 * Completion of the whole descriptor therefore requires: both done bits seen, engine idle, nothing remaining,
 * and the final output word actually landed in memory (it still holds its sentinel until the last beat;
 * a real CFAR word can never equal the sentinel because its bin field is 0).
 */
static int poll_dma_done(uint32_t sample_words) {
  volatile uint64_t *output = (volatile uint64_t *)OPERA_DSP_SCRATCH_OUTPUT_BASE;
  const uint64_t done_mask = DMA_INT_READ_DONE | DMA_INT_WRITE_DONE;
  const uint64_t last_sentinel = OUTPUT_SENTINEL | (uint64_t)(sample_words - 1);
  const long timeout = 200000L + 100L * (long)sample_words;
  const long stall_limit = 10000; /* iterations (~4 MMIO reads each) with frozen state */
  uint64_t prev_sig = ~0ULL;
  long stall_iters = 0;
  uint64_t status = 0, idle = 0, s2m = 0, m2s = 0;

  for (long i = 0; i < timeout; i++) {
    status = opera_r64(DMA_INT);

    if (status & DMA_ERROR_MASK) {
      printf("[opera-dsp] DMA error status=0x%lx\n", (unsigned long)status);
      return 1;
    }
    idle = opera_r64(DMA_IDLE) & 1UL;
    s2m = opera_r64(DMA_S2M_TRIGGER);
    m2s = opera_r64(DMA_M2S_TRIGGER);
    if ((status & done_mask) == done_mask && idle == 1 && s2m == 0 && m2s == 0) {
      opera_fence_rw();
      if (output[sample_words - 1] != last_sentinel) {
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

  opera_fence_rw();
  {
    int landed = 0;
    int first_gap = -1;
    for (uint32_t i = 0; i < sample_words; i++) {
      if (output[i] != (OUTPUT_SENTINEL | (uint64_t)i)) {
        landed++;
      } else if (first_gap < 0) {
        first_gap = (int)i;
      }
    }
    printf("[opera-dsp] DMA %s: status=0x%lx idle=%lu s2m_remaining=%lu m2s_remaining=%lu "
           "landed=%d/%lu first_gap=%d\n",
           stall_iters >= stall_limit ? "STALLED" : "timed out",
           (unsigned long)status, (unsigned long)idle, (unsigned long)s2m, (unsigned long)m2s,
           landed, (unsigned long)sample_words, first_gap);
  }
  return 1;
}

static int write_verify(uintptr_t addr, uint64_t value, uint64_t mask, const char *name) {
  opera_w64(addr, value);
  uint64_t actual = opera_r64(addr) & mask;
  if (actual != (value & mask)) {
    printf("[opera-dsp] %s CSR readback failed: wrote 0x%lx read 0x%lx\n",
           name, (unsigned long)(value & mask), (unsigned long)actual);
    return 1;
  }
  return 0;
}

static int configure_cfar(void) {
  int errors = 0;

  errors += write_verify(CFAR_REG_FFT_SIZE, OPERA_DSP_NUM_POINTS, 0x7ff, "cfar fft_size");
  errors += write_verify(CFAR_REG_THRESHOLD_SCALE, OPERA_DSP_CFAR_SCALE_RAW,
                         (1UL << CFAR_THR_BITS) - 1UL, "cfar threshold_scale");
  errors += write_verify(CFAR_REG_PEAK_GROUPING, OPERA_DSP_CFAR_PEAK_GROUPING, 0x1,
                         "cfar peak_grouping");
  errors += write_verify(CFAR_REG_MODE, OPERA_DSP_CFAR_MODE, 0x3, "cfar mode");
  errors += write_verify(CFAR_REG_REFERENCE_CELLS, OPERA_DSP_CFAR_REF_CELLS, 0x1f,
                         "cfar reference_cells");
  errors += write_verify(CFAR_REG_GUARD_CELLS, OPERA_DSP_CFAR_GUARD_CELLS, 0x7,
                         "cfar guard_cells");
  errors += write_verify(CFAR_REG_NOISE_DIV_SHIFT, OPERA_DSP_CFAR_NOISE_DIV_SHIFT, 0x7,
                         "cfar noise_div_shift");
  errors += write_verify(CFAR_REG_LOG_MODE, OPERA_DSP_CFAR_LOG_MODE, 0x1, "cfar log_mode");
  errors += write_verify(CFAR_REG_EDGE_POLICY, OPERA_DSP_CFAR_EDGE_POLICY, 0x3,
                         "cfar edge_policy");
  opera_w64(CFAR_REG_LOAD_CFG, 1); /* write-only pulse */

  return errors != 0;
}

static int configure_dsp(void) {
  opera_w64(DMA_ENABLE, 0);
  opera_w64(DMA_INT, 0);
  opera_w64(DMA_WATCHDOG, 1000000);

  opera_w64(WINDOWING_SIZE, OPERA_DSP_NUM_POINTS);
  opera_w64(WINDOWING_CTRL, 1);

  opera_w64(FFT_SIZE_LOG2, OPERA_DSP_FFT_SIZE_LOG2_VALUE);
  opera_w64(FFT_DIV_BY_2, OPERA_DSP_FFT_STAGE_MASK);
  opera_w64(FFT_DIRECTION, 1);
  opera_w64(FFT_LOAD_CFG, 1);
  opera_w64(FFT_OVERFLOW, OPERA_DSP_FFT_STAGE_MASK);
  opera_w64(FFT_DRAIN_ON_LAST, 1);

  opera_w64(MAG_SELECT, 1);

  if ((opera_r64(FFT_DRAIN_ON_LAST) & 1UL) != 1UL) {
    printf("[opera-dsp] FFT drain_on_last CSR readback failed\n");
    return 1;
  }
  if ((opera_r64(MAG_SELECT) & 1UL) != 1UL) {
    printf("[opera-dsp] log-magnitude select CSR readback failed\n");
    return 1;
  }
  return configure_cfar();
}

static int run_dsp_dma(uint32_t sample_words) {
  volatile uint64_t *output = (volatile uint64_t *)OPERA_DSP_SCRATCH_OUTPUT_BASE;

  opera_w64(DMA_ENABLE, 0);
  opera_w64(DMA_INT, 0);
  opera_w64(FFT_OVERFLOW, OPERA_DSP_FFT_STAGE_MASK);

  if ((opera_r64(DMA_IDLE) & 1UL) != 1UL) {
    printf("[opera-dsp] DMA not idle before trigger; resetting\n");
    opera_w64(DMA_ENABLE, 0);
    opera_w64(DMA_INT, 0);
    bool idle = false;
    for (int i = 0; i < 100000; i++) {
      if ((opera_r64(DMA_IDLE) & 1UL) == 1UL) {
        idle = true;
        break;
      }
    }
    if (!idle) {
      printf("[opera-dsp] DMA stuck busy; aborting run\n");
      return 1;
    }
  }

  /* Sentinels let poll_dma_done() confirm the final beat landed and report
   * exactly how far a failed transfer got. */
  for (uint32_t i = 0; i < sample_words; i++) {
    output[i] = OUTPUT_SENTINEL | (uint64_t)i;
  }
  opera_fence_rw();

  /* 
   * Lengths count 8-byte beats minus one:
   * the input packs two 32-bit samples per beat, the output is one 64-bit CFAR word per bin.
   * Never program S2M longer than the data the chain will produce.
   */
  opera_w64(DMA_S2M_BASE, OPERA_DSP_SCRATCH_OUTPUT_BASE);
  opera_w64(DMA_S2M_LENGTH, sample_words - 1);
  opera_w64(DMA_S2M_CYCLES, 0);
  opera_w64(DMA_S2M_FIXED, 0);

  opera_w64(DMA_M2S_BASE, OPERA_DSP_SCRATCH_INPUT_BASE);
  opera_w64(DMA_M2S_LENGTH, sample_words / 2 - 1);
  opera_w64(DMA_M2S_CYCLES, 0);
  opera_w64(DMA_M2S_FIXED, 0);

  opera_w64(DMA_ENABLE, 1);
  opera_w64(DMA_S2M_TRIGGER, 1); /* arm the output side before data can arrive */
  opera_w64(DMA_M2S_TRIGGER, 1);

  if (poll_dma_done(sample_words) != 0) {
    return 1;
  }
  opera_fence_rw();

  uint64_t overflow = opera_r64(FFT_OVERFLOW) & OPERA_DSP_FFT_STAGE_MASK;
  if (overflow != 0) {
    printf("[opera-dsp] FFT overflow status=0x%lx\n", (unsigned long)overflow);
    opera_w64(FFT_OVERFLOW, overflow);
  }
  return 0;
}

static void copy_output_to_transfer_buffer(uint32_t sample_words) {
  volatile uint64_t *output = (volatile uint64_t *)OPERA_DSP_SCRATCH_OUTPUT_BASE;

  for (uint32_t i = 0; i < sample_words; i++) {
    uint64_t word = output[i];
    write_le32(&file_buffer[i * OPERA_DSP_OUT_BYTES_PER_BIN], (uint32_t)word);
    write_le32(&file_buffer[i * OPERA_DSP_OUT_BYTES_PER_BIN + 4], (uint32_t)(word >> 32));
  }
}

/* Hardware-side sanity check that needs no PC tooling: peak count and the first few (frame, bin) detections over HTIF/UART. */
static void print_peak_summary(uint32_t sample_words) {
  enum { kMaxListedPeaks = 8 };
  volatile uint64_t *output = (volatile uint64_t *)OPERA_DSP_SCRATCH_OUTPUT_BASE;
  uint32_t peaks = 0;

  printf("[opera-dsp] peaks (frame,bin):");
  for (uint32_t i = 0; i < sample_words; i++) {
    if (cfar_peak(output[i])) {
      if (peaks < kMaxListedPeaks) {
        printf(" (%lu,%u)", (unsigned long)(i / OPERA_DSP_NUM_POINTS), cfar_bin(output[i]));
      }
      peaks++;
    }
  }
  printf("%s total=%lu\n", peaks > kMaxListedPeaks ? " ..." : "", (unsigned long)peaks);
}

static void wait_for_link(int phy) {
  printf("[opera-dsp] waiting for link...\n");
  bool link = false;
  for (uint32_t spins = 0; spins < kLinkPollMax; spins++) {
    if (phy >= 0 && rtl8211e_link_up(phy)) {
      link = true;
      break;
    }
  }

  if (link) {
    printf("[opera-dsp] LINK UP speed=%lu status=0x%lx\n",
           (unsigned long)eth_link_speed(), (unsigned long)eth_status());
  } else {
    printf("[opera-dsp] LINK DOWN timeout; continuing status=0x%lx\n",
           (unsigned long)eth_status());
  }
}

static int validate_ethernet_frontend(void) {
  uint64_t info = eth_r64(ETH_FRONTEND_INFO);
  uint32_t max_frame = (uint32_t)(info & 0xffffu);
  uint32_t rx_len_depth = (uint32_t)((info >> 16) & 0xffu);
  uint32_t tx_len_depth = (uint32_t)((info >> 24) & 0xffu);

  printf("[opera-dsp] ethernet frontend max_frame=%lu rx_len_depth=%lu tx_len_depth=%lu\n",
         (unsigned long)max_frame, (unsigned long)rx_len_depth,
         (unsigned long)tx_len_depth);
  if (max_frame != ETH_MAX_FRAME || rx_len_depth == 0 || tx_len_depth == 0) {
    printf("[opera-dsp] incompatible ethernet frontend info=0x%08lx expected max_frame=%d\n",
           (unsigned long)info, ETH_MAX_FRAME);
    return -1;
  }
  return 0;
}

int main(void) {
  setvbuf(stdout, NULL, _IONBF, 0);
  printf("[opera-dsp] init FFT=%d max_samples=%d\n",
         OPERA_DSP_NUM_POINTS, OPERA_DSP_MAX_SAMPLES);

  int phy = rtl8211e_bringup(printf);
  eth_init();
  if (validate_ethernet_frontend() < 0) {
    return 2;
  }
  wait_for_link(phy);

  while (true) {
    uint8_t peer_mac[kEthernetMacLen];
    uint32_t transfer_id = 0;
    uint32_t file_size = 0;
    uint32_t file_crc32 = 0;
    uint32_t sample_words = 0;

    if (receive_file(peer_mac, &transfer_id, &file_size, &file_crc32) < 0) {
      printf("[opera-dsp] receive failed; waiting for next transfer\n");
      continue;
    }

    int validation = validate_and_copy_input(file_size, &sample_words);
    if (validation != 0) {
      (void)send_error(peer_mac, transfer_id, (uint8_t)validation, 0);
      continue;
    }

    printf("[opera-dsp] processing samples=%lu frames=%lu\n",
           (unsigned long)sample_words,
           (unsigned long)(sample_words / OPERA_DSP_NUM_POINTS));

    if (configure_dsp() != 0 || run_dsp_dma(sample_words) != 0) {
      (void)send_error(peer_mac, transfer_id, kEthernetErrorCodeTimeout, 0);
      continue;
    }

    print_peak_summary(sample_words);
    copy_output_to_transfer_buffer(sample_words);
    uint32_t output_size = sample_words * OPERA_DSP_OUT_BYTES_PER_BIN;
    uint32_t output_crc32 = ethernet_crc32(file_buffer, output_size);
    if (send_file(peer_mac, transfer_id, output_size, output_crc32) < 0) {
      printf("[opera-dsp] output transfer failed; waiting for next transfer\n");
      continue;
    }
  }

  return 0;
}
