// Linux PC-side OPERA DSP raw-Ethernet client.
//
// Sends Q2.14 complex samples to the FPGA and receives one 8-byte CFAR record per FFT bin 
// ([63:41] threshold Q9.14 | [40:9] cut Q18.14 | [8:1] fft_bin | [0] peak — unpack helpers in ../opera_dsp_regs.h).
// Prints a detection summary and writes a CSV (frame,bin,cut,threshold,peak) for offline plotting.
//
// End-to-end demo (FPGA running opera_dsp_fpga.riscv, direct cable):
//   python3 ./generate_signal.py signal.txt  # tones at bins 37/123/211 plus noise
//   sudo ./opera_dsp_pc <iface> signal.txt out.csv
// Expected: detections at bins 37, 123 and 211 in every frame, thresholds tracking the noise floor.

#define _DEFAULT_SOURCE

#include "opera_dsp_regs.h"
#include "protocol.h"

#include <arpa/inet.h>
#include <ctype.h>
#include <errno.h>
#include <linux/if_packet.h>
#include <math.h>
#include <net/ethernet.h>
#include <net/if.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

enum {
  kAckRetries = 5,
  kReceiveTimeouts = 30,
  kSocketTimeoutSec = 1,
  kNumPoints = OPERA_DSP_NUM_POINTS,
  kMaxSamples = OPERA_DSP_MAX_SAMPLES,
  kQ214Scale = 16384,
  kBytesPerRecord = OPERA_DSP_OUT_BYTES_PER_BIN,
};

typedef struct ethernet_pc_socket {
  int fd;
  int ifindex;
  uint8_t mac[kEthernetMacLen];
} ethernet_pc_socket_t;

static const uint8_t kFpgaMac[kEthernetMacLen] = {0x02, 0x00, 0x00, 0x00, 0x00, 0x01};

static uint32_t read_le32(const uint8_t *buf) {
  return ((uint32_t)buf[0]) | ((uint32_t)buf[1] << 8) |
         ((uint32_t)buf[2] << 16) | ((uint32_t)buf[3] << 24);
}

static uint64_t read_le64(const uint8_t *buf) {
  return (uint64_t)read_le32(buf) | ((uint64_t)read_le32(buf + 4) << 32);
}

static void write_le32(uint8_t *buf, uint32_t value) {
  buf[0] = (uint8_t)(value & 0xffu);
  buf[1] = (uint8_t)((value >> 8) & 0xffu);
  buf[2] = (uint8_t)((value >> 16) & 0xffu);
  buf[3] = (uint8_t)((value >> 24) & 0xffu);
}

static int open_raw_socket(const char *ifname, ethernet_pc_socket_t *sock) {
  if (strlen(ifname) >= IFNAMSIZ) {
    fprintf(stderr, "[pc] interface name too long: %s\n", ifname);
    return -1;
  }

  int fd = socket(AF_PACKET, SOCK_RAW, htons(kEthernetEthertype));
  if (fd < 0) {
    perror("socket");
    return -1;
  }

  struct ifreq ifr;
  memset(&ifr, 0, sizeof(ifr));
  strncpy(ifr.ifr_name, ifname, IFNAMSIZ - 1);
  if (ioctl(fd, SIOCGIFINDEX, &ifr) < 0) {
    perror("SIOCGIFINDEX");
    close(fd);
    return -1;
  }
  int ifindex = ifr.ifr_ifindex;

  if (ioctl(fd, SIOCGIFHWADDR, &ifr) < 0) {
    perror("SIOCGIFHWADDR");
    close(fd);
    return -1;
  }

  struct sockaddr_ll bind_addr;
  memset(&bind_addr, 0, sizeof(bind_addr));
  bind_addr.sll_family = AF_PACKET;
  bind_addr.sll_protocol = htons(kEthernetEthertype);
  bind_addr.sll_ifindex = ifindex;
  if (bind(fd, (struct sockaddr *)&bind_addr, sizeof(bind_addr)) < 0) {
    perror("bind");
    close(fd);
    return -1;
  }

  struct timeval timeout = {
      .tv_sec = kSocketTimeoutSec,
      .tv_usec = 0,
  };
  if (setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) < 0) {
    perror("setsockopt SO_RCVTIMEO");
    close(fd);
    return -1;
  }

  sock->fd = fd;
  sock->ifindex = ifindex;
  memcpy(sock->mac, ifr.ifr_hwaddr.sa_data, kEthernetMacLen);
  return 0;
}

