/*
 * Copyright 2026 Synexia contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.synexia.tornadovm.cpu;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.Int8Array;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;
import uk.ac.manchester.tornado.api.types.arrays.LongArray;

/**
 * Chained compiled-basic-block execution tier.
 *
 * <p>Each lane repeatedly enters compiled blocks until its instruction quantum is consumed, a
 * block miss occurs, a trap boundary is reached, or self-modifying code invalidates the compiled
 * image. The exact unused instruction budget is written to {@code fallbackBudget}; the
 * architectural interpreter consumes only that remainder.
 */
public final class RiscV32ChainedBlockKernel {

    private RiscV32ChainedBlockKernel() {
    }

    public static void runBlocks(IntArray registers, IntArray pc, IntArray status, IntArray trapCause,
            IntArray trapValue, IntArray retiredInstructions, IntArray csrs, IntArray reservations,
            IntArray memory, Int8Array decodedInstructionLengths, IntArray blockBySlot,
            LongArray blockDescriptors, Int8Array blockValid, LongArray microOps,
            IntArray fallbackBudget, IntArray compiledBlockExecutions, int codeCacheBase,
            int wordsPerCore, int executionFlags, int instructionBudget) {

        final int coreCount = pc.getSize();
        final int codeCacheEnd = codeCacheBase + (blockBySlot.getSize() << 1);

        for (@Parallel int core = 0; core < coreCount; core++) {
            fallbackBudget.set(core, 0);

            int localStatus = status.get(core);
            if (localStatus != RiscV32.STATUS_RUNNING) {
                continue;
            }

            int memoryBytes = wordsPerCore << 2;
            int localPc = pc.get(core);
            int localTrap = trapCause.get(core);
            int localTrapValue = trapValue.get(core);
            int counter = retiredInstructions.get(core);
            int remaining = instructionBudget;
            int compiledBlocks = compiledBlockExecutions.get(core);

            while (remaining > 0 && localStatus == RiscV32.STATUS_RUNNING) {
                // Preserve architectural interrupt priority at every compiled-block boundary.
                if ((executionFlags & RiscV32.FLAG_VECTOR_TRAPS) != 0) {
                    int mstatus = csrs.get(csrIndex(coreCount, core, RiscV32.CSR_SLOT_MSTATUS));
                    if ((mstatus & RiscV32.MSTATUS_MIE) != 0) {
                        int enabledPending = csrs.get(csrIndex(coreCount, core, RiscV32.CSR_SLOT_MIE))
                                & csrs.get(csrIndex(coreCount, core, RiscV32.CSR_SLOT_MIP));
                        if (enabledPending != 0) {
                            fallbackBudget.set(core, remaining);
                            break;
                        }
                    }
                }

                if ((localPc & 1) != 0 || localPc < codeCacheBase || localPc >= codeCacheEnd) {
                    fallbackBudget.set(core, remaining);
                    break;
                }

                int slot = (localPc - codeCacheBase) >>> 1;
                if (slot < 0 || slot >= blockBySlot.getSize()) {
                    fallbackBudget.set(core, remaining);
                    break;
                }

                int block = blockBySlot.get(slot) - 1;
                if (block < 0 || block >= blockValid.getSize() || blockValid.get(block) == 0) {
                    fallbackBudget.set(core, remaining);
                    break;
                }

                long descriptor = blockDescriptors.get(block);
                int firstOperation = RiscV32BlockProgram.firstOperation(descriptor);
                int operationCount = RiscV32BlockProgram.operationCount(descriptor);
                int guestInstructionCount = RiscV32BlockProgram.guestInstructionCount(descriptor);
                if (operationCount <= 0 || guestInstructionCount <= 0
                        || guestInstructionCount > remaining) {
                    fallbackBudget.set(core, remaining);
                    break;
                }

                boolean leaveCompiledTier = false;
                boolean executedAny = false;

                for (int index = 0; index < operationCount
                        && !leaveCompiledTier
                        && localStatus == RiscV32.STATUS_RUNNING; index++) {
                    long op = microOps.get(firstOperation + index);
                    int kind = RiscV32MicroOp.kind(op);
                    int rd = RiscV32MicroOp.rd(op);
                    int rs1 = RiscV32MicroOp.rs1(op);
                    int rs2 = RiscV32MicroOp.rs2(op);
                    int immediate = RiscV32MicroOp.immediate(op);
                    int instructionBytes = RiscV32MicroOp.instructionBytes(op);
                    int retiredCount = RiscV32MicroOp.retiredInstructions(op);
                    int nextPc = localPc + instructionBytes;
                    int pendingTrap = -1;
                    int pendingTrapValue = 0;
                    int retiredBeforeTrap = 0;

                    switch (kind) {
                        case RiscV32MicroOp.LUI:
                            writeRegister(registers, coreCount, core, rd, immediate);
                            break;

                        case RiscV32MicroOp.AUIPC:
                            writeRegister(registers, coreCount, core, rd, localPc + immediate);
                            break;

                        case RiscV32MicroOp.JAL: {
                            int target = localPc + immediate;
                            if ((target & 1) != 0) {
                                pendingTrap = RiscV32.TRAP_INSTRUCTION_ADDRESS_MISALIGNED;
                                pendingTrapValue = target;
                            } else {
                                writeRegister(registers, coreCount, core, rd, localPc + instructionBytes);
                                nextPc = target;
                            }
                            break;
                        }

                        case RiscV32MicroOp.JALR: {
                            int source1 = readRegister(registers, coreCount, core, rs1);
                            int target = (source1 + immediate) & ~1;
                            writeRegister(registers, coreCount, core, rd, localPc + instructionBytes);
                            nextPc = target;
                            break;
                        }

                        case RiscV32MicroOp.BEQ:
                        case RiscV32MicroOp.BNE:
                        case RiscV32MicroOp.BLT:
                        case RiscV32MicroOp.BGE:
                        case RiscV32MicroOp.BLTU:
                        case RiscV32MicroOp.BGEU: {
                            int source1 = readRegister(registers, coreCount, core, rs1);
                            int source2 = readRegister(registers, coreCount, core, rs2);
                            boolean taken;
                            if (kind == RiscV32MicroOp.BEQ) {
                                taken = source1 == source2;
                            } else if (kind == RiscV32MicroOp.BNE) {
                                taken = source1 != source2;
                            } else if (kind == RiscV32MicroOp.BLT) {
                                taken = source1 < source2;
                            } else if (kind == RiscV32MicroOp.BGE) {
                                taken = source1 >= source2;
                            } else if (kind == RiscV32MicroOp.BLTU) {
                                taken = lessThanUnsigned(source1, source2);
                            } else {
                                taken = !lessThanUnsigned(source1, source2);
                            }

                            if (taken) {
                                int target = localPc + immediate;
                                if ((target & 1) != 0) {
                                    pendingTrap = RiscV32.TRAP_INSTRUCTION_ADDRESS_MISALIGNED;
                                    pendingTrapValue = target;
                                } else {
                                    nextPc = target;
                                }
                            }
                            break;
                        }

                        case RiscV32MicroOp.LB:
                        case RiscV32MicroOp.LH:
                        case RiscV32MicroOp.LW:
                        case RiscV32MicroOp.LBU:
                        case RiscV32MicroOp.LHU: {
                            int source1 = readRegister(registers, coreCount, core, rs1);
                            int address = source1 + immediate;
                            int width = (kind == RiscV32MicroOp.LB || kind == RiscV32MicroOp.LBU) ? 1
                                    : (kind == RiscV32MicroOp.LH || kind == RiscV32MicroOp.LHU) ? 2 : 4;

                            if ((width == 2 && (address & 1) != 0)
                                    || (width == 4 && (address & 3) != 0)) {
                                pendingTrap = RiscV32.TRAP_LOAD_ADDRESS_MISALIGNED;
                                pendingTrapValue = address;
                            } else if (!validAddress(address, width, memoryBytes)) {
                                pendingTrap = RiscV32.TRAP_LOAD_ACCESS_FAULT;
                                pendingTrapValue = address;
                            } else {
                                int value;
                                if (kind == RiscV32MicroOp.LB) {
                                    value = (byte) load8(memory, coreCount, core, address);
                                } else if (kind == RiscV32MicroOp.LH) {
                                    value = (short) load16(memory, coreCount, core, address);
                                } else if (kind == RiscV32MicroOp.LW) {
                                    value = load32(memory, coreCount, core, address);
                                } else if (kind == RiscV32MicroOp.LBU) {
                                    value = load8(memory, coreCount, core, address);
                                } else {
                                    value = load16(memory, coreCount, core, address);
                                }
                                writeRegister(registers, coreCount, core, rd, value);
                            }
                            break;
                        }

                        case RiscV32MicroOp.SB:
                        case RiscV32MicroOp.SH:
                        case RiscV32MicroOp.SW: {
                            int source1 = readRegister(registers, coreCount, core, rs1);
                            int source2 = readRegister(registers, coreCount, core, rs2);
                            int address = source1 + immediate;
                            int width = kind == RiscV32MicroOp.SB ? 1
                                    : kind == RiscV32MicroOp.SH ? 2 : 4;

                            if ((width == 2 && (address & 1) != 0)
                                    || (width == 4 && (address & 3) != 0)) {
                                pendingTrap = RiscV32.TRAP_STORE_ADDRESS_MISALIGNED;
                                pendingTrapValue = address;
                            } else if (!validAddress(address, width, memoryBytes)) {
                                pendingTrap = RiscV32.TRAP_STORE_ACCESS_FAULT;
                                pendingTrapValue = address;
                            } else {
                                if (width == 1) {
                                    store8(memory, coreCount, core, address, source2);
                                } else if (width == 2) {
                                    store16(memory, coreCount, core, address, source2);
                                } else {
                                    store32(memory, coreCount, core, address, source2);
                                }
                                reservations.set(core, -1);
                                if (invalidateCompiledCode(decodedInstructionLengths, blockValid,
                                        codeCacheBase, codeCacheEnd, address, width)) {
                                    leaveCompiledTier = true;
                                }
                            }
                            break;
                        }

                        case RiscV32MicroOp.FENCE:
                            // Isolated per-core RAM has no weaker architectural ordering to expose.
                            break;

                        case RiscV32MicroOp.LR_W:
                        case RiscV32MicroOp.SC_W:
                        case RiscV32MicroOp.AMOSWAP_W:
                        case RiscV32MicroOp.AMOADD_W:
                        case RiscV32MicroOp.AMOXOR_W:
                        case RiscV32MicroOp.AMOAND_W:
                        case RiscV32MicroOp.AMOOR_W:
                        case RiscV32MicroOp.AMOMIN_W:
                        case RiscV32MicroOp.AMOMAX_W:
                        case RiscV32MicroOp.AMOMINU_W:
                        case RiscV32MicroOp.AMOMAXU_W: {
                            int address = readRegister(registers, coreCount, core, rs1);
                            int source2 = readRegister(registers, coreCount, core, rs2);
                            boolean loadReserved = kind == RiscV32MicroOp.LR_W;

                            if ((address & 3) != 0) {
                                pendingTrap = loadReserved
                                        ? RiscV32.TRAP_LOAD_ADDRESS_MISALIGNED
                                        : RiscV32.TRAP_STORE_ADDRESS_MISALIGNED;
                                pendingTrapValue = address;
                            } else if (!validAddress(address, 4, memoryBytes)) {
                                pendingTrap = loadReserved
                                        ? RiscV32.TRAP_LOAD_ACCESS_FAULT
                                        : RiscV32.TRAP_STORE_ACCESS_FAULT;
                                pendingTrapValue = address;
                            } else {
                                int oldValue = load32(memory, coreCount, core, address);
                                if (kind == RiscV32MicroOp.LR_W) {
                                    reservations.set(core, address);
                                    writeRegister(registers, coreCount, core, rd, oldValue);
                                } else if (kind == RiscV32MicroOp.SC_W) {
                                    if (reservations.get(core) == address) {
                                        store32(memory, coreCount, core, address, source2);
                                        writeRegister(registers, coreCount, core, rd, 0);
                                        if (invalidateCompiledCode(decodedInstructionLengths, blockValid,
                                                codeCacheBase, codeCacheEnd, address, 4)) {
                                            leaveCompiledTier = true;
                                        }
                                    } else {
                                        writeRegister(registers, coreCount, core, rd, 1);
                                    }
                                    reservations.set(core, -1);
                                } else {
                                    int newValue;
                                    if (kind == RiscV32MicroOp.AMOSWAP_W) {
                                        newValue = source2;
                                    } else if (kind == RiscV32MicroOp.AMOADD_W) {
                                        newValue = oldValue + source2;
                                    } else if (kind == RiscV32MicroOp.AMOXOR_W) {
                                        newValue = oldValue ^ source2;
                                    } else if (kind == RiscV32MicroOp.AMOAND_W) {
                                        newValue = oldValue & source2;
                                    } else if (kind == RiscV32MicroOp.AMOOR_W) {
                                        newValue = oldValue | source2;
                                    } else if (kind == RiscV32MicroOp.AMOMIN_W) {
                                        newValue = oldValue < source2 ? oldValue : source2;
                                    } else if (kind == RiscV32MicroOp.AMOMAX_W) {
                                        newValue = oldValue > source2 ? oldValue : source2;
                                    } else if (kind == RiscV32MicroOp.AMOMINU_W) {
                                        newValue = lessThanUnsigned(oldValue, source2) ? oldValue : source2;
                                    } else {
                                        newValue = lessThanUnsigned(oldValue, source2) ? source2 : oldValue;
                                    }
                                    store32(memory, coreCount, core, address, newValue);
                                    writeRegister(registers, coreCount, core, rd, oldValue);
                                    reservations.set(core, -1);
                                    if (invalidateCompiledCode(decodedInstructionLengths, blockValid,
                                            codeCacheBase, codeCacheEnd, address, 4)) {
                                        leaveCompiledTier = true;
                                    }
                                }
                            }
                            break;
                        }

                        case RiscV32MicroOp.ECALL:
                            pendingTrap = RiscV32.TRAP_ECALL_M_MODE;
                            break;

                        case RiscV32MicroOp.EBREAK:
                            if ((executionFlags & RiscV32.FLAG_EBREAK_HALT) != 0) {
                                localStatus = RiscV32.STATUS_HALTED;
                                localTrap = RiscV32.TRAP_BREAKPOINT;
                                localTrapValue = localPc;
                            } else {
                                pendingTrap = RiscV32.TRAP_BREAKPOINT;
                                pendingTrapValue = localPc;
                            }
                            break;

                        case RiscV32MicroOp.MRET: {
                            int mstatusIndex = csrIndex(coreCount, core, RiscV32.CSR_SLOT_MSTATUS);
                            int mstatus = csrs.get(mstatusIndex);
                            boolean mpie = (mstatus & RiscV32.MSTATUS_MPIE) != 0;
                            mstatus &= ~(RiscV32.MSTATUS_MIE
                                    | RiscV32.MSTATUS_MPIE
                                    | RiscV32.MSTATUS_MPP_MASK);
                            if (mpie) {
                                mstatus |= RiscV32.MSTATUS_MIE;
                            }
                            mstatus |= RiscV32.MSTATUS_MPIE;
                            csrs.set(mstatusIndex, mstatus);

                            int target = csrs.get(csrIndex(coreCount, core, RiscV32.CSR_SLOT_MEPC));
                            if ((target & 1) != 0) {
                                pendingTrap = RiscV32.TRAP_INSTRUCTION_ADDRESS_MISALIGNED;
                                pendingTrapValue = target;
                            } else {
                                nextPc = target;
                            }
                            break;
                        }

                        case RiscV32MicroOp.WFI:
                            localStatus = RiscV32.STATUS_WAITING;
                            break;

                        case RiscV32MicroOp.CSRRW:
                        case RiscV32MicroOp.CSRRS:
                        case RiscV32MicroOp.CSRRC:
                        case RiscV32MicroOp.CSRRWI:
                        case RiscV32MicroOp.CSRRSI:
                        case RiscV32MicroOp.CSRRCI: {
                            int csrAddress = immediate;
                            if (!csrSupported(csrAddress)) {
                                pendingTrap = RiscV32.TRAP_ILLEGAL_INSTRUCTION;
                                pendingTrapValue = csrAddress << 20;
                                break;
                            }

                            int oldValue = readCsr(csrs, coreCount, core, csrAddress, counter);
                            boolean immediateForm = kind >= RiscV32MicroOp.CSRRWI;
                            int source = immediateForm
                                    ? rs1
                                    : readRegister(registers, coreCount, core, rs1);
                            int newValue = oldValue;
                            boolean write = false;

                            if (kind == RiscV32MicroOp.CSRRW || kind == RiscV32MicroOp.CSRRWI) {
                                newValue = source;
                                write = true;
                            } else if (kind == RiscV32MicroOp.CSRRS || kind == RiscV32MicroOp.CSRRSI) {
                                if (source != 0) {
                                    newValue = oldValue | source;
                                    write = true;
                                }
                            } else if (source != 0) {
                                newValue = oldValue & ~source;
                                write = true;
                            }

                            if (write) {
                                if (csrAddress == RiscV32.CSR_MCYCLE
                                        || csrAddress == RiscV32.CSR_MINSTRET) {
                                    counter = newValue;
                                } else if (!writeCsr(csrs, coreCount, core, csrAddress, newValue)) {
                                    pendingTrap = RiscV32.TRAP_ILLEGAL_INSTRUCTION;
                                    pendingTrapValue = csrAddress << 20;
                                    break;
                                }
                            }

                            writeRegister(registers, coreCount, core, rd, oldValue);
                            break;
                        }

                        case RiscV32MicroOp.LOAD_CONST:
                            writeRegister(registers, coreCount, core, rd, immediate);
                            break;

                        case RiscV32MicroOp.ADDI_CHAIN: {
                            int source1 = readRegister(registers, coreCount, core, rs1);
                            writeRegister(registers, coreCount, core, rd, source1 + immediate);
                            break;
                        }

                        case RiscV32MicroOp.MUL_ADD: {
                            int source1 = readRegister(registers, coreCount, core, rs1);
                            int source2 = readRegister(registers, coreCount, core, rs2);
                            int product = source1 * source2;
                            int otherRegister = immediate & 0x1f;
                            int addend = otherRegister == rd
                                    ? product
                                    : readRegister(registers, coreCount, core, otherRegister);
                            writeRegister(registers, coreCount, core, rd, product + addend);
                            break;
                        }

                        case RiscV32MicroOp.ADDI_BEQ:
                        case RiscV32MicroOp.ADDI_BNE:
                        case RiscV32MicroOp.ADDI_BLT:
                        case RiscV32MicroOp.ADDI_BGE:
                        case RiscV32MicroOp.ADDI_BLTU:
                        case RiscV32MicroOp.ADDI_BGEU: {
                            int source = readRegister(registers, coreCount, core, rs1);
                            int updated = source + RiscV32MicroOp.lowSigned16(immediate);
                            int other = readRegister(registers, coreCount, core, rs2);
                            writeRegister(registers, coreCount, core, rd, updated);

                            boolean taken;
                            if (kind == RiscV32MicroOp.ADDI_BEQ) {
                                taken = updated == other;
                            } else if (kind == RiscV32MicroOp.ADDI_BNE) {
                                taken = updated != other;
                            } else if (kind == RiscV32MicroOp.ADDI_BLT) {
                                taken = updated < other;
                            } else if (kind == RiscV32MicroOp.ADDI_BGE) {
                                taken = updated >= other;
                            } else if (kind == RiscV32MicroOp.ADDI_BLTU) {
                                taken = lessThanUnsigned(updated, other);
                            } else {
                                taken = !lessThanUnsigned(updated, other);
                            }

                            if (taken) {
                                int branchPc = localPc + RiscV32MicroOp.firstInstructionBytes(op);
                                int target = branchPc + RiscV32MicroOp.highSigned16(immediate);
                                if ((target & 1) != 0) {
                                    pendingTrap = RiscV32.TRAP_INSTRUCTION_ADDRESS_MISALIGNED;
                                    pendingTrapValue = target;
                                    // ADDI precedes the faulting branch and has architecturally retired.
                                    retiredBeforeTrap = 1;
                                } else {
                                    nextPc = target;
                                }
                            }
                            break;
                        }

                        case RiscV32MicroOp.ADDI:
                        case RiscV32MicroOp.SLTI:
                        case RiscV32MicroOp.SLTIU:
                        case RiscV32MicroOp.XORI:
                        case RiscV32MicroOp.ORI:
                        case RiscV32MicroOp.ANDI:
                        case RiscV32MicroOp.SLLI:
                        case RiscV32MicroOp.SRLI:
                        case RiscV32MicroOp.SRAI: {
                            int source1 = readRegister(registers, coreCount, core, rs1);
                            int result;
                            if (kind == RiscV32MicroOp.ADDI) {
                                result = source1 + immediate;
                            } else if (kind == RiscV32MicroOp.SLTI) {
                                result = source1 < immediate ? 1 : 0;
                            } else if (kind == RiscV32MicroOp.SLTIU) {
                                result = lessThanUnsigned(source1, immediate) ? 1 : 0;
                            } else if (kind == RiscV32MicroOp.XORI) {
                                result = source1 ^ immediate;
                            } else if (kind == RiscV32MicroOp.ORI) {
                                result = source1 | immediate;
                            } else if (kind == RiscV32MicroOp.ANDI) {
                                result = source1 & immediate;
                            } else if (kind == RiscV32MicroOp.SLLI) {
                                result = source1 << immediate;
                            } else if (kind == RiscV32MicroOp.SRLI) {
                                result = source1 >>> immediate;
                            } else {
                                result = source1 >> immediate;
                            }
                            writeRegister(registers, coreCount, core, rd, result);
                            break;
                        }

                        case RiscV32MicroOp.ADD:
                        case RiscV32MicroOp.SUB:
                        case RiscV32MicroOp.SLL:
                        case RiscV32MicroOp.SLT:
                        case RiscV32MicroOp.SLTU:
                        case RiscV32MicroOp.XOR:
                        case RiscV32MicroOp.SRL:
                        case RiscV32MicroOp.SRA:
                        case RiscV32MicroOp.OR:
                        case RiscV32MicroOp.AND:
                        case RiscV32MicroOp.MUL:
                        case RiscV32MicroOp.MULH:
                        case RiscV32MicroOp.MULHSU:
                        case RiscV32MicroOp.MULHU:
                        case RiscV32MicroOp.DIV:
                        case RiscV32MicroOp.DIVU:
                        case RiscV32MicroOp.REM:
                        case RiscV32MicroOp.REMU: {
                            int source1 = readRegister(registers, coreCount, core, rs1);
                            int source2 = readRegister(registers, coreCount, core, rs2);
                            int result;
                            if (kind == RiscV32MicroOp.ADD) {
                                result = source1 + source2;
                            } else if (kind == RiscV32MicroOp.SUB) {
                                result = source1 - source2;
                            } else if (kind == RiscV32MicroOp.SLL) {
                                result = source1 << (source2 & 0x1f);
                            } else if (kind == RiscV32MicroOp.SLT) {
                                result = source1 < source2 ? 1 : 0;
                            } else if (kind == RiscV32MicroOp.SLTU) {
                                result = lessThanUnsigned(source1, source2) ? 1 : 0;
                            } else if (kind == RiscV32MicroOp.XOR) {
                                result = source1 ^ source2;
                            } else if (kind == RiscV32MicroOp.SRL) {
                                result = source1 >>> (source2 & 0x1f);
                            } else if (kind == RiscV32MicroOp.SRA) {
                                result = source1 >> (source2 & 0x1f);
                            } else if (kind == RiscV32MicroOp.OR) {
                                result = source1 | source2;
                            } else if (kind == RiscV32MicroOp.AND) {
                                result = source1 & source2;
                            } else if (kind == RiscV32MicroOp.MUL) {
                                result = source1 * source2;
                            } else if (kind == RiscV32MicroOp.MULH) {
                                result = (int) (((long) source1 * (long) source2) >> 32);
                            } else if (kind == RiscV32MicroOp.MULHSU) {
                                result = (int) (((long) source1 * (source2 & 0xffffffffL)) >> 32);
                            } else if (kind == RiscV32MicroOp.MULHU) {
                                result = (int) (((source1 & 0xffffffffL) * (source2 & 0xffffffffL)) >>> 32);
                            } else if (kind == RiscV32MicroOp.DIV) {
                                if (source2 == 0) {
                                    result = -1;
                                } else if (source1 == Integer.MIN_VALUE && source2 == -1) {
                                    result = Integer.MIN_VALUE;
                                } else {
                                    result = source1 / source2;
                                }
                            } else if (kind == RiscV32MicroOp.DIVU) {
                                result = source2 == 0 ? -1
                                        : (int) ((source1 & 0xffffffffL) / (source2 & 0xffffffffL));
                            } else if (kind == RiscV32MicroOp.REM) {
                                if (source2 == 0) {
                                    result = source1;
                                } else if (source1 == Integer.MIN_VALUE && source2 == -1) {
                                    result = 0;
                                } else {
                                    result = source1 % source2;
                                }
                            } else {
                                result = source2 == 0 ? source1
                                        : (int) ((source1 & 0xffffffffL) % (source2 & 0xffffffffL));
                            }
                            writeRegister(registers, coreCount, core, rd, result);
                            break;
                        }

                        default:
                            // Compiler corruption is a tier miss, never a guessed execution.
                            fallbackBudget.set(core, remaining);
                            leaveCompiledTier = true;
                            continue;
                    }

                    if (pendingTrap >= 0) {
                        if (retiredBeforeTrap > 0) {
                            counter += retiredBeforeTrap;
                            remaining -= retiredBeforeTrap;
                        }
                        localTrap = pendingTrap;
                        localTrapValue = pendingTrapValue;
                        reservations.set(core, -1);

                        if ((executionFlags & RiscV32.FLAG_VECTOR_TRAPS) != 0) {
                            enterMachineTrap(csrs, coreCount, core, localPc, pendingTrap, pendingTrapValue);
                            int mtvec = csrs.get(csrIndex(coreCount, core, RiscV32.CSR_SLOT_MTVEC));
                            localPc = machineTrapTarget(mtvec, pendingTrap);
                            // A trap handler is a semantic boundary. Let the interpreter consume
                            // the exact remaining budget after this non-retiring instruction.
                            fallbackBudget.set(core, remaining);
                        } else {
                            localStatus = RiscV32.STATUS_TRAPPED;
                        }
                        leaveCompiledTier = true;
                        break;
                    }

                    localPc = nextPc;
                    counter += retiredCount;
                    remaining -= retiredCount;
                    executedAny = true;
                }

                if (executedAny) {
                    compiledBlocks++;
                }

                if (leaveCompiledTier) {
                    if (localStatus == RiscV32.STATUS_RUNNING
                            && fallbackBudget.get(core) == 0
                            && remaining > 0) {
                        fallbackBudget.set(core, remaining);
                    }
                    break;
                }
            }

            pc.set(core, localPc);
            status.set(core, localStatus);
            trapCause.set(core, localTrap);
            trapValue.set(core, localTrapValue);
            retiredInstructions.set(core, counter);
            compiledBlockExecutions.set(core, compiledBlocks);
            writeRegister(registers, coreCount, core, 0, 0);
        }
    }

