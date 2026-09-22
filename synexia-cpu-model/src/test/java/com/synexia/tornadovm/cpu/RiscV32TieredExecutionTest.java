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
import static com.synexia.tornadovm.cpu.RiscV32Assembler.jal;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.lrW;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.lui;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.mul;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.sw;
import static com.synexia.tornadovm.cpu.RiscV32CompressedAssembler.cAdd;
import static com.synexia.tornadovm.cpu.RiscV32CompressedAssembler.cAddi;
import static com.synexia.tornadovm.cpu.RiscV32CompressedAssembler.cEbreak;
import static com.synexia.tornadovm.cpu.RiscV32CompressedAssembler.cLi;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RiscV32TieredExecutionTest {

    private final JvmTieredRiscV32Executor executor = new JvmTieredRiscV32Executor();

    @Test
    public void compilesAndExecutesArithmeticBlockBeforeInterpreterBoundary() {
        RiscV32Machine machine = new RiscV32Machine(16, 512);
        machine.loadProgramAll(0,
                addi(1, 0, 6),
                addi(2, 0, 7),
                mul(3, 1, 2),
                add(4, 3, 1),
                ebreak());
        machine.buildCodeCache(0, 0, 20);
        machine.buildBlockCache(32);

        assertTrue(machine.hasBlockCache());
        assertTrue(machine.compiledBlockCount() > 0);
        assertTrue(machine.compiledOperationCount() >= 4);

        RiscV32ExecutionResult result = executor.execute(machine, 32, 8);

        assertTrue(result.allStopped());
        for (int core = 0; core < machine.cores(); core++) {
            assertEquals(42, machine.register(core, 3));
            assertEquals(48, machine.register(core, 4));
            assertEquals(RiscV32.STATUS_HALTED, machine.status(core));
            assertTrue(machine.compiledBlockExecutions(core) >= 1);
        }
    }

    @Test
    public void directBranchLoopReentersCompiledTargetBlock() {
        RiscV32Machine machine = new RiscV32Machine(4, 512);
        machine.loadProgramAll(0,
                addi(1, 0, 0),
                addi(2, 0, 10),
                addi(1, 1, 1),
                blt(1, 2, -4),
                ebreak());
        machine.buildCodeCache(0, 0, 20);
        machine.buildBlockCache(16);

        executor.execute(machine, 16, 32);

        for (int core = 0; core < machine.cores(); core++) {
            assertEquals(10, machine.register(core, 1));
            assertEquals(RiscV32.STATUS_HALTED, machine.status(core));
            assertTrue(machine.compiledBlockExecutions(core) >= 10);
        }
    }

    @Test
    public void compressedInstructionsCompileIntoSameMicroOpTier() {
        RiscV32Machine machine = new RiscV32Machine(8, 256);
        machine.loadHalfwordsAll(0,
                cLi(8, 5),
                cAddi(8, 3),
                cLi(9, 2),
                cAdd(8, 9),
                cEbreak());
        machine.buildCodeCache(0, 0, 10);
        machine.buildBlockCache(16);

        executor.execute(machine, 16, 8);

        for (int core = 0; core < machine.cores(); core++) {
            assertEquals(10, machine.register(core, 8));
            assertEquals(RiscV32.STATUS_HALTED, machine.status(core));
            assertTrue(machine.compiledBlockExecutions(core) >= 1);
        }
    }

    @Test
    public void unsupportedAtomicInstructionFallsBackWithoutStateLoss() {
        RiscV32Machine machine = new RiscV32Machine(1, 512);
        machine.writeWord(0, 128, 91);
        machine.loadProgramAll(0,
                addi(1, 0, 128),
                lrW(2, 1),
                ebreak());
        machine.buildCodeCache(0, 0, 12);
        machine.buildBlockCache(16);

        executor.execute(machine, 16, 8);

        assertEquals(91, machine.register(0, 2));
        assertEquals(RiscV32.STATUS_HALTED, machine.status(0));
        assertTrue(machine.compiledBlockExecutions(0) >= 1);
    }

    @Test
    public void compiledStoreToCodeInvalidatesBlocksAndFallsBackSafely() {
        RiscV32Machine machine = new RiscV32Machine(1, 512);
        machine.loadProgramAll(0,
                lui(2, 0x100),
                addi(2, 2, 0x73),
                sw(2, 0, 20),
                jal(0, 8),
                addi(3, 0, 1),
                addi(3, 0, 99),
                ebreak());
        machine.buildCodeCache(0, 0, 28);
        machine.buildBlockCache(32);

        executor.execute(machine, 32, 8);

        assertEquals(0, machine.register(0, 3));
        assertEquals(0x00100073, machine.readWord(0, 20));
        assertEquals(RiscV32.STATUS_HALTED, machine.status(0));
        assertTrue(machine.compiledBlockExecutions(0) >= 1);
    }

    @Test
    public void microOpPackingIsDenseAndLossless() {
        long op = RiscV32MicroOp.pack(RiscV32MicroOp.ADDI, 31, 30, 29, -123456789, 2);

        assertEquals(RiscV32MicroOp.ADDI, RiscV32MicroOp.kind(op));
        assertEquals(31, RiscV32MicroOp.rd(op));
        assertEquals(30, RiscV32MicroOp.rs1(op));
        assertEquals(29, RiscV32MicroOp.rs2(op));
        assertEquals(-123456789, RiscV32MicroOp.immediate(op));
        assertEquals(2, RiscV32MicroOp.instructionBytes(op));
    }
}
