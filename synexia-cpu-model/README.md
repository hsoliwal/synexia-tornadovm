# Synexia CPU-on-GPU model

This module implements a dense **RV32IMAC CPU interpreter whose exact Java kernel can run either
directly on the JVM or through TornadoVM on an accelerator**.

## Execution model

One accelerator work-item represents one independent virtual CPU. The hot state is primitive-only and
uses a lane-coalesced structure-of-arrays layout:

- registers: `registers[register * cores + core]`
- CSRs: `csrs[slot * cores + core]`
- RAM: `memory[wordAddress * cores + core]`
- PC, status, trap cause/value, reservation and retired-instruction counters in flat `IntArray` vectors
- byte-addressable RAM packed four bytes per `int`
- no maps, collections, reflection, allocation or object-per-instruction/core in the kernel

The transposed layout matters on a GPU. When adjacent work-items execute related code, instruction
fetches and accesses to the same architectural register become adjacent physical loads instead of
being separated by an entire per-core state block.

Each virtual core executes a configurable sequential instruction **quantum** inside its work-item.
Many virtual cores execute those quanta in parallel. This is the boundary between sequential ISA
semantics and SIMT hardware.

## Implemented ISA

### RV32I

- LUI, AUIPC
- JAL, JALR
- BEQ/BNE/BLT/BGE/BLTU/BGEU
- LB/LH/LW/LBU/LHU
- SB/SH/SW
- ADDI/SLTI/SLTIU/XORI/ORI/ANDI/SLLI/SRLI/SRAI
- ADD/SUB/SLL/SLT/SLTU/XOR/SRL/SRA/OR/AND
- FENCE/FENCE.I for the isolated-core memory model

### RV32M

- MUL/MULH/MULHSU/MULHU
- DIV/DIVU/REM/REMU

### RV32A

- LR.W / SC.W
- AMOSWAP.W / AMOADD.W
- AMOXOR.W / AMOAND.W / AMOOR.W
- AMOMIN.W / AMOMAX.W / AMOMINU.W / AMOMAXU.W

The current machine gives every virtual core private RAM, so the A-extension is fully meaningful for
reservation and read-modify-write semantics inside each virtual machine. Cross-core shared-memory
coherence is a separate machine-topology layer.

### RV32C

The common RV32 compressed integer instruction set is supported, including:

- C.ADDI4SPN, C.LW, C.SW
- C.NOP/C.ADDI, C.JAL, C.LI, C.LUI, C.ADDI16SP
- C.SRLI/C.SRAI/C.ANDI, C.SUB/C.XOR/C.OR/C.AND
- C.J, C.BEQZ, C.BNEZ
- C.SLLI, C.LWSP, C.SWSP
- C.JR/C.JALR, C.MV/C.ADD, C.EBREAK

Compressed instructions are expanded inside the kernel to equivalent 32-bit operations and then
flow through the same main decoder. Thirty-two-bit instructions starting at a 2-byte boundary are
handled correctly, including the case where the instruction straddles two packed memory words.

## Machine-mode support

The dense CSR bank supports:

- mstatus
- misa (RV32IMAC)
- mie / mip
- mtvec
- mscratch
- mepc
- mcause
- mtval
- mhartid
- cycle/instret and machine aliases

Implemented system behavior includes CSR read/modify/write instructions, ECALL, EBREAK, MRET and WFI.

By default traps stop the core, which is convenient for deterministic tests and bare-metal tooling.
Calling `machine.withTrapVectoring(true)` enables machine trap entry through `mtvec`, updates
`mepc/mcause/mtval/mstatus`, and permits handler return through MRET. EBREAK remains an environment
halt by default; `withEbreakHalt(false)` turns it into an architectural breakpoint trap instead.

WFI moves a virtual core to `WAITING`. The host can either wake such cores explicitly with
`resumeWaitingCores()` or inject machine-software, machine-timer, or machine-external interrupts.
Pending interrupts are arbitrated at instruction boundaries using `mstatus.MIE`, `mie`, and
`mip`. Direct and vectored `mtvec` modes are supported; vectored mode dispatches interrupts to
`BASE + 4 * cause`.

## Backends

`JvmRiscV32Executor` invokes `RiscV32Kernel.runQuantum` as ordinary Java and is the deterministic
reference implementation.

`TornadoRiscV32Executor` submits that **same method** as a TornadoVM task. Architectural state is
uploaded once and remains device-resident. Status/trap vectors are under-demand transfers rather
than unconditional copy-outs.

The Tornado executor polls status every eight quanta by default. If a core halts before the next
poll, subsequent dispatches simply see its device-resident non-running status and skip it. This
reduces synchronization/PCIe traffic without changing the maximum number of instruction quanta.
The polling interval is configurable in the executor constructor.

An explicit `TornadoDevice` may be supplied to `TornadoRiscV32Executor`; otherwise TornadoVM's
normal default-device selection applies.

## Binary images

`RiscV32ElfLoader` loads standard ELF32 little-endian RISC-V `ET_EXEC` / `ET_DYN` images.

- PT_LOAD segment loading
- physical-address preference with virtual-address fallback
- deterministic BSS zeroing
- per-core image installation
- reset to the ELF entry point

`RiscV32Machine.loadHalfwords*` additionally supports direct 16-bit compressed program images.

## Performance design

The hot loop intentionally uses a small set of predictable primitives:

1. lane-coalesced register/CSR/RAM arrays
2. packed little-endian word memory
3. no per-instruction object allocation
4. no hash tables, maps or sparse Java objects
5. local register values cached in scalar variables during decode
6. compressed code support to reduce fetch bandwidth
7. device-resident architectural state
8. batched host status polling
9. one shared semantic kernel for JVM reference and accelerator execution

The largest remaining GPU cost is ISA control-flow divergence: different virtual cores executing
different opcodes necessarily cause SIMT lanes to take different decoder paths. Workload grouping
and larger coherent batches are therefore more valuable than adding object-oriented abstraction to
the instruction loop.

## Build and test

From the repository root on the JDK 22+ profile:

```bash
./mvnw -Pjdk22plus -pl synexia-cpu-model -am test -DskipTests=false
```

Run the reference demo:

```bash
java -m synexia.cpu.model/com.synexia.tornadovm.cpu.RiscV32Demo
```

Run through TornadoVM:

```bash
tornado -m synexia.cpu.model/com.synexia.tornadovm.cpu.RiscV32Demo tornado
```

## Scope boundary

This is now an RV32IMAC execution engine with a useful subset of machine-mode control state, not a
claim that a complete Linux-capable RISC-V platform already exists.

The remaining additive system layers are:

- interrupt-controller/device models (CLINT/ACLINT, PLIC/APLIC/IMSIC) around the implemented
  machine software/timer/external interrupt injection
- complete privileged-mode state beyond M-mode, including S/U mode delegation
- Sv32 page-table/MMU translation and TLB behavior
- MMIO devices such as CLINT/PLIC/UART/virtio
- shared-memory/coherent multicore topology
- floating-point F/D and vector V register files/instructions

Those layers can be added without undoing the dense decoder or the backend-neutral execution
contract.

## Donor architecture

The design follows the CPU-in-GPU-kernel pattern demonstrated by projects such as RISKY-V
(RISC-V in GLSL) and Simulacore (x86 interpretation in CUDA), while this implementation remains an
original Java/TornadoVM implementation rather than a source copy.