    private static int readRegister(IntArray registers, int coreCount, int core, int register) {
        return register == 0 ? 0 : registers.get(register * coreCount + core);
    }

    private static void writeRegister(IntArray registers, int coreCount, int core, int register, int value) {
        if (register != 0) {
            registers.set(register * coreCount + core, value);
        }
    }

    private static int csrIndex(int coreCount, int core, int slot) {
        return slot * coreCount + core;
    }

    private static boolean csrSupported(int address) {
        return RiscV32.csrSlot(address) >= 0
                || address == RiscV32.CSR_MISA
                || address == RiscV32.CSR_MHARTID
                || address == RiscV32.CSR_MCYCLE
                || address == RiscV32.CSR_MINSTRET
                || address == RiscV32.CSR_CYCLE
                || address == RiscV32.CSR_INSTRET;
    }

    private static int readCsr(IntArray csrs, int coreCount, int core, int address, int counter) {
        int slot = RiscV32.csrSlot(address);
        if (slot >= 0) {
            return csrs.get(csrIndex(coreCount, core, slot));
        }
        switch (address) {
            case RiscV32.CSR_MISA:
                return RiscV32.MISA_RV32_IMAC;
            case RiscV32.CSR_MHARTID:
                return core;
            case RiscV32.CSR_MCYCLE:
            case RiscV32.CSR_MINSTRET:
            case RiscV32.CSR_CYCLE:
            case RiscV32.CSR_INSTRET:
                return counter;
            default:
                return 0;
        }
    }

