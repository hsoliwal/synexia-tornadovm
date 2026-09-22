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
import static com.synexia.tornadovm.cpu.RiscV32Assembler.lui;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.mul;

import java.util.Locale;

/**
 * Reproducible interpreter-versus-tiered throughput benchmark.
 *
 * <p>This is deliberately dependency-free rather than pulling JMH into the runtime module. It is
 * intended as a smoke/performance diagnostic on the exact hardware where TornadoVM will run.
 * Correctness is checked before reporting throughput; CI does not assert a speedup because hosted
 * runners and accelerator availability are not stable performance environments.
 */
public final class RiscV32TierBenchmark {

    private static final int DEFAULT_CORES = 64;
    private static final int DEFAULT_ITERATIONS = 50_000;
    private static final int DEFAULT_ROUNDS = 3;
    private static final int MEMORY_BYTES = 4096;

    private RiscV32TierBenchmark() {
    }

    public static void main(String[] args) {
        boolean tornado = args.length > 0 && "tornado".equalsIgnoreCase(args[0]);
        int offset = tornado ? 1 : 0;
        int cores = intArg(args, offset, DEFAULT_CORES);
        int iterations = intArg(args, offset + 1, DEFAULT_ITERATIONS);
        int rounds = intArg(args, offset + 2, DEFAULT_ROUNDS);

        int[] program = workload(iterations);

        // One unreported warm-up makes JVM/JIT comparisons less misleading.
        run(false, tornado, cores, program);
        run(true, tornado, cores, program);

        long interpreterBest = Long.MAX_VALUE;
        long tieredBest = Long.MAX_VALUE;
        RiscV32ExecutionResult interpreterResult = null;
        RiscV32ExecutionResult tieredResult = null;
        RiscV32CompilationStats compilation = null;

        for (int round = 0; round < rounds; round++) {
            Run interpreter = run(false, tornado, cores, program);
            Run tiered = run(true, tornado, cores, program);

            verifyEquivalent(interpreter.machine, tiered.machine);

            if (interpreter.result.elapsedNanos() < interpreterBest) {
                interpreterBest = interpreter.result.elapsedNanos();
                interpreterResult = interpreter.result;
            }
            if (tiered.result.elapsedNanos() < tieredBest) {
                tieredBest = tiered.result.elapsedNanos();
                tieredResult = tiered.result;
                compilation = tiered.compilation;
            }
        }

        double speedup = tieredBest == 0 ? 0.0 : (double) interpreterBest / tieredBest;
        System.out.printf(Locale.ROOT,
                "backend=%s cores=%d iterations/core=%d rounds=%d%n",
                tornado ? "TORNADO" : "JVM", cores, iterations, rounds);
        System.out.println("interpreter=" + interpreterResult);
        System.out.println("tiered=" + tieredResult);
        System.out.println("compilation=" + compilation);
        System.out.printf(Locale.ROOT, "best-time speedup=%.3fx%n", speedup);
    }

    private static Run run(boolean tiered, boolean tornado, int cores, int[] program) {
        RiscV32Machine machine = new RiscV32Machine(cores, MEMORY_BYTES);
        machine.loadProgramAll(0, program);

        RiscV32CompilationStats compilation = null;
        RiscV32Executor executor;
        if (tiered) {
            machine.buildCodeCache(0, 0, program.length * Integer.BYTES);
            machine.buildBlockCache(64);
            compilation = machine.compilationStats();
            executor = tornado ? new TornadoTieredRiscV32Executor() : new JvmTieredRiscV32Executor();
        } else {
            executor = tornado ? new TornadoRiscV32Executor() : new JvmRiscV32Executor();
        }

        // A quantum large enough to expose block chaining without making a single kernel unbounded.
        RiscV32ExecutionResult result = executor.execute(machine, 4096, 1024);
        if (!result.allStopped()) {
            throw new IllegalStateException("benchmark did not terminate: " + result);
        }
        return new Run(machine, result, compilation);
    }

    private static int[] workload(int iterations) {
        if (iterations <= 0) {
            throw new IllegalArgumentException("iterations must be positive");
        }

        int upper = (iterations + 0x800) >>> 12;
        int lower = iterations - (upper << 12);
        return new int[] {
                addi(1, 0, 0),
                lui(2, upper),
                addi(2, 2, lower),
                addi(1, 1, 1),
                mul(3, 1, 1),
                add(4, 4, 3),
                blt(1, 2, -12),
                ebreak()
        };
    }

    private static void verifyEquivalent(RiscV32Machine reference, RiscV32Machine tiered) {
        if (reference.cores() != tiered.cores()) {
            throw new AssertionError("core count differs");
        }
        for (int core = 0; core < reference.cores(); core++) {
            if (reference.status(core) != tiered.status(core)
                    || reference.pc(core) != tiered.pc(core)
                    || reference.retiredInstructions(core) != tiered.retiredInstructions(core)) {
                throw new AssertionError("architectural state differs for core " + core);
            }
            for (int register = 0; register < RiscV32.REGISTER_COUNT; register++) {
                if (reference.register(core, register) != tiered.register(core, register)) {
                    throw new AssertionError("x" + register + " differs for core " + core);
                }
            }
        }
    }

    private static int intArg(String[] args, int index, int defaultValue) {
        if (index >= args.length) {
            return defaultValue;
        }
        int value = Integer.parseInt(args[index]);
        if (value <= 0) {
            throw new IllegalArgumentException("numeric arguments must be positive");
        }
        return value;
    }

    private static final class Run {
        private final RiscV32Machine machine;
        private final RiscV32ExecutionResult result;
        private final RiscV32CompilationStats compilation;

        private Run(RiscV32Machine machine, RiscV32ExecutionResult result,
                RiscV32CompilationStats compilation) {
            this.machine = machine;
            this.result = result;
            this.compilation = compilation;
        }
    }
}
