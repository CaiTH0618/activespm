# Build and Verification

## Entering the Development Environment

Run all commands from the Chipyard repository root. Load both the normal
Chipyard environment and the project build-resource settings:

```sh
source env.sh
source scripts/chipyard-build-resources.sh
```

## Generator Tests and Compilation

Run the ActiveSPM Scala test suite and compile the integrating Chipyard project
with:

```sh
sbt "activespm/test" "chipyard/compile"
```

The ActiveSPM test task is configured to run tests serially. Generated
simulation artifacts are written below the generator's target directory and
are not part of the documentation or stable interface.

## Available Hardware-Test Coverage

| Area | Covered behavior |
| --- | --- |
| Parameters | Valid instance construction, stable public names, bank derivation, invalid scalar/range rejection, duplicate IDs, and overlapping public ranges |
| Interface ABI | Direction/error numeric encodings, register offsets and access width, and request/completion/progress field stability |
| Scratchpad | Shared visibility across global and local ports, full writes, byte-masked writes, distinct-bank concurrency, same-bank contention, backpressure, and reset during traffic |
| DMA | Both directions, arbitrary alignments and lengths, different external/native widths, zero length, validation precedence, source and destination TileLink failures, partial progress, and completion backpressure |
| MMIO control | Descriptor capture, request stability, busy-start rejection, sticky status, W1C behavior, reset, live/final progress, complete load/store paths, validation failures, and TileLink errors |

These tests verify modules directly and in focused combinations. They do not
constitute exhaustive formal proof, timing closure, performance
characterization, or validation on every possible TileLink topology.

## Elaborating Supplied Configurations

The single-instance scaffold checks the basic attachment, while the wide-SBus
scaffold checks width adaptation. The dual-instance mesh configuration is the
software integration target.

To elaborate a Verilator model for the dual-instance target and generate the
matching Gemmini parameter header, run:

```sh
make -C sims/verilator CONFIG=ActiveSPMDualGemminiMeshRocketConfig firrtl
```

Successful elaboration also checks parameter invariants, TileLink negotiation,
stable external node names, and Constellation endpoint mappings for that
configuration.

## Building the Bare-Metal Integration Test

After elaborating the matching SoC configuration, build the two-hart test with:

```sh
make -C generators/activespm/software/tests
```

The build expects the RISC-V bare-metal cross compiler and the Gemmini parameter
header generated for the selected configuration. It reuses the generic Gemmini
multicore CRT/runtime but keeps the ActiveSPM test source and public header in
the ActiveSPM generator.

The produced ELF is located at:

`generators/activespm/software/tests/build/dual-gemmini-mesh-baremetal`

The build checks that the program contains its two-hart entry point. It does
not itself execute the simulated SoC.

## Running the Verilator Integration Test

Run the generated ELF on the matching Verilator model with:

```sh
make -C sims/verilator \
  CONFIG=ActiveSPMDualGemminiMeshRocketConfig \
  BINARY="$PWD/generators/activespm/software/tests/build/dual-gemmini-mesh-baremetal" \
  run-binary
```

The test passes only if both harts complete their memory-to-scratchpad,
Gemmini, and scratchpad-to-memory paths; preserve external guard bytes; remain
isolated from one another; and observe correct sticky-status clearing.

Clean only the software-test build output with:

```sh
make -C generators/activespm/software/tests clean
```

## Interpreting Failures

When a Scala test fails, first identify whether it is a parameter/negotiation
failure at elaboration or a protocol/data mismatch during simulation. Width and
transfer-capability failures usually point to an incompatible attachment;
address validation failures usually point to a configuration or descriptor
range mismatch.

When the bare-metal build reports a missing Gemmini parameter header, elaborate
the exact dual-instance configuration before rebuilding. When simulation
reports a DMA failure, preserve the status, error code, and completed-byte count
before clearing them. A timeout does not imply that hardware has aborted the
request.

## Verification Boundaries and Future Work

The current verification establishes the intended initial implementation:
polling control, one active descriptor, byte-accurate data movement, bounded
buffering, one outstanding transaction per interface, coherent SBus
integration, and multi-instance Gemmini data flow.

Additional verification is required before relying on future features such as
interrupts, descriptor queues, multiple outstanding transactions, independent
clock domains, non-coherent attachments, ECC, access protection, or different
NoC channel organizations. Such features are outside the current software ABI
unless explicitly documented and tested.
