/* SPDX-License-Identifier: Apache-2.0 */
package com.synexia.m3.cpugpu;

import uk.ac.manchester.tornado.api.KernelContext;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * TornadoVM kernel: one work-item executes one independent RV32IM virtual core.
 */
public final class Rv32iKernel {
    private Rv32iKernel() {
    }

    public static void execute(KernelContext context, IntArray memory, IntArray registers,
            IntArray state, IntArray config) {
        int core = context.globalIdx;
        int coreCount = config.get(Rv32i.CONFIG_CORE_COUNT);
        if (core >= coreCount) {
            return;
        }

        int memoryWords = config.get(Rv32i.CONFIG_MEMORY_WORDS_PER_CORE);
        int instructionBudget = config.get(Rv32i.CONFIG_INSTRUCTION_BUDGET);
        int memoryBytes = memoryWords << 2;
        int memoryBase = core * memoryWords;
        int registerBase = core * Rv32i.REGISTER_COUNT;
        int stateBase = core * Rv32i.STATE_STRIDE;

        int status = state.get(stateBase + Rv32i.STATE_STATUS);
        if (status == Rv32i.STATUS_HALTED || status == Rv32i.STATUS_TRAPPED) {
            return;
        }
        state.set(stateBase + Rv32i.STATE_STATUS, Rv32i.STATUS_READY);

        int pc = state.get(stateBase + Rv32i.STATE_PC);
        int retired = state.get(stateBase + Rv32i.STATE_RETIRED);

        for (int step = 0; step < instructionBudget; step++) {
            if ((pc & 3) != 0) {
                trap(state, stateBase, pc, Rv32i.TRAP_INSTRUCTION_ADDRESS_MISALIGNED, pc);
                return;
            }
            if (!inRange(pc, 4, memoryBytes)) {
                trap(state, stateBase, pc, Rv32i.TRAP_INSTRUCTION_ACCESS_FAULT, pc);
                return;
            }

            int instruction = memory.get(memoryBase + (pc >>> 2));
            if (instruction == Rv32i.XM3_HALT) {
                retired++;
                pc += 4;
                state.set(stateBase + Rv32i.STATE_PC, pc);
                state.set(stateBase + Rv32i.STATE_RETIRED, retired);
                state.set(stateBase + Rv32i.STATE_STATUS, Rv32i.STATUS_HALTED);
                state.set(stateBase + Rv32i.STATE_EXIT_CODE, readRegister(registers, registerBase, 10));
                registers.set(registerBase, 0);
                return;
            }

            int opcode = instruction & 0x7F;
            int rd = instruction >>> 7 & 0x1F;
            int funct3 = instruction >>> 12 & 0x7;
            int rs1 = instruction >>> 15 & 0x1F;
            int rs2 = instruction >>> 20 & 0x1F;
            int funct7 = instruction >>> 25;
            int left = readRegister(registers, registerBase, rs1);
            int right = readRegister(registers, registerBase, rs2);
            int nextPc = pc + 4;
            int trapCause = Rv32i.TRAP_NONE;
            int trapValue = 0;
            boolean legal = true;

            switch (opcode) {
                case 0x37:
                    writeRegister(registers, registerBase, rd, immediateU(instruction));
                    break;
                case 0x17:
                    writeRegister(registers, registerBase, rd, pc + immediateU(instruction));
                    break;
                case 0x6F: {
                    int target = pc + immediateJ(instruction);
                    if ((target & 3) != 0) {
                        trapCause = Rv32i.TRAP_INSTRUCTION_ADDRESS_MISALIGNED;
                        trapValue = target;
                    } else {
                        writeRegister(registers, registerBase, rd, nextPc);
                        nextPc = target;
                    }
                    break;
                }
                case 0x67: {
                    if (funct3 != 0) {
                        legal = false;
                    } else {
                        int target = (left + immediateI(instruction)) & ~1;
                        if ((target & 3) != 0) {
                            trapCause = Rv32i.TRAP_INSTRUCTION_ADDRESS_MISALIGNED;
                            trapValue = target;
                        } else {
                            writeRegister(registers, registerBase, rd, nextPc);
                            nextPc = target;
                        }
                    }
                    break;
                }
                case 0x63: {
                    boolean take = false;
                    if (funct3 == 0) {
                        take = left == right;
                    } else if (funct3 == 1) {
                        take = left != right;
                    } else if (funct3 == 4) {
                        take = left < right;
                    } else if (funct3 == 5) {
                        take = left >= right;
                    } else if (funct3 == 6) {
                        take = unsignedLessThan(left, right);
                    } else if (funct3 == 7) {
                        take = !unsignedLessThan(left, right);
                    } else {
                        legal = false;
                    }
                    if (legal && take) {
                        int target = pc + immediateB(instruction);
                        if ((target & 3) != 0) {
                            trapCause = Rv32i.TRAP_INSTRUCTION_ADDRESS_MISALIGNED;
                            trapValue = target;
                        } else {
                            nextPc = target;
                        }
                    }
                    break;
                }
                case 0x03: {
                    int address = left + immediateI(instruction);
                    if (funct3 == 0) {
                        if (!inRange(address, 1, memoryBytes)) {
                            trapCause = Rv32i.TRAP_LOAD_ACCESS_FAULT;
                            trapValue = address;
                        } else {
                            writeRegister(registers, registerBase, rd,
                                    signExtend(loadByte(memory, memoryBase, address), 8));
                        }
                    } else if (funct3 == 1) {
                        if ((address & 1) != 0) {
                            trapCause = Rv32i.TRAP_LOAD_ADDRESS_MISALIGNED;
                            trapValue = address;
                        } else if (!inRange(address, 2, memoryBytes)) {
                            trapCause = Rv32i.TRAP_LOAD_ACCESS_FAULT;
                            trapValue = address;
                        } else {
                            writeRegister(registers, registerBase, rd,
                                    signExtend(loadHalf(memory, memoryBase, address), 16));
                        }
                    } else if (funct3 == 2) {
                        if ((address & 3) != 0) {
                            trapCause = Rv32i.TRAP_LOAD_ADDRESS_MISALIGNED;
                            trapValue = address;
                        } else if (!inRange(address, 4, memoryBytes)) {
                            trapCause = Rv32i.TRAP_LOAD_ACCESS_FAULT;
                            trapValue = address;
                        } else {
                            writeRegister(registers, registerBase, rd, memory.get(memoryBase + (address >>> 2)));
                        }
                    } else if (funct3 == 4) {
                        if (!inRange(address, 1, memoryBytes)) {
                            trapCause = Rv32i.TRAP_LOAD_ACCESS_FAULT;
                            trapValue = address;
                        } else {
                            writeRegister(registers, registerBase, rd, loadByte(memory, memoryBase, address));
                        }
                    } else if (funct3 == 5) {
                        if ((address & 1) != 0) {
                            trapCause = Rv32i.TRAP_LOAD_ADDRESS_MISALIGNED;
                            trapValue = address;
                        } else if (!inRange(address, 2, memoryBytes)) {
                            trapCause = Rv32i.TRAP_LOAD_ACCESS_FAULT;
                            trapValue = address;
                        } else {
                            writeRegister(registers, registerBase, rd, loadHalf(memory, memoryBase, address));
                        }
                    } else {
                        legal = false;
                    }
                    break;
                }
                case 0x23: {
                    int address = left + immediateS(instruction);
                    if (funct3 == 0) {
                        if (!inRange(address, 1, memoryBytes)) {
                            trapCause = Rv32i.TRAP_STORE_ACCESS_FAULT;
                            trapValue = address;
                        } else {
                            storeByte(memory, memoryBase, address, right);
                        }
                    } else if (funct3 == 1) {
                        if ((address & 1) != 0) {
                            trapCause = Rv32i.TRAP_STORE_ADDRESS_MISALIGNED;
                            trapValue = address;
                        } else if (!inRange(address, 2, memoryBytes)) {
                            trapCause = Rv32i.TRAP_STORE_ACCESS_FAULT;
                            trapValue = address;
                        } else {
                            storeHalf(memory, memoryBase, address, right);
                        }
                    } else if (funct3 == 2) {
                        if ((address & 3) != 0) {
                            trapCause = Rv32i.TRAP_STORE_ADDRESS_MISALIGNED;
                            trapValue = address;
                        } else if (!inRange(address, 4, memoryBytes)) {
                            trapCause = Rv32i.TRAP_STORE_ACCESS_FAULT;
                            trapValue = address;
                        } else {
                            memory.set(memoryBase + (address >>> 2), right);
                        }
                    } else {
                        legal = false;
                    }
                    break;
                }
                case 0x13:
                    legal = executeImmediate(registers, registerBase, rd, left, instruction, funct3, funct7);
                    break;
                case 0x33:
                    legal = executeRegister(registers, registerBase, rd, left, right, funct3, funct7);
                    break;
                case 0x0F:
                    if (funct3 != 0 && funct3 != 1) {
                        legal = false;
                    }
                    break;
                case 0x73:
                    if (instruction == 0x0000_0073) {
                        trapCause = Rv32i.TRAP_ENVIRONMENT_CALL;
                    } else if (instruction == 0x0010_0073) {
                        trapCause = Rv32i.TRAP_BREAKPOINT;
                        trapValue = pc;
                    } else {
                        legal = false;
                    }
                    break;
                default:
                    legal = false;
                    break;
            }

            if (!legal) {
                trap(state, stateBase, pc, Rv32i.TRAP_ILLEGAL_INSTRUCTION, instruction);
                return;
            }
            if (trapCause != Rv32i.TRAP_NONE) {
                trap(state, stateBase, pc, trapCause, trapValue);
                return;
            }

            retired++;
            pc = nextPc;
            registers.set(registerBase, 0);
            state.set(stateBase + Rv32i.STATE_PC, pc);
            state.set(stateBase + Rv32i.STATE_RETIRED, retired);
        }

        state.set(stateBase + Rv32i.STATE_PC, pc);
        state.set(stateBase + Rv32i.STATE_RETIRED, retired);
        state.set(stateBase + Rv32i.STATE_STATUS, Rv32i.STATUS_YIELDED);
        registers.set(registerBase, 0);
    }

