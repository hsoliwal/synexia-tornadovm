/*
 * Copyright 2026 Synexia contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.synexia.tornadovm.cpu;

/**
 * Small dependency-free RV32IM encoder used by tests, examples and generated workloads.
 */
public final class RiscV32Assembler {

    private RiscV32Assembler() {
    }

    public static int lui(int rd, int upper20) {
        register(rd);
        return ((upper20 & 0xfffff) << 12) | (rd << 7) | 0x37;
    }

    public static int auipc(int rd, int upper20) {
        register(rd);
        return ((upper20 & 0xfffff) << 12) | (rd << 7) | 0x17;
    }

    public static int jal(int rd, int offset) {
        register(rd);
        branchOffset(offset, 21);
        int immediate = offset & 0x1fffff;
        return (((immediate >>> 20) & 0x1) << 31)
                | (((immediate >>> 1) & 0x3ff) << 21)
                | (((immediate >>> 11) & 0x1) << 20)
                | (((immediate >>> 12) & 0xff) << 12)
                | (rd << 7) | 0x6f;
    }

    public static int jalr(int rd, int rs1, int immediate) {
        return iType(0x67, 0, rd, rs1, immediate);
    }

    public static int beq(int rs1, int rs2, int offset) {
        return branch(0, rs1, rs2, offset);
    }

    public static int bne(int rs1, int rs2, int offset) {
        return branch(1, rs1, rs2, offset);
    }

    public static int blt(int rs1, int rs2, int offset) {
        return branch(4, rs1, rs2, offset);
    }

    public static int bge(int rs1, int rs2, int offset) {
        return branch(5, rs1, rs2, offset);
    }

    public static int bltu(int rs1, int rs2, int offset) {
        return branch(6, rs1, rs2, offset);
    }

    public static int bgeu(int rs1, int rs2, int offset) {
        return branch(7, rs1, rs2, offset);
    }

    public static int lb(int rd, int rs1, int immediate) {
        return iType(0x03, 0, rd, rs1, immediate);
    }

    public static int lh(int rd, int rs1, int immediate) {
        return iType(0x03, 1, rd, rs1, immediate);
    }

    public static int lw(int rd, int rs1, int immediate) {
        return iType(0x03, 2, rd, rs1, immediate);
    }

    public static int lbu(int rd, int rs1, int immediate) {
        return iType(0x03, 4, rd, rs1, immediate);
    }

    public static int lhu(int rd, int rs1, int immediate) {
        return iType(0x03, 5, rd, rs1, immediate);
    }

    public static int sb(int rs2, int rs1, int immediate) {
        return sType(0, rs1, rs2, immediate);
    }

    public static int sh(int rs2, int rs1, int immediate) {
        return sType(1, rs1, rs2, immediate);
    }

    public static int sw(int rs2, int rs1, int immediate) {
        return sType(2, rs1, rs2, immediate);
    }

    public static int addi(int rd, int rs1, int immediate) {
        return iType(0x13, 0, rd, rs1, immediate);
    }

    public static int slti(int rd, int rs1, int immediate) {
        return iType(0x13, 2, rd, rs1, immediate);
    }

    public static int sltiu(int rd, int rs1, int immediate) {
        return iType(0x13, 3, rd, rs1, immediate);
    }

    public static int xori(int rd, int rs1, int immediate) {
        return iType(0x13, 4, rd, rs1, immediate);
    }

    public static int ori(int rd, int rs1, int immediate) {
        return iType(0x13, 6, rd, rs1, immediate);
    }

    public static int andi(int rd, int rs1, int immediate) {
        return iType(0x13, 7, rd, rs1, immediate);
    }

    public static int slli(int rd, int rs1, int shift) {
        shift(shift);
        return iTypeRaw(0x13, 1, rd, rs1, shift);
    }

    public static int srli(int rd, int rs1, int shift) {
        shift(shift);
        return iTypeRaw(0x13, 5, rd, rs1, shift);
    }

