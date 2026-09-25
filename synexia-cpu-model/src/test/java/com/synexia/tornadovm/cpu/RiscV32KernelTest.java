/*
 * Copyright 2026 Synexia contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.synexia.tornadovm.cpu;

import static com.synexia.tornadovm.cpu.RiscV32Assembler.add;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.addi;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.blt;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.ebreak;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.lb;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.lbu;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.lw;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.mul;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.sb;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.sw;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RiscV32KernelTest {

    private final JvmRiscV32Executor executor = new JvmRiscV32Executor();

    @Test
    public void arithmeticMemoryAndMExtension() {
        RiscV32Machine machine = new RiscV32Machine(1, 512);
        machine.loadProgramAll(0,
                addi(1, 0, 7),
                addi(2, 0, 5),
                add(3, 1, 2),
                mul(4, 1, 2),
                sw(4, 0, 128),
                lw(5, 0, 128),
                ebreak());

        RiscV32ExecutionResult result = executor.execute(machine, 64, 4);

        assertTrue(result.allStopped());
        assertEquals(RiscV32.STATUS_HALTED, machine.status(0));
        assertEquals(12, machine.register(0, 3));
        assertEquals(35, machine.register(0, 4));
        assertEquals(35, machine.register(0, 5));
        assertEquals(35, machine.readWord(0, 128));
        assertEquals(7L, machine.retiredInstructions(0));
    }

    @Test
    public void branchLoop() {
        RiscV32Machine machine = new RiscV32Machine(1, 256);
        machine.loadProgramAll(0,
                addi(1, 0, 10),
                addi(2, 0, 0),
                addi(2, 2, 1),
                blt(2, 1, -4),
                ebreak());

        executor.execute(machine, 128, 4);

        assertEquals(10, machine.register(0, 2));
        assertEquals(RiscV32.STATUS_HALTED, machine.status(0));
    }

    @Test
    public void signedAndUnsignedByteLoads() {
        RiscV32Machine machine = new RiscV32Machine(1, 256);
        machine.loadProgramAll(0,
                addi(1, 0, -1),
                sb(1, 0, 100),
                lb(2, 0, 100),
                lbu(3, 0, 100),
                ebreak());

        executor.execute(machine, 64, 4);

        assertEquals(-1, machine.register(0, 2));
        assertEquals(255, machine.register(0, 3));
    }

    @Test
    public void misalignedLoadTrapsAtFaultingInstruction() {
        RiscV32Machine machine = new RiscV32Machine(1, 256);
        machine.loadProgramAll(0,
                lw(1, 0, 2),
                ebreak());

        executor.execute(machine, 16, 2);

        assertEquals(RiscV32.STATUS_TRAPPED, machine.status(0));
        assertEquals(RiscV32.TRAP_LOAD_ADDRESS_MISALIGNED, machine.trapCause(0));
        assertEquals(0, machine.pc(0));
        assertEquals(0L, machine.retiredInstructions(0));
    }

    @Test
    public void coresHaveIndependentRegisterAndMemoryState() {
        RiscV32Machine machine = new RiscV32Machine(8, 256);
        machine.loadProgramAll(0,
                addi(1, 1, 1),
                sw(1, 0, 128),
                ebreak());

        for (int core = 0; core < machine.cores(); core++) {
            machine.register(core, 1, core * 10);
        }

        RiscV32ExecutionResult result = executor.execute(machine, 32, 4);

        assertFalse(result.runningCores() > 0);
        for (int core = 0; core < machine.cores(); core++) {
            assertEquals(core * 10 + 1, machine.register(core, 1));
            assertEquals(core * 10 + 1, machine.readWord(core, 128));
        }
    }
}
