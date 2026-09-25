/* SPDX-License-Identifier: Apache-2.0 */
package com.synexia.m3.cpugpu;

import java.util.Arrays;

/** Command-line smoke runner for CPU, GPU, and CPU-vs-GPU differential modes. */
public final class Rv32iDemo {
    private static final int DEFAULT_CORES = 1024;
    private static final int DEFAULT_BUDGET = 1024;
    private static final int MEMORY_BYTES_PER_CORE = 4096;

    private Rv32iDemo() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "cpu";
        int cores = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_CORES;
        int budget = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_BUDGET;
        Rv32iBatch batch = newBatch(cores);

        switch (mode) {
            case "cpu" -> {
                new CpuRv32iEngine().runSlice(batch, budget);
                printSummary("cpu", batch);
            }
            case "gpu" -> {
                try (TornadoRv32iSession session = new TornadoRv32iSession(batch)) {
                    session.runSlice(budget);
                    printSummary("gpu", batch);
                }
            }
            case "verify" -> verify(batch, budget);
            default -> throw new IllegalArgumentException("mode must be cpu, gpu, or verify");
        }
    }

    static Rv32iBatch newBatch(int cores) {
        Rv32iBatch batch = new Rv32iBatch(cores, MEMORY_BYTES_PER_CORE);
        int[] program = sumOneToHundredProgram();
        batch.loadWordsAllCores(0, program);
        for (int core = 0; core < cores; core++) {
            batch.setProgramCounter(core, 0);
        }
        return batch;
    }

    static int[] sumOneToHundredProgram() {
        return Rv32iAssembler.words(
                Rv32iAssembler.addi(1, 0, 0),
                Rv32iAssembler.addi(2, 0, 1),
                Rv32iAssembler.addi(3, 0, 101),
                Rv32iAssembler.add(1, 1, 2),
                Rv32iAssembler.addi(2, 2, 1),
                Rv32iAssembler.blt(2, 3, -8),
                Rv32iAssembler.addi(10, 1, 0),
                Rv32iAssembler.halt());
    }

    private static void verify(Rv32iBatch actual, int budget) throws Exception {
        Rv32iBatch expected = actual.copy();
        new CpuRv32iEngine().runSlice(expected, budget);
        try (TornadoRv32iSession session = new TornadoRv32iSession(actual)) {
            session.runSlice(budget);
            session.synchronizeAll();
        }
        requireEqual("registers", expected.snapshotRegisters(), actual.snapshotRegisters());
        requireEqual("state", expected.snapshotState(), actual.snapshotState());
        requireEqual("memory", expected.snapshotMemory(), actual.snapshotMemory());
        printSummary("verified", actual);
    }

    private static void requireEqual(String name, int[] expected, int[] actual) {
        if (!Arrays.equals(expected, actual)) {
            throw new IllegalStateException("CPU/GPU mismatch in " + name);
        }
    }

    private static void printSummary(String mode, Rv32iBatch batch) {
        System.out.printf("mode=%s cores=%d status0=%d retired0=%d a0=%d exit0=%d%n",
                mode,
                batch.coreCount(),
                batch.status(0),
                batch.retiredInstructions(0),
                batch.register(0, 10),
                batch.exitCode(0));
    }
}
