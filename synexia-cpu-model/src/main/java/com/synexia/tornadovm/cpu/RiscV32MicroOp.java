/*
 * Copyright 2026 Synexia contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.synexia.tornadovm.cpu;

/**
 * Dense 64-bit micro-op format used by the RV32 compiled tier.
 *
 * <pre>
 * 63                    32 31    28 27    24 23  22    18 17    13 12     8 7       0
 * +-----------------------+--------+--------+--+--------+--------+---------+---------+
 * | immediate/payload(32) |retired |halfword|F | rs2(5) | rs1(5) | rd(5)   | kind(8) |
 * +-----------------------+--------+--------+--+--------+--------+---------+---------+
 * </pre>
 *
 * <p>{@code halfword} is the total guest byte length divided by two. {@code retired} is the
 * number of architectural guest instructions represented by the micro-op. {@code F} records the
 * first guest instruction's length (0=2 bytes, 1=4 bytes), which lets fused control-flow
 * superinstructions reconstruct the branch instruction PC exactly.
 */
public final class RiscV32MicroOp {

    public static final int INVALID = 0;

    public static final int LUI = 1;
    public static final int AUIPC = 2;
    public static final int JAL = 3;
    public static final int JALR = 4;

    public static final int BEQ = 5;
    public static final int BNE = 6;
    public static final int BLT = 7;
    public static final int BGE = 8;
    public static final int BLTU = 9;
    public static final int BGEU = 10;

    public static final int LB = 11;
    public static final int LH = 12;
    public static final int LW = 13;
    public static final int LBU = 14;
    public static final int LHU = 15;

    public static final int SB = 16;
    public static final int SH = 17;
    public static final int SW = 18;

    public static final int ADDI = 19;
    public static final int SLTI = 20;
    public static final int SLTIU = 21;
    public static final int XORI = 22;
    public static final int ORI = 23;
    public static final int ANDI = 24;
    public static final int SLLI = 25;
    public static final int SRLI = 26;
    public static final int SRAI = 27;

    public static final int ADD = 28;
    public static final int SUB = 29;
    public static final int SLL = 30;
    public static final int SLT = 31;
    public static final int SLTU = 32;
    public static final int XOR = 33;
    public static final int SRL = 34;
    public static final int SRA = 35;
    public static final int OR = 36;
    public static final int AND = 37;

    public static final int MUL = 38;
    public static final int MULH = 39;
    public static final int MULHSU = 40;
    public static final int MULHU = 41;
    public static final int DIV = 42;
    public static final int DIVU = 43;
    public static final int REM = 44;
    public static final int REMU = 45;

    // Two-guest-instruction superinstructions.
    public static final int LOAD_CONST = 46;
    public static final int ADDI_CHAIN = 47;
    public static final int MUL_ADD = 48;
    public static final int ADDI_BEQ = 49;
    public static final int ADDI_BNE = 50;
    public static final int ADDI_BLT = 51;
    public static final int ADDI_BGE = 52;
    public static final int ADDI_BLTU = 53;
    public static final int ADDI_BGEU = 54;

    public static final int FENCE = 55;
    public static final int LR_W = 56;
    public static final int SC_W = 57;
    public static final int AMOSWAP_W = 58;
    public static final int AMOADD_W = 59;
    public static final int AMOXOR_W = 60;
    public static final int AMOAND_W = 61;
    public static final int AMOOR_W = 62;
    public static final int AMOMIN_W = 63;
    public static final int AMOMAX_W = 64;
    public static final int AMOMINU_W = 65;
    public static final int AMOMAXU_W = 66;

    public static final int ECALL = 67;
    public static final int EBREAK = 68;
    public static final int MRET = 69;
    public static final int WFI = 70;
    public static final int CSRRW = 71;
    public static final int CSRRS = 72;
    public static final int CSRRC = 73;
    public static final int CSRRWI = 74;
    public static final int CSRRSI = 75;
    public static final int CSRRCI = 76;

    private static final int RD_SHIFT = 8;
    private static final int RS1_SHIFT = 13;
    private static final int RS2_SHIFT = 18;
    private static final int FIRST_LENGTH_SHIFT = 23;
    private static final int TOTAL_HALFWORDS_SHIFT = 24;
    private static final int RETIRED_SHIFT = 28;

