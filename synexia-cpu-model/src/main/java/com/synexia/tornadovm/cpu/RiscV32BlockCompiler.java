/*
 * Copyright 2026 Synexia contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.synexia.tornadovm.cpu;

import uk.ac.manchester.tornado.api.types.arrays.Int8Array;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;
import uk.ac.manchester.tornado.api.types.arrays.LongArray;

/**
 * Host-side mechanical compiler from canonical RV32 instructions to packed basic-block micro-ops.
 *
 * <p>The compiler deliberately handles only side-effect semantics that the compiled kernel
 * implements directly. System/CSR, FENCE and AMO instructions form tier boundaries and fall back
 * to the full architectural interpreter.
 *
 * <p>Safe adjacent instruction patterns are fused into superinstructions. Fusion never crosses a
 * basic-block leader, so every externally reachable guest instruction remains a valid entry point.
 */
public final class RiscV32BlockCompiler {

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

    private RiscV32BlockCompiler() {
    }

    public static RiscV32BlockProgram compile(RiscV32Machine machine, int maxBlockInstructions) {
        if (machine == null) {
            throw new NullPointerException("machine");
        }
        if (!machine.hasCodeCache()) {
            throw new IllegalStateException("buildCodeCache must be called before buildBlockCache");
        }
        if (maxBlockInstructions <= 0 || maxBlockInstructions > 0xffff) {
            throw new IllegalArgumentException("maxBlockInstructions must be in [1,65535]");
        }

        IntArray instructions = machine.decodedInstructions();
        Int8Array lengths = machine.decodedInstructionLengths();
        int slots = lengths.getSize();
        boolean[] leaders = new boolean[slots];
        leaders[0] = true;

        // First pass: establish every externally reachable basic-block entry before fusion.
        int pc = machine.codeCacheBase();
        for (int slot = 0; slot < slots;) {
            int length = lengths.get(slot) & 0xff;
            if (length == 0) {
                slot++;
                pc += 2;
                continue;
            }

            int instruction = instructions.get(slot);
            int kind = microOpKind(instruction);
            int nextPc = pc + length;
            int nextSlot = slot + (length >>> 1);

            if (kind == RiscV32MicroOp.INVALID) {
                markLeader(leaders, nextSlot);
            } else if (kind >= RiscV32MicroOp.BEQ && kind <= RiscV32MicroOp.BGEU) {
                markLeader(leaders, slotForPc(machine, pc + immediateB(instruction)));
                markLeader(leaders, nextSlot);
            } else if (kind == RiscV32MicroOp.JAL) {
                markLeader(leaders, slotForPc(machine, pc + immediateJ(instruction)));
                markLeader(leaders, nextSlot);
            } else if (kind == RiscV32MicroOp.JALR) {
                markLeader(leaders, nextSlot);
            } else if (RiscV32MicroOp.terminatesBlock(kind)) {
                markLeader(leaders, nextSlot);
            }

            pc = nextPc;
            slot = nextSlot;
        }

        long[] tempOps = new long[slots];
        int[] tempBlockSlot = new int[slots];
        int[] tempBlockFirst = new int[slots];
        int[] tempBlockMicroOps = new int[slots];
        int[] tempBlockGuestInstructions = new int[slots];
        int blockCount = 0;
        int operationCount = 0;

        // Second pass: compile maximal supported blocks, fusing only within leader boundaries.
        for (int startSlot = 0; startSlot < slots; startSlot++) {
            if (!leaders[startSlot] || (lengths.get(startSlot) & 0xff) == 0) {
                continue;
            }

            int currentSlot = startSlot;
            int guestInstructionCount = 0;
            int microOpCount = 0;
            int firstOperation = operationCount;

            while (currentSlot < slots && guestInstructionCount < maxBlockInstructions) {
                if (guestInstructionCount > 0 && leaders[currentSlot]) {
                    break;
                }

                int firstLength = lengths.get(currentSlot) & 0xff;
                if (firstLength == 0) {
                    break;
                }

                int firstInstruction = instructions.get(currentSlot);
                int firstKind = microOpKind(firstInstruction);
                if (firstKind == RiscV32MicroOp.INVALID) {
                    break;
                }

                int nextSlot = currentSlot + (firstLength >>> 1);
                long fused = 0;
                int secondLength = 0;

                if (guestInstructionCount + 2 <= maxBlockInstructions
                        && nextSlot < slots
                        && !leaders[nextSlot]) {
                    secondLength = lengths.get(nextSlot) & 0xff;
                    if (secondLength != 0) {
                        int secondInstruction = instructions.get(nextSlot);
                        fused = tryFuse(firstInstruction, firstLength,
                                secondInstruction, secondLength);
                    }
                }

                if (fused != 0) {
                    tempOps[operationCount++] = fused;
                    microOpCount++;
                    guestInstructionCount += RiscV32MicroOp.retiredInstructions(fused);
                    currentSlot += RiscV32MicroOp.instructionBytes(fused) >>> 1;
                    if (RiscV32MicroOp.terminatesBlock(RiscV32MicroOp.kind(fused))) {
                        break;
                    }
                    continue;
                }

                long op = compileInstruction(firstInstruction, firstLength);
                if (op == 0) {
                    break;
                }

                tempOps[operationCount++] = op;
                microOpCount++;
                guestInstructionCount++;
                currentSlot = nextSlot;

                if (RiscV32MicroOp.terminatesBlock(firstKind)) {
                    break;
                }
            }

            if (guestInstructionCount > 0) {
                tempBlockSlot[blockCount] = startSlot;
                tempBlockFirst[blockCount] = firstOperation;
                tempBlockMicroOps[blockCount] = microOpCount;
                tempBlockGuestInstructions[blockCount] = guestInstructionCount;
                blockCount++;
            }
        }

        IntArray blockBySlot = new IntArray(Math.max(1, slots));
        LongArray descriptors = new LongArray(Math.max(1, blockCount));
        Int8Array valid = new Int8Array(Math.max(1, blockCount));
        LongArray microOps = new LongArray(Math.max(1, operationCount));

        for (int block = 0; block < blockCount; block++) {
            blockBySlot.set(tempBlockSlot[block], block + 1);
            descriptors.set(block, RiscV32BlockProgram.descriptor(
                    tempBlockFirst[block],
                    tempBlockMicroOps[block],
                    tempBlockGuestInstructions[block]));
            valid.set(block, (byte) 1);
        }
        for (int op = 0; op < operationCount; op++) {
            microOps.set(op, tempOps[op]);
        }

        return new RiscV32BlockProgram(
                blockBySlot, descriptors, valid, microOps, blockCount, operationCount);
    }

