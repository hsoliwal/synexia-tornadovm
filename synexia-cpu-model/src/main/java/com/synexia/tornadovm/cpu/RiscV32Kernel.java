/*
 * Copyright 2026 Synexia contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.synexia.tornadovm.cpu;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Allocation-free RV32IMAC interpreter kernel.
 *
 * <p>Each accelerator work-item represents one virtual CPU. Registers, CSRs and RAM use a
 * transposed structure-of-arrays layout so the same register/word across adjacent cores is
 * contiguous in device memory. This substantially improves memory coalescing when a warp/wave
 * executes related instruction streams.
 *
 * <p>RV32C instructions are expanded to equivalent 32-bit instructions inside the kernel and then
 * use the same decoder as ordinary RV32I/M/A instructions. This keeps one semantic implementation
 * for branches, loads/stores and arithmetic while preserving 16-bit code density.
 */
public final class RiscV32Kernel {

    private static final int OP_LOAD = 0x03;
    private static final int OP_MISC_MEM = 0x0f;
    private static final int OP_IMM = 0x13;
    private static final int OP_AUIPC = 0x17;
    private static final int OP_STORE = 0x23;
    private static final int OP_AMO = 0x2f;
    private static final int OP = 0x33;
    private static final int OP_LUI = 0x37;
    private static final int OP_BRANCH = 0x63;
    private static final int OP_JALR = 0x67;
    private static final int OP_JAL = 0x6f;
    private static final int OP_SYSTEM = 0x73;

    private RiscV32Kernel() {
    }

