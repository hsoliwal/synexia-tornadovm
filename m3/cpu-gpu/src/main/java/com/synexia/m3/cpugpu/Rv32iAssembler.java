/* SPDX-License-Identifier: Apache-2.0 */
package com.synexia.m3.cpugpu;

/**
 * Mechanical encoders for the RV32I 2.1 + M 2.0 instructions implemented by the engine.
 * Branch and jump immediates are byte offsets from the current PC.
 */
public final class Rv32iAssembler {
    private static final int OP_LOAD = 0x03;
    private static final int OP_MISC_MEM = 0x0F;
    private static final int OP_IMM = 0x13;
    private static final int OP_AUIPC = 0x17;
    private static final int OP_STORE = 0x23;
    private static final int OP = 0x33;
    private static final int OP_LUI = 0x37;
    private static final int OP_BRANCH = 0x63;
    private static final int OP_JALR = 0x67;
    private static final int OP_JAL = 0x6F;

    private Rv32iAssembler() {
    }

    public static int lui(int rd, int upper20) {
        return encodeU(upper20, rd, OP_LUI);
    }

    public static int auipc(int rd, int upper20) {
        return encodeU(upper20, rd, OP_AUIPC);
    }

    public static int jal(int rd, int offset) {
        return encodeJ(offset, rd, OP_JAL);
    }

    public static int jalr(int rd, int rs1, int immediate) {
        return encodeI(immediate, rs1, 0, rd, OP_JALR);
    }

    public static int beq(int rs1, int rs2, int offset) {
        return encodeB(offset, rs2, rs1, 0, OP_BRANCH);
    }

    public static int bne(int rs1, int rs2, int offset) {
        return encodeB(offset, rs2, rs1, 1, OP_BRANCH);
    }

    public static int blt(int rs1, int rs2, int offset) {
        return encodeB(offset, rs2, rs1, 4, OP_BRANCH);
    }

    public static int bge(int rs1, int rs2, int offset) {
        return encodeB(offset, rs2, rs1, 5, OP_BRANCH);
    }

    public static int bltu(int rs1, int rs2, int offset) {
        return encodeB(offset, rs2, rs1, 6, OP_BRANCH);
    }

    public static int bgeu(int rs1, int rs2, int offset) {
        return encodeB(offset, rs2, rs1, 7, OP_BRANCH);
    }

    public static int lb(int rd, int rs1, int immediate) {
        return encodeI(immediate, rs1, 0, rd, OP_LOAD);
    }

    public static int lh(int rd, int rs1, int immediate) {
        return encodeI(immediate, rs1, 1, rd, OP_LOAD);
    }

    public static int lw(int rd, int rs1, int immediate) {
        return encodeI(immediate, rs1, 2, rd, OP_LOAD);
    }

    public static int lbu(int rd, int rs1, int immediate) {
        return encodeI(immediate, rs1, 4, rd, OP_LOAD);
    }

    public static int lhu(int rd, int rs1, int immediate) {
        return encodeI(immediate, rs1, 5, rd, OP_LOAD);
    }

    public static int sb(int rs2, int rs1, int immediate) {
        return encodeS(immediate, rs2, rs1, 0, OP_STORE);
    }

    public static int sh(int rs2, int rs1, int immediate) {
        return encodeS(immediate, rs2, rs1, 1, OP_STORE);
    }

    public static int sw(int rs2, int rs1, int immediate) {
        return encodeS(immediate, rs2, rs1, 2, OP_STORE);
    }

    public static int addi(int rd, int rs1, int immediate) {
        return encodeI(immediate, rs1, 0, rd, OP_IMM);
    }

    public static int slti(int rd, int rs1, int immediate) {
        return encodeI(immediate, rs1, 2, rd, OP_IMM);
    }

    public static int sltiu(int rd, int rs1, int immediate) {
        return encodeI(immediate, rs1, 3, rd, OP_IMM);
    }

    public static int xori(int rd, int rs1, int immediate) {
        return encodeI(immediate, rs1, 4, rd, OP_IMM);
    }

    public static int ori(int rd, int rs1, int immediate) {
        return encodeI(immediate, rs1, 6, rd, OP_IMM);
    }

    public static int andi(int rd, int rs1, int immediate) {
        return encodeI(immediate, rs1, 7, rd, OP_IMM);
    }

    public static int slli(int rd, int rs1, int shift) {
        checkShift(shift);
        return encodeIUnchecked(shift, rs1, 1, rd, OP_IMM);
    }

    public static int srli(int rd, int rs1, int shift) {
        checkShift(shift);
        return encodeIUnchecked(shift, rs1, 5, rd, OP_IMM);
    }

    public static int srai(int rd, int rs1, int shift) {
        checkShift(shift);
        return encodeIUnchecked(0x400 | shift, rs1, 5, rd, OP_IMM);
    }

    public static int add(int rd, int rs1, int rs2) {
        return encodeR(0x00, rs2, rs1, 0, rd, OP);
    }

    public static int sub(int rd, int rs1, int rs2) {
        return encodeR(0x20, rs2, rs1, 0, rd, OP);
    }

    public static int sll(int rd, int rs1, int rs2) {
        return encodeR(0x00, rs2, rs1, 1, rd, OP);
    }

    public static int slt(int rd, int rs1, int rs2) {
        return encodeR(0x00, rs2, rs1, 2, rd, OP);
    }

    public static int sltu(int rd, int rs1, int rs2) {
        return encodeR(0x00, rs2, rs1, 3, rd, OP);
    }

    public static int xor(int rd, int rs1, int rs2) {
        return encodeR(0x00, rs2, rs1, 4, rd, OP);
    }

