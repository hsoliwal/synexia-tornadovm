/*
 * Copyright 2026 Synexia contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.synexia.tornadovm.cpu;

import static com.synexia.tornadovm.cpu.RiscV32Assembler.addi;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.blt;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.ebreak;

/**
 * Reproducible interpreter-vs-tier throughput harness.
 *
 * <p>No performance assertion is embedded in tests: this program is intended to be run on the
 * actual CPU/GPU being evaluated.
 */
public final class RiscV32PerformanceDemo {

    private static final int DEFAULT_CORES = 4096;
    private static final int DEFAULT_LOOP_ITERATIONS = 1000;
    private static final int DEFAULT_QUANTUM = 512;

    private RiscV32PerformanceDemo() {
    }

    public static void main(String[] args) {
        String backend = args.length > 0 ? args[0].toLowerCase() : "jvm";
        int cores = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_CORES;
        int loopIterations = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_LOOP_ITERATIONS;
        int quantum = args.length > 3 ? Integer.parseInt(args[3]) : DEFAULT_QUANTUM;

        if (cores <= 0) {
            throw new IllegalArgumentException("cores must be positive");
        }
        if (loopIterations <= 0 || loopIterations > 2047) {
            throw new IllegalArgumentException("loopIterations must be in [1,2047]");
        }
        if (quantum <= 0) {
            throw new IllegalArgumentException("quantum must be positive");
        }

        int[] program = {
                addi(1, 0, 0),
                addi(2, 0, loopIterations),
                addi(1, 1, 1),
                blt(1, 2, -4),
                ebreak()
        };

        RiscV32Machine interpreter = machine(cores, program);
        RiscV32Machine tiered = machine(cores, program);
        tiered.buildCodeCache(0, 0, program.length * 4);
        tiered.buildBlockCache(Math.max(16, quantum));

        int guestInstructionsPerCore = 2 + loopIterations * 2 + 1;
        int maxQuanta = Math.max(4,
                (guestInstructionsPerCore + quantum - 1) / quantum + 2);

        RiscV32ExecutionResult interpreterResult;
        RiscV32ExecutionResult tieredResult;

        if ("tornado".equals(backend)) {
            try (TornadoRiscV32Session interpreterSession =
                            new TornadoRiscV32Session(interpreter, quantum);
                    TornadoRiscV32Session tieredSession =
                            new TornadoRiscV32Session(tiered, quantum)) {
                interpreterResult = interpreterSession.executeUntilStop(maxQuanta);
                tieredResult = tieredSession.executeUntilStop(maxQuanta);
            }
        } else if ("jvm".equals(backend)) {
            interpreterResult = new JvmRiscV32Executor()
                    .execute(interpreter, quantum, maxQuanta);
            tieredResult = new JvmTieredRiscV32Executor()
                    .execute(tiered, quantum, maxQuanta);
        } else {
            throw new IllegalArgumentException("backend must be 'jvm' or 'tornado'");
        }

        verify(interpreter, tiered, loopIterations);

        System.out.println("cores=" + cores
                + " loopIterations=" + loopIterations
                + " quantum=" + quantum
                + " guestInstructionsPerCore=" + guestInstructionsPerCore);
        System.out.println("compilation=" + tiered.compilationStats());
        print("interpreter", interpreterResult);
        print("tiered", tieredResult);

        double baseline = interpreterResult.instructionsPerSecond();
        double optimized = tieredResult.instructionsPerSecond();
        if (baseline > 0.0) {
            System.out.println("tiered/baseline throughput ratio=" + (optimized / baseline));
        }
    }

    private static RiscV32Machine machine(int cores, int[] program) {
        RiscV32Machine machine = new RiscV32Machine(cores, 64);
        machine.loadProgramAll(0, program);
        return machine;
    }

    private static void verify(RiscV32Machine interpreter, RiscV32Machine tiered, int expected) {
        if (interpreter.cores() != tiered.cores()) {
            throw new IllegalStateException("benchmark machines differ in core count");
        }
        for (int core = 0; core < interpreter.cores(); core++) {
            if (interpreter.register(core, 1) != expected
                    || tiered.register(core, 1) != expected
                    || interpreter.status(core) != RiscV32.STATUS_HALTED
                    || tiered.status(core) != RiscV32.STATUS_HALTED) {
                throw new IllegalStateException("semantic mismatch at core " + core);
            }
        }
    }

    private static void print(String label, RiscV32ExecutionResult result) {
        System.out.println(label
                + ": retired=" + result.retiredInstructions()
                + " quanta=" + result.quanta()
                + " elapsedMs=" + (result.elapsedNanos() / 1_000_000.0)
                + " MIPS=" + result.millionInstructionsPerSecond());
    }
}
