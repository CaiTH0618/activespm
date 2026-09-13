# ActiveSPM

ActiveSPM is a Chipyard generator for a software-controlled DMA engine with a
globally addressable scratchpad memory. The generator is intended to be used by
the customized NPU subsystem in Chipyard.

This repository currently contains an elaboratable Scala interface scaffold and
a functional banked scratchpad. It does not yet implement DMA transfers or
functional MMIO register behavior.

## Directory Layout

- `src/main/scala/activespm`: Parameters, interfaces, module shells, and system
  integration.
- `src/test/scala/activespm`: Scala hardware tests.
- `software/tests`: C tests.
- `software/examples`: C example programs.

## Building

ActiveSPM is built as a subproject of its parent Chipyard repository. From the
Chipyard root, enter the configured environment and compile the project with:

```sh
source env.sh
sbt "activespm/test" "chipyard/compile"
```

`chipyard.ActiveSPMScaffoldRocketConfig` verifies the same-width subsystem
attachment. `chipyard.ActiveSPMWideSBusScaffoldRocketConfig` verifies a 16-byte
SBus attached to the scratchpad's 8-byte native interface. Both remain scaffold
configurations and must not be used as functional DMA/software configurations.

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

At this scaffold stage the MMIO fields read as zero and ignore writes, and the
DMA clients remain idle. Scratchpad accesses are functional, but no MMIO command
can yet launch a transfer.

## License

ActiveSPM is licensed under the BSD 3-Clause License. See `LICENSE`.
