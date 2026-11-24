# ActiveSPM

ActiveSPM is a Chipyard generator for a software-controlled DMA engine with a
globally addressable scratchpad memory.

The current implementation provides a shared banked scratchpad, a bidirectional
byte-accurate DMA datapath, a polling MMIO control block, multi-instance
Chipyard integration, and a dual-Rocket/dual-Gemmini mesh test configuration.
Software can launch one DMA transfer at a time through the 64-bit register
interface. Transfers support arbitrary byte alignment and length between the
local scratchpad and configured external physical-memory ranges.

The default configuration uses one outstanding transaction on each DMA TileLink
interface. An optional concurrent configuration retains the same MMIO and TL
slave interfaces, but pipelines aligned bulk transfers using multiple external
source IDs, a bounded flop data window and two private 256-bit SRAM paths.
Neither configuration adds interrupts, a software descriptor API, address
translation, ECC, or hardware buffer-ownership tracking.

For example, a concurrent configuration can select `dmaMaxInFlight=64`,
`dmaBufferBytes=4096`, `dmaLocalPorts=2`, `dmaLocalInFlight=16`,
`globalPipelineDepth=8` and `bankResponseQueueDepth=2`. Its data window holds
64 slots of 64 bytes each, reused by LOAD and STORE. Requests reserve a slot
before issue. The external TileLink port is 512 bits wide and the external
memory managers must support 64-byte Get and PutFullData requests. An ordered
global ingress preserves FIFO execution and response order. Bank request
handshakes execute physical single-port SRAM accesses directly. Bank response
queues and ingress response slots absorb return-path backpressure.

The software still submits one continuous-address command. Aligned bulk data
uses the concurrent engine, then a short tail uses the byte engine; unaligned
commands use the byte engine throughout. Completion waits for acknowledged
destination writes. `BYTES_COMPLETED` advances over the contiguous acknowledged
prefix of the destination in address order. Errors stop new issues and drain
accepted transactions before reporting that prefix.
The scratchpad address range, bank mapping, byte-write behavior and original
MMIO register semantics are unchanged. Concurrent DMA changes only its master
source-ID range and private local clients, so integration/NoC metadata must
reflect the selected concurrency. The physical SRAM capacity and geometry
do not change, and DMA never initiates copies to Gemmini or other SRAMs.

Set `descQueueDepth` to a positive value to enable DeSC request arbitration on
the concurrent DMA.
The additional sideband accepts aligned 64-byte decoder transfer descriptors
(external address and local offset in 64-byte units). Two queues hold inference
and decoder descriptors. Both use the shared DMA slots and SRAM ports.
At most half of the slots can be occupied by the decoder, including stores
waiting for data. The external A-channel arbiter grants a programmable fraction
`decoderWeight/16` of requests to decoding when both classes can issue.
With a positive weight, either class can use the external port when its peer
cannot issue. Weight zero stops new decoder grants and leaves already
committed TileLink requests to finish.

The configuration sideband writes the weight and per-class byte and burst
limits atomically. Each request consumes 64 bytes and one burst of credit.
Credit returns on the external D response. Sideband completion counters count
all acknowledged destination bytes from the aligned path per class. Class 0 is
inference and class 1 is decoding. The existing MMIO covers the inference
command, including byte-aligned fallback and tails. That engine temporarily
owns the TileLink ports after accepted fast-path traffic has drained.

## Directory Layout

- `src/main/scala/activespm`: Parameters, interfaces, functional modules, and
  system integration.
- `src/test/scala/activespm`: Scala hardware tests.
- `docs`: Architecture, configuration, interface, software, and verification
  documentation.
- `software/include`: The software-visible polling MMIO interface.
- `software/tests`: C tests and their standalone bare-metal build.

## Documentation

Start with [`docs/index.md`](docs/index.md) for the complete documentation
index. The documentation describes the system architecture, generator
parameters, Chipyard and NoC integration, scratchpad organization, DMA and MMIO
semantics, software synchronization, error handling, and verification flows.

## Building

ActiveSPM is built as a subproject of its parent Chipyard repository. From the
Chipyard root, enter the configured environment and compile the project with:

```sh
source env.sh && source scripts/chipyard-build-resources.sh
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

## Architecture and Interfaces

Each instance exposes three TileLink interfaces: a 64-bit MMIO control manager,
an aggregated scratchpad data manager, and an external-memory DMA client. The
DMA also connects to the scratchpad through private TileLink paths. The first
uses `activespm-local[i]` and the concurrent path adds `activespm-local[i]-1`.

The control manager attaches to CBus. The aggregated scratchpad manager and
external DMA client attach to SBus; on a Constellation NoC they use the stable
names `activespm-spad[i]` and `activespm-dma[i]`. The DMA-local path remains
inside the instance and does not consume a global NoC endpoint.

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

The DMA uses external physical addresses directly and contains no TLB or IOMMU.
Software must synchronize buffer ownership between CPUs, Gemmini, and the
ActiveSPM DMA with completion checks and RISC-V memory fences. The external DMA
client is connected through coherent SBus, so the supplied configurations do
not require a software-managed L2 flush for normal cacheable-memory transfers.

Hardware tests exercise the DMA both directly and through the complete MMIO
control path, including unaligned transfers, negotiated width differences,
descriptor failures, sticky status, and TileLink response errors.

Detailed interface and software requirements are documented in
[`docs/dma-control.md`](docs/dma-control.md) and
[`docs/software-guide.md`](docs/software-guide.md).

## Bare-Metal Integration Test

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
source env.sh && source scripts/chipyard-build-resources.sh
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
