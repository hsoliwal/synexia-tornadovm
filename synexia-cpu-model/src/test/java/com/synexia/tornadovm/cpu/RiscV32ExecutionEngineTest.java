/*
 * Copyright 2026 Synexia contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.synexia.tornadovm.cpu;

import static com.synexia.tornadovm.cpu.RiscV32Assembler.addi;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.ebreak;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RiscV32ExecutionEngineTest {

    @Test
    public void preparedMachineAutomaticallyUsesTieredJvmBackend() {
        RiscV32Machine machine = new RiscV32Machine(8, 256);
        int[] program = {
                addi(1, 0, 40),
                addi(1, 1, 2),
                ebreak()
        };
        machine.loadProgramAll(0, program);

        RiscV32ExecutionEngine engine = RiscV32ExecutionEngine.jvm();
        RiscV32CompilationStats compilation =
                engine.prepareCode(machine, 0, 0, program.length * Integer.BYTES);
        RiscV32ExecutionResult result = engine.execute(machine, 32, 4);

        assertTrue(machine.hasBlockCache());
        assertTrue(compilation.compiledBlocks() > 0);
        assertTrue(compilation.compiledCoverage() > 0.0);
        assertEquals("JVM-TIERED", result.backend());
        assertTrue(result.allStopped());
        for (int core = 0; core < machine.cores(); core++) {
            assertEquals(42, machine.register(core, 1));
        }
    }

    @Test
    public void unpreparedMachineRetainsInterpreterFallback() {
        RiscV32Machine machine = new RiscV32Machine(1, 128);
        machine.loadProgramAll(0,
                addi(1, 0, 7),
                ebreak());

        RiscV32ExecutionResult result =
                RiscV32ExecutionEngine.jvm().execute(machine, 16, 2);

        assertFalse(machine.hasBlockCache());
        assertEquals("JVM", result.backend());
        assertEquals(7, machine.register(0, 1));
    }

    @Test
    public void executionResultReportsThroughput() {
        RiscV32ExecutionResult result =
                new RiscV32ExecutionResult("TEST", 1, true, 0, 2_000_000L, 1_000_000_000L);

        assertEquals(2_000_000.0, result.instructionsPerSecond(), 0.0);
        assertEquals(2.0, result.millionInstructionsPerSecond(), 0.0);
    }
}
