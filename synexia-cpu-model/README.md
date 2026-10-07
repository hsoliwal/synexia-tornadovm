# Synexia RV32IMAC tiered CPU-on-GPU engine

This module implements a dense **RV32IMAC execution engine for JVM and TornadoVM accelerators**.

The architectural interpreter remains the correctness oracle and fallback tier. Normal prepared
execution uses shared predecode, compiled basic blocks, packed micro-ops, superinstructions and
multi-block chaining so hot guest code does **not** repeatedly pay instruction fetch/decode cost.

## Production entry point

Use `RiscV32ExecutionEngine` rather than choosing executor classes manually:

```java
RiscV32Machine machine = new RiscV32Machine(cores, memoryBytes);
machine.loadProgramAll(0, program);

RiscV32ExecutionEngine engine = RiscV32ExecutionEngine.jvm();
// or: RiscV32ExecutionEngine.tornado()
// or: RiscV32ExecutionEngine.tornado(device)

RiscV32CompilationStats stats =
        engine.prepareCode(machine, 0, 0, program.length * Integer.BYTES);

RiscV32ExecutionResult result =
        engine.execute(machine, 4096, 1024);
```

If a compiled block cache is installed the engine automatically selects the tiered executor.
Unprepared or invalidated code retains the full interpreter path.

For ELF images:

```java
RiscV32ElfImage image = engine.loadElf(machine, elfBytes);
RiscV32ExecutionResult result = engine.execute(machine, 4096, 1024);
```

Executable `PT_LOAD` ranges are discovered and prepared automatically.

## Execution pipeline

```text
RV32 ELF / raw image
        |
        v
canonical shared predecode
  - RV32C expanded once
  - raw instruction retained
        |
        v
basic-block compiler
  - leader discovery
  - direct-control-flow partitioning
        |
        v
packed 64-bit micro-op stream
  - no per-op Java objects
        |
        v
safe superinstruction fusion
        |
        v
chained compiled-block kernel
  - many blocks per lane/quantum
  - device-resident state
        |
        +---- miss / invalidation / unsupported boundary ----+
        |                                                    |
        v                                                    v
compiled execution                                  architectural interpreter
        |                                                    |
        +---------------- exact shared machine state --------+
```

The interpreter is therefore the **semantic floor**, not the intended steady-state hot path.

## GPU-oriented state layout

One accelerator work-item represents one independent virtual CPU. Hot architectural state uses
primitive TornadoVM arrays in lane-coalesced structure-of-arrays form:

- `registers[register * cores + core]`
- `csrs[slot * cores + core]`
- `memory[wordAddress * cores + core]`
- PC/status/trap/reservation/counters as flat vectors
- packed byte-addressable RAM: four guest bytes per Java/Tornado `int`

Adjacent lanes executing coherent code therefore access adjacent device locations for the same
architectural register or guest memory word.

There are no maps, collections, reflection objects or per-instruction allocations in accelerator
kernels.

## Shared predecode

`RiscV32Machine.buildCodeCache(core, base, length)` builds one canonical instruction image shared
by all virtual cores.

Each halfword slot can contain:

- raw guest instruction bits;
- canonical RV32 instruction;
- architectural instruction length.

A cache hit skips guest RAM instruction fetch and RV32C expansion.

Correctness under self-modifying code is preserved:

- host writes invalidate overlapping cached entries;
- guest SB/SH/SW and successful SC/AMO writes invalidate cached code;
- a write to the upper half of a 32-bit instruction also invalidates its preceding start slot;
- compiled blocks are invalidated with the canonical cache;
- invalid entries immediately fall back to architectural fetch/decode.

## Compiled basic blocks

`RiscV32Machine.buildBlockCache(maxBlockInstructions)` mechanically compiles canonical code into
primitive arrays:

- halfword-indexed block-entry table;
- packed 64-bit block descriptors;
- packed 64-bit micro-op stream;
- primitive validity state.

Direct branch/jump targets and fall-through boundaries are identified before fusion, so an externally
reachable guest instruction is never silently hidden inside a superinstruction.

The chained block kernel executes multiple blocks inside one instruction quantum. Tight loops can
therefore remain entirely in the compiled tier across many iterations without one GPU launch per
basic block.

When compiled execution encounters a tier boundary, the exact **remaining per-core instruction
budget** is passed to the interpreter. A lane never re-executes instructions already retired by the
compiled tier.

## Superinstructions

Safe adjacent guest patterns are fused while preserving original guest retirement counts and PC
semantics. Implemented fusions include:

- `LUI + ADDI -> LOAD_CONST`
- compatible `ADDI + ADDI -> ADDI_CHAIN`
- `MUL + ADD -> MUL_ADD`
- counted-loop `ADDI + BEQ/BNE/BLT/BGE/BLTU/BGEU`

Each packed micro-op records:

- operation kind;
- rd/rs1/rs2;
- immediate/payload;
- first guest instruction length;
- total guest byte length;
- number of architectural guest instructions represented.

This allows fused control-flow operations to reconstruct the branch PC and instruction-retirement
count exactly.

## ISA coverage

### RV32I