    private static boolean writeCsr(IntArray csrs, int coreCount, int core, int address, int value) {
        int slot = RiscV32.csrSlot(address);
        if (slot < 0) {
            return false;
        }
        if (address == RiscV32.CSR_MTVEC) {
            int mode = value & 3;
            if (mode > 1) {
                return false;
            }
        }
        csrs.set(csrIndex(coreCount, core, slot), value);
        return true;
    }

    private static boolean lessThanUnsigned(int left, int right) {
        return (left ^ Integer.MIN_VALUE) < (right ^ Integer.MIN_VALUE);
    }

    private static boolean validAddress(int address, int width, int memoryBytes) {
        return address >= 0 && width > 0 && address <= memoryBytes - width;
    }

    private static int memoryIndex(int coreCount, int core, int address) {
        return (address >>> 2) * coreCount + core;
    }

    private static int load8(IntArray memory, int coreCount, int core, int address) {
        int word = memory.get(memoryIndex(coreCount, core, address));
        return (word >>> ((address & 3) << 3)) & 0xff;
    }

    private static int load16(IntArray memory, int coreCount, int core, int address) {
        int word = memory.get(memoryIndex(coreCount, core, address));
        return (word >>> ((address & 2) << 3)) & 0xffff;
    }

    private static int load32(IntArray memory, int coreCount, int core, int address) {
        return memory.get(memoryIndex(coreCount, core, address));
    }

