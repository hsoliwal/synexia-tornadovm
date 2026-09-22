# Synexia CPU-on-GPU

This module is a Java/TornadoVM **RV32IMAC execution engine designed to turn RISC-V guest code into
GPU-friendly work rather than permanently interpreting every guest instruction**.

The architectural interpreter remains the correctness floor. The optimized path is now:

```text
ELF / raw RV32 image
        |
        v
shared canonical decode
(RV32C -> canonical RV32)
        |
        v
basic-block discovery
        |
        v
safe superinstruction fusion
        |
        v
packed 64-bit micro-op stream
        |
        v
chained compiled blocks on each accelerator lane
        |
        +---- missing / invalidated code ----> architectural interpreter
```

One accelerator lane represents one independent virtual CPU.

## Dense architectural state

The hot state is primitive-only and GPU-coalesced:

- registers: `registers[register * cores + core]`
- CSRs: `csrs[slot * cores + core]`
- RAM: `memory[wordAddress * cores + core]`
- PC/status/trap/reservation/counters: flat primitive Tornado arrays
- guest RAM: four little-endian guest bytes per Java/Tornado `int`
- no object per register, instruction, block, map entry, or virtual CPU in device execution

When neighboring lanes execute the same program, accesses to the same guest register and word become
neighboring device-memory accesses rather than strides across per-core object graphs.

## Architectural ISA

### RV32I

LUI, AUIPC, JAL/JALR, all six integer branches, LB/LH/LW/LBU/LHU, SB/SH/SW, immediate
ALU/compare/shift instructions, register ALU/compare/shift instructions, FENCE and FENCE.I.

### RV32M

MUL/MULH/MULHSU/MULHU and DIV/DIVU/REM/REMU, including architectural divide-by-zero and signed
overflow behavior.

### RV32A

LR.W, SC.W, AMOSWAP.W, AMOADD.W, AMOXOR.W, AMOAND.W, AMOOR.W, AMOMIN.W, AMOMAX.W,
AMOMINU.W and AMOMAXU.W.

The current machine topology gives each virtual CPU private RAM. The A extension therefore provides
correct reservation/read-modify-write semantics inside each virtual machine. Cross-hart coherent
shared memory is intentionally a separate topology layer.

### RV32C

The common RV32 compressed integer forms are supported, including ADDI4SPN, LW/SW, ADDI/NOP,
JAL/J, LI/LUI, ADDI16SP, compact shifts/logic, BEQZ/BNEZ, LWSP/SWSP, JR/JALR, MV/ADD and
EBREAK.

Thirty-two-bit instructions beginning on a two-byte boundary are fetched correctly even when they
span two packed RAM words.

## Machine mode

The dense machine CSR bank implements:

- mstatus, misa, mie, mip
- mtvec, mscratch
- mepc, mcause, mtval
- mhartid
- cycle/instret and machine aliases

Implemented system behavior includes CSR read/modify/write instructions and immediate forms, ECALL,
configurable EBREAK halt/breakpoint behavior, MRET and WFI.

Machine software, timer and external interrupts can be injected. Interrupt selection uses
`mstatus.MIE`, `mie` and `mip`. Direct and vectored `mtvec` modes are implemented. WFI cores
can be woken without resetting architectural state.

The compiled tier executes these same A/CSR/system operations directly; they are no longer automatic
interpreter boundaries. CSR operations remain basic-block boundaries so interrupt priority is
re-evaluated immediately after control-state changes.

## Tier 0: architectural interpreter

`RiscV32Kernel.runQuantum` is the reference implementation and fallback.

It remains useful for:

- uncached code
- code invalidated by self-modification
- unsupported future ISA extensions
- differential verification of compiled execution

`JvmRiscV32Executor` runs it as ordinary Java.
`TornadoRiscV32Executor` submits the same kernel through TornadoVM.

## Tier 1: shared canonical decode

`RiscV32Machine.buildCodeCache(core, base, length)` builds one shared instruction image for all
virtual cores running the same program.

Each halfword-indexed slot stores:

- raw guest instruction bits
- canonical 32-bit RV32 instruction
- original guest length: two or four bytes

A cache hit eliminates per-core guest instruction fetch and RV32C expansion.

Self-modifying code is conservative and correct:

- host writes invalidate overlapping entries
- guest SB/SH/SW, successful SC and AMO stores invalidate overlapping entries
- writes touching the upper half of a 32-bit instruction also invalidate its preceding start slot
- invalidated entries fall back to architectural fetch/decode
- device invalidation state is synchronized back before a later plan can reuse stale code

## Tier 2: compiled basic blocks

`RiscV32Machine.buildBlockCache(maxBlockInstructions)` mechanically lowers canonical code into
primitive block metadata plus one contiguous `LongArray` micro-op stream.

A halfword-indexed entry map resolves guest PC directly to a block descriptor. A descriptor records:

- first micro-op offset
- packed micro-op count
- original guest-instruction count

No AST or block object is present in the accelerator representation.

Direct branch/jump targets and fall-through addresses are discovered before fusion. Fusion never
crosses a basic-block leader, so another edge can still enter every legal guest instruction boundary.

## Tier 3: 64-bit superinstructions

Each micro-op is one packed `long` containing operation kind, rd/rs1/rs2, total guest byte length,
first-instruction length, architectural retire count and a 32-bit payload/immediate.