    private static boolean executeImmediate(IntArray registers, int base, int rd, int left,
            int instruction, int funct3, int funct7) {
        int immediate = immediateI(instruction);
        int shift = instruction >>> 20 & 0x1F;
        if (funct3 == 0) {
            writeRegister(registers, base, rd, left + immediate);
            return true;
        }
        if (funct3 == 2) {
            writeRegister(registers, base, rd, left < immediate ? 1 : 0);
            return true;
        }
        if (funct3 == 3) {
            writeRegister(registers, base, rd, unsignedLessThan(left, immediate) ? 1 : 0);
            return true;
        }
        if (funct3 == 4) {
            writeRegister(registers, base, rd, left ^ immediate);
            return true;
        }
        if (funct3 == 6) {
            writeRegister(registers, base, rd, left | immediate);
            return true;
        }
        if (funct3 == 7) {
            writeRegister(registers, base, rd, left & immediate);
            return true;
        }
        if (funct3 == 1) {
            if (funct7 != 0) {
                return false;
            }
            writeRegister(registers, base, rd, left << shift);
            return true;
        }
        if (funct3 == 5) {
            if (funct7 == 0) {
                writeRegister(registers, base, rd, left >>> shift);
                return true;
            }
            if (funct7 == 0x20) {
                writeRegister(registers, base, rd, left >> shift);
                return true;
            }
            return false;
        }
        return false;
    }

