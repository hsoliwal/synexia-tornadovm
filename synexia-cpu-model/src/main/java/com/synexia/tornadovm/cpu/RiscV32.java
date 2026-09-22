/*
 * Copyright 2026 Synexia contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.synexia.tornadovm.cpu;

/**
 * Constants for the Synexia RV32IMAC execution model.
 *
 * <p>The machine state is intentionally represented by primitive TornadoVM arrays. Register and
 * memory storage use a lane-coalesced structure-of-arrays layout so neighboring accelerator
 * work-items access neighboring words whenever virtual cores execute similar code.
 */
public final class RiscV32 {

    public static final int REGISTER_COUNT = 32;

    public static final int STATUS_RUNNING = 0;
    public static final int STATUS_HALTED = 1;
    public static final int STATUS_TRAPPED = 2;
    public static final int STATUS_WAITING = 3;

    public static final int TRAP_NONE = 0;
    public static final int TRAP_INSTRUCTION_ADDRESS_MISALIGNED = 0;
    public static final int TRAP_INSTRUCTION_ACCESS_FAULT = 1;
    public static final int TRAP_ILLEGAL_INSTRUCTION = 2;
    public static final int TRAP_BREAKPOINT = 3;
    public static final int TRAP_LOAD_ADDRESS_MISALIGNED = 4;
    public static final int TRAP_LOAD_ACCESS_FAULT = 5;
    public static final int TRAP_STORE_ADDRESS_MISALIGNED = 6;
    public static final int TRAP_STORE_ACCESS_FAULT = 7;
    public static final int TRAP_ECALL_M_MODE = 11;

    /** Stop on traps instead of vectoring into mtvec. Useful for bare-metal tests/debugging. */
    public static final int FLAG_VECTOR_TRAPS = 1;
    /** Treat EBREAK as an environment halt. Clear this flag for architectural breakpoint traps. */
    public static final int FLAG_EBREAK_HALT = 1 << 1;

    public static final int DEFAULT_EXECUTION_FLAGS = FLAG_EBREAK_HALT;

    public static final int CSR_MSTATUS = 0x300;
    public static final int CSR_MISA = 0x301;
    public static final int CSR_MIE = 0x304;
    public static final int CSR_MTVEC = 0x305;
    public static final int CSR_MSCRATCH = 0x340;
    public static final int CSR_MEPC = 0x341;
    public static final int CSR_MCAUSE = 0x342;
    public static final int CSR_MTVAL = 0x343;
    public static final int CSR_MIP = 0x344;
    public static final int CSR_MCYCLE = 0xb00;
    public static final int CSR_MINSTRET = 0xb02;
    public static final int CSR_CYCLE = 0xc00;
    public static final int CSR_INSTRET = 0xc02;
    public static final int CSR_MHARTID = 0xf14;

    public static final int CSR_SLOT_MSTATUS = 0;
    public static final int CSR_SLOT_MIE = 1;
    public static final int CSR_SLOT_MTVEC = 2;
    public static final int CSR_SLOT_MSCRATCH = 3;
    public static final int CSR_SLOT_MEPC = 4;
    public static final int CSR_SLOT_MCAUSE = 5;
    public static final int CSR_SLOT_MTVAL = 6;
    public static final int CSR_SLOT_MIP = 7;
    public static final int CSR_SLOT_COUNT = 8;

    public static final int MSTATUS_MIE = 1 << 3;
    public static final int MSTATUS_MPIE = 1 << 7;
    public static final int MSTATUS_MPP_SHIFT = 11;
    public static final int MSTATUS_MPP_MASK = 3 << MSTATUS_MPP_SHIFT;

    /** MXL=32 plus A, C, I and M extension bits. */
    public static final int MISA_RV32_IMAC = 0x40001105;

    private RiscV32() {
    }

    public static int csrSlot(int address) {
        switch (address) {
            case CSR_MSTATUS:
                return CSR_SLOT_MSTATUS;
            case CSR_MIE:
                return CSR_SLOT_MIE;
            case CSR_MTVEC:
                return CSR_SLOT_MTVEC;
            case CSR_MSCRATCH:
                return CSR_SLOT_MSCRATCH;
            case CSR_MEPC:
                return CSR_SLOT_MEPC;
            case CSR_MCAUSE:
                return CSR_SLOT_MCAUSE;
            case CSR_MTVAL:
                return CSR_SLOT_MTVAL;
            case CSR_MIP:
                return CSR_SLOT_MIP;
            default:
                return -1;
        }
    }

    public static String statusName(int status) {
        switch (status) {
            case STATUS_RUNNING:
                return "RUNNING";
            case STATUS_HALTED:
                return "HALTED";
            case STATUS_TRAPPED:
                return "TRAPPED";
            case STATUS_WAITING:
                return "WAITING";
            default:
                return "UNKNOWN(" + status + ")";
        }
    }

    public static String trapName(int cause) {
        switch (cause) {
            case TRAP_INSTRUCTION_ADDRESS_MISALIGNED:
                return "INSTRUCTION_ADDRESS_MISALIGNED";
            case TRAP_INSTRUCTION_ACCESS_FAULT:
                return "INSTRUCTION_ACCESS_FAULT";
            case TRAP_ILLEGAL_INSTRUCTION:
                return "ILLEGAL_INSTRUCTION";
            case TRAP_BREAKPOINT:
                return "BREAKPOINT";
            case TRAP_LOAD_ADDRESS_MISALIGNED:
                return "LOAD_ADDRESS_MISALIGNED";
            case TRAP_LOAD_ACCESS_FAULT:
                return "LOAD_ACCESS_FAULT";
            case TRAP_STORE_ADDRESS_MISALIGNED:
                return "STORE_ADDRESS_MISALIGNED";
            case TRAP_STORE_ACCESS_FAULT:
                return "STORE_ACCESS_FAULT";
            case TRAP_ECALL_M_MODE:
                return "ECALL_M_MODE";
            default:
                return "CAUSE(" + cause + ")";
        }
    }
}