Safe adjacent patterns currently fuse into one device operation:

- LUI + ADDI -> LOAD_CONST
- ADDI + ADDI on the same destination -> ADDI_CHAIN
- MUL + consuming/overwriting ADD -> MUL_ADD
- ADDI + BEQ/BNE/BLT/BGE/BLTU/BGEU -> counted-loop superinstructions

A fused operation still advances guest PC by the original byte count and retires the exact original
number of guest instructions. The packed representation therefore reduces dispatch without changing
architectural counters.

`RiscV32CompilationStats` exposes code bytes, cached instructions, compiled coverage, blocks,
micro-op count, fusion reduction and runtime compiled-block executions.

## Tier 4: chained block execution

`RiscV32ChainedBlockKernel` does not stop after one block. Each accelerator lane repeatedly resolves
and executes compiled blocks until:

- its guest-instruction quantum is consumed
- it leaves compiled code
- self-modifying code invalidates the compiled image
- a non-vectorable/missing path requires fallback
- the virtual CPU halts or waits

The remaining instruction budget is tracked per core. If fallback is needed, the interpreter consumes
**only that remainder**. A core never re-executes instructions already retired by the compiled tier.

Machine interrupts and synchronous vectored traps are entered directly in the compiled kernel. If
`mtvec` points to compiled code, the lane re-enters compiled execution without a host round trip.

`JvmTieredRiscV32Executor` is the reference tiered executor.
`TornadoTieredRiscV32Executor` runs the same block + fallback model through TornadoVM.

## Persistent accelerator sessions

For long-lived emulation, use `TornadoRiscV32Session` rather than repeatedly constructing one-shot
execution plans.

A session:

- precompiles the Tornado execution plan
- preserves compiled kernels and device allocations across calls
- can execute fixed quanta entirely device-resident
- can synchronize only status/trap state
- can refresh the small control state after interrupt injection or WFI wakeup
- can explicitly refresh all state after host-side mutations
- performs a full sync only when requested or at `executeUntilStop` completion

This removes execution-plan reconstruction from the steady-state hot path.

## Production facade

`RiscV32ExecutionEngine` automatically selects the tiered executor when a block cache is installed
and otherwise uses the interpreter.

Typical raw-image setup:

```java
RiscV32ExecutionEngine engine = RiscV32ExecutionEngine.tornado();
RiscV32CompilationStats stats =
        engine.prepareCode(machine, 0, codeBase, codeLength, 64);
RiscV32ExecutionResult result = engine.execute(machine, 512, 10000);
```

## ELF loading

`RiscV32ElfLoader` supports ELF32 little-endian RISC-V ET_EXEC/ET_DYN flat images:

- PT_LOAD loading
- physical-address preference with virtual-address fallback
- deterministic BSS zeroing
- per-core image installation
- RV32C-compatible two-byte entry alignment
- executable PT_LOAD range discovery

`RiscV32ElfLoader.loadAllPrepared(...)` performs:

```text
ELF load -> executable span discovery -> shared code cache -> block compilation
```

and returns `RiscV32ElfImage` metadata.

The loader deliberately does not pretend ET_DYN relocation, an MMU, or a Linux device platform exists.

## Measuring performance

`RiscV32ExecutionResult` reports elapsed nanoseconds, instructions/second and MIPS.

The reproducible harness compares interpreter and tiered semantics before printing throughput:

```bash
# JVM reference comparison
java -m synexia.cpu.model/com.synexia.tornadovm.cpu.RiscV32PerformanceDemo jvm 4096 1000 512

# TornadoVM accelerator comparison
tornado -m synexia.cpu.model/com.synexia.tornadovm.cpu.RiscV32PerformanceDemo tornado 4096 1000 512
```

Do not infer GPU speedup from hosted CI. Measure on the target GPU because useful crossover depends
on virtual-core count, control-flow coherence, kernel-launch cost, memory bandwidth and backend.

## Correctness gates

Tests cover:

- RV32I/M arithmetic, branches, loads/stores
- compressed fetch/decode including cross-word 32-bit fetch
- A-extension reservation and AMO behavior
- machine CSRs, trap entry, MRET and WFI
- direct/vectored interrupt delivery
- ELF loading/BSS/entry alignment
- shared-predecode invalidation
- compiled self-modifying-code invalidation
- block chaining and exact quantum budgets
- superinstruction fusion and exact retirement
- compiled A/CSR/system execution
- deterministic generated interpreter-vs-tier differential workloads

Build:

```bash
./mvnw -Pjdk22plus -pl synexia-cpu-model -am test -DskipTests=false
```

## Current platform boundary

The execution engine is RV32IMAC with M-mode control and interrupt behavior. A complete Linux-capable
RISC-V platform still requires additive layers around it:

- S/U privilege modes and delegation
- Sv32 translation, page tables and TLB behavior
- CLINT/ACLINT and PLIC/APLIC/IMSIC device models
- UART/virtio/MMIO devices
- coherent shared-memory multicore topology
- F/D floating-point register files/instructions
- V vector register files/instructions

Those are machine/platform extensions, not reasons to put object-heavy abstractions back into the
instruction hot path.

## Donor architecture

The design follows the established CPU-in-GPU-kernel idea demonstrated by projects such as RISKY-V
(RISC-V in GLSL) and Simulacore (x86 interpretation in CUDA), while the implementation here is an
original Java/TornadoVM design rather than copied source.
