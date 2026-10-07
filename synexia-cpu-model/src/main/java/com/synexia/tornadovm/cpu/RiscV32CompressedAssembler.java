/*
 * Copyright 2026 Synexia contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.synexia.tornadovm.cpu;

/**
 * Small RV32C encoder for tests, generated workloads and compact boot code.
 *
 * <p>Methods return the 16-bit instruction in the low half of an {@code int}.
 */
public final class RiscV32CompressedAssembler {

    private RiscV32CompressedAssembler() {
    }

    public static int cNop() {
        return 0x0001;
    }

    public static int cAddi(int rd, int immediate) {
        register(rd);
        signedImmediate(immediate, 6);
        return (bits(immediate, 5, 1) << 12) | (rd << 7) | (bits(immediate, 0, 5) << 2) | 0x1;
    }

    public static int cLi(int rd, int immediate) {
        registerNonZero(rd);
        signedImmediate(immediate, 6);
        return (2 << 13) | (bits(immediate, 5, 1) << 12) | (rd << 7)
                | (bits(immediate, 0, 5) << 2) | 0x1;
    }

    public static int cLui(int rd, int immediate6) {
        if (rd == 0 || rd == 2) {
            throw new IllegalArgumentException("C.LUI rd must be neither x0 nor x2");
        }
        register(rd);
        signedImmediate(immediate6, 6);
        if (immediate6 == 0) {
            throw new IllegalArgumentException("C.LUI immediate must be non-zero");
        }
        return (3 << 13) | (bits(immediate6, 5, 1) << 12) | (rd << 7)
                | (bits(immediate6, 0, 5) << 2) | 0x1;
    }

    public static int cAddi16sp(int immediate) {
        if (immediate == 0 || (immediate & 0xf) != 0 || immediate < -512 || immediate > 496) {
            throw new IllegalArgumentException("C.ADDI16SP immediate must be non-zero, 16-byte aligned and in [-512,496]");
        }
        return (3 << 13)
                | (bit(immediate, 9) << 12)
                | (2 << 7)
                | (bit(immediate, 4) << 6)
                | (bit(immediate, 6) << 5)
                | (bits(immediate, 7, 2) << 3)
                | (bit(immediate, 5) << 2)
                | 0x1;
    }

    public static int cAddi4spn(int rdPrime, int immediate) {
        compactRegister(rdPrime);
        if (immediate == 0 || (immediate & 3) != 0 || immediate < 0 || immediate > 1020) {
            throw new IllegalArgumentException("C.ADDI4SPN immediate must be non-zero, 4-byte aligned and <= 1020");
        }
        return (bits(immediate, 4, 2) << 11)
                | (bits(immediate, 6, 4) << 7)
                | (bit(immediate, 2) << 6)
                | (bit(immediate, 3) << 5)
                | ((rdPrime - 8) << 2);
    }

    public static int cLw(int rdPrime, int rs1Prime, int offset) {
        compactRegister(rdPrime);
        compactRegister(rs1Prime);
        wordOffset(offset, 124);
        return (2 << 13)
                | (bits(offset, 3, 3) << 10)
                | ((rs1Prime - 8) << 7)
                | (bit(offset, 2) << 6)
                | (bit(offset, 6) << 5)
                | ((rdPrime - 8) << 2);
    }

    public static int cSw(int rs2Prime, int rs1Prime, int offset) {
        compactRegister(rs2Prime);
        compactRegister(rs1Prime);
        wordOffset(offset, 124);
        return (6 << 13)
                | (bits(offset, 3, 3) << 10)
                | ((rs1Prime - 8) << 7)
                | (bit(offset, 2) << 6)
                | (bit(offset, 6) << 5)
                | ((rs2Prime - 8) << 2);
    }

    public static int cLwsp(int rd, int offset) {
        registerNonZero(rd);
        wordOffset(offset, 252);
        return (2 << 13)
                | (bit(offset, 5) << 12)
                | (rd << 7)
                | (bits(offset, 2, 3) << 4)
                | (bits(offset, 6, 2) << 2)
                | 0x2;
    }

    public static int cSwsp(int rs2, int offset) {
        register(rs2);
        wordOffset(offset, 252);
        return (6 << 13)
                | (bits(offset, 2, 4) << 9)
                | (bits(offset, 6, 2) << 7)
                | (rs2 << 2)
                | 0x2;
    }

    public static int cSlli(int rd, int shift) {
        registerNonZero(rd);
        shift(shift);
        return (rd << 7) | (shift << 2) | 0x2;
    }

    public static int cSrli(int rdPrime, int shift) {
        compactRegister(rdPrime);
        shift(shift);
        return (4 << 13) | ((rdPrime - 8) << 7) | (shift << 2) | 0x1;
    }

    public static int cSrai(int rdPrime, int shift) {
        compactRegister(rdPrime);
        shift(shift);
        return (4 << 13) | (1 << 10) | ((rdPrime - 8) << 7) | (shift << 2) | 0x1;
    }