    private static void store8(IntArray memory, int coreCount, int core, int address, int value) {
        int index = memoryIndex(coreCount, core, address);
        int shift = (address & 3) << 3;
        int mask = 0xff << shift;
        int oldWord = memory.get(index);
        memory.set(index, (oldWord & ~mask) | ((value & 0xff) << shift));
    }

    private static void store16(IntArray memory, int coreCount, int core, int address, int value) {
        int index = memoryIndex(coreCount, core, address);
        int shift = (address & 2) << 3;
        int mask = 0xffff << shift;
        int oldWord = memory.get(index);
        memory.set(index, (oldWord & ~mask) | ((value & 0xffff) << shift));
    }

    private static void store32(IntArray memory, int coreCount, int core, int address, int value) {
        memory.set(memoryIndex(coreCount, core, address), value);
    }

    private static boolean invalidateCompiledCode(Int8Array lengths, Int8Array blockValid,
            int cacheBase, int cacheEnd, int address, int width) {
        if (cacheEnd <= cacheBase || address >= cacheEnd || address + width <= cacheBase) {
            return false;
        }

        int candidateFirst = address - 2;
        int first = candidateFirst < cacheBase ? cacheBase : candidateFirst;
        int last = address + width - 1;
        if (last >= cacheEnd) {
            last = cacheEnd - 1;
        }

        int firstSlot = (first - cacheBase) >>> 1;
        int lastSlot = (last - cacheBase) >>> 1;
        for (int slot = firstSlot; slot <= lastSlot; slot++) {
            lengths.set(slot, (byte) 0);
        }
        for (int block = 0; block < blockValid.getSize(); block++) {
            blockValid.set(block, (byte) 0);
        }
        return true;
    }