static int build_frame(const ethernet_pc_socket_t *sock,
                       const uint8_t dst[kEthernetMacLen],
                       const ethernet_packet_header_t *header,
                       const uint8_t *data,
                       uint32_t data_len,
                       uint8_t frame[kEthernetMaxFrameLen]) {
  for (int i = 0; i < kEthernetMacLen; i++) {
    frame[i] = dst[i];
    frame[kEthernetMacLen + i] = sock->mac[i];
  }
  frame[12] = (uint8_t)(kEthernetEthertype >> 8);
  frame[13] = (uint8_t)(kEthernetEthertype & 0xffu);

  if (ethernet_encode_header(&frame[kEthernetL2HeaderLen],
                             kEthernetHeaderLen + (size_t)data_len, header) < 0) {
    return -1;
  }
  if (data_len > 0) {
    memcpy(&frame[kEthernetL2HeaderLen + kEthernetHeaderLen], data, data_len);
  }

  uint32_t payload_len = kEthernetHeaderLen + data_len;
  uint32_t padded_payload_len = payload_len;
  if (padded_payload_len < kEthernetMinPayloadLen) {
    padded_payload_len = kEthernetMinPayloadLen;
  }
  for (uint32_t i = payload_len; i < padded_payload_len; i++) {
    frame[kEthernetL2HeaderLen + i] = 0;
  }
  return kEthernetL2HeaderLen + (int)padded_payload_len;
}

static int send_packet(const ethernet_pc_socket_t *sock,
                       const uint8_t dst[kEthernetMacLen],
                       const ethernet_packet_header_t *header,
                       const uint8_t *data,
                       uint32_t data_len) {
  uint8_t frame[kEthernetMaxFrameLen];
  int frame_len = build_frame(sock, dst, header, data, data_len, frame);
  if (frame_len < 0) {
    return -1;
  }

  struct sockaddr_ll addr;
  memset(&addr, 0, sizeof(addr));
  addr.sll_family = AF_PACKET;
  addr.sll_protocol = htons(kEthernetEthertype);
  addr.sll_ifindex = sock->ifindex;
  addr.sll_halen = kEthernetMacLen;
  memcpy(addr.sll_addr, dst, kEthernetMacLen);

  ssize_t sent = sendto(sock->fd, frame, (size_t)frame_len, 0,
                        (struct sockaddr *)&addr, sizeof(addr));
  if (sent < 0) {
    perror("sendto");
    return -1;
  }
  if (sent != frame_len) {
    fprintf(stderr, "[pc] short send: %zd/%d\n", sent, frame_len);
    return -1;
  }
  return 0;
}

static int recv_protocol_packet(const ethernet_pc_socket_t *sock,
                                const uint8_t *expected_src,
                                ethernet_packet_header_t *header,
                                uint8_t *payload,
                                uint32_t *payload_len) {
  while (1) {
    uint8_t frame[kEthernetMaxFrameLen];
    struct sockaddr_ll addr;
    socklen_t addr_len = sizeof(addr);
    ssize_t len = recvfrom(sock->fd, frame, sizeof(frame), 0,
                           (struct sockaddr *)&addr, &addr_len);
    if (len < 0) {
      if (errno == EAGAIN || errno == EWOULDBLOCK) {
        return 1;
      }
      perror("recvfrom");
      return -1;
    }

    if (addr.sll_pkttype == PACKET_OUTGOING) {
      continue;
    }
    if (len < kEthernetL2HeaderLen + kEthernetHeaderLen) {
      continue;
    }
    uint16_t ethertype = (uint16_t)(((uint16_t)frame[12] << 8) | frame[13]);
    if (ethertype != kEthernetEthertype) {
      continue;
    }
    if (expected_src != NULL && memcmp(&frame[6], expected_src, kEthernetMacLen) != 0) {
      continue;
    }
    if (ethernet_decode_header(&frame[kEthernetL2HeaderLen],
                               (size_t)(len - kEthernetL2HeaderLen), header) < 0) {
      continue;
    }

    uint32_t body_len = (uint32_t)(len - kEthernetL2HeaderLen - kEthernetHeaderLen);
    if (body_len > kEthernetMaxChunkLen) {
      continue;
    }
    if (payload != NULL && body_len > 0) {
      memcpy(payload, &frame[kEthernetL2HeaderLen + kEthernetHeaderLen], body_len);
    }
    if (payload_len != NULL) {
      *payload_len = body_len;
    }
    return 0;
  }
}

