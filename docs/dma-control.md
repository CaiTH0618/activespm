# DMA and Control Interfaces

## Transfer Descriptor

One DMA request consists of four fields:

| Field | Width | Meaning |
| --- | ---: | --- |
| Direction | 1 bit | `0` loads external memory into the scratchpad; `1` stores scratchpad data into external memory. |
| External address | 64 bits | Physical byte address on the external-memory side. |
| Local offset | 64 bits | Byte offset from this instance's scratchpad base. |
| Byte count | 64 bits | Exact number of bytes to copy. |

The DMA implements byte-accurate `memcpy` behavior between the two address
spaces. Addresses and length may be arbitrarily aligned, and the external and
local byte-lane offsets do not need to match. External-to-external and
local-to-local requests are not supported.

The external address is physical. The DMA has no TLB or IOMMU and does not
translate a process virtual address.

## Internal Control-to-DMA Protocol

The control block and DMA communicate through three logical interfaces:

| Interface | Handshake | Information |
| --- | --- | --- |
| Request | Ready/valid | Direction and complete captured descriptor |
| Progress | Sampled status | Live busy state and acknowledged destination-byte count |
| Completion | Ready/valid | Final error code and acknowledged destination-byte count |

The control block snapshots all descriptor fields atomically when it accepts an
idle `START`. It then keeps the request stable until the DMA accepts it. The DMA
retains completion information until the control block accepts it. Progress is
informational and does not require a handshake.

## DMA Processing Flow

For every accepted request, the DMA follows this sequence:

1. Reset progress and validate the complete descriptor.
2. For a nonzero valid request, choose the source and destination from the
   direction and enter the active state.
3. Issue an aligned source read that stays entirely within the requested
   interval and fits available buffering.
4. Append returned source bytes to a bounded byte stream after removing their
   original bus-lane offset.
5. Form a legal destination write at its independent alignment. Use a partial
   write whenever the destination transfer envelope contains bytes outside the
   requested interval.
6. Increase `BYTES_COMPLETED` only when the destination write response is
   successfully acknowledged.
7. Complete after all bytes have been acknowledged at the destination, or stop
   and drain already issued transactions after an error.

Each external and local DMA interface has one source ID and allows at most one
outstanding transaction. A source read and a destination write may be in flight
at the same time. Registered request and response boundaries provide bounded
backpressure isolation around the realignment path.

A zero-length request succeeds without issuing TileLink traffic. Its addresses
are not dereferenced, and its completed-byte count is zero.

## MMIO Register Map

The control block uses aligned 64-bit MMIO accesses. All offsets are relative to
the configured control base.

| Offset | Register | Access | Definition |
| ---: | --- | --- | --- |
| `0x00` | `COMMAND` | Write only | Bit 0 is `START`; bit 1 is `DIRECTION`; all other bits are reserved and must be zero. |
| `0x08` | `EXTERNAL_ADDR` | Read/write | External physical byte address. |
| `0x10` | `LOCAL_OFFSET` | Read/write | Byte offset relative to this instance's scratchpad base. |
| `0x18` | `BYTE_COUNT` | Read/write | Number of bytes to copy. |
| `0x20` | `STATUS` | Read/write-one-to-clear | Bit 0 is `BUSY`; bit 1 is sticky `DONE`; bit 2 is sticky `ERROR`. |
| `0x28` | `BYTES_COMPLETED` | Read only | Length of the contiguous destination prefix whose writes have been acknowledged. |
| `0x30` | `ERROR_CODE` | Read only | Final or sticky error reason. |

Writes with `START=0` have no command effect. An idle `START=1` captures the
direction and descriptor registers, clears stale `DONE` and `ERROR`, clears the
error code, resets completed bytes, and marks the command busy. Descriptor
registers may be changed while busy, but those changes apply only to a later
command.

`DONE` and `ERROR` are cleared by writing one to their respective `STATUS`
bits. Writing zero leaves them unchanged. Clearing `ERROR` also restores
`ERROR_CODE` to `NONE`. `BUSY` is read-only even though it shares the status
register.

## Status Semantics

`BUSY` covers the control-visible lifetime from accepting an idle `START` until
the DMA result is received. `DONE` is set only for successful completion.
`ERROR` is set for descriptor, transport, or busy-command failures.

Starting a second command while busy does not alter the active descriptor or
stop the transfer. The new start is ignored and records the `BUSY` error. If the
original transfer then succeeds, both sticky `DONE` and `ERROR` may be set: the
done bit describes the original transfer and the error describes the rejected
start. If the active transfer itself later fails, that failure code takes
precedence over the earlier `BUSY` code.

Software should interpret completion as follows:

| Observed state | Meaning |
| --- | --- |
| `BUSY=1` | The captured command is pending or active; do not reuse its buffers. |
| `BUSY=0, DONE=1, ERROR=0` | The command completed successfully. |
| `BUSY=0, ERROR=1` | An error is present; inspect `ERROR_CODE` and `BYTES_COMPLETED`. |
| `BUSY=0, DONE=1, ERROR=1` | A transfer succeeded but another start was rejected while it was busy. |
| All three clear | Idle with no retained event, or status has been cleared. |

Completion means every destination write has received its TileLink response.
It does not replace the software fence required before another agent consumes
the destination.

## Error Codes

| Value | Name | Cause and effect |
| ---: | --- | --- |
| 0 | `NONE` | No error. |
| 1 | `BUSY` | A start was attempted while another command was active; the active command continues. |
| 2 | `LOCAL_RANGE` | The nonzero local interval extends beyond the scratchpad. No transfer traffic is issued. |
| 3 | `ADDRESS_OVERFLOW` | Addition of byte count to either 64-bit starting address overflows. No transfer traffic is issued. |
| 4 | `EXTERNAL_RANGE` | The complete nonzero external interval is not contained in one configured allowed range. No transfer traffic is issued. |
| 5 | `TILELINK` | A response was denied or corrupt, or negotiated operations could not make progress. New transactions stop and outstanding transactions drain. |

For a nonzero descriptor that violates more than one validation rule, address
overflow has precedence over local range, which has precedence over external
range.

After a TileLink failure, bytes already acknowledged at the destination are not
rolled back. Returned data carrying an error is not written as valid source
data, but earlier writes may have modified the destination. Software must treat
the destination as partially modified and use `BYTES_COMPLETED` only as the
length of the contiguous acknowledged prefix.

## Reserved Behavior

Unassigned command bits and register offsets are reserved for compatible future
extensions. Software must write zero to reserved bits and must not depend on the
value or side effects of reserved addresses.
