# Software Guide

## Address Model

Software needs two bases for each instance:

- The control base selects its MMIO register block.
- The scratchpad base selects its globally mapped storage.

DMA descriptors use an external physical address and a local offset. Direct CPU
or Gemmini access uses `scratchpad base + local offset`. Instance addresses come
from the SoC configuration and must not be inferred from the instance ID unless
the platform software itself defines such a convention.

Because ActiveSPM does no address translation, a C pointer is valid as the
external address only when it already represents the corresponding physical
TileLink address. This is normally true for the intended bare-metal,
identity-mapped environment. Hosted programs and virtual-memory systems must
obtain and authorize a physical DMA address through platform-specific support.

## Polling Transfer Sequence

A robust load or store follows this sequence:

1. Establish ownership of the source and destination regions. Confirm that no
   CPU, Gemmini, or DMA will conflict with the transfer.
2. If the source was produced by a core or accelerator, finish those operations
   and apply the required synchronization.
3. Optionally clear retained `DONE` and `ERROR` status before preparing the new
   command. A valid idle start also clears them automatically.
4. Write the external physical address, local offset, and byte count with
   aligned 64-bit MMIO stores.
5. Execute a RISC-V read/write memory fence so source data and descriptor writes
   are ordered before launch.
6. Write `COMMAND.START` together with the desired direction.
7. Poll `STATUS.BUSY` until it clears, with a software-defined timeout.
8. Read `STATUS`, `ERROR_CODE`, and `BYTES_COMPLETED`. Accept success only when
   `DONE` is set, `ERROR` is clear, the error code is `NONE`, and the completed
   count equals the requested count.
9. Execute a RISC-V read/write memory fence before a CPU or accelerator consumes
   the destination.
10. Clear retained status when it has been recorded and release the buffer to
    its next owner.

The timeout is a software policy. Hardware does not cancel a request because a
polling loop stops waiting, and the current interface has no abort command.

## Direction Rules

For a load, external memory is the source and the scratchpad offset is the
destination. Source data must be finalized before launch; the local region must
not be read until successful completion and the following fence.

For a store, the scratchpad offset is the source and external memory is the
destination. Scratchpad production must finish before launch; the local region
must remain unchanged until completion. The external destination must not be
consumed before successful completion and the following fence.

Arbitrary byte alignment is supported. Software does not need to pad a request
to a native beat, but it must ensure the exact requested local interval is
inside the scratchpad and the exact external interval lies within one configured
allowed range.

## Status and Failure Handling

Never treat `BUSY=0` alone as success. A descriptor rejected immediately may
transition quickly to an error result, and a TileLink failure clears busy after
draining issued transactions.

On any error:

1. Record `ERROR_CODE` and `BYTES_COMPLETED` before clearing status.
2. Treat the destination of every nonzero request as potentially partially
   modified, even if the completed count is zero.
3. Do not retry into a buffer whose partial contents matter; reinitialize it or
   use a fresh buffer.
4. Resolve configuration, address, or transport problems before retrying.
5. Clear `ERROR` when the diagnostic value is no longer needed.

A `BUSY` error differs from a transfer failure: it reports only a rejected
second start, while the already active command continues. Continue polling the
original command and account for the possibility that `DONE` and `ERROR` will
both be retained.

## Supplied C Interface

The public header at `software/include/activespm.h` defines the stable register
offsets, direction and error enumerations, status masks, aligned 64-bit MMIO
helpers, status readers, write-one-to-clear handling, and a launch helper.

The launch helper writes a complete descriptor, executes a read/write fence,
and writes the command. It intentionally does not poll, impose a timeout,
interpret completion, or perform post-completion synchronization. Callers own
those policies because their scheduling and failure requirements differ.

The header can be included from C or C++. Its MMIO operations are volatile, but
volatile access alone is not a replacement for the architectural fences used to
transfer buffer ownership.

## Coherence and Fences

The external DMA client is attached to coherent SBus. In the supplied coherent
systems, ordinary transfers to cacheable memory do not require a manually
managed L2 flush. The coherence manager must remain present in a multi-core,
multi-master configuration.

A fence orders accesses but does not turn a future non-coherent DMA path into a
coherent one. If ActiveSPM is integrated through a non-coherent path later, the
platform must add the appropriate cache maintenance and ownership protocol.

## Recommended Buffering Model

Ping-pong buffers allow transfer and computation to overlap without introducing
data hazards. At any point, assign each region one clear state such as free,
being filled by ActiveSPM, ready for Gemmini, being consumed by Gemmini, ready
for store, or being stored by ActiveSPM. Move to the next state only after the
producer's completion condition and the required fence.

Bank interleaving can permit concurrent non-overlapping traffic, but software
should regard that as a performance opportunity rather than an ownership
guarantee. Two byte ranges may use several of the same banks even when they do
not overlap.

## Dual-Hart Gemmini Example

The supplied bare-metal integration program uses two harts, two Gemmini
instances, and two ActiveSPM instances. Each hart owns the correspondingly
numbered compute and memory tile and performs:

1. A memory-to-ActiveSPM load into an input region.
2. A direct verification of the globally mapped scratchpad bytes.
3. Gemmini `mvin` from the input region and `mvout` to a distinct output region.
4. An ActiveSPM-to-memory store from the output region.
5. Output and guard-byte validation followed by W1C status validation.

Both harts run the sequence concurrently with distinct patterns. Barriers
separate ownership phases, and guard areas detect writes outside the intended
external buffers. This program validates data flow and instance isolation; it
is not a throughput benchmark or production scheduling example.
