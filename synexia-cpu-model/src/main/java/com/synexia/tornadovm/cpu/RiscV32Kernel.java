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
 * Object-free RV32IM interpreter kernel.
 *
 * <p>Each {@code @Parallel} iteration owns one virtual core. Architectural state uses a
 * structure-of-arrays layout:
 *
 * <ul>
 *   <li>{@code registers[core * 32 + register]}</li>
 *   <li>{@code pc[core]}, {@code status[core]}, {@code trapCause[core]}</li>
 *   <li>{@code memory[core * wordsPerCore + wordAddress]}</li>
 * </ul>
 *
 * <p>Memory is byte-addressed architecturally but stored as packed little-endian 32-bit words,
 * avoiding one Java object (or even one Java array) per virtual CPU.
 */
public final class RiscV32Kernel {

    private static final int OP_LOAD = 0x03;
    private static final int OP_MISC_MEM = 0x0f;
    private static final int OP_IMM = 0x13;
    private static final int OP_AUIPC = 0x17;
    private static final int OP_STORE = 0x23;
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
     * <p>The same method is called directly by the JVM reference backend and compiled by
     * TornadoVM for an accelerator. That makes the CPU implementation the executable oracle for
     * GPU parity instead of maintaining two interpreters.
     */
    public static void runQuantum(IntArray registers, IntArray pc, IntArray status, IntArray trapCause,
            IntArray retiredInstructions, IntArray memory, int wordsPerCore, int instructionBudget) {

        for (@Parallel int core = 0; core < pc.getSize(); core++) {
            int localStatus = status.get(core);
            if (localStatus != RiscV32.STATUS_RUNNING) {
                continue;
            }

            int registerBase = core * RiscV32.REGISTER_COUNT;
            int memoryBase = core * wordsPerCore;
            int memoryBytes = wordsPerCore << 2;
            int localPc = pc.get(core);
            int localTrap = trapCause.get(core);
            int retired = 0;

            for (int step = 0; step < instructionBudget && localStatus == RiscV32.STATUS_RUNNING; step++) {
                if ((localPc & 3) != 0) {
                    localStatus = RiscV32.STATUS_TRAPPED;
                    localTrap = RiscV32.TRAP_INSTRUCTION_ADDRESS_MISALIGNED;
                    break;
                }
                if (!validAddress(localPc, 4, memoryBytes)) {
                    localStatus = RiscV32.STATUS_TRAPPED;
                    localTrap = RiscV32.TRAP_INSTRUCTION_ACCESS_FAULT;
                    break;
                }

                int instruction = load32(memory, memoryBase, localPc);
                int opcode = instruction & 0x7f;
                int rd = (instruction >>> 7) & 0x1f;
                int funct3 = (instruction >>> 12) & 0x7;
                int rs1 = (instruction >>> 15) & 0x1f;
                int rs2 = (instruction >>> 20) & 0x1f;
                int funct7 = instruction >>> 25;
                int source1 = registers.get(registerBase + rs1);
                int source2 = registers.get(registerBase + rs2);
                int nextPc = localPc + 4;

                switch (opcode) {
                    case OP_LUI:
                        writeRegister(registers, registerBase, rd, instruction & 0xfffff000);
                        break;

                    case OP_AUIPC:
                        writeRegister(registers, registerBase, rd, localPc + (instruction & 0xfffff000));
                        break;

                    case OP_JAL:
                        writeRegister(registers, registerBase, rd, localPc + 4);
                        nextPc = localPc + immediateJ(instruction);
                        break;

                    case OP_JALR:
                        if (funct3 != 0) {
                            localStatus = RiscV32.STATUS_TRAPPED;
                            localTrap = RiscV32.TRAP_ILLEGAL_INSTRUCTION;
                            break;
                        }
                        writeRegister(registers, registerBase, rd, localPc + 4);
                        nextPc = (source1 + immediateI(instruction)) & ~1;
                        break;

                    case OP_BRANCH: {
                        boolean taken;
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
                                taken = false;
                                localStatus = RiscV32.STATUS_TRAPPED;
                                localTrap = RiscV32.TRAP_ILLEGAL_INSTRUCTION;
                                break;
                        }
                        if (localStatus == RiscV32.STATUS_RUNNING && taken) {
                            nextPc = localPc + immediateB(instruction);
                        }
                        break;
                    }

                    case OP_LOAD: {
                        int address = source1 + immediateI(instruction);
                        int width;
                        if (funct3 == 0 || funct3 == 4) {
                            width = 1;
                        } else if (funct3 == 1 || funct3 == 5) {
                            width = 2;
                        } else if (funct3 == 2) {
                            width = 4;
                        } else {
                            width = 0;
                        }

                        if (width == 0) {
                            localStatus = RiscV32.STATUS_TRAPPED;
                            localTrap = RiscV32.TRAP_ILLEGAL_INSTRUCTION;
                            break;
                        }
                        if ((width == 2 && (address & 1) != 0) || (width == 4 && (address & 3) != 0)) {
                            localStatus = RiscV32.STATUS_TRAPPED;
                            localTrap = RiscV32.TRAP_LOAD_ADDRESS_MISALIGNED;
                            break;
                        }
                        if (!validAddress(address, width, memoryBytes)) {
                            localStatus = RiscV32.STATUS_TRAPPED;
                            localTrap = RiscV32.TRAP_LOAD_ACCESS_FAULT;
                            break;
                        }

                        int value;
                        switch (funct3) {
                            case 0:
                                value = (byte) load8(memory, memoryBase, address);
                                break;
                            case 1:
                                value = (short) load16(memory, memoryBase, address);
                                break;
                            case 2:
                                value = load32(memory, memoryBase, address);
                                break;
                            case 4:
                                value = load8(memory, memoryBase, address);
                                break;
                            case 5:
                                value = load16(memory, memoryBase, address);
                                break;
                            default:
                                value = 0;
                                break;
                        }
                        writeRegister(registers, registerBase, rd, value);
                        break;
                    }

                    case OP_STORE: {
                        int address = source1 + immediateS(instruction);
                        int width;
                        if (funct3 == 0) {
                            width = 1;
                        } else if (funct3 == 1) {
                            width = 2;
                        } else if (funct3 == 2) {
                            width = 4;
                        } else {
                            width = 0;
                        }

                        if (width == 0) {
                            localStatus = RiscV32.STATUS_TRAPPED;
                            localTrap = RiscV32.TRAP_ILLEGAL_INSTRUCTION;
                            break;
                        }
                        if ((width == 2 && (address & 1) != 0) || (width == 4 && (address & 3) != 0)) {
                            localStatus = RiscV32.STATUS_TRAPPED;
                            localTrap = RiscV32.TRAP_STORE_ADDRESS_MISALIGNED;
                            break;
                        }
                        if (!validAddress(address, width, memoryBytes)) {
                            localStatus = RiscV32.STATUS_TRAPPED;
                            localTrap = RiscV32.TRAP_STORE_ACCESS_FAULT;
                            break;
                        }

                        if (width == 1) {
                            store8(memory, memoryBase, address, source2);
                        } else if (width == 2) {
                            store16(memory, memoryBase, address, source2);
                        } else {
                            store32(memory, memoryBase, address, source2);
                        }
                        break;
                    }

                    case OP_IMM: {
                        int immediate = immediateI(instruction);
                        int result;
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
                                if ((instruction >>> 25) != 0) {
                                    result = 0;
                                    localStatus = RiscV32.STATUS_TRAPPED;
                                    localTrap = RiscV32.TRAP_ILLEGAL_INSTRUCTION;
                                } else {
                                    result = source1 << ((instruction >>> 20) & 0x1f);
                                }
                                break;
                            case 5: {
                                int shiftEncoding = instruction >>> 25;
                                int shift = (instruction >>> 20) & 0x1f;
                                if (shiftEncoding == 0) {
                                    result = source1 >>> shift;
                                } else if (shiftEncoding == 0x20) {
                                    result = source1 >> shift;
                                } else {
                                    result = 0;
                                    localStatus = RiscV32.STATUS_TRAPPED;
                                    localTrap = RiscV32.TRAP_ILLEGAL_INSTRUCTION;
                                }
                                break;
                            }
                            default:
                                result = 0;
                                localStatus = RiscV32.STATUS_TRAPPED;
                                localTrap = RiscV32.TRAP_ILLEGAL_INSTRUCTION;
                                break;
                        }
                        if (localStatus == RiscV32.STATUS_RUNNING) {
                            writeRegister(registers, registerBase, rd, result);
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

                        if (!valid) {
                            localStatus = RiscV32.STATUS_TRAPPED;
                            localTrap = RiscV32.TRAP_ILLEGAL_INSTRUCTION;
                        } else {
                            writeRegister(registers, registerBase, rd, result);
                        }
                        break;
                    }

                    case OP_MISC_MEM:
                        if (funct3 != 0 && funct3 != 1) {
                            localStatus = RiscV32.STATUS_TRAPPED;
                            localTrap = RiscV32.TRAP_ILLEGAL_INSTRUCTION;
                        }
                        break;

                    case OP_SYSTEM:
                        if (instruction == 0x00100073) {
                            // EBREAK is the explicit stop instruction for this execution environment.
                            localStatus = RiscV32.STATUS_HALTED;
                            localTrap = RiscV32.TRAP_BREAKPOINT;
                        } else if (instruction == 0x00000073) {
                            localStatus = RiscV32.STATUS_TRAPPED;
                            localTrap = RiscV32.TRAP_ECALL_M_MODE;
                        } else {
                            localStatus = RiscV32.STATUS_TRAPPED;
                            localTrap = RiscV32.TRAP_ILLEGAL_INSTRUCTION;
                        }
                        break;

                    default:
                        localStatus = RiscV32.STATUS_TRAPPED;
                        localTrap = RiscV32.TRAP_ILLEGAL_INSTRUCTION;
                        break;
                }

                if (localStatus != RiscV32.STATUS_TRAPPED) {
                    localPc = nextPc;
                    retired++;
                }
                registers.set(registerBase, 0);
            }

            pc.set(core, localPc);
            status.set(core, localStatus);
            trapCause.set(core, localTrap);
            retiredInstructions.set(core, retiredInstructions.get(core) + retired);
            registers.set(registerBase, 0);
        }
    }

    private static void writeRegister(IntArray registers, int registerBase, int register, int value) {
        if (register != 0) {
            registers.set(registerBase + register, value);
        }
    }

    private static boolean lessThanUnsigned(int left, int right) {
        return (left ^ Integer.MIN_VALUE) < (right ^ Integer.MIN_VALUE);
    }

    private static boolean validAddress(int address, int width, int memoryBytes) {
        return address >= 0 && width > 0 && address <= memoryBytes - width;
    }

    private static int load8(IntArray memory, int memoryBase, int address) {
        int word = memory.get(memoryBase + (address >>> 2));
        int shift = (address & 3) << 3;
        return (word >>> shift) & 0xff;
    }

    private static int load16(IntArray memory, int memoryBase, int address) {
        int word = memory.get(memoryBase + (address >>> 2));
        int shift = (address & 2) << 3;
        return (word >>> shift) & 0xffff;
    }

    private static int load32(IntArray memory, int memoryBase, int address) {
        return memory.get(memoryBase + (address >>> 2));
    }

    private static void store8(IntArray memory, int memoryBase, int address, int value) {
        int index = memoryBase + (address >>> 2);
        int shift = (address & 3) << 3;
        int mask = 0xff << shift;
        int oldWord = memory.get(index);
        memory.set(index, (oldWord & ~mask) | ((value & 0xff) << shift));
    }

    private static void store16(IntArray memory, int memoryBase, int address, int value) {
        int index = memoryBase + (address >>> 2);
        int shift = (address & 2) << 3;
        int mask = 0xffff << shift;
        int oldWord = memory.get(index);
        memory.set(index, (oldWord & ~mask) | ((value & 0xffff) << shift));
    }

    private static void store32(IntArray memory, int memoryBase, int address, int value) {
        memory.set(memoryBase + (address >>> 2), value);
    }

    private static int immediateI(int instruction) {
        return instruction >> 20;
    }

    private static int immediateS(int instruction) {
        int value = ((instruction >>> 25) << 5) | ((instruction >>> 7) & 0x1f);
        return (value << 20) >> 20;
    }

    private static int immediateB(int instruction) {
        int value = ((instruction >>> 31) << 12)
                | (((instruction >>> 7) & 0x1) << 11)
                | (((instruction >>> 25) & 0x3f) << 5)
                | (((instruction >>> 8) & 0xf) << 1);
        return (value << 19) >> 19;
    }

    private static int immediateJ(int instruction) {
        int value = ((instruction >>> 31) << 20)
                | (((instruction >>> 12) & 0xff) << 12)
                | (((instruction >>> 20) & 0x1) << 11)
                | (((instruction >>> 21) & 0x3ff) << 1);
        return (value << 11) >> 11;
    }
}
