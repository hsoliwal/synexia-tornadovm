# Exploring this TornadoVM fork

TornadoVM offloads selected Java tasks to accelerator backends while the surrounding
application runs on the JVM. This fork's M3 direction explores accelerated precompute;
that direction is not proof that a workload benefits from a GPU. Preparation,
transfer, synchronization and retained device memory belong in the evaluation.

Read the [project README](../README.md) for the upstream introduction and
[installation prerequisites](../INSTALL_FROM_SOURCE.md) for platform setup.
This guide focuses on navigating this checkout and choosing evidence of the right scope.

## Source map: from Java method to device work

| Layer | Start here | What to follow |
| --- | --- | --- |
| Application API | [tornado-api](../tornado-api/) | TaskGraph, immutable graph snapshots, execution plans, arrays and annotations |
| Runtime | [tornado-runtime](../tornado-runtime/) | Execution coordination, compilation plumbing and JVMCI integration |
| Backends | [tornado-drivers](../tornado-drivers/) | Shared infrastructure and OpenCL, CUDA or Metal-specific paths |
| Packaging / launch | [tornado-assembly](../tornado-assembly/), [bin/compile](../bin/compile) | SDK assembly, environment and generated argfiles |
| Examples and validation | [examples](../tornado-examples/), [unit tests](../tornado-unittests/), [benchmarks](../tornado-benchmarks/) | Separate correctness demonstrations from performance experiments |
| Native library integration | [hybrid API guide](../HYBRID_API_GUIDE.md) | Library calls and kernels sharing an execution graph |

The [parent POM](../pom.xml) is the module/profile reference.
Do not infer a license for every module from that POM: preserve source headers and
the README's [per-module license breakdown](../README.md#licenses-per-module),
including Apache-2.0 and GPLv2 with Classpath Exception boundaries.

## Build and test cheat sheet

The examples below are for a Unix-like development shell at the repository root.
Commands were checked against source, **not executed for this documentation change**.

Before a source build, select a supported JDK and set `JAVA_HOME`; the installation
guide's baseline is JDK 21, GCC/G++ 13 or newer and backend-specific development
dependencies. The Python-based build scripts also require Python 3. OpenCL requires
a suitable runtime/driver; CUDA requires the NVIDIA toolkit and driver; Metal requires
a suitable Apple Silicon/macOS setup. Windows has separate setup instructions.

| Goal | Command | Important boundary |
| --- | --- | --- |
| Record the checkout/toolchain | `git rev-parse HEAD`; `"$JAVA_HOME/bin/java" -version` | Include these with every result |
| Build OpenCL with JDK 21 | `make jdk21 BACKEND=opencl` | Current default backend is OpenCL |
| Build CUDA alone with JDK 21 | `make jdk21 BACKEND=cuda` | Helps avoid accidentally testing an OpenCL device instead |
| Build Metal alone with JDK 21 | `make jdk21 BACKEND=metal` | Prefer explicit backend selection; the `metal` target selects metal,opencl |
| Use the newer-JDK build profile | `make jdk22plus BACKEND=opencl` | Select a matching supported JDK; this is a distinct profile |
| Limit Maven parallelism via the wrapper | `make mvn-single-threaded-jdk21 BACKEND=opencl` | The checked-in Maven config otherwise requests `-T1.5C` |
| Load the built SDK | `source setvars.sh` | Generated after successful build; inspect before sourcing |
| Enumerate usable devices | `tornado --devices` | Device discovery is not a kernel-correctness test |
| Run pure-JVM reflection tests | `make test-reflection JDK=jdk21` | API/runtime reactor; explicitly enables tests and cleans its outputs |
| Run no-device CUDA codegen tests | `make test-cuda-codegen JDK=jdk21` | Code generation tests, not CUDA hardware execution |
| Run hardware-backed test lane | `make fast-tests` | Requires built SDK launchers/native tools and a working selected device |
| Build Sphinx documentation | `make docs` | Install [docs requirements](requirements.txt) in your chosen environment first |

