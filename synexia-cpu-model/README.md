# Synexia CPU-on-GPU model

This module implements a dense **RV32IM CPU interpreter whose exact Java kernel can run either
directly on the JVM or through TornadoVM on an accelerator**.

## Execution model

One accelerator work-item represents one independent virtual CPU. The hot state is entirely primitive
and structure-of-arrays based:

- 32 integer registers per core in one flat `IntArray`
- PC, status, trap cause and retired-instruction counter in flat `IntArray` vectors
- byte-addressable RAM packed four bytes per `int`, with one contiguous slice per virtual core
- no map, collection, reflection, allocation or object per instruction/core in the kernel

A core executes a configurable sequential instruction **quantum** inside its work-item. Many virtual
cores execute those quanta in parallel. This is the deliberate boundary between sequential ISA
semantics and SIMT hardware.

## Implemented ISA

The interpreter implements the RV32I integer base operations used by ordinary compiled code plus the
RV32M multiply/divide extension:

- LUI, AUIPC
- JAL, JALR
- BEQ/BNE/BLT/BGE/BLTU/BGEU
- LB/LH/LW/LBU/LHU
- SB/SH/SW
- ADDI/SLTI/SLTIU/XORI/ORI/ANDI/SLLI/SRLI/SRAI
- ADD/SUB/SLL/SLT/SLTU/XOR/SRL/SRA/OR/AND
- MUL/MULH/MULHSU/MULHU/DIV/DIVU/REM/REMU
- FENCE/FENCE.I as ordering no-ops in the isolated-core model
- ECALL trap
- EBREAK as the explicit environment halt instruction

Misaligned instruction/load/store addresses, out-of-range memory and illegal instructions produce
architectural trap state. Register x0 is forced to zero.

Not yet implemented are compressed (C), atomics (A), floating point (F/D), vector (V), CSRs,
privileged modes, interrupts, page tables/MMU, devices and Linux boot plumbing. Those are intentionally
separate additive layers rather than hidden inside the base decoder.

## Backends

`JvmRiscV32Executor` invokes `RiscV32Kernel.runQuantum` as ordinary Java and is the deterministic
reference implementation.

`TornadoRiscV32Executor` submits that **same method** as a TornadoVM task. Machine state is uploaded
once. Between quanta only status/trap vectors return to the host; register files, PCs, counters and
RAM stay resident on the device. Full state is copied back on demand at completion.

An explicit `TornadoDevice` may be supplied to `TornadoRiscV32Executor`; otherwise TornadoVM's
normal default-device selection applies.

## Build and test

From the repository root on the JDK 22+ profile:

```bash
mvn -Pjdk22plus -pl synexia-cpu-model -am test -DskipTests=false
```

Run the reference demo:

```bash
java -m synexia.cpu.model/com.synexia.tornadovm.cpu.RiscV32Demo
```

Run through TornadoVM:

```bash
tornado -m synexia.cpu.model/com.synexia.tornadovm.cpu.RiscV32Demo tornado
```

## Donor architecture

The design follows the established CPU-in-GPU-kernel pattern demonstrated by projects such as
RISKY-V (RISC-V in GLSL) and Simulacore (x86 interpretation in CUDA), but this implementation is an
original Java/TornadoVM implementation and does not copy their source. That keeps licensing and the
Java-only execution contract clear while retaining the useful architectural idea.

The next additive layers can implement privileged RISC-V, devices/MMIO, shared physical memory,
ELF loading, and additional ISA decoders without changing the backend contract.
