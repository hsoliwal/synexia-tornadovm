/*
 * Copyright 2026 Synexia contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.synexia.tornadovm.cpu;

import static com.synexia.tornadovm.cpu.RiscV32Assembler.addi;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.amoAddW;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.csrrc;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.csrrs;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.csrrw;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.ebreak;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.ecall;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.jal;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.lrW;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.lui;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.sw;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.mret;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.scW;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.wfi;
import static com.synexia.tornadovm.cpu.RiscV32CompressedAssembler.cAddi;
import static com.synexia.tornadovm.cpu.RiscV32CompressedAssembler.cEbreak;
import static com.synexia.tornadovm.cpu.RiscV32CompressedAssembler.cLi;
import static com.synexia.tornadovm.cpu.RiscV32CompressedAssembler.cLwsp;
import static com.synexia.tornadovm.cpu.RiscV32CompressedAssembler.cNop;
import static com.synexia.tornadovm.cpu.RiscV32CompressedAssembler.cSwsp;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RiscV32ExtendedKernelTest {

    private final JvmRiscV32Executor executor = new JvmRiscV32Executor();

    @Test
    public void compressedArithmeticLoadStoreAndHalt() {
        RiscV32Machine machine = new RiscV32Machine(4, 512);
        machine.loadHalfwordsAll(0,
                cLi(8, 5),
                cAddi(8, 3),
                cSwsp(8, 0),
                cLwsp(9, 0),
                cEbreak());

        for (int core = 0; core < machine.cores(); core++) {
            machine.register(core, 2, 128);
        }

        RiscV32ExecutionResult result = executor.execute(machine, 32, 4);

        assertTrue(result.allStopped());
        for (int core = 0; core < machine.cores(); core++) {
            assertEquals(8, machine.register(core, 8));
            assertEquals(8, machine.register(core, 9));
            assertEquals(8, machine.readWord(core, 128));
            assertEquals(RiscV32.STATUS_HALTED, machine.status(core));
        }
    }

    @Test
    public void thirtyTwoBitInstructionMayStartAtHalfwordBoundary() {
        RiscV32Machine machine = new RiscV32Machine(1, 256);
        int wide = addi(5, 0, 123);
        machine.loadHalfwordsAll(0,
                cNop(),
                wide & 0xffff,
                wide >>> 16,
                cEbreak());

        executor.execute(machine, 16, 2);

        assertEquals(123, machine.register(0, 5));
        assertEquals(RiscV32.STATUS_HALTED, machine.status(0));
        assertEquals(8, machine.pc(0));
    }

    @Test
    public void machineCsrsRoundTrip() {
        RiscV32Machine machine = new RiscV32Machine(1, 256);
        machine.loadProgramAll(0,
                addi(1, 0, 64),
                csrrw(2, RiscV32.CSR_MTVEC, 1),
                csrrs(3, RiscV32.CSR_MTVEC, 0),
                ebreak());

        executor.execute(machine, 32, 2);

        assertEquals(0, machine.register(0, 2));
        assertEquals(64, machine.register(0, 3));
        assertEquals(64, machine.csr(0, RiscV32.CSR_MTVEC));
        assertEquals(RiscV32.MISA_RV32_IMAC, machine.csr(0, RiscV32.CSR_MISA));
        assertEquals(0, machine.csr(0, RiscV32.CSR_MHARTID));
    }

    @Test
    public void ecallVectorsToMtvecAndMretReturns() {
        RiscV32Machine machine = new RiscV32Machine(1, 512)
                .withTrapVectoring(true);

        machine.loadProgramAll(0,
                ecall(),
                addi(5, 5, 1),
                ebreak());

        machine.loadProgram(0, 64,
                csrrs(1, RiscV32.CSR_MEPC, 0),
                addi(1, 1, 4),
                csrrw(0, RiscV32.CSR_MEPC, 1),
                mret());

        machine.csr(0, RiscV32.CSR_MTVEC, 64);
        executor.execute(machine, 64, 4);

        assertEquals(1, machine.register(0, 5));
        assertEquals(RiscV32.STATUS_HALTED, machine.status(0));
        // trapCause records the latest environment event (the final EBREAK halt), while
        // architectural mcause remains the last vectored exception handled by M-mode.
        assertEquals(RiscV32.TRAP_BREAKPOINT, machine.trapCause(0));
        assertEquals(4, machine.csr(0, RiscV32.CSR_MEPC));
        assertEquals(RiscV32.TRAP_ECALL_M_MODE, machine.csr(0, RiscV32.CSR_MCAUSE));
    }

    @Test
    public void misalignedLrUsesLoadAddressFault() {
        RiscV32Machine machine = new RiscV32Machine(1, 256);
        machine.loadProgramAll(0,
                addi(1, 0, 2),
                lrW(2, 1),
                ebreak());

        executor.execute(machine, 16, 2);

        assertEquals(RiscV32.STATUS_TRAPPED, machine.status(0));
        assertEquals(RiscV32.TRAP_LOAD_ADDRESS_MISALIGNED, machine.trapCause(0));
        assertEquals(2, machine.trapValue(0));
    }

    @Test
    public void atomicReadModifyWriteAndReservation() {
        RiscV32Machine machine = new RiscV32Machine(1, 512);
        machine.writeWord(0, 128, 7);
        machine.loadProgramAll(0,
                addi(1, 0, 128),
                addi(2, 0, 5),
                amoAddW(3, 1, 2),
                lrW(4, 1),
                addi(5, 0, 99),
                scW(6, 1, 5),
                scW(7, 1, 2),
                ebreak());

        executor.execute(machine, 64, 4);

        assertEquals(7, machine.register(0, 3));
        assertEquals(12, machine.register(0, 4));
        assertEquals(0, machine.register(0, 6));
        assertEquals(1, machine.register(0, 7));
        assertEquals(99, machine.readWord(0, 128));
    }

    @Test
    public void sharedCodeCacheExecutesCanonicalInstructions() {
        RiscV32Machine machine = new RiscV32Machine(8, 512);
        machine.loadProgramAll(0,
                addi(5, 0, 41),
                addi(5, 5, 1),
                ebreak());
        machine.buildCodeCache(0, 0, 12);

        assertTrue(machine.hasCodeCache());
        executor.execute(machine, 16, 2);

        for (int core = 0; core < machine.cores(); core++) {
            assertEquals(42, machine.register(core, 5));
            assertEquals(RiscV32.STATUS_HALTED, machine.status(core));
        }
    }

    @Test
    public void guestStoreInvalidatesSharedCodeCacheBeforeJump() {
        RiscV32Machine machine = new RiscV32Machine(1, 512);
        machine.loadProgramAll(0,
                lui(2, 0x100),          // x2 = 0x00100000
                addi(2, 2, 0x73),       // x2 = EBREAK encoding 0x00100073
                sw(2, 0, 20),           // overwrite cached instruction at 20
                jal(0, 8),              // jump 12 -> 20
                addi(3, 0, 1),          // unreachable filler
                addi(3, 0, 99),         // must be invalidated before execution
                ebreak());
        machine.buildCodeCache(0, 0, 28);

        executor.execute(machine, 32, 2);

        assertEquals(0, machine.register(0, 3));
        assertEquals(RiscV32.STATUS_HALTED, machine.status(0));
        assertEquals(0x00100073, machine.readWord(0, 20));
    }

    @Test
    public void hostWriteInvalidatesSharedCodeCache() {
        RiscV32Machine machine = new RiscV32Machine(1, 256);
        machine.loadProgramAll(0,
                addi(4, 0, 1),
                ebreak());
        machine.buildCodeCache(0, 0, 8);

        machine.loadProgramAll(0,
                addi(4, 0, 77),
                ebreak());

        executor.execute(machine, 16, 2);
        assertEquals(77, machine.register(0, 4));
    }

    @Test
    public void timerInterruptWakesWfiAndUsesVectoredMtvec() {
        RiscV32Machine machine = new RiscV32Machine(1, 512)
                .withTrapVectoring(true);

        machine.loadProgramAll(0,
                wfi(),
                addi(10, 10, 1),
                ebreak());

        int mtvecBase = 64;
        int timerVector = mtvecBase + 4 * 7;
        machine.loadProgram(0, timerVector,
                addi(1, 0, RiscV32.MIP_MTIP),
                csrrc(0, RiscV32.CSR_MIP, 1),
                mret());

        machine.csr(0, RiscV32.CSR_MTVEC, mtvecBase | 1);
        machine.csr(0, RiscV32.CSR_MIE, RiscV32.MIP_MTIP);
        machine.csr(0, RiscV32.CSR_MSTATUS, RiscV32.MSTATUS_MIE);

        executor.execute(machine, 16, 2);
        assertEquals(RiscV32.STATUS_WAITING, machine.status(0));

        machine.setMachineTimerInterrupt(0, true);
        executor.execute(machine, 64, 4);

        assertEquals(1, machine.register(0, 10));
        assertEquals(RiscV32.STATUS_HALTED, machine.status(0));
        assertEquals(0, machine.csr(0, RiscV32.CSR_MIP) & RiscV32.MIP_MTIP);
        assertEquals(RiscV32.INTERRUPT_MACHINE_TIMER, machine.csr(0, RiscV32.CSR_MCAUSE));
    }

    @Test
    public void wfiCanBeResumedWithoutResettingArchitecturalState() {
        RiscV32Machine machine = new RiscV32Machine(1, 256);
        machine.loadProgramAll(0,
                wfi(),
                addi(10, 10, 1),
                ebreak());

        executor.execute(machine, 16, 2);
        assertEquals(RiscV32.STATUS_WAITING, machine.status(0));
        assertEquals(4, machine.pc(0));

        machine.resumeWaitingCores();
        executor.execute(machine, 16, 2);

        assertEquals(1, machine.register(0, 10));
        assertEquals(RiscV32.STATUS_HALTED, machine.status(0));
    }
}