    /**
     * Execute up to {@code instructionBudget} instructions per runnable virtual core.
     *
     * <p>The identical method is used directly by the JVM reference executor and compiled by
     * TornadoVM for an accelerator.
     */
    public static void runQuantum(IntArray registers, IntArray pc, IntArray status, IntArray trapCause,
            IntArray trapValue, IntArray retiredInstructions, IntArray csrs, IntArray reservations,
            IntArray memory, int wordsPerCore, int executionFlags, int instructionBudget) {

        final int coreCount = pc.getSize();

        for (@Parallel int core = 0; core < coreCount; core++) {
            int localStatus = status.get(core);
            if (localStatus != RiscV32.STATUS_RUNNING) {
                continue;
            }

            int memoryBytes = wordsPerCore << 2;
            int localPc = pc.get(core);
            int localTrap = trapCause.get(core);
            int localTrapValue = trapValue.get(core);
            int counter = retiredInstructions.get(core);

            for (int step = 0; step < instructionBudget && localStatus == RiscV32.STATUS_RUNNING; step++) {
                if ((executionFlags & RiscV32.FLAG_VECTOR_TRAPS) != 0) {
                    int mstatus = csrs.get(csrIndex(coreCount, core, RiscV32.CSR_SLOT_MSTATUS));
                    if ((mstatus & RiscV32.MSTATUS_MIE) != 0) {
                        int enabledPending = csrs.get(csrIndex(coreCount, core, RiscV32.CSR_SLOT_MIE))
                                & csrs.get(csrIndex(coreCount, core, RiscV32.CSR_SLOT_MIP));
                        int interruptCause = 0;
                        if ((enabledPending & RiscV32.MIP_MEIP) != 0) {
                            interruptCause = RiscV32.INTERRUPT_MACHINE_EXTERNAL;
                        } else if ((enabledPending & RiscV32.MIP_MSIP) != 0) {
                            interruptCause = RiscV32.INTERRUPT_MACHINE_SOFTWARE;
                        } else if ((enabledPending & RiscV32.MIP_MTIP) != 0) {
                            interruptCause = RiscV32.INTERRUPT_MACHINE_TIMER;
                        }
                        if (interruptCause != 0) {
                            localTrap = interruptCause;
                            localTrapValue = 0;
                            reservations.set(core, -1);
                            enterMachineTrap(csrs, coreCount, core, localPc, interruptCause, 0);
                            int mtvec = csrs.get(csrIndex(coreCount, core, RiscV32.CSR_SLOT_MTVEC));
                            localPc = machineTrapTarget(mtvec, interruptCause);
                            continue;
                        }
                    }
                }

                int pendingTrap = -1;
                int pendingTrapValue = 0;
                int instruction = 0;
                int rawInstruction = 0;
                int instructionBytes = 4;

                if ((localPc & 1) != 0) {
                    pendingTrap = RiscV32.TRAP_INSTRUCTION_ADDRESS_MISALIGNED;
                    pendingTrapValue = localPc;
                } else if (!validAddress(localPc, 2, memoryBytes)) {
                    pendingTrap = RiscV32.TRAP_INSTRUCTION_ACCESS_FAULT;
                    pendingTrapValue = localPc;
                } else {
                    int halfword = load16(memory, coreCount, core, localPc);
                    rawInstruction = halfword;
                    if ((halfword & 3) != 3) {
                        instructionBytes = 2;
                        instruction = decompress(halfword);
                        if (instruction == 0) {
                            pendingTrap = RiscV32.TRAP_ILLEGAL_INSTRUCTION;
                            pendingTrapValue = halfword;
                        }
                    } else if (!validAddress(localPc, 4, memoryBytes)) {
                        pendingTrap = RiscV32.TRAP_INSTRUCTION_ACCESS_FAULT;
                        pendingTrapValue = localPc;
                    } else {
                        instruction = loadInstruction32(memory, coreCount, core, localPc);
                        rawInstruction = instruction;
                    }
                }

                int nextPc = localPc + instructionBytes;

                if (pendingTrap < 0) {
                    int opcode = instruction & 0x7f;
                    int rd = (instruction >>> 7) & 0x1f;
                    int funct3 = (instruction >>> 12) & 0x7;
                    int rs1 = (instruction >>> 15) & 0x1f;
                    int rs2 = (instruction >>> 20) & 0x1f;
                    int funct7 = instruction >>> 25;
                    int source1 = readRegister(registers, coreCount, core, rs1);
                    int source2 = readRegister(registers, coreCount, core, rs2);

                    switch (opcode) {
                        case OP_LUI:
                            writeRegister(registers, coreCount, core, rd, instruction & 0xfffff000);
                            break;

                        case OP_AUIPC:
                            writeRegister(registers, coreCount, core, rd, localPc + (instruction & 0xfffff000));
                            break;

                        case OP_JAL: {
                            int target = localPc + immediateJ(instruction);
                            if ((target & 1) != 0) {
                                pendingTrap = RiscV32.TRAP_INSTRUCTION_ADDRESS_MISALIGNED;
                                pendingTrapValue = target;
                            } else {
                                writeRegister(registers, coreCount, core, rd, localPc + instructionBytes);
                                nextPc = target;
                            }
                            break;
                        }

                        case OP_JALR:
                            if (funct3 != 0) {
                                pendingTrap = RiscV32.TRAP_ILLEGAL_INSTRUCTION;
                                pendingTrapValue = rawInstruction;
                            } else {
                                int target = (source1 + immediateI(instruction)) & ~1;
                                if ((target & 1) != 0) {
                                    pendingTrap = RiscV32.TRAP_INSTRUCTION_ADDRESS_MISALIGNED;
                                    pendingTrapValue = target;
                                } else {
                                    writeRegister(registers, coreCount, core, rd, localPc + instructionBytes);
                                    nextPc = target;
                                }
                            }
                            break;

                        case OP_BRANCH: {
                            boolean taken = false;
                            switch (funct3) {
                                case 0:
                                    taken = source1 == source2;
                                    break;
                                case 1:
                                    taken = source1 != source2;
                                    break;
                                case 4:
                                    taken = source1 < source2;
                                    break;
                                case 5:
                                    taken = source1 >= source2;
                                    break;
                                case 6:
                                    taken = lessThanUnsigned(source1, source2);
                                    break;
                                case 7:
                                    taken = !lessThanUnsigned(source1, source2);
                                    break;
                                default:
                                    pendingTrap = RiscV32.TRAP_ILLEGAL_INSTRUCTION;
                                    pendingTrapValue = rawInstruction;
                                    break;
                            }
                            if (pendingTrap < 0 && taken) {
                                int target = localPc + immediateB(instruction);
                                if ((target & 1) != 0) {
                                    pendingTrap = RiscV32.TRAP_INSTRUCTION_ADDRESS_MISALIGNED;
                                    pendingTrapValue = target;
                                } else {
                                    nextPc = target;
                                }
                            }
                            break;
                        }

                        case OP_LOAD: {
                            int address = source1 + immediateI(instruction);
                            int width = 0;
                            if (funct3 == 0 || funct3 == 4) {
                                width = 1;
                            } else if (funct3 == 1 || funct3 == 5) {
                                width = 2;
                            } else if (funct3 == 2) {
                                width = 4;
                            }

                            if (width == 0) {
                                pendingTrap = RiscV32.TRAP_ILLEGAL_INSTRUCTION;
                                pendingTrapValue = rawInstruction;
                            } else if ((width == 2 && (address & 1) != 0) || (width == 4 && (address & 3) != 0)) {
                                pendingTrap = RiscV32.TRAP_LOAD_ADDRESS_MISALIGNED;
                                pendingTrapValue = address;
                            } else if (!validAddress(address, width, memoryBytes)) {
                                pendingTrap = RiscV32.TRAP_LOAD_ACCESS_FAULT;
                                pendingTrapValue = address;
                            } else {
                                int value = 0;
                                switch (funct3) {
                                    case 0:
                                        value = (byte) load8(memory, coreCount, core, address);
                                        break;
                                    case 1:
                                        value = (short) load16(memory, coreCount, core, address);
                                        break;
                                    case 2:
                                        value = load32(memory, coreCount, core, address);
                                        break;
                                    case 4:
                                        value = load8(memory, coreCount, core, address);
                                        break;
                                    case 5:
                                        value = load16(memory, coreCount, core, address);
                                        break;
                                    default:
                                        break;
                                }
                                writeRegister(registers, coreCount, core, rd, value);
                            }
                            break;
                        }

                        case OP_STORE: {
                            int address = source1 + immediateS(instruction);
                            int width = 0;
                            if (funct3 == 0) {
                                width = 1;
                            } else if (funct3 == 1) {
                                width = 2;
                            } else if (funct3 == 2) {
                                width = 4;
                            }

                            if (width == 0) {
                                pendingTrap = RiscV32.TRAP_ILLEGAL_INSTRUCTION;
                                pendingTrapValue = rawInstruction;
                            } else if ((width == 2 && (address & 1) != 0) || (width == 4 && (address & 3) != 0)) {
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
                            }
                            break;
                        }

                        case OP_IMM: {
                            int immediate = immediateI(instruction);
                            int result = 0;
                            boolean valid = true;
                            switch (funct3) {
                                case 0:
                                    result = source1 + immediate;
                                    break;
                                case 2:
                                    result = source1 < immediate ? 1 : 0;
                                    break;
                                case 3:
                                    result = lessThanUnsigned(source1, immediate) ? 1 : 0;
                                    break;
                                case 4:
                                    result = source1 ^ immediate;
                                    break;
                                case 6:
                                    result = source1 | immediate;
                                    break;
                                case 7:
                                    result = source1 & immediate;
                                    break;
                                case 1:
                                    valid = (instruction >>> 25) == 0;
                                    result = source1 << ((instruction >>> 20) & 0x1f);
                                    break;
                                case 5: {
                                    int shiftEncoding = instruction >>> 25;
                                    int shift = (instruction >>> 20) & 0x1f;
                                    if (shiftEncoding == 0) {
                                        result = source1 >>> shift;
                                    } else if (shiftEncoding == 0x20) {
                                        result = source1 >> shift;
                                    } else {
                                        valid = false;
                                    }
                                    break;
                                }
                                default:
                                    valid = false;
                                    break;
                            }
                            if (valid) {
                                writeRegister(registers, coreCount, core, rd, result);
                            } else {
                                pendingTrap = RiscV32.TRAP_ILLEGAL_INSTRUCTION;
                                pendingTrapValue = rawInstruction;
                            }
                            break;
                        }

                        case OP: {
                            int result = 0;
                            boolean valid = true;
                            if (funct7 == 0x01) {
                                switch (funct3) {
                                    case 0:
                                        result = source1 * source2;
                                        break;
                                    case 1:
                                        result = (int) (((long) source1 * (long) source2) >> 32);
                                        break;
                                    case 2:
                                        result = (int) (((long) source1 * (source2 & 0xffffffffL)) >> 32);
                                        break;
                                    case 3:
                                        result = (int) (((source1 & 0xffffffffL) * (source2 & 0xffffffffL)) >>> 32);
                                        break;
                                    case 4:
                                        if (source2 == 0) {
                                            result = -1;
                                        } else if (source1 == Integer.MIN_VALUE && source2 == -1) {
                                            result = Integer.MIN_VALUE;
                                        } else {
                                            result = source1 / source2;
                                        }
                                        break;
                                    case 5:
                                        result = source2 == 0 ? -1
                                                : (int) ((source1 & 0xffffffffL) / (source2 & 0xffffffffL));
                                        break;
                                    case 6:
                                        if (source2 == 0) {
                                            result = source1;
                                        } else if (source1 == Integer.MIN_VALUE && source2 == -1) {
                                            result = 0;
                                        } else {
                                            result = source1 % source2;
                                        }
                                        break;
                                    case 7:
                                        result = source2 == 0 ? source1
                                                : (int) ((source1 & 0xffffffffL) % (source2 & 0xffffffffL));
                                        break;
                                    default:
                                        valid = false;
                                        break;
                                }
                            } else {
                                switch (funct3) {
                                    case 0:
                                        if (funct7 == 0) {
                                            result = source1 + source2;
                                        } else if (funct7 == 0x20) {
                                            result = source1 - source2;
                                        } else {
                                            valid = false;
                                        }
                                        break;
                                    case 1:
                                        valid = funct7 == 0;
                                        result = source1 << (source2 & 0x1f);
                                        break;
                                    case 2:
                                        valid = funct7 == 0;
                                        result = source1 < source2 ? 1 : 0;
                                        break;
                                    case 3:
                                        valid = funct7 == 0;
                                        result = lessThanUnsigned(source1, source2) ? 1 : 0;
                                        break;
                                    case 4:
                                        valid = funct7 == 0;
                                        result = source1 ^ source2;
                                        break;
                                    case 5:
                                        if (funct7 == 0) {
                                            result = source1 >>> (source2 & 0x1f);
                                        } else if (funct7 == 0x20) {
                                            result = source1 >> (source2 & 0x1f);
                                        } else {
                                            valid = false;
                                        }
                                        break;
                                    case 6:
                                        valid = funct7 == 0;
                                        result = source1 | source2;
                                        break;
                                    case 7:
                                        valid = funct7 == 0;
                                        result = source1 & source2;
                                        break;
                                    default:
                                        valid = false;
                                        break;
                                }
                            }

                            if (valid) {
                                writeRegister(registers, coreCount, core, rd, result);
                            } else {
                                pendingTrap = RiscV32.TRAP_ILLEGAL_INSTRUCTION;
                                pendingTrapValue = rawInstruction;
                            }
                            break;
                        }

                        case OP_AMO: {
                            int address = source1;
                            if (funct3 != 2) {
                                pendingTrap = RiscV32.TRAP_ILLEGAL_INSTRUCTION;
                                pendingTrapValue = rawInstruction;
                            } else if ((address & 3) != 0) {
                                pendingTrap = RiscV32.TRAP_STORE_ADDRESS_MISALIGNED;
                                pendingTrapValue = address;
                            } else if (!validAddress(address, 4, memoryBytes)) {
                                pendingTrap = RiscV32.TRAP_STORE_ACCESS_FAULT;
                                pendingTrapValue = address;
                            } else {
                                int funct5 = instruction >>> 27;
                                int oldValue = load32(memory, coreCount, core, address);
                                int newValue = oldValue;
                                boolean store = true;

                                switch (funct5) {
                                    case 0x02: // LR.W
                                        if (rs2 != 0) {
                                            pendingTrap = RiscV32.TRAP_ILLEGAL_INSTRUCTION;
                                            pendingTrapValue = rawInstruction;
                                        } else {
                                            reservations.set(core, address);
                                            writeRegister(registers, coreCount, core, rd, oldValue);
                                            store = false;
                                        }
                                        break;
                                    case 0x03: // SC.W
                                        if (reservations.get(core) == address) {
                                            store32(memory, coreCount, core, address, source2);
                                            writeRegister(registers, coreCount, core, rd, 0);
                                        } else {
                                            writeRegister(registers, coreCount, core, rd, 1);
                                        }
                                        reservations.set(core, -1);
                                        store = false;
                                        break;
                                    case 0x01: // AMOSWAP.W
                                        newValue = source2;
                                        break;
                                    case 0x00: // AMOADD.W
                                        newValue = oldValue + source2;
                                        break;
                                    case 0x04: // AMOXOR.W
                                        newValue = oldValue ^ source2;
                                        break;
                                    case 0x0c: // AMOAND.W
                                        newValue = oldValue & source2;
                                        break;
                                    case 0x08: // AMOOR.W
                                        newValue = oldValue | source2;
                                        break;
                                    case 0x10: // AMOMIN.W
                                        newValue = oldValue < source2 ? oldValue : source2;
                                        break;
                                    case 0x14: // AMOMAX.W
                                        newValue = oldValue > source2 ? oldValue : source2;
                                        break;
                                    case 0x18: // AMOMINU.W
                                        newValue = lessThanUnsigned(oldValue, source2) ? oldValue : source2;
                                        break;
                                    case 0x1c: // AMOMAXU.W
                                        newValue = lessThanUnsigned(oldValue, source2) ? source2 : oldValue;
                                        break;
                                    default:
                                        pendingTrap = RiscV32.TRAP_ILLEGAL_INSTRUCTION;
                                        pendingTrapValue = rawInstruction;
                                        store = false;
                                        break;
                                }

                                if (pendingTrap < 0 && store) {
                                    store32(memory, coreCount, core, address, newValue);
                                    writeRegister(registers, coreCount, core, rd, oldValue);
                                    reservations.set(core, -1);
                                }
                            }
                            break;
                        }

                        case OP_MISC_MEM:
                            if (funct3 != 0 && funct3 != 1) {
                                pendingTrap = RiscV32.TRAP_ILLEGAL_INSTRUCTION;
                                pendingTrapValue = rawInstruction;
                            }
                            break;

                        case OP_SYSTEM:
                            if (funct3 == 0) {
                                if (instruction == 0x00000073) { // ECALL
                                    pendingTrap = RiscV32.TRAP_ECALL_M_MODE;
                                } else if (instruction == 0x00100073) { // EBREAK
                                    if ((executionFlags & RiscV32.FLAG_EBREAK_HALT) != 0) {
                                        localStatus = RiscV32.STATUS_HALTED;
                                        localTrap = RiscV32.TRAP_BREAKPOINT;
                                        localTrapValue = localPc;
                                    } else {
                                        pendingTrap = RiscV32.TRAP_BREAKPOINT;
                                        pendingTrapValue = localPc;
                                    }
                                } else if (instruction == 0x30200073) { // MRET
                                    int mstatusIndex = csrIndex(coreCount, core, RiscV32.CSR_SLOT_MSTATUS);
                                    int mstatus = csrs.get(mstatusIndex);
                                    boolean mpie = (mstatus & RiscV32.MSTATUS_MPIE) != 0;
                                    mstatus &= ~(RiscV32.MSTATUS_MIE | RiscV32.MSTATUS_MPIE | RiscV32.MSTATUS_MPP_MASK);
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
                                } else if (instruction == 0x10500073) { // WFI
                                    localStatus = RiscV32.STATUS_WAITING;
                                } else {
                                    pendingTrap = RiscV32.TRAP_ILLEGAL_INSTRUCTION;
                                    pendingTrapValue = rawInstruction;
                                }
                            } else {
                                int csrAddress = instruction >>> 20;
                                if (!csrSupported(csrAddress)) {
                                    pendingTrap = RiscV32.TRAP_ILLEGAL_INSTRUCTION;
                                    pendingTrapValue = rawInstruction;
                                } else {
                                    int oldValue = readCsr(csrs, retiredInstructions, coreCount, core, csrAddress, counter);
                                    int source = funct3 >= 5 ? rs1 : source1;
                                    int newValue = oldValue;
                                    boolean write = false;

                                    switch (funct3) {
                                        case 1: // CSRRW
                                        case 5: // CSRRWI
                                            newValue = source;
                                            write = true;
                                            break;
                                        case 2: // CSRRS
                                        case 6: // CSRRSI
                                            if (source != 0) {
                                                newValue = oldValue | source;
                                                write = true;
                                            }
                                            break;
                                        case 3: // CSRRC
                                        case 7: // CSRRCI
                                            if (source != 0) {
                                                newValue = oldValue & ~source;
                                                write = true;
                                            }
                                            break;
                                        default:
                                            pendingTrap = RiscV32.TRAP_ILLEGAL_INSTRUCTION;
                                            pendingTrapValue = rawInstruction;
                                            break;
                                    }

                                    if (pendingTrap < 0) {
                                        if (write && !writeCsr(csrs, retiredInstructions, coreCount, core, csrAddress, newValue)) {
                                            pendingTrap = RiscV32.TRAP_ILLEGAL_INSTRUCTION;
                                            pendingTrapValue = rawInstruction;
                                        } else {
                                            if (write && (csrAddress == RiscV32.CSR_MCYCLE || csrAddress == RiscV32.CSR_MINSTRET)) {
                                                counter = newValue;
                                            }
                                            writeRegister(registers, coreCount, core, rd, oldValue);
                                        }
                                    }
                                }
                            }
                            break;

                        default:
                            pendingTrap = RiscV32.TRAP_ILLEGAL_INSTRUCTION;
                            pendingTrapValue = rawInstruction;
                            break;
                    }
                }

                if (pendingTrap >= 0) {
                    localTrap = pendingTrap;
                    localTrapValue = pendingTrapValue;
                    reservations.set(core, -1);

                    if ((executionFlags & RiscV32.FLAG_VECTOR_TRAPS) != 0) {
                        enterMachineTrap(csrs, coreCount, core, localPc, pendingTrap, pendingTrapValue);
                        int mtvec = csrs.get(csrIndex(coreCount, core, RiscV32.CSR_SLOT_MTVEC));
                        localPc = machineTrapTarget(mtvec, pendingTrap);
                        writeRegister(registers, coreCount, core, 0, 0);
                        continue;
                    }

                    localStatus = RiscV32.STATUS_TRAPPED;
                    break;
                }

                localPc = nextPc;
                counter++;
                writeRegister(registers, coreCount, core, 0, 0);
            }

            pc.set(core, localPc);
            status.set(core, localStatus);
            trapCause.set(core, localTrap);
            trapValue.set(core, localTrapValue);
            retiredInstructions.set(core, counter);
            writeRegister(registers, coreCount, core, 0, 0);
        }
    }