    public static int cAndi(int rdPrime, int immediate) {
        compactRegister(rdPrime);
        signedImmediate(immediate, 6);
        return (4 << 13) | (2 << 10) | (bit(immediate, 5) << 12)
                | ((rdPrime - 8) << 7) | (bits(immediate, 0, 5) << 2) | 0x1;
    }

    public static int cSub(int rdPrime, int rs2Prime) {
        return compactAlu(0, rdPrime, rs2Prime);
    }

    public static int cXor(int rdPrime, int rs2Prime) {
        return compactAlu(1, rdPrime, rs2Prime);
    }

    public static int cOr(int rdPrime, int rs2Prime) {
        return compactAlu(2, rdPrime, rs2Prime);
    }

    public static int cAnd(int rdPrime, int rs2Prime) {
        return compactAlu(3, rdPrime, rs2Prime);
    }

    public static int cJ(int offset) {
        return compressedJump(5, offset);
    }

    public static int cJal(int offset) {
        return compressedJump(1, offset);
    }

    public static int cBeqz(int rs1Prime, int offset) {
        return compressedBranch(6, rs1Prime, offset);
    }

    public static int cBnez(int rs1Prime, int offset) {
        return compressedBranch(7, rs1Prime, offset);
    }

    public static int cMv(int rd, int rs2) {
        registerNonZero(rd);
        registerNonZero(rs2);
        return (4 << 13) | (rd << 7) | (rs2 << 2) | 0x2;
    }

    public static int cAdd(int rd, int rs2) {
        registerNonZero(rd);
        registerNonZero(rs2);
        return (4 << 13) | (1 << 12) | (rd << 7) | (rs2 << 2) | 0x2;
    }

    public static int cJr(int rs1) {
        registerNonZero(rs1);
        return (4 << 13) | (rs1 << 7) | 0x2;
    }

    public static int cJalr(int rs1) {
        registerNonZero(rs1);
        return (4 << 13) | (1 << 12) | (rs1 << 7) | 0x2;
    }

    public static int cEbreak() {
        return 0x9002;
    }

    private static int compactAlu(int op, int rdPrime, int rs2Prime) {
        compactRegister(rdPrime);
        compactRegister(rs2Prime);
        return (4 << 13) | (3 << 10) | ((rdPrime - 8) << 7)
                | (op << 5) | ((rs2Prime - 8) << 2) | 0x1;
    }

    private static int compressedJump(int funct3, int offset) {
        if ((offset & 1) != 0 || offset < -2048 || offset > 2046) {
            throw new IllegalArgumentException("compressed jump offset must be even and in [-2048,2046]");
        }
        return (funct3 << 13)
                | (bit(offset, 11) << 12)
                | (bit(offset, 4) << 11)
                | (bits(offset, 8, 2) << 9)
                | (bit(offset, 10) << 8)
                | (bit(offset, 6) << 7)
                | (bit(offset, 7) << 6)
                | (bits(offset, 1, 3) << 3)
                | (bit(offset, 5) << 2)
                | 0x1;
    }

    private static int compressedBranch(int funct3, int rs1Prime, int offset) {
        compactRegister(rs1Prime);
        if ((offset & 1) != 0 || offset < -256 || offset > 254) {
            throw new IllegalArgumentException("compressed branch offset must be even and in [-256,254]");
        }
        return (funct3 << 13)
                | (bit(offset, 8) << 12)
                | (bits(offset, 3, 2) << 10)
                | ((rs1Prime - 8) << 7)
                | (bits(offset, 6, 2) << 5)
                | (bits(offset, 1, 2) << 3)
                | (bit(offset, 5) << 2)
                | 0x1;
    }

    private static int bit(int value, int bit) {
        return (value >>> bit) & 1;
    }

    private static int bits(int value, int low, int count) {
        return (value >>> low) & ((1 << count) - 1);
    }

    private static void compactRegister(int register) {
        if (register < 8 || register > 15) {
            throw new IllegalArgumentException("compressed register must be in [x8,x15]: x" + register);
        }
    }

    private static void register(int register) {
        if (register < 0 || register >= RiscV32.REGISTER_COUNT) {
            throw new IllegalArgumentException("register must be in [0,31]: " + register);
        }
    }

    private static void registerNonZero(int register) {
        register(register);
        if (register == 0) {
            throw new IllegalArgumentException("register must not be x0");
        }
    }

    private static void shift(int shift) {
        if (shift < 0 || shift > 31) {
            throw new IllegalArgumentException("shift must be in [0,31]: " + shift);
        }
    }

    private static void signedImmediate(int value, int bits) {
        int minimum = -(1 << (bits - 1));
        int maximum = (1 << (bits - 1)) - 1;
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException("signed " + bits + "-bit immediate out of range: " + value);
        }
    }

    private static void wordOffset(int offset, int maximum) {
        if (offset < 0 || offset > maximum || (offset & 3) != 0) {
            throw new IllegalArgumentException("offset must be 4-byte aligned and in [0," + maximum + "]: " + offset);
        }
    }
}
