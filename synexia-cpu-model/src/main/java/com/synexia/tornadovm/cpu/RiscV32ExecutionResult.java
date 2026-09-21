/*
 * Copyright 2026 Synexia contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.synexia.tornadovm.cpu;

/**
 * Immutable summary of an emulator run.
 */
public final class RiscV32ExecutionResult {

    private final String backend;
    private final int quanta;
    private final boolean allStopped;
    private final int runningCores;
    private final long retiredInstructions;
    private final long elapsedNanos;

    public RiscV32ExecutionResult(String backend, int quanta, boolean allStopped, int runningCores,
            long retiredInstructions, long elapsedNanos) {
        this.backend = backend;
        this.quanta = quanta;
        this.allStopped = allStopped;
        this.runningCores = runningCores;
        this.retiredInstructions = retiredInstructions;
        this.elapsedNanos = elapsedNanos;
    }

    public String backend() {
        return backend;
    }

    public int quanta() {
        return quanta;
    }

    public boolean allStopped() {
        return allStopped;
    }

    public int runningCores() {
        return runningCores;
    }

    public long retiredInstructions() {
        return retiredInstructions;
    }

    public long elapsedNanos() {
        return elapsedNanos;
    }

    @Override
    public String toString() {
        return "RiscV32ExecutionResult{backend='" + backend + "', quanta=" + quanta
                + ", allStopped=" + allStopped + ", runningCores=" + runningCores
                + ", retiredInstructions=" + retiredInstructions + ", elapsedNanos=" + elapsedNanos + "}";
    }
}