    private static int readRegister(IntArray registers, int coreCount, int core, int register) {
        return registers.get(register * coreCount + core);
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

    private static int readCsr(IntArray csrs, IntArray retiredInstructions, int coreCount, int core,
            int address, int counter) {
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

    private static boolean writeCsr(IntArray csrs, IntArray retiredInstructions, int coreCount, int core,
            int address, int value) {
        int slot = RiscV32.csrSlot(address);
        if (slot >= 0) {
            if (address == RiscV32.CSR_MTVEC) {
                int mode = value & 3;
                if (mode > 1) {
                    return false;
                }
            }
            csrs.set(csrIndex(coreCount, core, slot), value);
            return true;
        }
        if (address == RiscV32.CSR_MCYCLE || address == RiscV32.CSR_MINSTRET) {
            retiredInstructions.set(core, value);
            return true;
        }
        return false;
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

    private static int loadInstruction32(IntArray memory, int coreCount, int core, int address) {
        if ((address & 3) == 0) {
            return load32(memory, coreCount, core, address);
        }
        int low = load16(memory, coreCount, core, address);
        int high = load16(memory, coreCount, core, address + 2);
        return low | (high << 16);
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

    private static int immediateI(int instruction) {
        return instruction >> 20;
    }

    private static int immediateS(int instruction) {
        int value = ((instruction >>> 25) << 5) | ((instruction >>> 7) & 0x1f);
        return signExtend(value, 12);
    }

    private static int immediateB(int instruction) {
        int value = ((instruction >>> 31) << 12)
                | (((instruction >>> 7) & 1) << 11)
                | (((instruction >>> 25) & 0x3f) << 5)
                | (((instruction >>> 8) & 0xf) << 1);
        return signExtend(value, 13);
    }

    private static int immediateJ(int instruction) {
        int value = ((instruction >>> 31) << 20)
                | (((instruction >>> 12) & 0xff) << 12)
                | (((instruction >>> 20) & 1) << 11)
                | (((instruction >>> 21) & 0x3ff) << 1);
        return signExtend(value, 21);
    }

    private static int signExtend(int value, int bits) {
        int shift = 32 - bits;
        return (value << shift) >> shift;
    }

    /**
     * Expand an RV32C instruction to an equivalent RV32I instruction.
     *
     * @return expanded instruction, or zero for a reserved/illegal encoding
     */
    private static int decompress(int c) {
        int quadrant = c & 3;
        int funct3 = (c >>> 13) & 7;

        if (quadrant == 0) {
            int rdPrime = 8 + ((c >>> 2) & 7);
            int rs1Prime = 8 + ((c >>> 7) & 7);
            switch (funct3) {
                case 0: { // C.ADDI4SPN
                    int immediate = (((c >>> 7) & 0xf) << 6)
                            | (((c >>> 11) & 3) << 4)
                            | (((c >>> 5) & 1) << 3)
                            | (((c >>> 6) & 1) << 2);
                    return immediate == 0 ? 0 : encodeI(OP_IMM, 0, rdPrime, 2, immediate);
                }
                case 2: { // C.LW
                    int immediate = (((c >>> 10) & 7) << 3)
                            | (((c >>> 6) & 1) << 2)
                            | (((c >>> 5) & 1) << 6);
                    return encodeI(OP_LOAD, 2, rdPrime, rs1Prime, immediate);
                }
                case 6: { // C.SW
                    int rs2Prime = rdPrime;
                    int immediate = (((c >>> 10) & 7) << 3)
                            | (((c >>> 6) & 1) << 2)
                            | (((c >>> 5) & 1) << 6);
                    return encodeS(2, rs1Prime, rs2Prime, immediate);
                }
                default:
                    return 0;
            }
        }

        if (quadrant == 1) {
            int rd = (c >>> 7) & 0x1f;
            int immediate6 = signExtend(((c >>> 2) & 0x1f) | (((c >>> 12) & 1) << 5), 6);
            switch (funct3) {
                case 0: // C.NOP / C.ADDI
                    return encodeI(OP_IMM, 0, rd, rd, immediate6);

                case 1: // C.JAL (RV32)
                    return encodeJ(1, compressedJumpImmediate(c));

                case 2: // C.LI
                    return rd == 0 ? 0 : encodeI(OP_IMM, 0, rd, 0, immediate6);

                case 3:
                    if (rd == 2) { // C.ADDI16SP
                        int immediate = (((c >>> 12) & 1) << 9)
                                | (((c >>> 6) & 1) << 4)
                                | (((c >>> 5) & 1) << 6)
                                | (((c >>> 3) & 3) << 7)
                                | (((c >>> 2) & 1) << 5);
                        immediate = signExtend(immediate, 10);
                        return immediate == 0 ? 0 : encodeI(OP_IMM, 0, 2, 2, immediate);
                    } else if (rd != 0) { // C.LUI
                        int upper = signExtend(((c >>> 2) & 0x1f) | (((c >>> 12) & 1) << 5), 6);
                        if (upper == 0) {
                            return 0;
                        }
                        return (upper << 12) | (rd << 7) | OP_LUI;
                    }
                    return 0;

                case 4: {
                    int subop = (c >>> 10) & 3;
                    int rdPrime = 8 + ((c >>> 7) & 7);
                    if (subop == 0) { // C.SRLI
                        if (((c >>> 12) & 1) != 0) {
                            return 0;
                        }
                        return encodeI(OP_IMM, 5, rdPrime, rdPrime, (c >>> 2) & 0x1f);
                    }
                    if (subop == 1) { // C.SRAI
                        if (((c >>> 12) & 1) != 0) {
                            return 0;
                        }
                        return encodeI(OP_IMM, 5, rdPrime, rdPrime, 0x400 | ((c >>> 2) & 0x1f));
                    }
                    if (subop == 2) { // C.ANDI
                        return encodeI(OP_IMM, 7, rdPrime, rdPrime, immediate6);
                    }

                    if (((c >>> 12) & 1) != 0) {
                        return 0;
                    }
                    int rs2Prime = 8 + ((c >>> 2) & 7);
                    switch ((c >>> 5) & 3) {
                        case 0:
                            return encodeR(0x20, 0, rdPrime, rdPrime, rs2Prime); // C.SUB
                        case 1:
                            return encodeR(0, 4, rdPrime, rdPrime, rs2Prime); // C.XOR
                        case 2:
                            return encodeR(0, 6, rdPrime, rdPrime, rs2Prime); // C.OR
                        case 3:
                            return encodeR(0, 7, rdPrime, rdPrime, rs2Prime); // C.AND
                        default:
                            return 0;
                    }
                }

                case 5: // C.J
                    return encodeJ(0, compressedJumpImmediate(c));

                case 6: // C.BEQZ
                case 7: {
                    int rs1Prime = 8 + ((c >>> 7) & 7);
                    int branchImmediate = (((c >>> 12) & 1) << 8)
                            | (((c >>> 10) & 3) << 3)
                            | (((c >>> 5) & 3) << 6)
                            | (((c >>> 3) & 3) << 1)
                            | (((c >>> 2) & 1) << 5);
                    branchImmediate = signExtend(branchImmediate, 9);
                    return encodeB(funct3 == 6 ? 0 : 1, rs1Prime, 0, branchImmediate);
                }

                default:
                    return 0;
            }
        }

        if (quadrant == 2) {
            int rd = (c >>> 7) & 0x1f;
            int rs2 = (c >>> 2) & 0x1f;
            switch (funct3) {
                case 0: // C.SLLI
                    if (((c >>> 12) & 1) != 0 || rd == 0) {
                        return 0;
                    }
                    return encodeI(OP_IMM, 1, rd, rd, rs2);

                case 2: { // C.LWSP
                    if (rd == 0) {
                        return 0;
                    }
                    int immediate = (((c >>> 12) & 1) << 5)
                            | (((c >>> 4) & 7) << 2)
                            | (((c >>> 2) & 3) << 6);
                    return encodeI(OP_LOAD, 2, rd, 2, immediate);
                }

                case 4:
                    if (((c >>> 12) & 1) == 0) {
                        if (rs2 == 0) {
                            return rd == 0 ? 0 : encodeI(OP_JALR, 0, 0, rd, 0); // C.JR
                        }
                        return rd == 0 ? 0 : encodeR(0, 0, rd, 0, rs2); // C.MV
                    }

                    if (rd == 0 && rs2 == 0) {
                        return 0x00100073; // C.EBREAK
                    }
                    if (rs2 == 0) {
                        return rd == 0 ? 0 : encodeI(OP_JALR, 0, 1, rd, 0); // C.JALR
                    }
                    return rd == 0 ? 0 : encodeR(0, 0, rd, rd, rs2); // C.ADD

                case 6: { // C.SWSP
                    int immediate = (((c >>> 9) & 0xf) << 2)
                            | (((c >>> 7) & 3) << 6);
                    return encodeS(2, 2, rs2, immediate);
                }

                default:
                    return 0;
            }
        }

        return 0;
    }

    private static int compressedJumpImmediate(int c) {
        int immediate = (((c >>> 12) & 1) << 11)
                | (((c >>> 11) & 1) << 4)
                | (((c >>> 9) & 3) << 8)
                | (((c >>> 8) & 1) << 10)
                | (((c >>> 7) & 1) << 6)
                | (((c >>> 6) & 1) << 7)
                | (((c >>> 3) & 7) << 1)
                | (((c >>> 2) & 1) << 5);
        return signExtend(immediate, 12);
    }

    private static int encodeI(int opcode, int funct3, int rd, int rs1, int immediate) {
        return ((immediate & 0xfff) << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | opcode;
    }

    private static int encodeS(int funct3, int rs1, int rs2, int immediate) {
        int value = immediate & 0xfff;
        return (((value >>> 5) & 0x7f) << 25)
                | (rs2 << 20) | (rs1 << 15) | (funct3 << 12)
                | ((value & 0x1f) << 7) | OP_STORE;
    }

    private static int encodeB(int funct3, int rs1, int rs2, int offset) {
        int value = offset & 0x1fff;
        return (((value >>> 12) & 1) << 31)
                | (((value >>> 5) & 0x3f) << 25)
                | (rs2 << 20) | (rs1 << 15) | (funct3 << 12)
                | (((value >>> 1) & 0xf) << 8)
                | (((value >>> 11) & 1) << 7)
                | OP_BRANCH;
    }

    private static int encodeR(int funct7, int funct3, int rd, int rs1, int rs2) {
        return (funct7 << 25) | (rs2 << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | OP;
    }

    private static int encodeJ(int rd, int offset) {
        int value = offset & 0x1fffff;
        return (((value >>> 20) & 1) << 31)
                | (((value >>> 1) & 0x3ff) << 21)
                | (((value >>> 11) & 1) << 20)
                | (((value >>> 12) & 0xff) << 12)
                | (rd << 7) | OP_JAL;
    }
}
