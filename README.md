# ActiveSPM

ActiveSPM is a Chipyard generator for a software-controlled DMA engine with a
globally addressable scratchpad memory. The generator is intended to be used by
the customized NPU subsystem in Chipyard.

This repository currently contains only the project skeleton. It does not yet
provide hardware, software, configuration, or test implementations.

## Directory Layout

- `src/main/scala/activespm`: Scala generator sources.
- `src/test/scala/activespm`: Scala hardware tests.
- `software/tests`: C tests.
- `software/examples`: C example programs.

## Building

ActiveSPM is built as a subproject of its parent Chipyard repository. From the
Chipyard root, enter the configured environment and compile the project with:

```sh
source env.sh
sbt "activespm/compile" "activespm/Test/compile"
```

## License

ActiveSPM is licensed under the BSD 3-Clause License. See `LICENSE`.