    private static boolean executeRegister(IntArray registers, int base, int rd, int left, int right,
            int funct3, int funct7) {
        int value;
        if (funct7 == 0x01) {
            if (funct3 == 0) {
                value = left * right;
            } else if (funct3 == 1) {
                value = (int) (((long) left * (long) right) >> 32);
            } else if (funct3 == 2) {
                value = (int) (((long) left * (right & 0xFFFF_FFFFL)) >> 32);
            } else if (funct3 == 3) {
                value = (int) (((left & 0xFFFF_FFFFL) * (right & 0xFFFF_FFFFL)) >>> 32);
            } else if (funct3 == 4) {
                value = divideSigned(left, right);
            } else if (funct3 == 5) {
                value = divideUnsigned(left, right);
            } else if (funct3 == 6) {
                value = remainderSigned(left, right);
            } else if (funct3 == 7) {
                value = remainderUnsigned(left, right);
            } else {
                return false;
            }
            writeRegister(registers, base, rd, value);
            return true;
        }

        if (funct3 == 0) {
            if (funct7 == 0) {
                value = left + right;
            } else if (funct7 == 0x20) {
                value = left - right;
            } else {
                return false;
            }
        } else if (funct3 == 1) {
            if (funct7 != 0) {
                return false;
            }
            value = left << (right & 0x1F);
        } else if (funct3 == 2) {
            if (funct7 != 0) {
                return false;
            }
            value = left < right ? 1 : 0;
        } else if (funct3 == 3) {
            if (funct7 != 0) {
                return false;
            }
            value = unsignedLessThan(left, right) ? 1 : 0;
        } else if (funct3 == 4) {
            if (funct7 != 0) {
                return false;
            }
            value = left ^ right;
        } else if (funct3 == 5) {
            if (funct7 == 0) {
                value = left >>> (right & 0x1F);
            } else if (funct7 == 0x20) {
                value = left >> (right & 0x1F);
            } else {
                return false;
            }
        } else if (funct3 == 6) {
            if (funct7 != 0) {
                return false;
            }
            value = left | right;
        } else if (funct3 == 7) {
            if (funct7 != 0) {
                return false;
            }
            value = left & right;
        } else {
            return false;
        }
        writeRegister(registers, base, rd, value);
        return true;
    }

