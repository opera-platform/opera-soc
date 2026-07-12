#!/usr/bin/env bash
set -eo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "usage: $0 <tx_file.txt> <fpga_rx.csv> [expected.hex]" >&2
  exit 2
fi

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
root=$(cd -- "$script_dir/../../.." && pwd)
input_text=$1
fpga_csv=$2
if [[ $# -eq 3 ]]; then
  expected_hex=$3
else
  generated_dir="$script_dir/generated"
  mkdir -p "$generated_dir"
  expected_hex="$generated_dir/opera_dsp_expected.hex"
fi
model_header="${expected_hex}.h"
regs="$root/software/opera-dsp/opera_dsp_regs.h"
config="$root/software/opera-dsp/mmio_config.h"

read_macro() {
  local file=$1
  local name=$2
  awk -v name="$name" '$1 == "#define" && $2 == name { print $3; exit }' "$file"
}

num_points=$(read_macro "$regs" OPERA_DSP_NUM_POINTS)
cfar_scale=$(read_macro "$config" OPERA_DSP_CFAR_SCALE_RAW)
cfar_ref=$(read_macro "$config" OPERA_DSP_CFAR_REF_CELLS)
cfar_guard=$(read_macro "$config" OPERA_DSP_CFAR_GUARD_CELLS)
cfar_shift=$(read_macro "$config" OPERA_DSP_CFAR_NOISE_DIV_SHIFT)
cfar_mode=$(read_macro "$config" OPERA_DSP_CFAR_MODE)
cfar_edge=$(read_macro "$config" OPERA_DSP_CFAR_EDGE_POLICY)
peak_grouping=$(read_macro "$config" OPERA_DSP_CFAR_PEAK_GROUPING)

cd "$root"
source env.sh
set -u
sbt -J-Xms2048M -J-Xmx8G \
  "chipyard/Test/runMain chipyard.example.operadsp.DspChainApp \
--num-points $num_points \
--cfar-scale-raw $cfar_scale \
--cfar-ref $cfar_ref \
--cfar-guard $cfar_guard \
--cfar-shift $cfar_shift \
--cfar-mode $cfar_mode \
--cfar-edge-policy $cfar_edge \
--peak-grouping $peak_grouping \
--input-text $input_text \
--expected-hex $expected_hex \
--out $model_header"

python3 "$script_dir/compare_cfar.py" "$expected_hex" "$fpga_csv"
