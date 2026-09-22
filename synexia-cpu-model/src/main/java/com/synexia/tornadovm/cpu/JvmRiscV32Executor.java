/*
 * Copyright 2026 Synexia contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.synexia.tornadovm.cpu;

import java.util.Objects;

/**
 * Deterministic reference backend that invokes the accelerator kernel as ordinary Java.
 */
public final class JvmRiscV32Executor implements RiscV32Executor {

    @Override
    public RiscV32ExecutionResult execute(RiscV32Machine machine, int instructionsPerQuantum, int maxQuanta) {
        validate(machine, instructionsPerQuantum, maxQuanta);

        long start = System.nanoTime();
        int quanta = 0;
        while (quanta < maxQuanta && !machine.allStopped()) {
            RiscV32Kernel.runQuantum(machine.registers(), machine.pc(), machine.status(), machine.trapCause(),
                    machine.trapValue(), machine.retiredInstructions(), machine.csrs(), machine.reservations(),
                    machine.memory(), machine.wordsPerCore(), machine.executionFlags(), instructionsPerQuantum);
            quanta++;
        }
        long elapsed = System.nanoTime() - start;

        return new RiscV32ExecutionResult("JVM", quanta, machine.allStopped(), machine.runningCores(),
                machine.totalRetiredInstructions(), elapsed);
    }

    static void validate(RiscV32Machine machine, int instructionsPerQuantum, int maxQuanta) {
        Objects.requireNonNull(machine, "machine");
        if (instructionsPerQuantum <= 0) {
            throw new IllegalArgumentException("instructionsPerQuantum must be positive");
        }
        if (maxQuanta <= 0) {
            throw new IllegalArgumentException("maxQuanta must be positive");
        }
    }
}