    public static int srai(int rd, int rs1, int shift) {
        shift(shift);
        return iTypeRaw(0x13, 5, rd, rs1, (0x20 << 5) | shift);
    }

    public static int add(int rd, int rs1, int rs2) {
        return rType(0x00, 0, rd, rs1, rs2);
    }

    public static int sub(int rd, int rs1, int rs2) {
        return rType(0x20, 0, rd, rs1, rs2);
    }

    public static int sll(int rd, int rs1, int rs2) {
        return rType(0x00, 1, rd, rs1, rs2);
    }

    public static int slt(int rd, int rs1, int rs2) {
        return rType(0x00, 2, rd, rs1, rs2);
    }

    public static int sltu(int rd, int rs1, int rs2) {
        return rType(0x00, 3, rd, rs1, rs2);
    }

    public static int xor(int rd, int rs1, int rs2) {
        return rType(0x00, 4, rd, rs1, rs2);
    }

    public static int srl(int rd, int rs1, int rs2) {
        return rType(0x00, 5, rd, rs1, rs2);
    }

    public static int sra(int rd, int rs1, int rs2) {
        return rType(0x20, 5, rd, rs1, rs2);
    }

    public static int or(int rd, int rs1, int rs2) {
        return rType(0x00, 6, rd, rs1, rs2);
    }

    public static int and(int rd, int rs1, int rs2) {
        return rType(0x00, 7, rd, rs1, rs2);
    }

    public static int mul(int rd, int rs1, int rs2) {
        return rType(0x01, 0, rd, rs1, rs2);
    }

    public static int mulh(int rd, int rs1, int rs2) {
        return rType(0x01, 1, rd, rs1, rs2);
    }

    public static int mulhsu(int rd, int rs1, int rs2) {
        return rType(0x01, 2, rd, rs1, rs2);
    }

    public static int mulhu(int rd, int rs1, int rs2) {
        return rType(0x01, 3, rd, rs1, rs2);
    }

    public static int div(int rd, int rs1, int rs2) {
        return rType(0x01, 4, rd, rs1, rs2);
    }

    public static int divu(int rd, int rs1, int rs2) {
        return rType(0x01, 5, rd, rs1, rs2);
    }

    public static int rem(int rd, int rs1, int rs2) {
        return rType(0x01, 6, rd, rs1, rs2);
    }

    public static int remu(int rd, int rs1, int rs2) {
        return rType(0x01, 7, rd, rs1, rs2);
    }

    public static int fence() {
        return 0x0000000f;
    }

    public static int fenceI() {
        return 0x0000100f;
    }

    public static int ecall() {
        return 0x00000073;
    }

    public static int ebreak() {
        return 0x00100073;
    }

    public static int mret() {
        return 0x30200073;
    }

    public static int wfi() {
        return 0x10500073;
    }

    public static int csrrw(int rd, int csr, int rs1) {
        return csr(1, rd, csr, rs1);
    }

    public static int csrrs(int rd, int csr, int rs1) {
        return csr(2, rd, csr, rs1);
    }

    public static int csrrc(int rd, int csr, int rs1) {
        return csr(3, rd, csr, rs1);
    }

    public static int csrrwi(int rd, int csr, int immediate) {
        zimm(immediate);
        return csr(5, rd, csr, immediate);
    }

    public static int csrrsi(int rd, int csr, int immediate) {
        zimm(immediate);
        return csr(6, rd, csr, immediate);
    }

    public static int csrrci(int rd, int csr, int immediate) {
        zimm(immediate);
        return csr(7, rd, csr, immediate);
    }

    public static int lrW(int rd, int rs1) {
        return amo(0x02, rd, rs1, 0);
    }

    public static int scW(int rd, int rs1, int rs2) {
        return amo(0x03, rd, rs1, rs2);
    }

    public static int amoSwapW(int rd, int rs1, int rs2) {
        return amo(0x01, rd, rs1, rs2);
    }

