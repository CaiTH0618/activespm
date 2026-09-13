# Configuration and System Integration

## Enabling ActiveSPM

The Chipyard digital top includes the optional ActiveSPM subsystem attachment
trait. A configuration enables the generator by supplying the complete ordered
sequence of instance parameters through the ActiveSPM configuration fragment.
An empty sequence produces no instances.

The configuration fragment replaces the entire instance sequence at its point
in the Config composition; it is not an append operation. Config composition
must therefore leave the intended fragment as the effective value.

## Instance Parameters

| Parameter | Meaning |
| --- | --- |
| `id` | Non-negative instance identifier used for stable naming and software-to-topology correspondence; it does not derive addresses. |
| `controlAddress` | Finite contiguous physical range containing the MMIO control block. |
| `scratchpadAddress` | Finite contiguous global physical range occupied by the scratchpad. Its size is the address-set mask plus one. |
| `spadBeatBytes` | Native scratchpad TileLink width in bytes, physical bank word width, bank-interleaving granularity, and DMA-local width. |
| `nBanks` | Number of interleaved physical scratchpad banks. |
| `externalMemoryRanges` | Non-empty list of finite contiguous physical ranges that the DMA is allowed to use on its external side. |
| `controlXType` | Standard TileLink crossing applied between the CBus control attachment and the SBus-derived ActiveSPM domain. |

Addresses are explicit. Integrators must allocate each instance's control and
scratchpad regions in the platform memory map and must give software the same
bases. Changing an instance ID does not relocate either region.

The external-memory allowlist prevents accidental DMA access to unrelated MMIO
or reserved address space. It is descriptor validation and client visibility
information, not a security boundary.

## Elaboration-Time Requirements

Each instance must satisfy all of the following:

- The ID is non-negative and unique in the configured sequence.
- The control range is finite, contiguous, 8-byte aligned, and large enough to
  contain the register at offset `0x30` in full.
- The scratchpad range is finite and contiguous.
- Scratchpad size, native beat bytes, and bank count are powers of two.
- The scratchpad base is aligned to the complete scratchpad size.
- The scratchpad contains at least one native beat per bank.
- Every external-memory range is finite and contiguous, and at least one range
  is present.
- All public control and scratchpad ranges across all configured instances are
  pairwise non-overlapping.

The bank address sets are derived from these parameters and are checked to be
disjoint, equal in capacity, contained in the scratchpad range, and collectively
cover the complete range.

## Bus Attachment

For every configured instance, subsystem integration performs three distinct
attachments:

| Port | Bus operation | Adaptation |
| --- | --- | --- |
| Control manager | Coupled to CBus | CBus-width adaptation, fragmentation to 8-byte MMIO accesses, and the configured clock crossing |
| Scratchpad manager | Coupled to SBus | SBus-width adaptation, fragmentation to the native scratchpad width, and FIFO-domain repair |
| External DMA client | Coupled from SBus | Negotiated directly as a coherent SBus initiator |

The system-bus width need not equal `spadBeatBytes`. Standard TileLink adapters
convert the global scratchpad path, while the private DMA-local connection stays
at the native scratchpad width. The DMA realigns bytes independently of either
interface width.

Only `Get`, `PutFullData`, and `PutPartialData` are required on the data paths.
The DMA selects transfer sizes according to negotiated manager capabilities,
alignment, remaining length, and beat boundaries. It does not assume that a
full beat is always legal.

## Stable Node Names

For instance `i`, the public Diplomacy names are:

| Function | Name |
| --- | --- |
| Control manager | `activespm-ctrl[i]` |
| Aggregated scratchpad manager | `activespm-spad[i]` |
| External-memory DMA client | `activespm-dma[i]` |

These exact strings are also Constellation mapping keys. The brackets make the
identifier boundary explicit, avoiding ambiguous substring matches such as
instance 1 matching instance 10.

On an SBus NoC, the DMA name is an ingress and the scratchpad name is an egress.
They should normally map to the same router because they represent one physical
memory tile. The control manager remains on CBus and does not require a separate
SBus NoC endpoint. Private bank managers and the local DMA client must not be
added to the global mapping.

## Provided Chipyard Configurations

| Configuration | Purpose | ActiveSPM organization |
| --- | --- | --- |
| `ActiveSPMScaffoldRocketConfig` | Single-instance elaboration and same-width attachment scaffold | One 64 KiB, four-bank instance with an 8-byte native beat |
| `ActiveSPMWideSBusScaffoldRocketConfig` | Width-adaptation elaboration scaffold | The same instance on a 128-bit SBus |
| `ActiveSPMDualGemminiMeshRocketConfig` | Multi-instance Rocket, Gemmini, NoC, and bare-metal integration | Two 64 KiB, four-bank instances on a 128-bit 3-by-2 SBus mesh |

The scaffold configurations demonstrate attachment and can launch MMIO-driven
DMA transfers, but they are not software-qualified production configurations.

The dual-instance configuration assigns instance 0 control and scratchpad bases
of `0x10050000` and `0x70000000`, and instance 1 bases of `0x10051000` and
`0x70010000`. Both DMAs allow the physical memory interval from `0x80000000`
through `0x8fffffff`.

Its mesh is organized by row as follows:

| Row | Compute router | ActiveSPM router | Memory router |
| --- | --- | --- | --- |
| 0 | Rocket 0 and Gemmini 0 | DMA ingress and scratchpad egress for instance 0 | Inclusive-L2/system bank 0 |
| 1 | Rocket 1 and Gemmini 1 | DMA ingress and scratchpad egress for instance 1 | Inclusive-L2/system bank 1 |

The design retains a coherence manager. An inclusive L2 is used in the supplied
dual-instance configuration; a broadcast coherence manager is also suitable
when an L2 data cache is not desired.

## Integration Checklist

Before using a new SoC configuration, verify that:

1. All physical ranges are reserved consistently in the platform memory map.
2. External-memory allowlists cover every DMA buffer but exclude unintended
   device regions.
3. Software uses the configured instance bases rather than deriving them from
   the ID.
4. SBus NoC mappings use the exact public names and colocate each DMA/scratchpad
   pair as intended.
5. The selected coherence manager and memory path accept the negotiated DMA
   reads and writes.
6. Any CBus-to-SBus clock difference is represented by `controlXType`.
7. Elaboration succeeds for the intended SBus width and scratchpad native width.