static int send_control(const ethernet_pc_socket_t *sock,
                        const uint8_t dst[kEthernetMacLen],
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
  return send_packet(sock, dst, &header, NULL, 0);
}

static int send_ack(const ethernet_pc_socket_t *sock,
                    const uint8_t dst[kEthernetMacLen],
                    uint32_t transfer_id,
                    uint8_t acked_type,
                    uint32_t sequence) {
  return send_control(sock, dst, kEthernetPacketTypeAck, acked_type, transfer_id, sequence);
}

static int send_error(const ethernet_pc_socket_t *sock,
                      const uint8_t dst[kEthernetMacLen],
                      uint32_t transfer_id,
                      uint8_t code,
                      uint32_t sequence) {
  return send_control(sock, dst, kEthernetPacketTypeError, code, transfer_id, sequence);
}

static int send_and_wait_ack(const ethernet_pc_socket_t *sock,
                             const ethernet_packet_header_t *header,
                             const uint8_t *data,
                             uint32_t data_len,
                             uint8_t acked_type,
                             uint32_t sequence) {
  for (int attempt = 1; attempt <= kAckRetries; attempt++) {
    if (send_packet(sock, kFpgaMac, header, data, data_len) < 0) {
      return -1;
    }

    while (1) {
      ethernet_packet_header_t rx_header;
      int ret = recv_protocol_packet(sock, kFpgaMac, &rx_header, NULL, NULL);
      if (ret == 1) {
        printf("[pc] retry type=%u seq=%lu attempt=%d/%d\n", acked_type,
               (unsigned long)sequence, attempt, kAckRetries);
        break;
      }
      if (ret < 0) {
        return -1;
      }
      if (rx_header.transfer_id != header->transfer_id) {
        continue;
      }
      if (rx_header.type == kEthernetPacketTypeAck && rx_header.code == acked_type &&
          rx_header.sequence == sequence) {
        return 0;
      }
      if (rx_header.type == kEthernetPacketTypeError) {
        fprintf(stderr, "[pc] FPGA ERROR code=%u seq=%lu\n", rx_header.code,
                (unsigned long)rx_header.sequence);
        return -1;
      }
    }
  }

  fprintf(stderr, "[pc] no ACK for type=%u seq=%lu\n", acked_type,
          (unsigned long)sequence);
  return -1;
}

static int quantize_q2_14(double value, int16_t *raw_out) {
  double scaled = value * (double)kQ214Scale;
  double rounded = (scaled >= 0.0) ? floor(scaled + 0.5) : ceil(scaled - 0.5);

  if (rounded < -32768.0 || rounded > 32767.0) {
    return -1;
  }
  *raw_out = (int16_t)rounded;
  return 0;
}

static int append_word(uint8_t **data,
                       uint32_t *samples,
                       uint32_t *capacity_samples,
                       uint32_t word) {
  if (*samples >= kMaxSamples) {
    fprintf(stderr, "[pc] too many samples; max is %d\n", kMaxSamples);
    return -1;
  }
  if (*samples == *capacity_samples) {
    uint32_t new_capacity = (*capacity_samples == 0) ? 1024u : (*capacity_samples * 2u);
    if (new_capacity > kMaxSamples) {
      new_capacity = kMaxSamples;
    }
    uint8_t *new_data = realloc(*data, (size_t)new_capacity * 4u);
    if (new_data == NULL) {
      perror("realloc");
      return -1;
    }
    *data = new_data;
    *capacity_samples = new_capacity;
  }

  write_le32(&(*data)[*samples * 4u], word);
  (*samples)++;
  return 0;
}

