#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
chipyard_root=$(cd -- "$script_dir/../../.." && pwd)
config=ActiveSPMDualGemminiMeshRocketConfig
generated_root="$chipyard_root/sims/verilator/generated-src"
generated_dir="$generated_root/chipyard.harness.TestHarness.$config"
prefix="$generated_dir/chipyard.harness.TestHarness.$config"
log="$prefix.chisel.log"
dts="$prefix.dts"
memmap="$prefix.memmap.json"
xy="$prefix.TLNoC.noc.xy"
rocket_dir="$generated_root/chipyard.harness.TestHarness.RocketConfig"
rocket_prefix="$rocket_dir/chipyard.harness.TestHarness.RocketConfig"

fail() {
  echo "ActiveSPM dual-mesh check failed: $*" >&2
  exit 1
}

for artifact in "$log" "$dts" "$memmap" "$xy"; do
  test -f "$artifact" || fail "missing $artifact; run the $config firrtl target"
done

mapping=$(sed -n \
  '/Constellation: TLNoC .* inwards mapping:/,/Constellation: TLNoC Checking full connectivity/p' \
  "$log")

require_mapping() {
  local router=$1
  local endpoint=$2
  grep -F "  $router <- " <<<"$mapping" | grep -Fq "$endpoint" ||
    fail "$endpoint is not mapped to router $router"
}

require_mapping 0 'serial_tl'
require_mapping 0 'Core 0 '
require_mapping 0 'Gemmini0-'
require_mapping 1 'activespm-dma[0]'
require_mapping 3 'Core 1 '
require_mapping 3 'Gemmini1-'
require_mapping 4 'activespm-dma[1]'

require_mapping 0 'pbus[0]'
require_mapping 1 'activespm-spad[0]'
require_mapping 2 'system[0]'
require_mapping 4 'activespm-spad[1]'
require_mapping 5 'system[1]'

if grep -Fq '  X <- ' <<<"$mapping"; then
  fail "the SBus NoC contains an unmapped endpoint"
fi
if grep -Eq 'activespm-local|activespm_spad_.*bank' <<<"$mapping"; then
  fail "an internal ActiveSPM endpoint escaped into the SBus NoC mapping"
fi
test "$(grep -Fc 'activespm-dma[0]' <<<"$mapping")" -eq 1 ||
  fail "activespm-dma[0] must appear exactly once"
test "$(grep -Fc 'activespm-dma[1]' <<<"$mapping")" -eq 1 ||
  fail "activespm-dma[1] must appear exactly once"
test "$(grep -Fc 'activespm-spad[0]' <<<"$mapping")" -eq 1 ||
  fail "activespm-spad[0] must appear exactly once"
test "$(grep -Fc 'activespm-spad[1]' <<<"$mapping")" -eq 1 ||
  fail "activespm-spad[1] must appear exactly once"

for router in 0 1 2 3 4 5; do
  awk -v router="$router" '$1 == router { found = 1 } END { exit !found }' "$xy" ||
    fail "mesh router $router is absent from the generated topology"
done

test "$(grep -Ec 'cpu@[01] \{' "$dts")" -eq 2 ||
  fail "DTS does not contain exactly two CPUs"
grep -Fq 'activespm-ctrl-0@10050000' "$dts" || fail "missing control instance 0"
grep -Fq 'reg = <0x10050000 0x1000>;' "$dts" || fail "wrong control instance 0 range"
grep -Fq 'activespm-ctrl-1@10051000' "$dts" || fail "missing control instance 1"
grep -Fq 'reg = <0x10051000 0x1000>;' "$dts" || fail "wrong control instance 1 range"
grep -Fq 'memory@70000000' "$dts" || fail "missing scratchpad instance 0"
grep -Fq 'reg = <0x70000000 0x10000>;' "$dts" || fail "wrong scratchpad instance 0 range"
grep -Fq 'memory@70010000' "$dts" || fail "missing scratchpad instance 1"
grep -Fq 'reg = <0x70010000 0x10000>;' "$dts" || fail "wrong scratchpad instance 1 range"

grep -Fq '"base":[268763136],"size":[4096]' "$memmap" ||
  fail "memory map is missing control instance 0"
grep -Fq '"base":[268767232],"size":[4096]' "$memmap" ||
  fail "memory map is missing control instance 1"
grep -Fq '"base":[1879048192],"size":[65536]' "$memmap" ||
  fail "memory map is missing scratchpad instance 0"
grep -Fq '"base":[1879113728],"size":[65536]' "$memmap" ||
  fail "memory map is missing scratchpad instance 1"

if test -f "$rocket_prefix.dts" && test -f "$rocket_prefix.memmap.json"; then
  if grep -Eqi 'activespm|10050000|70000000' \
      "$rocket_prefix.dts" "$rocket_prefix.memmap.json"; then
    fail "default RocketConfig unexpectedly contains ActiveSPM"
  fi
else
  echo "Default RocketConfig artifacts are absent; skipping the empty-key check."
fi

echo "ActiveSPM dual Gemmini 3x2 mesh mapping and address map are correct."
