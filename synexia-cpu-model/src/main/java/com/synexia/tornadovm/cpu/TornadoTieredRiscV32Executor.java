/*
 * Copyright 2026 Synexia contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.synexia.tornadovm.cpu;

import java.util.concurrent.atomic.AtomicLong;

import uk.ac.manchester.tornado.api.ImmutableTaskGraph;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.TornadoExecutionResult;
import uk.ac.manchester.tornado.api.common.TornadoDevice;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.exceptions.TornadoExecutionPlanException;

/**
 * TornadoVM tiered executor.
 *
 * <p>The first task executes one compiled basic block for matching lanes. The second task is the
 * full architectural interpreter gated by the device-resident fallback mask produced by the first
 * task. Hot lanes therefore avoid fetch/decode while cold, exceptional or invalidated lanes retain
 * exact interpreter semantics.
 */
public final class TornadoTieredRiscV32Executor implements RiscV32Executor {

    private static final AtomicLong GRAPH_IDS = new AtomicLong();
    private static final int DEFAULT_STATUS_POLL_INTERVAL = 8;

    private final TornadoDevice device;
    private final int statusPollInterval;

    public TornadoTieredRiscV32Executor() {
        this(null, DEFAULT_STATUS_POLL_INTERVAL);
    }

    public TornadoTieredRiscV32Executor(TornadoDevice device) {
        this(device, DEFAULT_STATUS_POLL_INTERVAL);
    }

    public TornadoTieredRiscV32Executor(TornadoDevice device, int statusPollInterval) {
        if (statusPollInterval <= 0) {
            throw new IllegalArgumentException("statusPollInterval must be positive");
        }
        this.device = device;
        this.statusPollInterval = statusPollInterval;
    }

    @Override
    public RiscV32ExecutionResult execute(RiscV32Machine machine, int instructionsPerQuantum, int maxQuanta) {
        JvmRiscV32Executor.validate(machine, instructionsPerQuantum, maxQuanta);
        if (!machine.hasBlockCache()) {
            throw new IllegalStateException("buildBlockCache must be called before tiered execution");
        }

        String graphName = "synexia-rv32-tiered-" + GRAPH_IDS.incrementAndGet();

        TaskGraph graph = new TaskGraph(graphName)
                .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                        machine.registers(), machine.pc(), machine.status(), machine.trapCause(), machine.trapValue(),
                        machine.retiredInstructions(), machine.csrs(), machine.reservations(), machine.memory(),
                        machine.decodedInstructions(), machine.decodedRawInstructions(),
                        machine.decodedInstructionLengths(), machine.blockBySlot(), machine.blockDescriptors(),
                        machine.blockValid(), machine.microOps(), machine.tierFallbackMask(),
                        machine.compiledBlockExecutionsArray())
                .task("compiled-block", RiscV32BlockKernel::runOneBlock,
                        machine.registers(), machine.pc(), machine.status(), machine.trapCause(), machine.trapValue(),
                        machine.retiredInstructions(), machine.csrs(), machine.reservations(), machine.memory(),
                        machine.decodedInstructionLengths(), machine.blockBySlot(), machine.blockDescriptors(),
                        machine.blockValid(), machine.microOps(), machine.tierFallbackMask(),
                        machine.compiledBlockExecutionsArray(), machine.codeCacheBase(),
                        machine.wordsPerCore(), machine.executionFlags(), instructionsPerQuantum)
                .task("interpreter-fallback", RiscV32Kernel::runQuantum,
                        machine.registers(), machine.pc(), machine.status(), machine.trapCause(), machine.trapValue(),
                        machine.retiredInstructions(), machine.csrs(), machine.reservations(), machine.memory(),
                        machine.decodedInstructions(), machine.decodedRawInstructions(),
                        machine.decodedInstructionLengths(), machine.blockValid(), machine.tierFallbackMask(), 1,
                        machine.codeCacheBase(), machine.codeCacheEnd(),
                        machine.wordsPerCore(), machine.executionFlags(), instructionsPerQuantum)
                .transferToHost(DataTransferMode.UNDER_DEMAND,
                        machine.status(), machine.trapCause(), machine.trapValue(),
                        machine.registers(), machine.pc(), machine.retiredInstructions(),
                        machine.csrs(), machine.reservations(), machine.memory(),
                        machine.decodedInstructionLengths(), machine.blockValid(), machine.tierFallbackMask(),
                        machine.compiledBlockExecutionsArray());

        ImmutableTaskGraph immutableGraph = graph.snapshot();
        TornadoExecutionPlan plan = new TornadoExecutionPlan(immutableGraph);
        if (device != null) {
            plan = plan.withDevice(device);
        }

        long start = System.nanoTime();
        int quanta = 0;
        TornadoExecutionResult lastResult = null;
        try {
            while (quanta < maxQuanta && !machine.allStopped()) {
                lastResult = plan.execute();
                quanta++;

                if (quanta % statusPollInterval == 0 || quanta == maxQuanta) {
                    lastResult.transferToHost(machine.status(), machine.trapCause(), machine.trapValue());
                    if (machine.allStopped()) {
                        break;
                    }
                }
            }

            if (lastResult != null) {
                lastResult.transferToHost(
                        machine.status(), machine.trapCause(), machine.trapValue(),
                        machine.registers(), machine.pc(), machine.retiredInstructions(),
                        machine.csrs(), machine.reservations(), machine.memory(),
                        machine.decodedInstructionLengths(), machine.blockValid(), machine.tierFallbackMask(),
                        machine.compiledBlockExecutionsArray());
            }

            long elapsed = System.nanoTime() - start;
            return new RiscV32ExecutionResult("TORNADO-TIERED", quanta, machine.allStopped(), machine.runningCores(),
                    machine.totalRetiredInstructions(), elapsed);
        } finally {
            close(plan);
        }
    }

    private static void close(TornadoExecutionPlan plan) {
        try {
            plan.close();
        } catch (TornadoExecutionPlanException exception) {
            throw new IllegalStateException("Unable to close TornadoVM tiered execution plan", exception);
        }
    }
}
