<!-- SPDX-License-Identifier: Apache-2.0 -->
# M3 CPU-on-GPU: RV32IM virtual cores on TornadoVM

This additive M3 module executes many independent **RV32I 2.1 + M 2.0** virtual CPU cores inside a TornadoVM kernel. The host and device implementations share one flat ABI but not one interpreter implementation: `CpuRv32iEngine` is the independent heap oracle, while `Rv32iKernel` is deliberately written in GPU-friendly Java over `KernelContext` and `IntArray`.

The mapping is intentionally mechanical:

```text
GPU work-item 0 -> virtual core 0 -> x0..x31 + PC/state + private RAM slice
GPU work-item 1 -> virtual core 1 -> x0..x31 + PC/state + private RAM slice
...
GPU work-item N -> virtual core N -> x0..x31 + PC/state + private RAM slice
```

Each work-item fetches, decodes and executes sequential guest instructions for a bounded instruction budget. Thousands of independent virtual CPUs can therefore be advanced in parallel without a grid-wide barrier or cross-core lock.

## Implemented execution contract

The engine implements the complete instruction families used by RV32I integer user code plus the ratified M extension: LUI/AUIPC, JAL/JALR, all six conditional branches, byte/half/word loads and stores, all base integer immediate/register ALU operations, MUL/MULH/MULHSU/MULHU, DIV/DIVU/REM/REMU, FENCE, FENCE.I, ECALL and EBREAK. `x0` is hard-wired to zero; natural load/store alignment and 4-byte instruction alignment are checked; RISC-V exception cause numbers are reported for alignment, access, illegal-instruction, breakpoint and environment-call traps.

`0x0000000B` is reserved by this module as **Xm3halt**, a custom-0 semihosting instruction. It halts the virtual core and records `x10/a0` as the exit code. This keeps batch termination deterministic without pretending that the module implements a privileged operating-system ABI.

The initial memory model is intentionally private per virtual core. It is a good fit for coding-agent workers, deterministic transformations, hashes, parsers, search/scoring workers, bytecode/ISA experiments and massive independent simulations. Shared-memory SMP, the A extension, CSRs, page tables, interrupts, devices and Linux boot are separate architectural layers and are not silently emulated.

## ELF loading

`Elf32RiscVLoader` accepts little-endian RISC-V ELF32 `ET_EXEC` images, maps `PT_LOAD` segments into a core's flat RAM, zero-fills BSS, and sets the ELF entry point. It intentionally rejects dynamic linking, relocations, TLS and addresses outside the configured flat RAM.

## CPU oracle and GPU differential mode

Correctness is not inferred from identical source code. The CPU oracle and GPU kernel have separate decode/execution implementations. `Rv32iDemo verify` runs the same batch on both and compares the full register bank, architectural state, and RAM image.

```bash
# CPU-only compile/smoke gate, no TornadoVM installation needed
out=$(mktemp -d)
javac --release 21 -Xlint:all -Werror -d "$out" \
  src/main/java/com/synexia/m3/cpugpu/Rv32i.java \
  src/main/java/com/synexia/m3/cpugpu/Rv32iBatch.java \
  src/main/java/com/synexia/m3/cpugpu/Rv32iAssembler.java \
  src/main/java/com/synexia/m3/cpugpu/CpuRv32iEngine.java \
  src/main/java/com/synexia/m3/cpugpu/Elf32RiscVLoader.java
rm -rf "$out"
```

With Maven and TornadoVM available, the standalone module can be tested without adding it to the upstream TornadoVM reactor:

```bash
mvn -f m3/cpu-gpu/pom.xml test
```

GPU differential tests are opt-in because they require a qualified TornadoVM device/runtime:

```bash
mvn -f m3/cpu-gpu/pom.xml -Dm3.gpu=true test
```

Run the demonstration through a normal TornadoVM-enabled Java launch:

```text
com.synexia.m3.cpugpu.Rv32iDemo cpu    [cores] [instructionBudget]
com.synexia.m3.cpugpu.Rv32iDemo gpu    [cores] [instructionBudget]
com.synexia.m3.cpugpu.Rv32iDemo verify [cores] [instructionBudget]
```

The included program sums 1..100 independently on every virtual core and exits with 5050 in `a0`.

## M3 placement and donor policy

This module lives only below `m3/`; it does not alter TornadoVM runtime, driver, compiler, module or root reactor files. It consumes TornadoVM's public `TaskGraph`, `KernelContext`, `WorkerGrid1D`, transfer and off-heap array APIs instead of creating another accelerator runtime.

The architecture was informed by the public descriptions of `Vogtinator/risky-v` (RISC-V system emulation in a GPU shader) and `OpenDGPS/simulacore` (multicore opcode interpretation in CUDA). Their GitHub repositories currently expose no license metadata and no root `LICENSE` file through the GitHub API, so **no source code from either project is copied into this module**. TornadoVM itself is the execution donor already present in this repository; new files here are Apache-2.0, consistent with the existing `m3/` policy.

See [`docs/DESIGN.md`](docs/DESIGN.md) for the memory ABI, state machine, divergence model, proof gates and extension path.
