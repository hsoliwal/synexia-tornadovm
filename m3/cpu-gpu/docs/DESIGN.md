<!-- SPDX-License-Identifier: Apache-2.0 -->
# Design: deterministic sequential CPUs inside SIMT execution

## 1. Goal

Provide a literal CPU interpreter that can execute inside TornadoVM-generated CUDA/OpenCL/Metal kernels while keeping a mechanically comparable CPU implementation as the oracle. The unit of parallelism is a complete virtual CPU, not an individual guest instruction.

This choice turns the sequential/stateful guest problem into an embarrassingly parallel outer problem: each GPU lane owns one virtual core and advances it for a fixed instruction budget. There is no attempt to parallelize the instructions *within* one core.

## 2. Stable flat ABI

The device receives four `IntArray` values:

| Array | Layout |
| --- | --- |
| `memory` | `coreCount * memoryWordsPerCore`; little-endian packed bytes |
| `registers` | `coreCount * 32`; x0 is forced to zero |
| `state` | `coreCount * 6`; PC, status, retired, trap cause, trap value, exit code |
| `config` | core count, words/core, instruction budget |

All indices are arithmetic. There are no object graphs, maps, virtual dispatch tables, host pointers or permission-bearing objects in the kernel.

The state machine is:

```text
READY --budget exhausted--> YIELDED --next slice--> READY
READY --Xm3halt---------> HALTED
READY --architectural fault--> TRAPPED
HALTED/TRAPPED --next slice--> unchanged
```

A faulting instruction is not retired. A successful Xm3halt is retired and advances the PC by four before recording HALTED.

## 3. ISA surface

The executable contract is RV32I 2.1 plus M 2.0 and FENCE.I semantics appropriate to a single coherent backing memory. The engine implements all base integer arithmetic/control-flow/load/store forms and all eight M operations. The virtual machine is unprivileged and uses the standard synchronous exception cause numbers for the exceptions it can produce.

FENCE is a semantic no-op because no other core can observe a core's private RAM. FENCE.I is also a semantic no-op because instruction fetch and stores address the same coherent `IntArray` image. Both instructions are still decoded so ordinary compiler output can execute.

Xm3halt (`custom-0`, encoding `0x0000000B`) is the only private instruction. Its purpose is explicit semihosting termination; it is not presented as a standard RISC-V instruction.

## 4. Why one virtual core per GPU work-item

Mapping one guest instruction to one GPU lane would require global ordering and would make every data dependency a synchronization problem. Mapping one complete guest core to a lane preserves the guest's sequential semantics inside normal scalar locals (`pc`, decoded fields) and only persists architectural state at slice boundaries/instruction retirement.

Warp divergence still exists: neighboring virtual cores may execute different opcodes or branches. That cost is accepted because correctness and deterministic isolation come first. Throughput can later be improved by grouping batches by program counter/opcode or running homogeneous worker pools, without changing the architectural ABI.

## 5. Memory behavior

Each core owns `[core * wordsPerCore, (core + 1) * wordsPerCore)` in the global `IntArray`. Byte and halfword operations use little-endian mask/shift read-modify-write operations. Word accesses are direct. Natural alignment is enforced for halfwords and words; instruction fetch is 4-byte aligned because compressed instructions are not in this contract.

Private slices intentionally avoid atomics and data races. A future shared-memory/SMP layer must define memory ordering and LR/SC reservation semantics before adding the RISC-V A extension; it must not simply expose overlapping slices and call them atomic.

## 6. Bounded execution

A kernel launch has a positive instruction budget. A core that neither halts nor traps transitions to YIELDED after consuming the budget. The next launch resumes from persisted PC/register/state. This prevents a guest infinite loop from monopolizing a GPU kernel forever and creates a natural host scheduling/preemption point.

RAM is kept resident by `TornadoRv32iSession`; registers/state are downloaded after each slice for scheduling/inspection, while full RAM download is explicit. This avoids paying a large D2H transfer merely to check whether cores halted.

## 7. ELF boundary

The loader intentionally supports the smallest deterministic executable boundary: ELFCLASS32, little endian, EM_RISCV, ET_EXEC, PT_LOAD. It checks every file and guest-memory range using long arithmetic, maps p_paddr when non-zero (otherwise p_vaddr), zero-fills `p_memsz - p_filesz`, and validates the entry through the same PC setter used by raw programs.

Dynamic loaders, relocations, virtual memory and devices belong above this layer. They are not guessed.

## 8. Correctness proof gates

1. **Diff:** changes stay below `m3/cpu-gpu/`.
2. **Lint/compile:** Java 21 `-Xlint:all -Werror` for the CPU-only core; Maven compiler uses the same release/lint policy for the full module.
3. **Unit tests:** assembler immediate boundaries, RV32I arithmetic/control/memory, M corner cases, traps, slice resume, ELF mapping/BSS.
4. **CPU/GPU differential:** opt-in GPU test compares every register, state word and RAM word for a multi-core batch.
5. **Hardware evidence:** only an actual TornadoVM device run may claim CUDA/OpenCL/Metal parity or performance. A successful CPU compile is never reported as GPU execution.

## 9. Extension path

Additions should remain layered and independently testable:

```text
RV32IM user core (this module)
  -> optional A extension + explicitly shared memory model
  -> Zicsr + machine/user CSR bank
  -> traps/interrupt controller + CLINT/PLIC-style devices
  -> Sv32 MMU / address translation
  -> SBI/semihosting devices
  -> Linux-capable machine profile
```

x86 should be a separate decoder/state package behind the same batch/session idea rather than growing x86 variable-length decode and segmentation semantics into this RISC-V core.
