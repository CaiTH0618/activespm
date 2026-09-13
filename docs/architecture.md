# ActiveSPM Architecture

## Role in the NPU Subsystem

ActiveSPM provides a software-managed memory tile between compute engines and
external memory. Its scratchpad is part of the global physical address map, so
Rocket cores, Gemmini DMA engines, and other permitted TileLink masters can
access it like a memory region. Its own DMA engine uses a private local path to
the same storage and a coherent system-bus path to external memory.

This arrangement supports a common staged execution pattern:

1. A Rocket core programs ActiveSPM to load an input region from memory.
2. The ActiveSPM DMA writes the input into the local scratchpad.
3. After successful completion, Gemmini reads that region through its existing
   DMA and performs computation.
4. Gemmini writes results to a separate ActiveSPM region.
5. ActiveSPM stores the result region back to memory.

Multiple instances can operate independently. In the provided dual-instance
configuration, each hart controls the correspondingly numbered ActiveSPM and
Gemmini, while all instances remain globally addressable.

## Module Composition

One instance contains three functional blocks:

| Block | Responsibility |
| --- | --- |
| Control | Implements the 64-bit polling MMIO register block, snapshots descriptors, launches requests, and retains completion state. |
| DMA | Validates a descriptor, reads the source, realigns bytes, writes the destination, and reports acknowledged progress or failure. |
| Scratchpad | Provides shared banked storage to the global system-facing port and the private DMA-local port. |

The top-level instance connects the control block to the DMA with request,
progress, and completion signals. It connects the DMA's private local TileLink
client directly to the scratchpad's local manager. Only the control manager,
aggregated scratchpad manager, and external-memory DMA client leave the
instance.

## External Interfaces

| Interface | TileLink role | Attachment | Purpose |
| --- | --- | --- | --- |
| `activespm-ctrl[i]` | Manager | CBus | Processor-visible MMIO registers for instance `i` |
| `activespm-spad[i]` | Manager | SBus | Globally mapped access to the complete scratchpad |
| `activespm-dma[i]` | Client | SBus | DMA traffic to allowed external-memory ranges |

The bracketed instance ID is part of the stable Diplomacy name. Internal banks
and the DMA-local scratchpad client are not visible as global NoC endpoints.

The three external interfaces use only ordinary reads and writes. The
scratchpad is not executable and does not support atomics or coherent acquire
operations. The external DMA path is attached as a coherent SBus client, so
normal cacheable-memory traffic participates in the SoC's coherence structure
before reaching the memory bus.

## Data Paths

### Global scratchpad path

A Rocket core, Gemmini, or another system master issues a request to the
scratchpad's global physical range. SBus width and transfer adapters present a
single aggregated manager externally. Inside ActiveSPM, the address selects a
bank and the request competes fairly with DMA-local traffic targeting the same
bank.

### DMA load path

For a load, the external physical address is the source and the local offset is
the destination. The DMA validates both intervals before issuing traffic,
reads only bytes inside the requested external interval, realigns the returned
byte stream, and writes it to the scratchpad through the private local path.
Completion is reported only after every destination write response has arrived.

### DMA store path

For a store, the local offset is the source and the external physical address
is the destination. The DMA reads the scratchpad through the same private path,
realigns the byte stream, and writes the requested external interval. Partial
writes preserve destination bytes outside that interval.

The local path never traverses SBus or the global NoC. It nevertheless observes
exactly the same physical banks as the global scratchpad interface.

## Concurrency and Ownership

Different scratchpad banks may serve traffic concurrently. Requests to the same
bank are serialized with round-robin arbitration and normal TileLink
backpressure. A DMA may overlap its one outstanding source read with its one
outstanding destination write, but the current implementation accepts only one
software command at a time.

There is no hardware dependency tracking between a CPU, Gemmini, and ActiveSPM
DMA. Concurrent accesses are safe only when software assigns non-overlapping
regions or otherwise establishes that no reader observes data while it is being
produced and no writer modifies data while it is being consumed. Ping-pong
buffers are the intended steady-state organization.

## Clock and Reset Model

The DMA, control logic, private TileLink path, and all banks of an instance run
in one synchronous domain derived from SBus. The control interface may cross
from CBus using the configured standard TileLink crossing type. The initial
design does not provide independent scratchpad or DMA clocks and does not
perform an implicit internal clock-domain crossing.

Reset aborts an active operation. Control status returns to idle and clears its
sticky state. Scratchpad contents are unspecified after reset, and any
partially completed source or destination effects from the aborted operation
must not be relied upon.

## Scope Boundaries

ActiveSPM deliberately does not provide:

- virtual-address translation, a TLB, or an IOMMU;
- an interrupt or completion tag;
- multiple queued or simultaneously active software descriptors;
- cache, ECC, scratchpad initialization, or rollback after a failed transfer;
- access-ownership enforcement between requesters;
- external-to-external or local-to-local copying.

These boundaries are part of the current programming model, not merely
performance characteristics.
