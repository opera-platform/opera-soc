# OPERA DSP Ethernet FPGA Demo

This demo sends text samples from a Linux PC to a Nexys Video Rocket SoC over raw Ethernet, processes them through the OPERA DSP chain, and returns one 8-byte CFAR detection record per FFT bin as `rx_file.csv`.

DSP path:

```text
PC tx_file.txt -> Ethernet -> scratchpad -> DMA -> windowing -> FFT(256) -> log-magnitude -> CFAR -> DMA -> scratchpad -> Ethernet -> PC rx_file.csv
```

## Build

```bash
cd <opera-soc>
source env.sh
make -C software/opera-dsp
make -C software/opera-dsp/pc
```

Outputs:

- FPGA bare-metal program: `software/opera-dsp/opera_dsp_fpga.riscv`
- PC transfer tool: `software/opera-dsp/pc/opera_dsp_pc`
- PC signal generator: `software/opera-dsp/pc/generate_signal.py`

The FPGA firmware shares `eth.h`, `mdio.h`, `rtl8211e.h`, and the raw-Ethernet
protocol implementation from `software/ethernet`;

## FPGA Bitstream

```bash
cd <opera-soc>
source env.sh
# Vivado must be in path!
make -C fpga SUB_PROJECT=nexysvideo CONFIG=OperaDspNexysVideoConfig bitstream
```

The Ethernet DMA/frontend/MAC/MDIO windows are `0x10060000`, `0x10061000`,
`0x10062000`, and `0x10063000`. They are disjoint from the DSP windows at
`0x10050000..0x10055fff`. Ethernet uses scratchpad bounce buffers at
`0x0800C000` and `0x0800D000`; DSP input/output remain at `0x08000000` and
`0x08004000`.

## Run

Disable NIC offloads before testing:

```bash
sudo ip link set <ifname> up
sudo ethtool -K <ifname> rx off tx off gro off gso off tso off
```

Run the FPGA-side program over UART-TSI:

```bash
cd <opera-soc>/software/opera-dsp
uart_tsi +tty=/dev/ttyUSBX ./opera_dsp_fpga.riscv
```

Generate sixteen 256-sample frames (tones at bins 37, 123, 211 plus noise -- an ideal CFAR stimulus) and transfer them:

```bash
cd <opera-soc>/software/opera-dsp/pc
python3 ./generate_signal.py tx_file.txt 4096
sudo ./opera_dsp_pc <ifname> tx_file.txt rx_file.csv
```

The PC tool prints a detection summary (peaks should land at bins 37, 123 and 211 in every frame) and writes the full record set as CSV. The CSV includes the raw packed 64-bit word so numerical regression checks do not depend on formatted floating-point values.

## Bit-exact FPGA result check

The Scala chain model can consume the exact decimal sample file sent by the PC, rather than its built-in generated stimulus. Compare every returned CFAR word (bin, peak, CUT, and threshold fields) with:

```bash
cd <opera-soc>
bash software/opera-dsp/pc/check_fpga_result.sh \
  software/opera-dsp/pc/tx_file.txt \
  software/opera-dsp/pc/rx_file.csv
```

The script reads the 256-point and CFAR settings from the firmware headers, runs `DspChainApp` on the exact input text, and invokes `compare_cfar.py`. Success is reported as, for example:

```text
[compare] PASS: 4096 CFAR words match bit-exactly
```

A mismatch report includes the record, frame, bin, expected raw 64-bit word, and FPGA raw 64-bit word. This distinguishes numerical DSP mismatches from a mere difference in peak counts.

`tx_file.txt` is decimal text:

```text
real imag
```

The PC tool quantizes input to packed Q2.14 complex words:

- bits `[31:16]`: signed real
- bits `[15:0]`: signed imaginary

The response carries one 8-byte CFAR record per bin,
`[63:41] threshold Q9.14 | [40:9] cut Q18.14 | [8:1] fft_bin | [0] peak`
(unpack helpers in `opera_dsp_regs.h`), written to `rx_file.csv` as:

```text
frame,bin,cut,threshold,peak,raw
```

## Limits

- FFT size: 256
- maximum samples per transfer: 4096
- sample count must be a nonzero multiple of 256
- DMA buffers are in the 64 KiB scratchpad:
  - input: `0x08000000` (4 B/sample, 16 KiB max)
  - output: `0x08004000` (8 B/bin CFAR records, 32 KiB max)