    private static int divideSigned(int dividend, int divisor) {
        if (divisor == 0) {
            return -1;
        }
        if (dividend == Integer.MIN_VALUE && divisor == -1) {
            return Integer.MIN_VALUE;
        }
        return dividend / divisor;
    }

    private static int remainderSigned(int dividend, int divisor) {
        if (divisor == 0) {
            return dividend;
        }
        if (dividend == Integer.MIN_VALUE && divisor == -1) {
            return 0;
        }
        return dividend % divisor;
    }

    private static int divideUnsigned(int dividend, int divisor) {
        if (divisor == 0) {
            return -1;
        }
        long left = dividend & 0xFFFF_FFFFL;
        long right = divisor & 0xFFFF_FFFFL;
        return (int) (left / right);
    }

    private static int remainderUnsigned(int dividend, int divisor) {
        if (divisor == 0) {
            return dividend;
        }
        long left = dividend & 0xFFFF_FFFFL;
        long right = divisor & 0xFFFF_FFFFL;
        return (int) (left % right);
    }

    private static int readRegister(IntArray registers, int base, int register) {
        return register == 0 ? 0 : registers.get(base + register);
    }

    private static void writeRegister(IntArray registers, int base, int register, int value) {
        if (register != 0) {
            registers.set(base + register, value);
        }
    }

    private static boolean inRange(int address, int width, int memoryBytes) {
        return address >= 0 && address <= memoryBytes - width;
    }

    private static int loadByte(IntArray memory, int base, int address) {
        int word = memory.get(base + (address >>> 2));
        return word >>> ((address & 3) << 3) & 0xFF;
    }

    private static int loadHalf(IntArray memory, int base, int address) {
        int word = memory.get(base + (address >>> 2));
        return word >>> ((address & 2) << 3) & 0xFFFF;
    }

    private static void storeByte(IntArray memory, int base, int address, int value) {
        int index = base + (address >>> 2);
        int shift = (address & 3) << 3;
        int mask = 0xFF << shift;
        int word = memory.get(index);
        memory.set(index, word & ~mask | (value & 0xFF) << shift);
    }

    private static void storeHalf(IntArray memory, int base, int address, int value) {
        int index = base + (address >>> 2);
        int shift = (address & 2) << 3;
        int mask = 0xFFFF << shift;
        int word = memory.get(index);
        memory.set(index, word & ~mask | (value & 0xFFFF) << shift);
    }

    private static void trap(IntArray state, int stateBase, int pc, int cause, int value) {
        state.set(stateBase + Rv32i.STATE_PC, pc);
        state.set(stateBase + Rv32i.STATE_STATUS, Rv32i.STATUS_TRAPPED);
        state.set(stateBase + Rv32i.STATE_TRAP_CAUSE, cause);
        state.set(stateBase + Rv32i.STATE_TRAP_VALUE, value);
    }

    private static int signExtend(int value, int bits) {
        int shift = 32 - bits;
        return value << shift >> shift;
    }

    private static int immediateI(int instruction) {
        return instruction >> 20;
    }

    private static int immediateS(int instruction) {
        int value = ((instruction >>> 25) << 5) | ((instruction >>> 7) & 0x1F);
        return signExtend(value, 12);
    }

    private static int immediateB(int instruction) {
        int value = ((instruction >>> 31) << 12)
                | (((instruction >>> 7) & 1) << 11)
                | (((instruction >>> 25) & 0x3F) << 5)
                | (((instruction >>> 8) & 0xF) << 1);
        return signExtend(value, 13);
    }

    private static int immediateU(int instruction) {
        return instruction & 0xFFFFF000;
    }

    private static int immediateJ(int instruction) {
        int value = ((instruction >>> 31) << 20)
                | (((instruction >>> 12) & 0xFF) << 12)
                | (((instruction >>> 20) & 1) << 11)
                | (((instruction >>> 21) & 0x3FF) << 1);
        return signExtend(value, 21);
    }

    private static boolean unsignedLessThan(int left, int right) {
        return (left ^ Integer.MIN_VALUE) < (right ^ Integer.MIN_VALUE);
    }
}