    private static long tryFuse(int firstInstruction, int firstLength,
            int secondInstruction, int secondLength) {
        int firstKind = microOpKind(firstInstruction);
        int secondKind = microOpKind(secondInstruction);
        if (secondKind == RiscV32MicroOp.INVALID) {
            return 0;
        }

        int firstRd = (firstInstruction >>> 7) & 0x1f;
        int firstRs1 = (firstInstruction >>> 15) & 0x1f;
        int firstRs2 = (firstInstruction >>> 20) & 0x1f;
        int secondRd = (secondInstruction >>> 7) & 0x1f;
        int secondRs1 = (secondInstruction >>> 15) & 0x1f;
        int secondRs2 = (secondInstruction >>> 20) & 0x1f;
        int totalBytes = firstLength + secondLength;

        // LUI rd,upper ; ADDI rd,rd,lo -> materialize the final constant directly.
        if (firstKind == RiscV32MicroOp.LUI
                && secondKind == RiscV32MicroOp.ADDI
                && firstRd != 0
                && secondRd == firstRd
                && secondRs1 == firstRd) {
            int value = (firstInstruction & 0xfffff000) + immediateI(secondInstruction);
            return RiscV32MicroOp.packSuper(
                    RiscV32MicroOp.LOAD_CONST, firstRd, 0, 0, value,
                    totalBytes, 2, firstLength);
        }

        // ADDI rd,src,a ; ADDI rd,rd,b -> one add with a+b modulo 2^32.
        if (firstKind == RiscV32MicroOp.ADDI
                && secondKind == RiscV32MicroOp.ADDI
                && firstRd != 0
                && secondRd == firstRd
                && secondRs1 == firstRd) {
            int combined = immediateI(firstInstruction) + immediateI(secondInstruction);
            return RiscV32MicroOp.packSuper(
                    RiscV32MicroOp.ADDI_CHAIN, firstRd, firstRs1, 0, combined,
                    totalBytes, 2, firstLength);
        }

        // MUL rd,a,b ; ADD rd,rd,c (or ADD rd,c,rd) -> product plus third source.
        if (firstKind == RiscV32MicroOp.MUL
                && secondKind == RiscV32MicroOp.ADD
                && firstRd != 0
                && secondRd == firstRd
                && (secondRs1 == firstRd || secondRs2 == firstRd)) {
            int other = secondRs1 == firstRd ? secondRs2 : secondRs1;
            return RiscV32MicroOp.packSuper(
                    RiscV32MicroOp.MUL_ADD, firstRd, firstRs1, firstRs2, other,
                    totalBytes, 2, firstLength);
        }

        // ADDI rd,src,delta ; BRANCH rd,other,target. This is the common counted-loop shape.
        if (firstKind == RiscV32MicroOp.ADDI
                && firstRd != 0
                && secondKind >= RiscV32MicroOp.BEQ
                && secondKind <= RiscV32MicroOp.BGEU
                && secondRs1 == firstRd) {
            int payload = RiscV32MicroOp.packSigned16Pair(
                    immediateI(firstInstruction), immediateB(secondInstruction));
            int fusedKind = RiscV32MicroOp.ADDI_BEQ
                    + (secondKind - RiscV32MicroOp.BEQ);
            return RiscV32MicroOp.packSuper(
                    fusedKind, firstRd, firstRs1, secondRs2, payload,
                    totalBytes, 2, firstLength);
        }

        return 0;
    }