static int read_text_samples(const char *path,
                             uint8_t **data_out,
                             uint32_t *size_out,
                             uint32_t *samples_out) {
  FILE *fp = fopen(path, "r");
  if (fp == NULL) {
    perror(path);
    return -1;
  }

  uint8_t *data = NULL;
  uint32_t samples = 0;
  uint32_t capacity_samples = 0;
  char line[256];
  int line_no = 0;

  while (fgets(line, sizeof(line), fp) != NULL) {
    line_no++;
    char *comment = strchr(line, '#');
    if (comment != NULL) {
      *comment = '\0';
    }

    char *cursor = line;
    while (isspace((unsigned char)*cursor)) {
      cursor++;
    }
    if (*cursor == '\0') {
      continue;
    }

    double real = 0.0;
    double imag = 0.0;
    char extra = 0;
    if (sscanf(cursor, "%lf %lf %c", &real, &imag, &extra) != 2) {
      fprintf(stderr, "[pc] malformed sample at %s:%d\n", path, line_no);
      free(data);
      fclose(fp);
      return -1;
    }

    int16_t real_raw = 0;
    int16_t imag_raw = 0;
    if (quantize_q2_14(real, &real_raw) < 0 || quantize_q2_14(imag, &imag_raw) < 0) {
      fprintf(stderr, "[pc] Q2.14 range error at %s:%d real=%f imag=%f\n",
              path, line_no, real, imag);
      free(data);
      fclose(fp);
      return -1;
    }

    uint32_t word = ((uint32_t)(uint16_t)real_raw << 16) | (uint32_t)(uint16_t)imag_raw;
    if (append_word(&data, &samples, &capacity_samples, word) < 0) {
      free(data);
      fclose(fp);
      return -1;
    }
  }

  if (ferror(fp)) {
    perror(path);
    free(data);
    fclose(fp);
    return -1;
  }
  fclose(fp);

  if (samples == 0 || (samples % kNumPoints) != 0) {
    fprintf(stderr, "[pc] sample count must be nonzero and a multiple of %d, got %lu\n",
            kNumPoints, (unsigned long)samples);
    free(data);
    return -1;
  }

  *data_out = data;
  *samples_out = samples;
  *size_out = samples * 4u;
  return 0;
}

