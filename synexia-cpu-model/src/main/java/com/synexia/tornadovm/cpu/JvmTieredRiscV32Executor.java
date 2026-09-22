/*
 * Copyright 2026 Synexia contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.synexia.tornadovm.cpu;

/**
 * JVM reference executor for the tiered engine.
 *
 * <p>Each quantum attempts one compiled basic block per core, then invokes the architectural
 * interpreter only for lanes marked as misses by the block kernel.
 */
public final class JvmTieredRiscV32Executor implements RiscV32Executor {

    @Override
    public RiscV32ExecutionResult execute(RiscV32Machine machine, int instructionsPerQuantum, int maxQuanta) {
        JvmRiscV32Executor.validate(machine, instructionsPerQuantum, maxQuanta);
        if (!machine.hasBlockCache()) {
            throw new IllegalStateException("buildBlockCache must be called before tiered execution");
        }

        long start = System.nanoTime();
        int quanta = 0;
        while (quanta < maxQuanta && !machine.allStopped()) {
            RiscV32ChainedBlockKernel.runBlocks(
                    machine.registers(), machine.pc(), machine.status(), machine.trapCause(),
                    machine.trapValue(), machine.retiredInstructions(), machine.csrs(), machine.reservations(),
                    machine.memory(), machine.decodedInstructionLengths(), machine.blockBySlot(),
                    machine.blockDescriptors(), machine.blockValid(), machine.microOps(),
                    machine.tierFallbackBudget(), machine.compiledBlockExecutionsArray(),
                    machine.codeCacheBase(), machine.wordsPerCore(),
                    machine.executionFlags(), instructionsPerQuantum);

            RiscV32Kernel.runQuantum(
                    machine.registers(), machine.pc(), machine.status(), machine.trapCause(),
                    machine.trapValue(), machine.retiredInstructions(), machine.csrs(), machine.reservations(),
                    machine.memory(), machine.decodedInstructions(), machine.decodedRawInstructions(),
                    machine.decodedInstructionLengths(), machine.blockValid(), machine.tierFallbackBudget(), 1,
                    machine.codeCacheBase(), machine.codeCacheEnd(),
                    machine.wordsPerCore(), machine.executionFlags(), instructionsPerQuantum);
            quanta++;
        }

        long elapsed = System.nanoTime() - start;
        return new RiscV32ExecutionResult("JVM-TIERED", quanta, machine.allStopped(), machine.runningCores(),
                machine.totalRetiredInstructions(), elapsed);
    }
}
