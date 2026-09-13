# ActiveSPM Documentation

## Introduction

ActiveSPM is a Chipyard generator that combines a globally addressable,
banked scratchpad memory with a software-controlled DMA engine. A processor or
accelerator accesses the scratchpad through its system address, while the DMA
moves an arbitrary byte range between that same storage and an allowed external
physical-memory range.

An ActiveSPM instance is intended to act as a software-managed staging point in
the NPU subsystem. Rocket cores configure transfers through a polling MMIO
interface, Gemmini reaches ActiveSPM through its existing system-bus DMA, and
the ActiveSPM DMA independently transfers data between its local scratchpad and
memory. Hardware provides the movement mechanisms; software remains responsible
for buffer ownership and synchronization.

The current design has one active DMA command, one outstanding transaction on
each DMA TileLink interface, one clock domain per instance, and no interrupt,
command queue, address translation, ECC, or hardware data-hazard tracking.

## Document Index

- [Architecture](architecture.md): System role, module responsibilities,
  interfaces, data paths, and end-to-end transfer flows.
- [Configuration and Integration](configuration-integration.md): Generator
  parameters, validation rules, Chipyard attachment, clocks, TileLink, and NoC
  endpoint mapping.
- [Scratchpad](scratchpad.md): Addressing, bank organization, supported
  operations, arbitration, visibility, and access rules.
- [DMA and Control](dma-control.md): DMA semantics, internal request/completion
  protocol, MMIO register ABI, status behavior, and error handling.
- [Software Guide](software-guide.md): Physical-address requirements, polling
  sequence, synchronization, buffer ownership, helper API, and the dual-hart
  Gemmini example.
- [Build and Verification](build-verification.md): Development commands,
  available configurations, test coverage, integration-test procedure, and
  current verification boundaries.

## Quick Orientation

| Software intent | Local side | External side | Result |
| --- | --- | --- | --- |
| Load (`DIRECTION=0`) | Destination scratchpad offset | Source physical address | External memory is copied into ActiveSPM |
| Store (`DIRECTION=1`) | Source scratchpad offset | Destination physical address | ActiveSPM is copied into external memory |

Software identifies the local side with an offset relative to the selected
instance's scratchpad base. CPUs and other system masters instead use the
globally mapped address `scratchpad base + local offset`.

For the complete software sequence, start with the
[Software Guide](software-guide.md). For SoC construction or NoC work, start
with [Configuration and Integration](configuration-integration.md).
