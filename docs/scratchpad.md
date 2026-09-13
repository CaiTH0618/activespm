# Scratchpad

## Function and Addressing

Each ActiveSPM instance exposes one contiguous scratchpad range in the global
physical address map. A CPU, Gemmini, or another permitted system master uses
the global address:

`scratchpad base + byte offset`

An ActiveSPM DMA descriptor names the same byte using only the local offset.
The DMA adds its own instance's scratchpad base internally. A descriptor cannot
select another ActiveSPM instance as its local side; another instance's globally
mapped range would be an external address only if the configuration explicitly
allowed it, which the supplied configurations do not.

## Bank Organization

Storage is divided into a power-of-two number of equal-capacity banks. The
native scratchpad beat is both the bank word size and the interleaving unit. For
a local offset, bank selection is:

`(local offset / native beat bytes) modulo bank count`

Consecutive native beats therefore rotate across banks. Bytes within one native
beat remain in the same bank, and each bank holds every `nBanks`-th beat. The
complete scratchpad still appears as one contiguous manager to external
requesters.

For example, with an 8-byte native beat and four banks, offsets `0x00`, `0x08`,
`0x10`, and `0x18` select banks 0, 1, 2, and 3; offset `0x20` returns to bank 0.

## Access Ports and Arbitration

The banks have two logical access paths:

- The global path accepts traffic from SBus after width and transfer-size
  adaptation.
- The local path accepts traffic directly from the instance's DMA at the native
  scratchpad width.

Both paths converge at bank-level TileLink arbitration and access one set of
storage. There is no cache or private replica between them. A write observed
through one path is subsequently visible through the other according to normal
TileLink ordering and software synchronization.

Requests to different banks may make progress concurrently. Requests from the
two paths to the same bank are serialized using fair round-robin arbitration;
either requester may receive backpressure while contending.

## Supported Operations

The scratchpad supports reads, full writes, and masked partial writes with
transfer sizes from one byte through one native beat, subject to TileLink
alignment rules. A partial write modifies only selected byte lanes and preserves
every unselected byte.

The region is non-cacheable, non-executable, non-atomic, and idempotent. It does
not accept acquire, arithmetic, logical, or hint operations. Masters must not
attempt instruction fetches or atomic read-modify-write operations in the
scratchpad range.

## Width Adaptation

`spadBeatBytes` defines the physical bank width and DMA-local interface width;
it does not fix SBus width. If SBus is wider or narrower, the subsystem
attachment adapts and fragments global requests into operations supported by
the native manager. From outside the instance there is still exactly one
manager covering the whole scratchpad range.

The DMA's byte realignment is independent of global-path width conversion. DMA
source and destination offsets may have unrelated byte-lane alignments.

## Visibility and Ordering

ActiveSPM does not detect semantic hazards. Software must not:

- read a region while the DMA or another producer is still filling it;
- overwrite a region while the DMA, Gemmini, or a CPU is consuming it;
- assume that completion of one agent automatically orders an unrelated
  agent's earlier or later accesses.

Use explicit completion checks, Gemmini synchronization, RISC-V memory fences,
and buffer ownership transitions. Separate input and output areas or ping-pong
buffers make these transitions unambiguous and permit non-overlapping traffic
to different banks.

## Reset and Initialization

Scratchpad storage is not initialized by reset and is not guaranteed to contain
zero. Software must write or DMA-load a region before treating its contents as
valid. Reset during traffic returns protocol logic to an idle state, but the
contents of any region involved in the interrupted access are unspecified.
