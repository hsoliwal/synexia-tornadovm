/*
 * Copyright 2026 Synexia contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.synexia.tornadovm.cpu;

/**
 * Constants for the Synexia RV32IM execution model.
 *
 * <p>The model intentionally keeps architectural state in primitive TornadoVM arrays so one
 * accelerator work-item can represent one virtual CPU without a Java object per emulated core.
 */
public final class RiscV32 {

    public static final int REGISTER_COUNT = 32;

    public static final int STATUS_RUNNING = 0;
    public static final int STATUS_HALTED = 1;
    public static final int STATUS_TRAPPED = 2;

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

    private RiscV32() {
    }

    public static String statusName(int status) {
        switch (status) {
            case STATUS_RUNNING:
                return "RUNNING";
            case STATUS_HALTED:
                return "HALTED";
            case STATUS_TRAPPED:
                return "TRAPPED";
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