    private RiscV32MicroOp() {
    }

    public static long pack(int kind, int rd, int rs1, int rs2, int immediate, int instructionBytes) {
        return pack(kind, rd, rs1, rs2, immediate, instructionBytes, 1, instructionBytes);
    }

    public static long packSuper(int kind, int rd, int rs1, int rs2, int payload,
            int totalInstructionBytes, int retiredInstructions, int firstInstructionBytes) {
        return pack(kind, rd, rs1, rs2, payload, totalInstructionBytes,
                retiredInstructions, firstInstructionBytes);
    }

    private static long pack(int kind, int rd, int rs1, int rs2, int payload,
            int totalInstructionBytes, int retiredInstructions, int firstInstructionBytes) {
        if (kind <= INVALID || kind > 0xff) {
            throw new IllegalArgumentException("invalid micro-op kind: " + kind);
        }
        requireRegister(rd);
        requireRegister(rs1);
        requireRegister(rs2);
        if (totalInstructionBytes <= 0 || (totalInstructionBytes & 1) != 0
                || totalInstructionBytes > 30) {
            throw new IllegalArgumentException("totalInstructionBytes must be even and in [2,30]");
        }
        if (retiredInstructions <= 0 || retiredInstructions > 15) {
            throw new IllegalArgumentException("retiredInstructions must be in [1,15]");
        }
        if (firstInstructionBytes != 2 && firstInstructionBytes != 4) {
            throw new IllegalArgumentException("firstInstructionBytes must be 2 or 4");
        }

        int halfwords = totalInstructionBytes >>> 1;
        long metadata = (kind & 0xffL)
                | ((long) rd << RD_SHIFT)
                | ((long) rs1 << RS1_SHIFT)
                | ((long) rs2 << RS2_SHIFT)
                | ((firstInstructionBytes == 4 ? 1L : 0L) << FIRST_LENGTH_SHIFT)
                | ((long) halfwords << TOTAL_HALFWORDS_SHIFT)
                | ((long) retiredInstructions << RETIRED_SHIFT);
        return ((long) payload << 32) | metadata;
    }

    public static int kind(long op) {
        return (int) op & 0xff;
    }

    public static int rd(long op) {
        return ((int) op >>> RD_SHIFT) & 0x1f;
    }

    public static int rs1(long op) {
        return ((int) op >>> RS1_SHIFT) & 0x1f;
    }

    public static int rs2(long op) {
        return ((int) op >>> RS2_SHIFT) & 0x1f;
    }

    public static int immediate(long op) {
        return (int) (op >> 32);
    }

    public static int instructionBytes(long op) {
        return (((int) op >>> TOTAL_HALFWORDS_SHIFT) & 0xf) << 1;
    }

    public static int firstInstructionBytes(long op) {
        return ((((int) op >>> FIRST_LENGTH_SHIFT) & 1) == 0) ? 2 : 4;
    }

    public static int retiredInstructions(long op) {
        return ((int) op >>> RETIRED_SHIFT) & 0xf;
    }

    public static int packSigned16Pair(int low, int high) {
        if (low < Short.MIN_VALUE || low > Short.MAX_VALUE
                || high < Short.MIN_VALUE || high > Short.MAX_VALUE) {
            throw new IllegalArgumentException("pair components must fit signed 16 bits");
        }
        return (low & 0xffff) | (high << 16);
    }

    public static int lowSigned16(int payload) {
        return (short) payload;
    }

    public static int highSigned16(int payload) {
        return (short) (payload >>> 16);
    }

    public static boolean terminatesBlock(int kind) {
        return kind == JAL || kind == JALR
                || (kind >= BEQ && kind <= BGEU)
                || (kind >= ADDI_BEQ && kind <= ADDI_BGEU)
                || kind == ECALL || kind == EBREAK || kind == MRET || kind == WFI
                || (kind >= CSRRW && kind <= CSRRCI);
    }

    private static void requireRegister(int register) {
        if (register < 0 || register >= RiscV32.REGISTER_COUNT) {
            throw new IllegalArgumentException("register must be in [0,31]: " + register);
        }
    }
}