    public static int srl(int rd, int rs1, int rs2) {
        return encodeR(0x00, rs2, rs1, 5, rd, OP);
    }

    public static int sra(int rd, int rs1, int rs2) {
        return encodeR(0x20, rs2, rs1, 5, rd, OP);
    }

    public static int or(int rd, int rs1, int rs2) {
        return encodeR(0x00, rs2, rs1, 6, rd, OP);
    }

    public static int and(int rd, int rs1, int rs2) {
        return encodeR(0x00, rs2, rs1, 7, rd, OP);
    }

    public static int mul(int rd, int rs1, int rs2) {
        return encodeR(0x01, rs2, rs1, 0, rd, OP);
    }

    public static int mulh(int rd, int rs1, int rs2) {
        return encodeR(0x01, rs2, rs1, 1, rd, OP);
    }

    public static int mulhsu(int rd, int rs1, int rs2) {
        return encodeR(0x01, rs2, rs1, 2, rd, OP);
    }

    public static int mulhu(int rd, int rs1, int rs2) {
        return encodeR(0x01, rs2, rs1, 3, rd, OP);
    }

    public static int div(int rd, int rs1, int rs2) {
        return encodeR(0x01, rs2, rs1, 4, rd, OP);
    }

    public static int divu(int rd, int rs1, int rs2) {
        return encodeR(0x01, rs2, rs1, 5, rd, OP);
    }

    public static int rem(int rd, int rs1, int rs2) {
        return encodeR(0x01, rs2, rs1, 6, rd, OP);
    }

    public static int remu(int rd, int rs1, int rs2) {
        return encodeR(0x01, rs2, rs1, 7, rd, OP);
    }

    public static int fence() {
        return OP_MISC_MEM;
    }

    public static int fenceI() {
        return 1 << 12 | OP_MISC_MEM;
    }

    public static int ecall() {
        return 0x0000_0073;
    }

    public static int ebreak() {
        return 0x0010_0073;
    }

    public static int halt() {
        return Rv32i.XM3_HALT;
    }

    public static int[] words(int... words) {
        return words.clone();
    }

    private static int encodeR(int funct7, int rs2, int rs1, int funct3, int rd, int opcode) {
        checkRegister(rd);
        checkRegister(rs1);
        checkRegister(rs2);
        return funct7 << 25 | rs2 << 20 | rs1 << 15 | funct3 << 12 | rd << 7 | opcode;
    }

    private static int encodeI(int immediate, int rs1, int funct3, int rd, int opcode) {
        checkSignedImmediate(immediate, 12);
        return encodeIUnchecked(immediate & 0xFFF, rs1, funct3, rd, opcode);
    }

    private static int encodeIUnchecked(int immediateBits, int rs1, int funct3, int rd, int opcode) {
        checkRegister(rd);
        checkRegister(rs1);
        return (immediateBits & 0xFFF) << 20 | rs1 << 15 | funct3 << 12 | rd << 7 | opcode;
    }

    private static int encodeS(int immediate, int rs2, int rs1, int funct3, int opcode) {
        checkSignedImmediate(immediate, 12);
        checkRegister(rs1);
        checkRegister(rs2);
        int bits = immediate & 0xFFF;
        return (bits >>> 5) << 25 | rs2 << 20 | rs1 << 15 | funct3 << 12 | (bits & 0x1F) << 7 | opcode;
    }

    private static int encodeB(int immediate, int rs2, int rs1, int funct3, int opcode) {
        checkSignedImmediate(immediate, 13);
        checkEven(immediate, "branch offset");
        checkRegister(rs1);
        checkRegister(rs2);
        int bits = immediate & 0x1FFF;
        return ((bits >>> 12) & 1) << 31
                | ((bits >>> 5) & 0x3F) << 25
                | rs2 << 20
                | rs1 << 15
                | funct3 << 12
                | ((bits >>> 1) & 0xF) << 8
                | ((bits >>> 11) & 1) << 7
                | opcode;
    }

    private static int encodeU(int upper20, int rd, int opcode) {
        if ((upper20 & ~0xFFFFF) != 0) {
            throw new IllegalArgumentException("upper20 must fit in 20 bits");
        }
        checkRegister(rd);
        return upper20 << 12 | rd << 7 | opcode;
    }

    private static int encodeJ(int immediate, int rd, int opcode) {
        checkSignedImmediate(immediate, 21);
        checkEven(immediate, "jump offset");
        checkRegister(rd);
        int bits = immediate & 0x1F_FFFF;
        return ((bits >>> 20) & 1) << 31
                | ((bits >>> 1) & 0x3FF) << 21
                | ((bits >>> 11) & 1) << 20
                | ((bits >>> 12) & 0xFF) << 12
                | rd << 7
                | opcode;
    }

    private static void checkSignedImmediate(int value, int bits) {
        int minimum = -(1 << (bits - 1));
        int maximum = (1 << (bits - 1)) - 1;
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException("signed " + bits + "-bit immediate out of range: " + value);
        }
    }

    private static void checkRegister(int register) {
        if (register < 0 || register >= Rv32i.REGISTER_COUNT) {
            throw new IllegalArgumentException("register out of range: " + register);
        }
    }

    private static void checkShift(int shift) {
        if (shift < 0 || shift > 31) {
            throw new IllegalArgumentException("RV32 shift amount out of range: " + shift);
        }
    }

    private static void checkEven(int value, String name) {
        if ((value & 1) != 0) {
            throw new IllegalArgumentException(name + " must be a multiple of 2: " + value);
        }
    }
}
