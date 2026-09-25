/*
 * Copyright 2026 Synexia contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.synexia.tornadovm.cpu;

import uk.ac.manchester.tornado.api.common.TornadoDevice;

/**
 * Production-facing execution facade.
 *
 * <p>The engine automatically selects the compiled chained-block tier when a block cache is
 * installed and otherwise uses the architectural interpreter. Preparation remains explicit so
 * callers control which memory is immutable executable code and when recompilation is appropriate.
 */
public final class RiscV32ExecutionEngine {

    private static final int DEFAULT_MAX_BLOCK_INSTRUCTIONS = 64;

    private final TornadoDevice device;
    private final boolean accelerator;
    private final int statusPollInterval;

    private RiscV32ExecutionEngine(TornadoDevice device, boolean accelerator, int statusPollInterval) {
        if (statusPollInterval <= 0) {
            throw new IllegalArgumentException("statusPollInterval must be positive");
        }
        this.device = device;
        this.accelerator = accelerator;
        this.statusPollInterval = statusPollInterval;
    }

    public static RiscV32ExecutionEngine jvm() {
        return new RiscV32ExecutionEngine(null, false, 1);
    }

    public static RiscV32ExecutionEngine tornado() {
        return new RiscV32ExecutionEngine(null, true, 8);
    }

    public static RiscV32ExecutionEngine tornado(TornadoDevice device) {
        return new RiscV32ExecutionEngine(device, true, 8);
    }

    public static RiscV32ExecutionEngine tornado(TornadoDevice device, int statusPollInterval) {
        return new RiscV32ExecutionEngine(device, true, statusPollInterval);
    }

    public RiscV32CompilationStats prepareCode(RiscV32Machine machine,
            int sourceCore, int byteAddress, int byteLength) {
        return prepareCode(machine, sourceCore, byteAddress, byteLength,
                DEFAULT_MAX_BLOCK_INSTRUCTIONS);
    }

    public RiscV32CompilationStats prepareCode(RiscV32Machine machine,
            int sourceCore, int byteAddress, int byteLength, int maxBlockInstructions) {
        machine.buildCodeCache(sourceCore, byteAddress, byteLength);
        machine.buildBlockCache(maxBlockInstructions);
        return machine.compilationStats();
    }

    public RiscV32ElfImage loadElf(RiscV32Machine machine, byte[] elf) {
        return loadElf(machine, elf, DEFAULT_MAX_BLOCK_INSTRUCTIONS);
    }

    public RiscV32ElfImage loadElf(RiscV32Machine machine, byte[] elf, int maxBlockInstructions) {
        return RiscV32ElfLoader.loadAllPrepared(machine, elf, maxBlockInstructions);
    }

    /**
     * Open a long-lived TornadoVM session for repeated run/wake/interrupt/resume cycles.
     *
     * <p>Use this instead of repeated {@link #execute} calls in services or emulators where plan
     * construction, kernel compilation and device allocation must be amortized across many runs.
     */
    public TornadoRiscV32Session openSession(RiscV32Machine machine, int instructionsPerQuantum) {
        if (!accelerator) {
            throw new IllegalStateException("long-lived device sessions require a TornadoVM engine");
        }
        return new TornadoRiscV32Session(
                machine, instructionsPerQuantum, device, statusPollInterval);
    }

    public RiscV32ExecutionResult execute(RiscV32Machine machine,
            int instructionsPerQuantum, int maxQuanta) {
        if (accelerator) {
            if (machine.hasBlockCache()) {
                return new TornadoTieredRiscV32Executor(device, statusPollInterval)
                        .execute(machine, instructionsPerQuantum, maxQuanta);
            }
            return new TornadoRiscV32Executor(device, statusPollInterval)
                    .execute(machine, instructionsPerQuantum, maxQuanta);
        }

        if (machine.hasBlockCache()) {
            return new JvmTieredRiscV32Executor()
                    .execute(machine, instructionsPerQuantum, maxQuanta);
        }
        return new JvmRiscV32Executor()
                .execute(machine, instructionsPerQuantum, maxQuanta);
    }

    public boolean accelerator() {
        return accelerator;
    }

    public TornadoDevice device() {
        return device;
    }

    public int statusPollInterval() {
        return statusPollInterval;
    }
}