    public static int amoAddW(int rd, int rs1, int rs2) {
        return amo(0x00, rd, rs1, rs2);
    }

    public static int amoXorW(int rd, int rs1, int rs2) {
        return amo(0x04, rd, rs1, rs2);
    }

    public static int amoAndW(int rd, int rs1, int rs2) {
        return amo(0x0c, rd, rs1, rs2);
    }

    public static int amoOrW(int rd, int rs1, int rs2) {
        return amo(0x08, rd, rs1, rs2);
    }

    public static int amoMinW(int rd, int rs1, int rs2) {
        return amo(0x10, rd, rs1, rs2);
    }

    public static int amoMaxW(int rd, int rs1, int rs2) {
        return amo(0x14, rd, rs1, rs2);
    }

    public static int amoMinuW(int rd, int rs1, int rs2) {
        return amo(0x18, rd, rs1, rs2);
    }

    public static int amoMaxuW(int rd, int rs1, int rs2) {
        return amo(0x1c, rd, rs1, rs2);
    }

    public static int nop() {
        return addi(0, 0, 0);
    }

    private static int iType(int opcode, int funct3, int rd, int rs1, int immediate) {
        signedImmediate(immediate, 12);
        return iTypeRaw(opcode, funct3, rd, rs1, immediate & 0xfff);
    }

    private static int iTypeRaw(int opcode, int funct3, int rd, int rs1, int immediate12) {
        register(rd);
        register(rs1);
        return ((immediate12 & 0xfff) << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | opcode;
    }

    private static int sType(int funct3, int rs1, int rs2, int immediate) {
        register(rs1);
        register(rs2);
        signedImmediate(immediate, 12);
        int value = immediate & 0xfff;
        return (((value >>> 5) & 0x7f) << 25)
                | (rs2 << 20) | (rs1 << 15) | (funct3 << 12)
                | ((value & 0x1f) << 7) | 0x23;
    }

    private static int branch(int funct3, int rs1, int rs2, int offset) {
        register(rs1);
        register(rs2);
        branchOffset(offset, 13);
        int value = offset & 0x1fff;
        return (((value >>> 12) & 1) << 31)
                | (((value >>> 5) & 0x3f) << 25)
                | (rs2 << 20) | (rs1 << 15) | (funct3 << 12)
                | (((value >>> 1) & 0xf) << 8)
                | (((value >>> 11) & 1) << 7)
                | 0x63;
    }

    private static int rType(int funct7, int funct3, int rd, int rs1, int rs2) {
        register(rd);
        register(rs1);
        register(rs2);
        return (funct7 << 25) | (rs2 << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | 0x33;
    }

    private static int csr(int funct3, int rd, int csr, int source) {
        register(rd);
        if (funct3 < 5) {
            register(source);
        } else {
            zimm(source);
        }
        if (csr < 0 || csr > 0xfff) {
            throw new IllegalArgumentException("CSR address must be in [0,4095]: " + csr);
        }
        return (csr << 20) | (source << 15) | (funct3 << 12) | (rd << 7) | 0x73;
    }

    private static int amo(int funct5, int rd, int rs1, int rs2) {
        register(rd);
        register(rs1);
        register(rs2);
        return (funct5 << 27) | (rs2 << 20) | (rs1 << 15) | (2 << 12) | (rd << 7) | 0x2f;
    }

    private static void zimm(int value) {
        if (value < 0 || value > 31) {
            throw new IllegalArgumentException("CSR immediate must be in [0,31]: " + value);
        }
    }

    private static void register(int register) {
        if (register < 0 || register >= RiscV32.REGISTER_COUNT) {
            throw new IllegalArgumentException("register must be in [0,31]: " + register);
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

    private static void branchOffset(int offset, int bits) {
        if ((offset & 1) != 0) {
            throw new IllegalArgumentException("branch/jump offset must be 2-byte aligned: " + offset);
        }
        signedImmediate(offset, bits);
    }
}
