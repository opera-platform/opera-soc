/* OPERA Ethernet + DSP end-to-end regression.
 *
 * The Ethernet transports the generated golden input through the RGMII pin loopback into the DSP input scratchpad.
 * The existing opera-dsp-chain test is then compiled into this translation unit unchanged
 * (apart from skipping its normal direct input fill),
 * preserving all DMA, guard, bin, CUT, CFAR, repeat, and checksum checks used by the standalone DSP regression.
 */

#include "eth.h"

#define OPERA_DSP_CHAIN_INPUT_PRELOADED 1
#define main opera_dsp_chain_main
#include "opera-dsp-chain.c"
#undef main

#define ETH_DSP_PAYLOAD_BYTES 1400
#define ETH_DSP_HEADER_BYTES 16
#define ETH_DSP_FRAME_BYTES (ETH_DSP_HEADER_BYTES + ETH_DSP_PAYLOAD_BYTES)

static const uint8_t eth_dsp_dst_mac[6] = {0x02, 0x00, 0x00, 0x00, 0x00, 0x02};
static const uint8_t eth_dsp_src_mac[6] = {0x02, 0x00, 0x00, 0x00, 0x00, 0x01};
static uint8_t eth_dsp_tx_frame[ETH_DSP_FRAME_BYTES];
static uint8_t eth_dsp_rx_frame[ETH_DSP_FRAME_BYTES];

static void print_ethernet_state(const char *where)
{
  printf("ethernet: %s int=0x%lx s2m=%lu m2s=%lu rx_count=%lu mac=0x%lx\n",
         where,
         (unsigned long)eth_r64(ETH_DMA_INT),
         (unsigned long)eth_r64(ETH_DMA_S2M_TRIGGER),
         (unsigned long)eth_r64(ETH_DMA_M2S_TRIGGER),
         (unsigned long)eth_r64(ETH_RX_COUNT),
         (unsigned long)eth_status());
}

static int run_ethernet_leg(void)
{
  const uint8_t *golden = (const uint8_t *)opera_dsp_chain_input;
  volatile uint8_t *dsp_input = (volatile uint8_t *)(uintptr_t)SCRATCH_BASE;
  const unsigned long input_bytes = (unsigned long)INPUT_WORDS * 4UL;
  const uint32_t mac_error_bits = ETH_ST_RX_ERR_BAD_FRAME |
                                  ETH_ST_RX_ERR_BAD_FCS |
                                  ETH_ST_RX_FIFO_OVERFLOW |
                                  ETH_ST_RX_FIFO_BAD_FR;
  unsigned long offset = 0;
  unsigned sequence = 0;

  eth_init();
  if ((eth_r64(ETH_FRONTEND_INFO) & 0xffffu) != ETH_MAX_FRAME) {
    printf("ethernet: frontend INFO mismatch: 0x%lx\n",
           (unsigned long)eth_r64(ETH_FRONTEND_INFO));
    return 1;
  }

  while (offset < input_bytes) {
    unsigned long remaining = input_bytes - offset;
    int payload = remaining > ETH_DSP_PAYLOAD_BYTES
                      ? ETH_DSP_PAYLOAD_BYTES
                      : (int)remaining;
    int frame_len = ETH_DSP_HEADER_BYTES + payload;
    int rx_len = -1;

    for (int i = 0; i < 6; i++) {
      eth_dsp_tx_frame[i] = eth_dsp_dst_mac[i];
      eth_dsp_tx_frame[6 + i] = eth_dsp_src_mac[i];
    }
    eth_dsp_tx_frame[12] = 0x88;
    eth_dsp_tx_frame[13] = 0xb5;
    eth_dsp_tx_frame[14] = (uint8_t)(sequence >> 8);
    eth_dsp_tx_frame[15] = (uint8_t)sequence;
    for (int i = 0; i < payload; i++) {
      eth_dsp_tx_frame[ETH_DSP_HEADER_BYTES + i] = golden[offset + (unsigned long)i];
    }

    if (eth_send_frame_bounded(eth_dsp_tx_frame, frame_len) < 0) {
      printf("ethernet: TX failed at sequence=%u offset=%lu\n",
             sequence, offset);
      print_ethernet_state("TX failure");
      return 1;
    }
    if (eth_recv_frame_bounded(eth_dsp_rx_frame,
                               (int)sizeof(eth_dsp_rx_frame), &rx_len) < 0) {
      printf("ethernet: RX failed at sequence=%u offset=%lu\n",
             sequence, offset);
      print_ethernet_state("RX failure");
      return 1;
    }
    if (rx_len != frame_len) {
      printf("ethernet: RX_LEN mismatch sequence=%u got=%d expected=%d\n",
             sequence, rx_len, frame_len);
      print_ethernet_state("length mismatch");
      return 1;
    }
    for (int i = 0; i < frame_len; i++) {
      if (eth_dsp_rx_frame[i] != eth_dsp_tx_frame[i]) {
        printf("ethernet: frame byte mismatch sequence=%u byte=%d "
               "got=0x%02x expected=0x%02x\n",
               sequence, i, eth_dsp_rx_frame[i], eth_dsp_tx_frame[i]);
        print_ethernet_state("byte mismatch");
        return 1;
      }
    }
    {
      uint32_t status = eth_status();
      if ((status & mac_error_bits) != 0) {
        printf("ethernet: MAC RX error sequence=%u status=0x%lx\n",
               sequence, (unsigned long)status);
        print_ethernet_state("MAC error");
        return 1;
      }
    }

    for (int i = 0; i < payload; i++) {
      dsp_input[offset + (unsigned long)i] =
          eth_dsp_rx_frame[ETH_DSP_HEADER_BYTES + i];
    }
    offset += (unsigned long)payload;
    printf("ethernet: frame=%u payload=%d total=%lu/%lu\n",
           sequence, payload, offset, input_bytes);
    sequence++;
  }

  eth_fence_rw();
  for (unsigned long i = 0; i < input_bytes; i++) {
    if (dsp_input[i] != golden[i]) {
      printf("ethernet: scratchpad mismatch byte=%lu got=0x%02x expected=0x%02x\n",
             i, dsp_input[i], golden[i]);
      return 1;
    }
  }

  printf("ethernet: pass frames=%u bytes=%lu\n", sequence, input_bytes);
  return 0;
}

int main(void)
{
  if (run_ethernet_leg() != 0) {
    printf("ETH-DSP FAIL (ethernet)\n");
    return 1;
  }

  if (opera_dsp_chain_main() != 0) {
    printf("ETH-DSP FAIL (DSP)\n");
    return 1;
  }

  printf("ETH-DSP PASS\n");
  return 0;
}
