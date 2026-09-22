/*
 * Copyright 2026 Synexia contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.synexia.tornadovm.cpu;

import static com.synexia.tornadovm.cpu.RiscV32Assembler.add;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.addi;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.and;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.div;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.ebreak;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.lw;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.mul;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.or;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.rem;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.sll;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.slt;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.sub;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.sw;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.xor;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

/**
 * Differential tests use the architectural interpreter as the executable oracle for the compiled
 * tier. Fixed seeds make failures reproducible.
 */
public class RiscV32DifferentialTierTest {

    private static final int MEMORY_BYTES = 4096;
    private static final int DATA_BASE = 2048;
    private static final int GENERATED_INSTRUCTIONS = 256;

    @Test
    public void generatedIntegerAndMemoryWorkloadsMatchInterpreter() {
        for (int seed = 1; seed <= 8; seed++) {
            verifySeed(0x5EED_0000L + seed);
        }
    }

    private static void verifySeed(long seed) {
        Random random = new Random(seed);
        int[] program = new int[GENERATED_INSTRUCTIONS + 2];
        int cursor = 0;
        program[cursor++] = addi(20, 0, DATA_BASE);

        while (cursor < program.length - 1) {
            int rd = 1 + random.nextInt(15);
            int rs1 = 1 + random.nextInt(15);
            int rs2 = 1 + random.nextInt(15);
            int op = random.nextInt(13);

            switch (op) {
                case 0:
                    program[cursor++] = addi(rd, rs1, random.nextInt(511) - 255);
                    break;
                case 1:
                    program[cursor++] = add(rd, rs1, rs2);
                    break;
                case 2:
                    program[cursor++] = sub(rd, rs1, rs2);
                    break;
                case 3:
                    program[cursor++] = xor(rd, rs1, rs2);
                    break;
                case 4:
                    program[cursor++] = or(rd, rs1, rs2);
                    break;
                case 5:
                    program[cursor++] = and(rd, rs1, rs2);
                    break;
                case 6:
                    program[cursor++] = mul(rd, rs1, rs2);
                    break;
                case 7:
                    program[cursor++] = div(rd, rs1, rs2);
                    break;
                case 8:
                    program[cursor++] = rem(rd, rs1, rs2);
                    break;
                case 9:
                    program[cursor++] = sll(rd, rs1, rs2);
                    break;
                case 10:
                    program[cursor++] = slt(rd, rs1, rs2);
                    break;
                case 11: {
                    int offset = (random.nextInt(16) << 2);
                    program[cursor++] = sw(rs1, 20, offset);
                    break;
                }
                default: {
                    int offset = (random.nextInt(16) << 2);
                    program[cursor++] = lw(rd, 20, offset);
                    break;
                }
            }
        }
        program[cursor] = ebreak();

        RiscV32Machine reference = new RiscV32Machine(1, MEMORY_BYTES);
        RiscV32Machine compiled = new RiscV32Machine(1, MEMORY_BYTES);
        reference.loadProgramAll(0, program);
        compiled.loadProgramAll(0, program);

        // Seed identical non-zero architectural state. Program initialization then establishes x20.
        for (int register = 1; register < RiscV32.REGISTER_COUNT; register++) {
            int value = random.nextInt();
            reference.register(0, register, value);
            compiled.register(0, register, value);
        }
        for (int offset = 0; offset < 64; offset += 4) {
            int value = random.nextInt();
            reference.writeWord(0, DATA_BASE + offset, value);
            compiled.writeWord(0, DATA_BASE + offset, value);
        }

        compiled.buildCodeCache(0, 0, program.length * 4);
        compiled.buildBlockCache(512);

        RiscV32ExecutionResult referenceResult =
                new JvmRiscV32Executor().execute(reference, 512, 4);
        RiscV32ExecutionResult compiledResult =
                new JvmTieredRiscV32Executor().execute(compiled, 512, 4);

        assertTrue("reference seed " + seed, referenceResult.allStopped());
        assertTrue("compiled seed " + seed, compiledResult.allStopped());
        assertEquals("status seed " + seed, reference.status(0), compiled.status(0));
        assertEquals("pc seed " + seed, reference.pc(0), compiled.pc(0));
        assertEquals("retired seed " + seed,
                reference.retiredInstructions(0), compiled.retiredInstructions(0));

        for (int register = 0; register < RiscV32.REGISTER_COUNT; register++) {
            assertEquals("x" + register + " seed " + seed,
                    reference.register(0, register), compiled.register(0, register));
        }
        for (int offset = 0; offset < 64; offset += 4) {
            assertEquals("memory+" + offset + " seed " + seed,
                    reference.readWord(0, DATA_BASE + offset),
                    compiled.readWord(0, DATA_BASE + offset));
        }
    }
}
