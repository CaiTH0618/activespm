# ActiveSPM

ActiveSPM is a Chipyard generator for a software-controlled DMA engine with a
globally addressable scratchpad memory. The generator is intended to be used by
the customized NPU subsystem in Chipyard.

This repository currently contains an elaboratable Scala interface scaffold.
It does not yet implement DMA transfers, scratchpad storage, or functional MMIO
register behavior.

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

`chipyard.ActiveSPMScaffoldRocketConfig` is provided only to verify subsystem
elaboration. It must not be used to run software or to access the scratchpad.

## Interfaces

Each instance exposes three TileLink interfaces: a 64-bit MMIO control manager,
an aggregated scratchpad data manager, and an external-memory DMA client. The
DMA also has a private TileLink client connected to the scratchpad's internal
manager; that path does not leave the instance.

The control block submits one command at a time through a Decoupled request
channel. DMA completion also uses a Decoupled channel so the result remains
stable until accepted. Live busy and byte-count progress are reported
separately without a handshake.

At this scaffold stage the MMIO fields read as zero and ignore writes, the DMA
clients remain idle, and any scratchpad request triggers an assertion explaining
that storage is not implemented.

## License

ActiveSPM is licensed under the BSD 3-Clause License. See `LICENSE`.
