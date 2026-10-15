# Explore the fork: two RISC-V engines and a CPU-first experiment

**Run a tiny guest program on the plain-Java oracle, then follow the path toward
accelerator execution.** This fork contains two distinct RISC-V experiments on
top of [upstream TornadoVM](https://github.com/beehive-lab/TornadoVM). They share
an interest in dense machine state, but have different APIs, instruction coverage
and build boundaries. This guide makes those choices visible before you install
an SDK or select a device.

[Choose a lane](#choose-a-lane) · [Try the CPU oracle](#try-the-cpu-oracle) ·
[Command cheat sheet](#command-cheat-sheet) · [Read a result](#read-a-result)

## Choose a lane

| Surface | Entry point and purpose | Boundary |
| --- | --- | --- |
| Independent RV32IM batches | [`m3/cpu-gpu`](../m3/cpu-gpu/README.md): `Rv32iBatch`, `CpuRv32iEngine`, `TornadoRv32iSession` | Private RAM per core; independent CPU oracle and GPU kernel |
| Tiered RV32IMAC engine | [`synexia-cpu-model`](../synexia-cpu-model/README.md): `RiscV32ExecutionEngine` | Shared predecode, packed basic blocks and interpreter fallback; separate classes and state |
| Optional M3JDK adapter | [`m3/jdk-bulk-provider`](../m3/jdk-bulk-provider/README.md): `M3JdkRv32iBulkProvider` | Exact pinned internal JDK contract; not in the root TornadoVM reactor |
| Source custody | [`m3/`](../m3/README.md) | Provenance checks are separate from compilation, device parity and speed |

These are fork additions. TornadoVM supplies the underlying Java task-graph,
compiler, runtime and backend infrastructure; the fork does not replace those
with another accelerator runtime. Its [M3 direction](../README.md#m3-direction-in-this-fork)
remains CPU-first: preparation, transfers, synchronization and memory costs count.

The independent RV32IM lane maps one GPU work-item to one virtual core, with an
instruction budget and private memory. Its custom `Xm3halt` instruction reports
`x10/a0` as the exit code. It is not an operating-system ABI or Linux machine.
The RV32IMAC lane adds compressed instructions, atomics, machine-mode state and
tiered execution. Its README lists the remaining privilege/MMU/device layers;
those capabilities must not be inferred from the module name.

## Try the CPU oracle

This exercise uses only four plain-Java source files from the RV32IM lane. It
needs Git, a POSIX-like shell with `mktemp`, and a full JDK 21 with `javac` and
`jshell`. It does not require Maven, a TornadoVM SDK or a GPU. Run from a fresh
checkout, or omit the clone steps if you already have one:

```bash
git clone https://github.com/hsoliwal/synexia-tornadovm.git
cd synexia-tornadovm
export M3_DEMO_JDK=/absolute/path/to/jdk-21
"$M3_DEMO_JDK/bin/java" -version
git rev-parse HEAD
M3_DEMO_CLASSES=$(mktemp -d)
"$M3_DEMO_JDK/bin/javac" --release 21 -Xlint:all -Werror \
  -d "$M3_DEMO_CLASSES" \
  m3/cpu-gpu/src/main/java/com/synexia/m3/cpugpu/Rv32i.java \
  m3/cpu-gpu/src/main/java/com/synexia/m3/cpugpu/Rv32iBatch.java \
  m3/cpu-gpu/src/main/java/com/synexia/m3/cpugpu/Rv32iAssembler.java \
  m3/cpu-gpu/src/main/java/com/synexia/m3/cpugpu/CpuRv32iEngine.java &&
"$M3_DEMO_JDK/bin/jshell" --class-path "$M3_DEMO_CLASSES"
```

Stop if compilation fails. In JShell, paste:

```java
import com.synexia.m3.cpugpu.*;
var batch = new Rv32iBatch(2, 256);
batch.loadWordsAllCores(0, Rv32iAssembler.addi(10, 1, 5), Rv32iAssembler.halt());
batch.setRegister(0, 1, 7);
batch.setRegister(1, 1, 35);
batch.setProgramCounter(0, 0);
batch.setProgramCounter(1, 0);
new CpuRv32iEngine().runSlice(batch, 8);
for (int core = 0; core < batch.coreCount(); core++) {
    System.out.println("core=" + core + " halted=" + (batch.status(core) == Rv32i.STATUS_HALTED)
        + " exit=" + batch.exitCode(core));
}
```

Expected from the source contract:

```text
core=0 halted=true exit=12
core=1 halted=true exit=40
```

Each core executes `a0 = x1 + 5` and then halts. The identical guest instructions
operate on separate register and memory state. `runSlice` gives each core an
instruction budget; the plain-Java oracle visits the cores sequentially, so this
example is not host-thread parallel execution or a speedup measurement.

Use `/exit` to leave JShell. Compiled classes are in the temporary directory named
by `M3_DEMO_CLASSES`; no repository source is changed. This documentation pass
source-checked the example but did not compile or execute it. The output above
is an expectation, not a captured test receipt.

### Small experiments to read and try

- Start a new batch and give the first call a budget of `1`: it should yield after
  the add. A second one-instruction slice reaches the halt. Inspect `status`,
  `programCounter` and `retiredInstructions`; an unhalted core has no final exit
  result yet.
- Start another fresh batch, change only one core's `x1` and check the other
  core's result stays the same. A halted batch will not rerun just because a
  register changed.
- Read the illegal-instruction and misaligned-load cases in
  [`CpuRv32iEngineTest`](../m3/cpu-gpu/src/test/java/com/synexia/m3/cpugpu/CpuRv32iEngineTest.java).
  They inspect trap cause/value and retirement count, not just a final integer.

The assembler, batch and oracle are small enough to inspect together:
[`Rv32iAssembler`](../m3/cpu-gpu/src/main/java/com/synexia/m3/cpugpu/Rv32iAssembler.java),
[`Rv32iBatch`](../m3/cpu-gpu/src/main/java/com/synexia/m3/cpugpu/Rv32iBatch.java),
[`CpuRv32iEngine`](../m3/cpu-gpu/src/main/java/com/synexia/m3/cpugpu/CpuRv32iEngine.java).

## Command cheat sheet

Run Maven commands from the checkout root with the appropriate JDK selected.
These commands describe different evidence scopes, not interchangeable passes.

| Goal | Command or route | Prerequisites / interpretation |
| --- | --- | --- |
| CPU-only first look | Four-file `javac`/JShell exercise above | JDK 21; no Tornado API dependency in this subset |
| Independent RV32IM tests | `mvn -f m3/cpu-gpu/pom.xml test` | JDK 21, Maven and resolvable pinned Tornado API/JUnit artifacts; GPU tests default off |
| RV32IM device differential tests | `mvn -f m3/cpu-gpu/pom.xml -Dm3.gpu=true test` | Additionally needs a qualified TornadoVM runtime/device; inspect full parity and actual test reports |
| Tiered RV32IMAC test route | [CPU-model workflow](../.github/workflows/synexia-cpu-model.yml) and [module guide](../synexia-cpu-model/README.md) | Separate root-reactor module, staged Graal/JVMCI dependencies and the documented JDK profile |
| M3JDK adapter contract | [Adapter guide](../m3/jdk-bulk-provider/README.md) and [source lock](../m3/jdk-bulk-provider/SOURCE_LOCK.json) | Exact receiver contract; no silent CPU fallback on device/parity failure |
| Full SDK / normal Java kernels | Current [Makefile](../Makefile), [build driver](../bin/compile) and [installation guide](../INSTALL_FROM_SOURCE.md) | Native/backend prerequisites and a selected device |

The standalone RV32IM [POM](../m3/cpu-gpu/pom.xml) targets Java 21, enables
preview for its Tornado API dependency and pins `tornado-api` to `6.1.0-jdk21`.
That differs from the root reactor's profiles. A root build or newer SDK version
is not proof that this standalone module's pinned dependency was tested.

## Read a result

A successful CPU exercise demonstrates only the observed oracle behavior. Device
qualification must compare the independent CPU and GPU results across registers,
architectural state and RAM. The optional JDK adapter admits `rv32im-slice` only
through its pinned contract and a verified receipt; an exception is not a
successful fallback.

For a report, include the exact commit, JDK, command, core count, memory per core,
instruction budget, trap/status/retirement results and expected values. GPU
reports additionally need SDK/backend, device and driver identity plus transfer
and synchronization costs. A compile pass, CPU parity test and GPU performance
measurement answer different questions.

Troubleshooting starts with the lane:

- **`javac`/`jshell` missing:** select a full JDK, not only a Java runtime.
- **Class not found:** keep the classpath pointed at the same temporary output
  directory and confirm the four-file compile succeeded.
- **Preview/class-version errors in Maven:** use the standalone module's JDK 21
  contract and pinned dependency; do not mix root-profile outputs blindly.
- **Unexpected yield/trap:** inspect budget, PC, alignment and trap fields before
  treating an exit code as a completed result.
- **No device or parity failure:** use the CPU oracle to narrow the behavior, but
  retain the device failure in the report.

Preserve upstream and per-file licenses; see the [fork's custody boundaries](../m3/README.md)
and [contribution guide](../CONTRIBUTING.md). This document changes no engine,
recipe, native code or qualification gate. Source inspected at
[`7c47d0a58281fdf7e779d34384e1cae6c50de733`](https://github.com/hsoliwal/synexia-tornadovm/tree/7c47d0a58281fdf7e779d34384e1cae6c50de733).
No Java compilation/execution, Maven tests, GPU execution or benchmark was run
for this guide.