LUI, AUIPC, JAL/JALR, all six integer branches, LB/LH/LW/LBU/LHU, SB/SH/SW,
ADDI/SLTI/SLTIU/XORI/ORI/ANDI/SLLI/SRLI/SRAI,
ADD/SUB/SLL/SLT/SLTU/XOR/SRL/SRA/OR/AND and FENCE/FENCE.I semantics for the current topology.

### RV32M

MUL/MULH/MULHSU/MULHU and DIV/DIVU/REM/REMU, including divide-by-zero and signed-overflow rules.

### RV32A

LR.W, SC.W, AMOSWAP.W, AMOADD.W, AMOXOR.W, AMOAND.W, AMOOR.W,
AMOMIN.W, AMOMAX.W, AMOMINU.W and AMOMAXU.W.

Reservation invalidation and LR load-class faults are preserved.

### RV32C

Common RV32 compressed integer instructions are supported, including C.ADDI4SPN, C.LW/C.SW,
C.NOP/C.ADDI, C.JAL, C.LI/C.LUI/C.ADDI16SP, compact ALU operations, C.J,
C.BEQZ/C.BNEZ, C.SLLI, C.LWSP/C.SWSP, C.JR/C.JALR, C.MV/C.ADD and C.EBREAK.

32-bit instructions beginning on a 2-byte boundary and crossing packed RAM words are supported.

## Machine mode

Primitive CSR state includes:

- mstatus / misa / mie / mip
- mtvec / mscratch
- mepc / mcause / mtval
- mhartid
- cycle/instret aliases

Implemented system behavior includes CSR read/modify/write instructions, ECALL, EBREAK, MRET and
WFI.

Machine software, timer and external interrupts can be injected. Pending interrupts are arbitrated
at instruction/block boundaries from `mstatus.MIE`, `mie` and `mip`. Direct and vectored
`mtvec` modes are supported.

The compiled tier includes system/CSR/atomic semantics where supported by the packed execution model;
the architectural interpreter remains available as the oracle/fallback for cold or invalidated code.

## ELF loading

`RiscV32ElfLoader` supports ELF32 little-endian RISC-V `ET_EXEC` / `ET_DYN` flat images:

- `PT_LOAD` segment loading;
- physical-address preference with virtual-address fallback;
- deterministic BSS zeroing;
- executable `PF_X` range discovery;
- per-core image installation;
- 2-byte RV32C-compatible entry alignment;
- reset to ELF entry;
- prepared loading through `loadAllPrepared`.

This is a flat physical-memory environment. It does not claim Sv32 relocation/MMU behavior that is
not implemented.

## Observability

`RiscV32CompilationStats` reports:

- executable code bytes;
- cached instruction count;
- compiled block count;
- compiled guest-instruction count;
- micro-op count;
- fused-instruction count;
- compiled coverage;
- fusion reduction;
- compiled-block execution count.

`RiscV32ExecutionResult` reports backend, quanta, stopped/running state, retired instructions,
elapsed time, instructions/second and MIPS.

## Reproducible performance benchmark

The module includes a dependency-free benchmark that compares the interpreter against the tiered
engine and checks architectural equivalence before printing speed:

```bash
# JVM, defaults: 64 virtual cores, 50,000 loop iterations/core, 3 rounds
java -m synexia.cpu.model/com.synexia.tornadovm.cpu.RiscV32TierBenchmark

# JVM with explicit size
java -m synexia.cpu.model/com.synexia.tornadovm.cpu.RiscV32TierBenchmark 256 100000 5

# TornadoVM accelerator
tornado -m synexia.cpu.model/com.synexia.tornadovm.cpu.RiscV32TierBenchmark tornado 256 100000 5
```

CI deliberately does **not** assert a performance ratio: hosted runner scheduling and GPU
availability are unsuitable for stable performance thresholds. It does assert semantic parity.

## Verification

The test suite includes:

- RV32I/M/A/C instruction behavior;
- alignment/access/illegal-instruction traps;
- multicore state isolation;
- ELF loading and executable-range preparation;
- compressed instruction fetch across packed-word boundaries;
- CSR/trap/MRET/WFI/interrupt flows;
- LR/SC and AMOs;
- shared predecode invalidation;
- overlapping self-modifying-code invalidation;
- compiled basic-block chaining;
- superinstruction packing/fusion;
- exact guest retirement through fused operations;
- compiled atomics/CSRs/system operations;
- deterministic generated-workload differential tests using the interpreter as executable oracle;
- production execution-facade selection.

Build:

```bash
./mvnw -Pjdk22plus -pl synexia-cpu-model -am test -DskipTests=false
```

## Remaining platform layers

The execution tier is intentionally separate from full machine/platform emulation. A Linux-capable
system still needs additive layers such as:

- S/U privilege modes and delegation;
- Sv32 page-table translation/TLB behavior;
- CLINT/ACLINT and PLIC/APLIC/IMSIC device models;
- UART/virtio/MMIO devices;
- coherent shared-memory multicore topology;
- F/D floating-point and V vector state/instructions.

Those layers can be added around the current dense execution engine without putting Java object
graphs back into the hot path.

## Design principle

The target is not “a GPU that slowly interprets a CPU forever.”

The target is:

```text
guest ISA -> canonical decode -> packed blocks -> superinstructions
          -> chained accelerator execution
          -> interpreter only for correctness boundaries
```

The CPU ISA is treated as an **input language** to a dense accelerator execution engine.
