# ActiveSPM

ActiveSPM is a Chipyard generator for a software-controlled DMA engine with a
globally addressable scratchpad memory. The generator is intended to be used by
the customized NPU subsystem in Chipyard.

This repository currently contains an elaboratable Scala interface framework,
a functional banked scratchpad, a functional DMA datapath, and a polling MMIO
control block. Software or a hardware master can configure and launch one DMA
transfer at a time through the 64-bit register interface.

## Directory Layout

- `src/main/scala/activespm`: Parameters, interfaces, module shells, and system
  integration.
- `src/test/scala/activespm`: Scala hardware tests.
- `software/include`: The software-visible polling MMIO interface.
- `software/tests`: C tests and their standalone bare-metal build.
- `software/examples`: C example programs.
- `scripts`: Integration-artifact checks.

## Building

ActiveSPM is built as a subproject of its parent Chipyard repository. From the
Chipyard root, enter the configured environment and compile the project with:

```sh
source env.sh
sbt "activespm/test" "chipyard/compile"
```

`chipyard.ActiveSPMScaffoldRocketConfig` verifies the same-width subsystem
attachment. `chipyard.ActiveSPMWideSBusScaffoldRocketConfig` verifies a 16-byte
SBus attached to the scratchpad's 8-byte native interface. Both configurations
can launch DMA transfers through MMIO, but remain development scaffolds rather
than software-qualified production configurations.

`chipyard.ActiveSPMDualGemminiMeshRocketConfig` is the multi-instance
integration configuration. It contains two Huge Rocket cores, one default
Gemmini per hart, two ActiveSPM instances, two inclusive-L2 banks, and a 128-bit
SBus implemented as a full-channel 3x2 Constellation mesh. Its rows are:

```text
Core 0 + Gemmini 0 -- ActiveSPM 0 -- L2/system[0]
Core 1 + Gemmini 1 -- ActiveSPM 1 -- L2/system[1]
```

The Core and Gemmini in a row share a router. Each ActiveSPM and each L2 bank
has a dedicated router. An ActiveSPM DMA ingress and its aggregated scratchpad
egress are colocated; the control manager remains on CBus.

## Interfaces

Each instance exposes three TileLink interfaces: a 64-bit MMIO control manager,
an aggregated scratchpad data manager, and an external-memory DMA client. The
DMA also has a private TileLink client connected to the scratchpad's internal
manager; that path does not leave the instance.

The global and DMA-local scratchpad paths share one set of `TLRAM` banks through
a round-robin TileLink crossbar. `spadBeatBytes` defines the scratchpad's native
TileLink width, each bank's word width, and the bank-interleaving granularity.
For a local byte offset, the bank is
`(localOffset / spadBeatBytes) % nBanks`. The SBus width is negotiated and
adapted independently, so it does not need to equal `spadBeatBytes`.

The scratchpad supports `Get`, `PutFullData`, and `PutPartialData`. Partial
writes preserve all unselected bytes, and global and local accesses observe the
same storage. Different banks can operate concurrently; accesses to the same
bank are fairly arbitrated. RAM contents are unspecified after reset.

The control block submits one command at a time through a Decoupled request
channel. DMA completion also uses a Decoupled channel so the result remains
stable until accepted. Live busy and byte-count progress are reported
separately without a handshake.

The polling register block uses aligned 64-bit accesses. Software writes the
external physical address at `0x08`, the local scratchpad offset at `0x10`, and
the byte count at `0x18`, then writes `START` in bit 0 of `COMMAND` at `0x00`;
bit 1 selects store rather than load. `STATUS` at `0x20` reports `BUSY`, sticky
`DONE`, and sticky `ERROR`. `BYTES_COMPLETED` at `0x28` and `ERROR_CODE` at
`0x30` retain the final result. Writing one to the `DONE` or `ERROR` status bit
clears it, and clearing `ERROR` also clears its code.

Descriptor registers may be updated while a command is active without changing
the captured request. A second `START` while busy is rejected, leaves the active
DMA untouched, and records the `BUSY` error. If that active DMA later succeeds,
`DONE` and the rejected-start `ERROR` can both remain set for software to clear.

The DMA copies bytes in either direction between external physical memory and
the local scratchpad. It accepts arbitrary source address, destination address,
and length alignment, including different byte-lane offsets and different
negotiated beat widths. A bounded little-endian byte realigner connects a
single-outstanding source reader to a single-outstanding destination writer;
the two transactions may overlap. Destination masks preserve all bytes outside
the requested interval.

Requests are rejected before traffic is issued if their 64-bit end address
overflows, their local interval exceeds the scratchpad, or their external
interval is not contained in one configured external-memory range. TileLink
`denied` or `corrupt` responses stop new traffic, drain traffic already in
flight, and return the acknowledged contiguous destination prefix through
`bytesCompleted`. A zero-length request succeeds without issuing TileLink
traffic.

Hardware tests exercise the DMA both directly and through the complete MMIO
control path, including unaligned transfers, negotiated width differences,
descriptor failures, sticky status, and TileLink response errors.

## Bare-metal integration test

`software/include/activespm.h` exposes the stable 64-bit polling ABI without
hiding hardware status or error codes. `activespm_start` writes a complete
descriptor, applies a RISC-V memory fence, and starts one load or store. The
caller owns polling and timeout policy; the header also provides status,
completion, error, and W1C helpers.

The dual-hart test belongs to this repository. It reuses the sibling Gemmini
repository's generic multicore CRT/runtime and the `gemmini_params.h` generated
by elaborating the matching SoC config; it does not add ActiveSPM sources to the
Gemmini build. Build and run it from the Chipyard root with:

```sh
source env.sh
make -C sims/verilator CONFIG=ActiveSPMDualGemminiMeshRocketConfig firrtl
make -C generators/activespm/software/tests
make -C sims/verilator CONFIG=ActiveSPMDualGemminiMeshRocketConfig \
  BINARY="$PWD/generators/activespm/software/tests/build/dual-gemmini-mesh-baremetal" \
  run-binary
```

Each hart operates only on its same-numbered Gemmini and ActiveSPM. Both harts
concurrently run a DRAM-to-ActiveSPM load, Gemmini `mvin`/`mvout` through its
private scratchpad, and an ActiveSPM-to-DRAM store. Distinct data patterns and
guard bytes check data integrity and cross-instance isolation. This remains a
development integration test, not a performance benchmark or production SoC
configuration.

## License

ActiveSPM is licensed under the BSD 3-Clause License. See `LICENSE`.