    private static void markLeader(boolean[] leaders, int slot) {
        if (slot >= 0 && slot < leaders.length) {
            leaders[slot] = true;
        }
    }

    private static int slotForPc(RiscV32Machine machine, int pc) {
        if ((pc & 1) != 0 || pc < machine.codeCacheBase() || pc >= machine.codeCacheEnd()) {
            return -1;
        }
        return (pc - machine.codeCacheBase()) >>> 1;
    }

    private static long compileInstruction(int instruction, int length) {
        int kind = microOpKind(instruction);
        if (kind == RiscV32MicroOp.INVALID) {
            return 0;
        }

        int rd = (instruction >>> 7) & 0x1f;
        int rs1 = (instruction >>> 15) & 0x1f;
        int rs2 = (instruction >>> 20) & 0x1f;
        int immediate = 0;

        switch (instruction & 0x7f) {
            case OP_LUI:
            case OP_AUIPC:
                immediate = instruction & 0xfffff000;
                break;
            case OP_JAL:
                immediate = immediateJ(instruction);
                break;
            case OP_JALR:
            case OP_LOAD:
            case OP_IMM:
                immediate = immediateI(instruction);
                break;
            case OP_BRANCH:
                immediate = immediateB(instruction);
                break;
            case OP_STORE:
                immediate = immediateS(instruction);
                break;
            case OP_SYSTEM:
                immediate = instruction >>> 20;
                break;
            default:
                break;
        }

        if (kind == RiscV32MicroOp.SLLI
                || kind == RiscV32MicroOp.SRLI
                || kind == RiscV32MicroOp.SRAI) {
            immediate = (instruction >>> 20) & 0x1f;
        }

        return RiscV32MicroOp.pack(kind, rd, rs1, rs2, immediate, length);
    }

