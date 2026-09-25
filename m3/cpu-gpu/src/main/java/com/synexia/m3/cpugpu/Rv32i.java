/* SPDX-License-Identifier: Apache-2.0 */
package com.synexia.m3.cpugpu;

/**
 * Stable host/device ABI and architectural constants for the M3 RV32IM engine.
 */
public final class Rv32i {
    public static final int REGISTER_COUNT = 32;

    public static final int STATE_PC = 0;
    public static final int STATE_STATUS = 1;
    public static final int STATE_RETIRED = 2;
    public static final int STATE_TRAP_CAUSE = 3;
    public static final int STATE_TRAP_VALUE = 4;
    public static final int STATE_EXIT_CODE = 5;
    public static final int STATE_STRIDE = 6;

    public static final int STATUS_READY = 0;
    public static final int STATUS_HALTED = 1;
    public static final int STATUS_TRAPPED = 2;
    public static final int STATUS_YIELDED = 3;

    /** No active exception. Architectural RISC-V exception causes start at zero. */
    public static final int TRAP_NONE = -1;
    public static final int TRAP_INSTRUCTION_ADDRESS_MISALIGNED = 0;
    public static final int TRAP_INSTRUCTION_ACCESS_FAULT = 1;
    public static final int TRAP_ILLEGAL_INSTRUCTION = 2;
    public static final int TRAP_BREAKPOINT = 3;
    public static final int TRAP_LOAD_ADDRESS_MISALIGNED = 4;
    public static final int TRAP_LOAD_ACCESS_FAULT = 5;
    public static final int TRAP_STORE_ADDRESS_MISALIGNED = 6;
    public static final int TRAP_STORE_ACCESS_FAULT = 7;
    public static final int TRAP_ENVIRONMENT_CALL = 8;

    public static final int CONFIG_CORE_COUNT = 0;
    public static final int CONFIG_MEMORY_WORDS_PER_CORE = 1;
    public static final int CONFIG_INSTRUCTION_BUDGET = 2;
    public static final int CONFIG_STRIDE = 3;

    /**
     * Non-standard custom-0 instruction used only as deterministic semihosting stop.
     * The exit code is read from x10/a0.
     */
    public static final int XM3_HALT = 0x0000_000B;

    private Rv32i() {
    }

    static int signExtend(int value, int bits) {
        int shift = Integer.SIZE - bits;
        return value << shift >> shift;
    }

    static int immediateI(int instruction) {
        return instruction >> 20;
    }

    static int immediateS(int instruction) {
        int value = ((instruction >>> 25) << 5) | ((instruction >>> 7) & 0x1F);
        return signExtend(value, 12);
    }

    static int immediateB(int instruction) {
        int value = ((instruction >>> 31) << 12)
                | (((instruction >>> 7) & 0x1) << 11)
                | (((instruction >>> 25) & 0x3F) << 5)
                | (((instruction >>> 8) & 0xF) << 1);
        return signExtend(value, 13);
    }

    static int immediateU(int instruction) {
        return instruction & 0xFFFFF000;
    }

    static int immediateJ(int instruction) {
        int value = ((instruction >>> 31) << 20)
                | (((instruction >>> 12) & 0xFF) << 12)
                | (((instruction >>> 20) & 0x1) << 11)
                | (((instruction >>> 21) & 0x3FF) << 1);
        return signExtend(value, 21);
    }

    static boolean unsignedLessThan(int left, int right) {
        return (left ^ Integer.MIN_VALUE) < (right ^ Integer.MIN_VALUE);
    }
}