static int send_file(const ethernet_pc_socket_t *sock,
                     const uint8_t *data,
                     uint32_t file_size,
                     uint32_t transfer_id,
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

  printf("[pc] tx START bytes=%lu crc=0x%08lx\n", (unsigned long)file_size,
         (unsigned long)file_crc32);
  if (send_and_wait_ack(sock, &header, NULL, 0, kEthernetPacketTypeStart, 0) < 0) {
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
    header.crc32 = ethernet_crc32(&data[offset], chunk_len);
    if (send_and_wait_ack(sock, &header, &data[offset], chunk_len, kEthernetPacketTypeData,
                          sequence) < 0) {
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
  if (send_and_wait_ack(sock, &header, NULL, 0, kEthernetPacketTypeEnd, sequence) < 0) {
    return -1;
  }

  printf("[pc] tx complete chunks=%lu\n", (unsigned long)sequence);
  return 0;
}

static int receive_output_file(const ethernet_pc_socket_t *sock,
                               uint32_t transfer_id,
                               uint8_t **data_out,
                               uint32_t *size_out) {
  uint8_t *data = NULL;
  uint32_t file_size = 0;
  uint32_t expected_crc32 = 0;
  uint32_t expected_sequence = 0;
  uint32_t received = 0;
  int timeouts = 0;

  printf("[pc] waiting for processed START\n");
  while (1) {
    ethernet_packet_header_t header;
    int ret = recv_protocol_packet(sock, kFpgaMac, &header, NULL, NULL);
    if (ret == 1) {
      timeouts++;
      if (timeouts >= kReceiveTimeouts) {
        fprintf(stderr, "[pc] timeout waiting for processed START\n");
        return -1;
      }
      continue;
    }
    if (ret < 0) {
      return -1;
    }
    if (header.transfer_id != transfer_id) {
      continue;
    }
    if (header.type == kEthernetPacketTypeError) {
      fprintf(stderr, "[pc] FPGA ERROR code=%u seq=%lu\n", header.code,
              (unsigned long)header.sequence);
      return -1;
    }
    if (header.type != kEthernetPacketTypeStart) {
      continue;
    }
    if (header.length > kEthernetMaxFileSize || (header.length % kBytesPerRecord) != 0) {
      (void)send_error(sock, kFpgaMac, transfer_id, kEthernetErrorCodeMalformed,
                       header.sequence);
      return -1;
    }

    file_size = header.length;
    expected_crc32 = header.crc32;
    data = malloc((file_size == 0) ? 1u : (size_t)file_size);
    if (data == NULL) {
      perror("malloc");
      return -1;
    }
    if (send_ack(sock, kFpgaMac, transfer_id, kEthernetPacketTypeStart,
                 header.sequence) < 0) {
      free(data);
      return -1;
    }
    printf("[pc] rx START bytes=%lu crc=0x%08lx\n", (unsigned long)file_size,
           (unsigned long)expected_crc32);
    break;
  }

  timeouts = 0;
  while (1) {
    uint8_t payload[kEthernetMaxChunkLen];
    uint32_t payload_len = 0;
    ethernet_packet_header_t header;
    int ret = recv_protocol_packet(sock, kFpgaMac, &header, payload, &payload_len);
    if (ret == 1) {
      timeouts++;
      if (timeouts >= kReceiveTimeouts) {
        fprintf(stderr, "[pc] timeout waiting for processed data\n");
        (void)send_error(sock, kFpgaMac, transfer_id, kEthernetErrorCodeTimeout,
                         expected_sequence);
        free(data);
        return -1;
      }
      continue;
    }
    if (ret < 0) {
      free(data);
      return -1;
    }
    if (header.transfer_id != transfer_id) {
      continue;
    }

    timeouts = 0;
    if (header.type == kEthernetPacketTypeData) {
      if (header.sequence != expected_sequence || header.offset != received ||
          header.length > kEthernetMaxChunkLen || header.length > payload_len ||
          header.length > file_size - received) {
        fprintf(stderr, "[pc] DATA sequence/range error seq=%lu expected=%lu\n",
                (unsigned long)header.sequence, (unsigned long)expected_sequence);
        (void)send_error(sock, kFpgaMac, transfer_id, kEthernetErrorCodeSequence,
                         header.sequence);
        free(data);
        return -1;
      }

      uint32_t chunk_crc32 = ethernet_crc32(payload, header.length);
      if (chunk_crc32 != header.crc32) {
        fprintf(stderr, "[pc] DATA checksum error seq=%lu\n",
                (unsigned long)header.sequence);
        (void)send_error(sock, kFpgaMac, transfer_id, kEthernetErrorCodeChecksum,
                         header.sequence);
        free(data);
        return -1;
      }

      if (header.length > 0) {
        memcpy(&data[received], payload, header.length);
      }
      received += header.length;
      if (send_ack(sock, kFpgaMac, transfer_id, kEthernetPacketTypeData,
                   header.sequence) < 0) {
        free(data);
        return -1;
      }
      expected_sequence++;
    } else if (header.type == kEthernetPacketTypeEnd) {
      uint32_t actual_crc32 = ethernet_crc32(data, received);
      if (header.sequence != expected_sequence || header.offset != received ||
          header.length != file_size || received != file_size ||
          header.crc32 != expected_crc32 || actual_crc32 != expected_crc32) {
        fprintf(stderr, "[pc] END verification error size=%lu received=%lu\n",
                (unsigned long)file_size, (unsigned long)received);
        (void)send_error(sock, kFpgaMac, transfer_id, kEthernetErrorCodeChecksum,
                         header.sequence);
        free(data);
        return -1;
      }

      if (send_ack(sock, kFpgaMac, transfer_id, kEthernetPacketTypeEnd,
                   header.sequence) < 0) {
        free(data);
        return -1;
      }
      printf("[pc] rx complete chunks=%lu bytes=%lu crc=0x%08lx\n",
             (unsigned long)expected_sequence, (unsigned long)received,
             (unsigned long)actual_crc32);
      *data_out = data;
      *size_out = received;
      return 0;
    } else if (header.type == kEthernetPacketTypeError) {
      fprintf(stderr, "[pc] FPGA ERROR code=%u seq=%lu\n", header.code,
              (unsigned long)header.sequence);
      free(data);
      return -1;
    } else {
      fprintf(stderr, "[pc] unexpected packet type %u\n", header.type);
      (void)send_error(sock, kFpgaMac, transfer_id, kEthernetErrorCodeMalformed,
                       header.sequence);
      free(data);
      return -1;
    }
  }
}

/* cut (Q18.14) and threshold (Q7.14) are log-magnitude-domain values; divide by 2^14 for the float representation. */
static double q14_to_double(int64_t raw) {
  return (double)raw / (double)kQ214Scale;
}

static void print_detection_summary(const uint8_t *data, uint32_t size) {
  uint32_t records = size / kBytesPerRecord;
  uint32_t peaks = 0;

  printf("[pc] detections:\n");
  printf("[pc]   %5s %5s %12s %12s\n", "frame", "bin", "cut", "threshold");
  for (uint32_t i = 0; i < records; i++) {
    uint64_t word = read_le64(&data[i * kBytesPerRecord]);
    if (cfar_peak(word)) {
      printf("[pc]   %5lu %5u %12.4f %12.4f\n",
             (unsigned long)(i / kNumPoints), cfar_bin(word),
             q14_to_double((int32_t)cfar_cut_raw(word)),
             q14_to_double(cfar_thr_raw(word)));
      peaks++;
    }
  }
  printf("[pc] peaks=%lu frames=%lu\n", (unsigned long)peaks,
         (unsigned long)(records / kNumPoints));
}

static int write_output_csv(const char *path, const uint8_t *data, uint32_t size) {
  FILE *fp = fopen(path, "w");
  if (fp == NULL) {
    perror(path);
    return -1;
  }

  fprintf(fp, "frame,bin,cut,threshold,peak,raw\n");
  uint32_t records = size / kBytesPerRecord;
  for (uint32_t i = 0; i < records; i++) {
    uint64_t word = read_le64(&data[i * kBytesPerRecord]);
    fprintf(fp, "%lu,%u,%.9f,%.9f,%d,0x%016llx\n",
            (unsigned long)(i / kNumPoints), cfar_bin(word),
            q14_to_double((int32_t)cfar_cut_raw(word)),
            q14_to_double(cfar_thr_raw(word)), cfar_peak(word),
            (unsigned long long)word);
  }

  if (fclose(fp) != 0) {
    perror("fclose");
    return -1;
  }
  return 0;
}

static uint32_t make_transfer_id(void) {
  return ((uint32_t)time(NULL) ^ ((uint32_t)getpid() << 16) ^ (uint32_t)getpid());
}

static void usage(const char *prog) {
  fprintf(stderr, "usage: sudo %s <ifname> [tx_file.txt] [rx_file.csv]\n", prog);
}

int main(int argc, char **argv) {
  if (argc < 2 || argc > 4) {
    usage(argv[0]);
    return 2;
  }

  const char *ifname = argv[1];
  const char *tx_path = (argc >= 3) ? argv[2] : "tx_file.txt";
  const char *rx_path = (argc >= 4) ? argv[3] : "rx_file.csv";

  uint8_t *tx_data = NULL;
  uint32_t tx_size = 0;
  uint32_t samples = 0;
  if (read_text_samples(tx_path, &tx_data, &tx_size, &samples) < 0) {
    return 1;
  }

  ethernet_pc_socket_t sock;
  if (open_raw_socket(ifname, &sock) < 0) {
    free(tx_data);
    return 1;
  }

  uint32_t transfer_id = make_transfer_id();
  uint32_t tx_crc32 = ethernet_crc32(tx_data, tx_size);
  printf("[pc] input samples=%lu frames=%lu bytes=%lu\n",
         (unsigned long)samples, (unsigned long)(samples / kNumPoints),
         (unsigned long)tx_size);

  int rc = 0;
  uint8_t *rx_data = NULL;
  uint32_t rx_size = 0;
  uint32_t expected_rx_size = samples * (uint32_t)kBytesPerRecord;
  if (send_file(&sock, tx_data, tx_size, transfer_id, tx_crc32) < 0 ||
      receive_output_file(&sock, transfer_id, &rx_data, &rx_size) < 0) {
    rc = 1;
  } else if (rx_size != expected_rx_size) {
    fprintf(stderr, "[pc] output byte count mismatch: got=%lu expected=%lu (8 B/bin)\n",
            (unsigned long)rx_size, (unsigned long)expected_rx_size);
    rc = 1;
  } else if (write_output_csv(rx_path, rx_data, rx_size) < 0) {
    rc = 1;
  } else {
    print_detection_summary(rx_data, rx_size);
    printf("[pc] wrote CFAR records to %s\n", rx_path);
  }

  free(rx_data);
  free(tx_data);
  close(sock.fd);
  return rc;
}
