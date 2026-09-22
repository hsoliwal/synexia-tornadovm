/*
 * Copyright 2026 Synexia contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.synexia.tornadovm.cpu;

/**
 * Dense 64-bit micro-op format used by the RV32 basic-block tier.
 *
 * <pre>
 * 63                    32 31              24 23  22    18 17    13 12     8 7       0
 * +-----------------------+------------------+--+--------+--------+---------+---------+
 * | signed immediate (32) | reserved         |L | rs2(5) | rs1(5) | rd(5)   | kind(8) |
 * +-----------------------+------------------+--+--------+--------+---------+---------+
 * </pre>
 *
 * L=0 means a 2-byte guest instruction, L=1 means a 4-byte guest instruction.
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

    private static final int RD_SHIFT = 8;
    private static final int RS1_SHIFT = 13;
    private static final int RS2_SHIFT = 18;
    private static final int LENGTH_SHIFT = 23;

    private RiscV32MicroOp() {
    }

    public static long pack(int kind, int rd, int rs1, int rs2, int immediate, int instructionBytes) {
        if (kind <= INVALID || kind > 0xff) {
            throw new IllegalArgumentException("invalid micro-op kind: " + kind);
        }
        requireRegister(rd);
        requireRegister(rs1);
        requireRegister(rs2);
        if (instructionBytes != 2 && instructionBytes != 4) {
            throw new IllegalArgumentException("instructionBytes must be 2 or 4");
        }

        long metadata = (kind & 0xffL)
                | ((long) rd << RD_SHIFT)
                | ((long) rs1 << RS1_SHIFT)
                | ((long) rs2 << RS2_SHIFT)
                | ((instructionBytes == 4 ? 1L : 0L) << LENGTH_SHIFT);
        return ((long) immediate << 32) | metadata;
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
        return ((((int) op >>> LENGTH_SHIFT) & 1) == 0) ? 2 : 4;
    }

    public static boolean terminatesBlock(int kind) {
        return kind == JAL || kind == JALR
                || (kind >= BEQ && kind <= BGEU);
    }

    private static void requireRegister(int register) {
        if (register < 0 || register >= RiscV32.REGISTER_COUNT) {
            throw new IllegalArgumentException("register must be in [0,31]: " + register);
        }
    }
}