    private static int microOpKind(int instruction) {
        int opcode = instruction & 0x7f;
        int funct3 = (instruction >>> 12) & 7;
        int funct7 = instruction >>> 25;

        switch (opcode) {
            case OP_LUI:
                return RiscV32MicroOp.LUI;
            case OP_AUIPC:
                return RiscV32MicroOp.AUIPC;
            case OP_JAL:
                return RiscV32MicroOp.JAL;
            case OP_JALR:
                return funct3 == 0 ? RiscV32MicroOp.JALR : RiscV32MicroOp.INVALID;
            case OP_BRANCH:
                switch (funct3) {
                    case 0:
                        return RiscV32MicroOp.BEQ;
                    case 1:
                        return RiscV32MicroOp.BNE;
                    case 4:
                        return RiscV32MicroOp.BLT;
                    case 5:
                        return RiscV32MicroOp.BGE;
                    case 6:
                        return RiscV32MicroOp.BLTU;
                    case 7:
                        return RiscV32MicroOp.BGEU;
                    default:
                        return RiscV32MicroOp.INVALID;
                }
            case OP_LOAD:
                switch (funct3) {
                    case 0:
                        return RiscV32MicroOp.LB;
                    case 1:
                        return RiscV32MicroOp.LH;
                    case 2:
                        return RiscV32MicroOp.LW;
                    case 4:
                        return RiscV32MicroOp.LBU;
                    case 5:
                        return RiscV32MicroOp.LHU;
                    default:
                        return RiscV32MicroOp.INVALID;
                }
            case OP_STORE:
                switch (funct3) {
                    case 0:
                        return RiscV32MicroOp.SB;
                    case 1:
                        return RiscV32MicroOp.SH;
                    case 2:
                        return RiscV32MicroOp.SW;
                    default:
                        return RiscV32MicroOp.INVALID;
                }
            case OP_MISC_MEM:
                return (funct3 == 0 || funct3 == 1)
                        ? RiscV32MicroOp.FENCE
                        : RiscV32MicroOp.INVALID;

            case OP_AMO:
                if (funct3 != 2) {
                    return RiscV32MicroOp.INVALID;
                }
                switch (instruction >>> 27) {
                    case 0x02:
                        return ((instruction >>> 20) & 0x1f) == 0
                                ? RiscV32MicroOp.LR_W
                                : RiscV32MicroOp.INVALID;
                    case 0x03:
                        return RiscV32MicroOp.SC_W;
                    case 0x01:
                        return RiscV32MicroOp.AMOSWAP_W;
                    case 0x00:
                        return RiscV32MicroOp.AMOADD_W;
                    case 0x04:
                        return RiscV32MicroOp.AMOXOR_W;
                    case 0x0c:
                        return RiscV32MicroOp.AMOAND_W;
                    case 0x08:
                        return RiscV32MicroOp.AMOOR_W;
                    case 0x10:
                        return RiscV32MicroOp.AMOMIN_W;
                    case 0x14:
                        return RiscV32MicroOp.AMOMAX_W;
                    case 0x18:
                        return RiscV32MicroOp.AMOMINU_W;
                    case 0x1c:
                        return RiscV32MicroOp.AMOMAXU_W;
                    default:
                        return RiscV32MicroOp.INVALID;
                }

            case OP_IMM:
                switch (funct3) {
                    case 0:
                        return RiscV32MicroOp.ADDI;
                    case 2:
                        return RiscV32MicroOp.SLTI;
                    case 3:
                        return RiscV32MicroOp.SLTIU;
                    case 4:
                        return RiscV32MicroOp.XORI;
                    case 6:
                        return RiscV32MicroOp.ORI;
                    case 7:
                        return RiscV32MicroOp.ANDI;
                    case 1:
                        return funct7 == 0 ? RiscV32MicroOp.SLLI : RiscV32MicroOp.INVALID;
                    case 5:
                        if (funct7 == 0) {
                            return RiscV32MicroOp.SRLI;
                        }
                        return funct7 == 0x20 ? RiscV32MicroOp.SRAI : RiscV32MicroOp.INVALID;
                    default:
                        return RiscV32MicroOp.INVALID;
                }
            case OP:
                if (funct7 == 1) {
                    switch (funct3) {
                        case 0:
                            return RiscV32MicroOp.MUL;
                        case 1:
                            return RiscV32MicroOp.MULH;
                        case 2:
                            return RiscV32MicroOp.MULHSU;
                        case 3:
                            return RiscV32MicroOp.MULHU;
                        case 4:
                            return RiscV32MicroOp.DIV;
                        case 5:
                            return RiscV32MicroOp.DIVU;
                        case 6:
                            return RiscV32MicroOp.REM;
                        case 7:
                            return RiscV32MicroOp.REMU;
                        default:
                            return RiscV32MicroOp.INVALID;
                    }
                }
                switch (funct3) {
                    case 0:
                        if (funct7 == 0) {
                            return RiscV32MicroOp.ADD;
                        }
                        return funct7 == 0x20 ? RiscV32MicroOp.SUB : RiscV32MicroOp.INVALID;
                    case 1:
                        return funct7 == 0 ? RiscV32MicroOp.SLL : RiscV32MicroOp.INVALID;
                    case 2:
                        return funct7 == 0 ? RiscV32MicroOp.SLT : RiscV32MicroOp.INVALID;
                    case 3:
                        return funct7 == 0 ? RiscV32MicroOp.SLTU : RiscV32MicroOp.INVALID;
                    case 4:
                        return funct7 == 0 ? RiscV32MicroOp.XOR : RiscV32MicroOp.INVALID;
                    case 5:
                        if (funct7 == 0) {
                            return RiscV32MicroOp.SRL;
                        }
                        return funct7 == 0x20 ? RiscV32MicroOp.SRA : RiscV32MicroOp.INVALID;
                    case 6:
                        return funct7 == 0 ? RiscV32MicroOp.OR : RiscV32MicroOp.INVALID;
                    case 7:
                        return funct7 == 0 ? RiscV32MicroOp.AND : RiscV32MicroOp.INVALID;
                    default:
                        return RiscV32MicroOp.INVALID;
                }
            case OP_SYSTEM:
                if (funct3 == 0) {
                    if (instruction == 0x00000073) {
                        return RiscV32MicroOp.ECALL;
                    }
                    if (instruction == 0x00100073) {
                        return RiscV32MicroOp.EBREAK;
                    }
                    if (instruction == 0x30200073) {
                        return RiscV32MicroOp.MRET;
                    }
                    if (instruction == 0x10500073) {
                        return RiscV32MicroOp.WFI;
                    }
                    return RiscV32MicroOp.INVALID;
                }
                switch (funct3) {
                    case 1:
                        return RiscV32MicroOp.CSRRW;
                    case 2:
                        return RiscV32MicroOp.CSRRS;
                    case 3:
                        return RiscV32MicroOp.CSRRC;
                    case 5:
                        return RiscV32MicroOp.CSRRWI;
                    case 6:
                        return RiscV32MicroOp.CSRRSI;
                    case 7:
                        return RiscV32MicroOp.CSRRCI;
                    default:
                        return RiscV32MicroOp.INVALID;
                }

            default:
                return RiscV32MicroOp.INVALID;
        }
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
}