    private static int machineTrapTarget(int mtvec, int cause) {
        int base = mtvec & ~3;
        int mode = mtvec & 3;
        if (mode == 1 && (cause & RiscV32.INTERRUPT_FLAG) != 0) {
            return base + 4 * (cause & ~RiscV32.INTERRUPT_FLAG);
        }
        return base;
    }

    private static void enterMachineTrap(IntArray csrs, int coreCount, int core, int pc, int cause, int value) {
        int mstatusIndex = csrIndex(coreCount, core, RiscV32.CSR_SLOT_MSTATUS);
        int mstatus = csrs.get(mstatusIndex);
        boolean mie = (mstatus & RiscV32.MSTATUS_MIE) != 0;
        mstatus &= ~(RiscV32.MSTATUS_MIE | RiscV32.MSTATUS_MPIE | RiscV32.MSTATUS_MPP_MASK);
        if (mie) {
            mstatus |= RiscV32.MSTATUS_MPIE;
        }
        mstatus |= 3 << RiscV32.MSTATUS_MPP_SHIFT;
        csrs.set(mstatusIndex, mstatus);
        csrs.set(csrIndex(coreCount, core, RiscV32.CSR_SLOT_MEPC), pc);
        csrs.set(csrIndex(coreCount, core, RiscV32.CSR_SLOT_MCAUSE), cause);
        csrs.set(csrIndex(coreCount, core, RiscV32.CSR_SLOT_MTVAL), value);
    }
}
