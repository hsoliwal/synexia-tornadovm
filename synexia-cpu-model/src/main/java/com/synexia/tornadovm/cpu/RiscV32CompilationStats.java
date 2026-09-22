/*
 * Copyright 2026 Synexia contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.synexia.tornadovm.cpu;

/**
 * Host-side observability snapshot for the compiled execution tier.
 *
 * <p>This object is never passed to an accelerator kernel; device state remains primitive-only.
 */
public final class RiscV32CompilationStats {

    private final int codeBytes;
    private final int cachedInstructions;
    private final int compiledBlocks;
    private final int compiledGuestInstructions;
    private final int microOps;
    private final int fusedInstructions;
    private final long compiledBlockExecutions;

    RiscV32CompilationStats(int codeBytes, int cachedInstructions, int compiledBlocks,
            int compiledGuestInstructions, int microOps, int fusedInstructions,
            long compiledBlockExecutions) {
        this.codeBytes = codeBytes;
        this.cachedInstructions = cachedInstructions;
        this.compiledBlocks = compiledBlocks;
        this.compiledGuestInstructions = compiledGuestInstructions;
        this.microOps = microOps;
        this.fusedInstructions = fusedInstructions;
        this.compiledBlockExecutions = compiledBlockExecutions;
    }

    public int codeBytes() {
        return codeBytes;
    }

    public int cachedInstructions() {
        return cachedInstructions;
    }

    public int compiledBlocks() {
        return compiledBlocks;
    }

    public int compiledGuestInstructions() {
        return compiledGuestInstructions;
    }

    public int microOps() {
        return microOps;
    }

    public int fusedInstructions() {
        return fusedInstructions;
    }

    public long compiledBlockExecutions() {
        return compiledBlockExecutions;
    }

    public double compiledCoverage() {
        return cachedInstructions == 0 ? 0.0
                : (double) compiledGuestInstructions / cachedInstructions;
    }

    public double fusionReduction() {
        return compiledGuestInstructions == 0 ? 0.0
                : (double) fusedInstructions / compiledGuestInstructions;
    }

    @Override
    public String toString() {
        return "RiscV32CompilationStats{codeBytes=" + codeBytes
                + ", cachedInstructions=" + cachedInstructions
                + ", compiledBlocks=" + compiledBlocks
                + ", compiledGuestInstructions=" + compiledGuestInstructions
                + ", microOps=" + microOps
                + ", fusedInstructions=" + fusedInstructions
                + ", compiledCoverage=" + compiledCoverage()
                + ", fusionReduction=" + fusionReduction()
                + ", compiledBlockExecutions=" + compiledBlockExecutions + "}";
    }
}