These targets are defined in the [Makefile](../Makefile).
The default `make` route selects JDK 21; it is not the same as the `sdk` target's
JDK detection. Build scripts may download/bootstrap dependencies and clean Maven
outputs. Check network access, cache locations and free disk space before running.

### Avoid two common false positives

1. **A successful Maven install is not necessarily a test pass.** The
   [POM](../pom.xml) controls test skipping; the reflection/codegen Makefile targets
   explicitly use `-DskipTests=false`. Report the actual suite and count.
2. **A generated SDK path is not a fixed historical filename.**
   [bin/compile](../bin/compile) generates `setvars.sh` and the SDK's argfile.
   Use `TORNADOVM_HOME` from that environment and inspect
   `"$TORNADOVM_HOME/tornado-argfile"`. Older examples in
   [BUILD_AND_RUN.md](../BUILD_AND_RUN.md) include versioned paths and root-argfile
   assumptions; confirm them against the current generated SDK before copying them.
   [.mvn/maven.config](../.mvn/maven.config) controls parallelism and output options;
   it does not itself select the JDK/backend profiles.

## Worked reading exercise: vector addition

Open [VectorAddInt.java](../tornado-examples/src/main/java/uk/ac/manchester/tornado/examples/VectorAddInt.java).
It is small enough to trace the complete host/device contract:

1. `IntArray` inputs are initialized to 10 and 20. The kernel iterates over the
   output size with `@Parallel` and writes `c[i] = a[i] + b[i]`.
2. A task graph named `s0` transfers both inputs with `EVERY_EXECUTION`,
   invokes task `t0`, then transfers the output back with `EVERY_EXECUTION`.
   Changing transfer modes would change assumptions about input mutation.
3. `snapshot()` produces the immutable graph consumed by a
   `TornadoExecutionPlan`. Trace the API classes before jumping into a driver.
4. The example executes ten times and checks that every returned value equals 30.
   It also invokes a sequential method, but does not establish a controlled
   end-to-end performance comparison.
5. The reported profiler total uses nanoseconds. Keep compilation, transfers,
   kernel time and host work distinct; do not label this example's output a speedup.

After building and loading the environment, the source's documented invocation is:

```sh
tornado --enableProfiler console -m tornado.examples/uk.ac.manchester.tornado.examples.VectorAddInt 256
```

Expected behavior from reading the source: it prints a correctness message for each
iteration and a profiler log. This is not a captured execution result. Confirm the
selected backend/device and inspect any “Result is wrong” output before discussing
timing. A tiny vector is a teaching example, not a representative acceleration case.

## When something fails

- **JDK/profile mismatch:** inspect `JAVA_HOME`, the reported Java version and the
  explicit Makefile target. Do not mix artifacts from different profile builds.
- **Missing native library / no devices:** check the chosen backend's driver/toolkit
  and SDK environment; a Java-only test cannot validate this boundary.
- **Module or classpath error:** inspect the generated SDK argfile and actual JAR
  names. Avoid substituting a version string from an old guide.
- **Test passed on the wrong device:** record device enumeration, backend and selected
  device with the result. Prefer single-backend qualification when isolating a failure.
- **Small kernel is slower:** account for build-up, transfers and synchronization;
  reuse and arithmetic intensity are hypotheses to measure, not guaranteed wins.

## Contributing a useful experiment

Include the exact commit, JDK/profile, OS/CPU/GPU, driver/toolkit versions, command,
input size and correctness oracle. Separate cold-start from warmed execution and
record transfer costs, memory and a comparable CPU baseline before making a claim.
Keep failed, skipped and not-run cases visible.

For changes to this fork, use its current default branch as the review base and
keep the proposed scope explicit. Upstream contributions have their own
[contributor process](../CONTRIBUTING.md), including branch/CLA expectations.
Do not assume a fork PR has been reviewed or accepted upstream.

Keep upstream authorship and per-file licenses unchanged. This guide adds no source,
runtime, CI or license changes. No compiler, GPU execution, benchmark or Sphinx build
was run for this documentation revision.
