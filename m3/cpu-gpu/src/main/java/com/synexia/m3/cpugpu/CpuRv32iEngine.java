/* SPDX-License-Identifier: Apache-2.0 */
package com.synexia.m3.cpugpu;

/**
 * Independent heap-based RV32IM interpreter used as the correctness oracle for GPU execution.
 */
public final class CpuRv32iEngine {

    public void runSlice(Rv32iBatch batch, int instructionBudget) {
        if (instructionBudget <= 0) {
            throw new IllegalArgumentException("instructionBudget must be > 0");
        }
        for (int core = 0; core < batch.coreCount(); core++) {
            runCore(batch, core, instructionBudget);
        }
    }

    private static void runCore(Rv32iBatch batch, int core, int instructionBudget) {
        int[] memory = batch.rawMemory();
        int[] registers = batch.rawRegisters();
        int[] state = batch.rawState();
        int memoryBase = batch.memoryBase(core);
        int registerBase = batch.registerBase(core);
        int stateBase = batch.stateBase(core);
        int memoryBytes = batch.memoryBytesPerCore();

        int status = state[stateBase + Rv32i.STATE_STATUS];
        if (status == Rv32i.STATUS_HALTED || status == Rv32i.STATUS_TRAPPED) {
            return;
        }
        state[stateBase + Rv32i.STATE_STATUS] = Rv32i.STATUS_READY;

        int pc = state[stateBase + Rv32i.STATE_PC];
        int retired = state[stateBase + Rv32i.STATE_RETIRED];

        for (int step = 0; step < instructionBudget; step++) {
            if ((pc & 3) != 0) {
                trap(state, stateBase, pc, Rv32i.TRAP_INSTRUCTION_ADDRESS_MISALIGNED, pc);
                return;
            }
            if (!inRange(pc, 4, memoryBytes)) {
                trap(state, stateBase, pc, Rv32i.TRAP_INSTRUCTION_ACCESS_FAULT, pc);
                return;
            }

            int instruction = memory[memoryBase + (pc >>> 2)];
            if (instruction == Rv32i.XM3_HALT) {
                retired++;
                pc += 4;
                state[stateBase + Rv32i.STATE_PC] = pc;
                state[stateBase + Rv32i.STATE_RETIRED] = retired;
                state[stateBase + Rv32i.STATE_STATUS] = Rv32i.STATUS_HALTED;
                state[stateBase + Rv32i.STATE_EXIT_CODE] = readRegister(registers, registerBase, 10);
                registers[registerBase] = 0;
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
                case 0x37 -> writeRegister(registers, registerBase, rd, Rv32i.immediateU(instruction));
                case 0x17 -> writeRegister(registers, registerBase, rd, pc + Rv32i.immediateU(instruction));
                case 0x6F -> {
                    int target = pc + Rv32i.immediateJ(instruction);
                    if ((target & 3) != 0) {
                        trapCause = Rv32i.TRAP_INSTRUCTION_ADDRESS_MISALIGNED;
                        trapValue = target;
                    } else {
                        writeRegister(registers, registerBase, rd, nextPc);
                        nextPc = target;
                    }
                }
                case 0x67 -> {
                    if (funct3 != 0) {
                        legal = false;
                    } else {
                        int target = (left + Rv32i.immediateI(instruction)) & ~1;
                        if ((target & 3) != 0) {
                            trapCause = Rv32i.TRAP_INSTRUCTION_ADDRESS_MISALIGNED;
                            trapValue = target;
                        } else {
                            writeRegister(registers, registerBase, rd, nextPc);
                            nextPc = target;
                        }
                    }
                }
                case 0x63 -> {
                    boolean take;
                    switch (funct3) {
                        case 0 -> take = left == right;
                        case 1 -> take = left != right;
                        case 4 -> take = left < right;
                        case 5 -> take = left >= right;
                        case 6 -> take = Rv32i.unsignedLessThan(left, right);
                        case 7 -> take = !Rv32i.unsignedLessThan(left, right);
                        default -> {
                            take = false;
                            legal = false;
                        }
                    }
                    if (legal && take) {
                        int target = pc + Rv32i.immediateB(instruction);
                        if ((target & 3) != 0) {
                            trapCause = Rv32i.TRAP_INSTRUCTION_ADDRESS_MISALIGNED;
                            trapValue = target;
                        } else {
                            nextPc = target;
                        }
                    }
                }
                case 0x03 -> {
                    int address = left + Rv32i.immediateI(instruction);
                    switch (funct3) {
                        case 0 -> {
                            if (!inRange(address, 1, memoryBytes)) {
                                trapCause = Rv32i.TRAP_LOAD_ACCESS_FAULT;
                                trapValue = address;
                            } else {
                                writeRegister(registers, registerBase, rd,
                                        Rv32i.signExtend(loadByte(memory, memoryBase, address), 8));
                            }
                        }
                        case 1 -> {
                            if ((address & 1) != 0) {
                                trapCause = Rv32i.TRAP_LOAD_ADDRESS_MISALIGNED;
                                trapValue = address;
                            } else if (!inRange(address, 2, memoryBytes)) {
                                trapCause = Rv32i.TRAP_LOAD_ACCESS_FAULT;
                                trapValue = address;
                            } else {
                                writeRegister(registers, registerBase, rd,
                                        Rv32i.signExtend(loadHalf(memory, memoryBase, address), 16));
                            }
                        }
                        case 2 -> {
                            if ((address & 3) != 0) {
                                trapCause = Rv32i.TRAP_LOAD_ADDRESS_MISALIGNED;
                                trapValue = address;
                            } else if (!inRange(address, 4, memoryBytes)) {
                                trapCause = Rv32i.TRAP_LOAD_ACCESS_FAULT;
                                trapValue = address;
                            } else {
                                writeRegister(registers, registerBase, rd, memory[memoryBase + (address >>> 2)]);
                            }
                        }
                        case 4 -> {
                            if (!inRange(address, 1, memoryBytes)) {
                                trapCause = Rv32i.TRAP_LOAD_ACCESS_FAULT;
                                trapValue = address;
                            } else {
                                writeRegister(registers, registerBase, rd, loadByte(memory, memoryBase, address));
                            }
                        }
                        case 5 -> {
                            if ((address & 1) != 0) {
                                trapCause = Rv32i.TRAP_LOAD_ADDRESS_MISALIGNED;
                                trapValue = address;
                            } else if (!inRange(address, 2, memoryBytes)) {
                                trapCause = Rv32i.TRAP_LOAD_ACCESS_FAULT;
                                trapValue = address;
                            } else {
                                writeRegister(registers, registerBase, rd, loadHalf(memory, memoryBase, address));
                            }
                        }
                        default -> legal = false;
                    }
                }
                case 0x23 -> {
                    int address = left + Rv32i.immediateS(instruction);
                    switch (funct3) {
                        case 0 -> {
                            if (!inRange(address, 1, memoryBytes)) {
                                trapCause = Rv32i.TRAP_STORE_ACCESS_FAULT;
                                trapValue = address;
                            } else {
                                storeByte(memory, memoryBase, address, right);
                            }
                        }
                        case 1 -> {
                            if ((address & 1) != 0) {
                                trapCause = Rv32i.TRAP_STORE_ADDRESS_MISALIGNED;
                                trapValue = address;
                            } else if (!inRange(address, 2, memoryBytes)) {
                                trapCause = Rv32i.TRAP_STORE_ACCESS_FAULT;
                                trapValue = address;
                            } else {
                                storeHalf(memory, memoryBase, address, right);
                            }
                        }
                        case 2 -> {
                            if ((address & 3) != 0) {
                                trapCause = Rv32i.TRAP_STORE_ADDRESS_MISALIGNED;
                                trapValue = address;
                            } else if (!inRange(address, 4, memoryBytes)) {
                                trapCause = Rv32i.TRAP_STORE_ACCESS_FAULT;
                                trapValue = address;
                            } else {
                                memory[memoryBase + (address >>> 2)] = right;
                            }
                        }
                        default -> legal = false;
                    }
                }
                case 0x13 -> legal = executeImmediate(registers, registerBase, rd, left, instruction, funct3, funct7);
                case 0x33 -> legal = executeRegister(registers, registerBase, rd, left, right, funct3, funct7);
                case 0x0F -> {
                    // FENCE is a no-op because each virtual core has private coherent RAM.
                    // FENCE.I is also a no-op: fetches read the same IntArray backing store directly.
                    if (funct3 != 0 && funct3 != 1) {
                        legal = false;
                    }
                }
                case 0x73 -> {
                    if (instruction == 0x0000_0073) {
                        trapCause = Rv32i.TRAP_ENVIRONMENT_CALL;
                        trapValue = 0;
                    } else if (instruction == 0x0010_0073) {
                        trapCause = Rv32i.TRAP_BREAKPOINT;
                        trapValue = pc;
                    } else {
                        legal = false;
                    }
                }
                default -> legal = false;
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
            registers[registerBase] = 0;
            state[stateBase + Rv32i.STATE_PC] = pc;
            state[stateBase + Rv32i.STATE_RETIRED] = retired;
        }

        state[stateBase + Rv32i.STATE_PC] = pc;
        state[stateBase + Rv32i.STATE_RETIRED] = retired;
        state[stateBase + Rv32i.STATE_STATUS] = Rv32i.STATUS_YIELDED;
        registers[registerBase] = 0;
    }

    private static boolean executeImmediate(int[] registers, int base, int rd, int left,
            int instruction, int funct3, int funct7) {
        int immediate = Rv32i.immediateI(instruction);
        int shift = instruction >>> 20 & 0x1F;
        return switch (funct3) {
            case 0 -> {
                writeRegister(registers, base, rd, left + immediate);
                yield true;
            }
            case 2 -> {
                writeRegister(registers, base, rd, left < immediate ? 1 : 0);
                yield true;
            }
            case 3 -> {
                writeRegister(registers, base, rd, Rv32i.unsignedLessThan(left, immediate) ? 1 : 0);
                yield true;
            }
            case 4 -> {
                writeRegister(registers, base, rd, left ^ immediate);
                yield true;
            }
            case 6 -> {
                writeRegister(registers, base, rd, left | immediate);
                yield true;
            }
            case 7 -> {
                writeRegister(registers, base, rd, left & immediate);
                yield true;
            }
            case 1 -> {
                if (funct7 != 0) {
                    yield false;
                }
                writeRegister(registers, base, rd, left << shift);
                yield true;
            }
            case 5 -> {
                if (funct7 == 0) {
                    writeRegister(registers, base, rd, left >>> shift);
                    yield true;
                }
                if (funct7 == 0x20) {
                    writeRegister(registers, base, rd, left >> shift);
                    yield true;
                }
                yield false;
            }
            default -> false;
        };
    }

    private static boolean executeRegister(int[] registers, int base, int rd, int left, int right,
            int funct3, int funct7) {
        if (funct7 == 0x01) {
            int value;
            switch (funct3) {
                case 0 -> value = left * right;
                case 1 -> value = (int) (((long) left * (long) right) >> 32);
                case 2 -> value = (int) (((long) left * (right & 0xFFFF_FFFFL)) >> 32);
                case 3 -> value = (int) (((left & 0xFFFF_FFFFL) * (right & 0xFFFF_FFFFL)) >>> 32);
                case 4 -> value = divideSigned(left, right);
                case 5 -> value = divideUnsigned(left, right);
                case 6 -> value = remainderSigned(left, right);
                case 7 -> value = remainderUnsigned(left, right);
                default -> {
                    return false;
                }
            }
            writeRegister(registers, base, rd, value);
            return true;
        }

        int value;
        switch (funct3) {
            case 0 -> {
                if (funct7 == 0) {
                    value = left + right;
                } else if (funct7 == 0x20) {
                    value = left - right;
                } else {
                    return false;
                }
            }
            case 1 -> {
                if (funct7 != 0) {
                    return false;
                }
                value = left << (right & 0x1F);
            }
            case 2 -> {
                if (funct7 != 0) {
                    return false;
                }
                value = left < right ? 1 : 0;
            }
            case 3 -> {
                if (funct7 != 0) {
                    return false;
                }
                value = Rv32i.unsignedLessThan(left, right) ? 1 : 0;
            }
            case 4 -> {
                if (funct7 != 0) {
                    return false;
                }
                value = left ^ right;
            }
            case 5 -> {
                if (funct7 == 0) {
                    value = left >>> (right & 0x1F);
                } else if (funct7 == 0x20) {
                    value = left >> (right & 0x1F);
                } else {
                    return false;
                }
            }
            case 6 -> {
                if (funct7 != 0) {
                    return false;
                }
                value = left | right;
            }
            case 7 -> {
                if (funct7 != 0) {
                    return false;
                }
                value = left & right;
            }
            default -> {
                return false;
            }
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

    private static int readRegister(int[] registers, int base, int register) {
        return register == 0 ? 0 : registers[base + register];
    }

    private static void writeRegister(int[] registers, int base, int register, int value) {
        if (register != 0) {
            registers[base + register] = value;
        }
    }

    private static boolean inRange(int address, int width, int memoryBytes) {
        return address >= 0 && address <= memoryBytes - width;
    }

    private static int loadByte(int[] memory, int base, int address) {
        int word = memory[base + (address >>> 2)];
        return word >>> ((address & 3) << 3) & 0xFF;
    }

    private static int loadHalf(int[] memory, int base, int address) {
        int word = memory[base + (address >>> 2)];
        return word >>> ((address & 2) << 3) & 0xFFFF;
    }

    private static void storeByte(int[] memory, int base, int address, int value) {
        int index = base + (address >>> 2);
        int shift = (address & 3) << 3;
        int mask = 0xFF << shift;
        memory[index] = memory[index] & ~mask | (value & 0xFF) << shift;
    }

    private static void storeHalf(int[] memory, int base, int address, int value) {
        int index = base + (address >>> 2);
        int shift = (address & 2) << 3;
        int mask = 0xFFFF << shift;
        memory[index] = memory[index] & ~mask | (value & 0xFFFF) << shift;
    }

    private static void trap(int[] state, int stateBase, int pc, int cause, int value) {
        state[stateBase + Rv32i.STATE_PC] = pc;
        state[stateBase + Rv32i.STATE_STATUS] = Rv32i.STATUS_TRAPPED;
        state[stateBase + Rv32i.STATE_TRAP_CAUSE] = cause;
        state[stateBase + Rv32i.STATE_TRAP_VALUE] = value;
    }
}
