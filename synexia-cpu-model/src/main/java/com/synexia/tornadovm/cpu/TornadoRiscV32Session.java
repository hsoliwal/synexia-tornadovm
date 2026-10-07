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
 * Long-lived TornadoVM execution session.
 *
 * <p>Unlike the one-shot executors, a session keeps the execution plan, compiled kernels and
 * device buffers alive across calls. This is the intended surface for emulators, agents and
 * services that repeatedly run, wake, interrupt and resume the same virtual machines.
 */
public final class TornadoRiscV32Session implements AutoCloseable {

    private static final AtomicLong IDS = new AtomicLong();

    private final RiscV32Machine machine;
    private final int instructionsPerQuantum;
    private final int statusPollInterval;
    private final boolean tiered;
    private final TornadoExecutionPlan plan;

    private TornadoExecutionResult lastResult;
    private boolean closed;

    public TornadoRiscV32Session(RiscV32Machine machine, int instructionsPerQuantum) {
        this(machine, instructionsPerQuantum, null, 8);
    }

    public TornadoRiscV32Session(RiscV32Machine machine, int instructionsPerQuantum,
            TornadoDevice device, int statusPollInterval) {
        JvmRiscV32Executor.validate(machine, instructionsPerQuantum, 1);
        if (statusPollInterval <= 0) {
            throw new IllegalArgumentException("statusPollInterval must be positive");
        }

        this.machine = machine;
        this.instructionsPerQuantum = instructionsPerQuantum;
        this.statusPollInterval = statusPollInterval;
        this.tiered = machine.hasBlockCache();

        String graphName = "synexia-rv32-session-" + IDS.incrementAndGet();
        TaskGraph graph = new TaskGraph(graphName);

        if (tiered) {
            graph.transferToDevice(DataTransferMode.FIRST_EXECUTION,
                            machine.registers(), machine.pc(), machine.status(), machine.trapCause(),
                            machine.trapValue(), machine.retiredInstructions(), machine.csrs(),
                            machine.reservations(), machine.memory(), machine.decodedInstructions(),
                            machine.decodedRawInstructions(), machine.decodedInstructionLengths(),
                            machine.blockBySlot(), machine.blockDescriptors(), machine.blockValid(),
                            machine.microOps(), machine.tierFallbackBudget(),
                            machine.compiledBlockExecutionsArray())
                    .task("compiled-blocks", RiscV32ChainedBlockKernel::runBlocks,
                            machine.registers(), machine.pc(), machine.status(), machine.trapCause(),
                            machine.trapValue(), machine.retiredInstructions(), machine.csrs(),
                            machine.reservations(), machine.memory(), machine.decodedInstructionLengths(),
                            machine.blockBySlot(), machine.blockDescriptors(), machine.blockValid(),
                            machine.microOps(), machine.tierFallbackBudget(),
                            machine.compiledBlockExecutionsArray(), machine.codeCacheBase(),
                            machine.wordsPerCore(), machine.executionFlags(), instructionsPerQuantum)
                    .task("interpreter-fallback", RiscV32Kernel::runQuantum,
                            machine.registers(), machine.pc(), machine.status(), machine.trapCause(),
                            machine.trapValue(), machine.retiredInstructions(), machine.csrs(),
                            machine.reservations(), machine.memory(), machine.decodedInstructions(),
                            machine.decodedRawInstructions(), machine.decodedInstructionLengths(),
                            machine.blockValid(), machine.tierFallbackBudget(), 1,
                            machine.codeCacheBase(), machine.codeCacheEnd(), machine.wordsPerCore(),
                            machine.executionFlags(), instructionsPerQuantum)
                    .transferToHost(DataTransferMode.UNDER_DEMAND,
                            machine.status(), machine.trapCause(), machine.trapValue(),
                            machine.registers(), machine.pc(), machine.retiredInstructions(),
                            machine.csrs(), machine.reservations(), machine.memory(),
                            machine.decodedInstructionLengths(), machine.blockValid(),
                            machine.tierFallbackBudget(), machine.compiledBlockExecutionsArray());
        } else {
            graph.transferToDevice(DataTransferMode.FIRST_EXECUTION,
                            machine.registers(), machine.pc(), machine.status(), machine.trapCause(),
                            machine.trapValue(), machine.retiredInstructions(), machine.csrs(),
                            machine.reservations(), machine.memory(), machine.decodedInstructions(),
                            machine.decodedRawInstructions(), machine.decodedInstructionLengths(),
                            machine.blockValid(), machine.tierFallbackBudget())
                    .task("interpreter", RiscV32Kernel::runQuantum,
                            machine.registers(), machine.pc(), machine.status(), machine.trapCause(),
                            machine.trapValue(), machine.retiredInstructions(), machine.csrs(),
                            machine.reservations(), machine.memory(), machine.decodedInstructions(),
                            machine.decodedRawInstructions(), machine.decodedInstructionLengths(),
                            machine.blockValid(), machine.tierFallbackBudget(), 0,
                            machine.codeCacheBase(), machine.codeCacheEnd(), machine.wordsPerCore(),
                            machine.executionFlags(), instructionsPerQuantum)
                    .transferToHost(DataTransferMode.UNDER_DEMAND,
                            machine.status(), machine.trapCause(), machine.trapValue(),
                            machine.registers(), machine.pc(), machine.retiredInstructions(),
                            machine.csrs(), machine.reservations(), machine.memory(),
                            machine.decodedInstructionLengths(), machine.blockValid(),
                            machine.tierFallbackBudget());
        }

        ImmutableTaskGraph immutable = graph.snapshot();
        TornadoExecutionPlan configured = new TornadoExecutionPlan(immutable);
        if (device != null) {
            configured = configured.withDevice(device);
        }
        this.plan = configured.withPreCompilation();
    }

    /**
     * Execute until all cores are non-running or {@code maxQuanta} is reached, then synchronize
     * complete architectural state to the host.
     */
    public RiscV32ExecutionResult executeUntilStop(int maxQuanta) {
        requireOpen();
        if (maxQuanta <= 0) {
            throw new IllegalArgumentException("maxQuanta must be positive");
        }

        long start = System.nanoTime();
        int quanta = 0;
        while (quanta < maxQuanta && !machine.allStopped()) {
            lastResult = plan.execute();
            quanta++;

            if (quanta % statusPollInterval == 0 || quanta == maxQuanta) {
                syncStatusFromDevice();
                if (machine.allStopped()) {
                    break;
                }
            }
        }

        if (lastResult != null) {
            syncFromDevice();
        }

        long elapsed = System.nanoTime() - start;
        return new RiscV32ExecutionResult(
                tiered ? "TORNADO-TIERED-SESSION" : "TORNADO-SESSION",
                quanta,
                machine.allStopped(),
                machine.runningCores(),
                machine.totalRetiredInstructions(),
                elapsed);
    }

    /**
     * Execute an exact number of quanta without copying state back. This is the lowest-overhead
     * steady-state surface; call {@link #syncStatusFromDevice()} or {@link #syncFromDevice()} when
     * host visibility is required.
     */
    public void executeDeviceResident(int quanta) {
        requireOpen();
        if (quanta <= 0) {
            throw new IllegalArgumentException("quanta must be positive");
        }
        for (int index = 0; index < quanta; index++) {
            lastResult = plan.execute();
        }
    }

    /**
     * Upload only host-controlled execution state. Use after injecting interrupts, waking WFI,
     * changing PC, or modifying machine CSRs between device-resident runs.
     */
    public void syncControlToDevice() {
        requireOpen();
        plan.transferToDevice(
                machine.pc(), machine.status(), machine.trapCause(), machine.trapValue(),
                machine.csrs(), machine.reservations());
    }

    /**
     * Upload all mutable architectural and compiled-tier state after host-side modifications.
     */
    public void syncAllToDevice() {
        requireOpen();
        if (tiered) {
            plan.transferToDevice(
                    machine.registers(), machine.pc(), machine.status(), machine.trapCause(),
                    machine.trapValue(), machine.retiredInstructions(), machine.csrs(),
                    machine.reservations(), machine.memory(), machine.decodedInstructions(),
                    machine.decodedRawInstructions(), machine.decodedInstructionLengths(),
                    machine.blockBySlot(), machine.blockDescriptors(), machine.blockValid(),
                    machine.microOps(), machine.tierFallbackBudget(),
                    machine.compiledBlockExecutionsArray());
        } else {
            plan.transferToDevice(
                    machine.registers(), machine.pc(), machine.status(), machine.trapCause(),
                    machine.trapValue(), machine.retiredInstructions(), machine.csrs(),
                    machine.reservations(), machine.memory(), machine.decodedInstructions(),
                    machine.decodedRawInstructions(), machine.decodedInstructionLengths(),
                    machine.blockValid(), machine.tierFallbackBudget());
        }
    }

    public void syncStatusFromDevice() {
        requireOpen();
        if (lastResult != null) {
            lastResult.transferToHost(machine.status(), machine.trapCause(), machine.trapValue());
        }
    }

    public void syncFromDevice() {
        requireOpen();
        if (lastResult == null) {
            return;
        }
        if (tiered) {
            lastResult.transferToHost(
                    machine.status(), machine.trapCause(), machine.trapValue(),
                    machine.registers(), machine.pc(), machine.retiredInstructions(),
                    machine.csrs(), machine.reservations(), machine.memory(),
                    machine.decodedInstructionLengths(), machine.blockValid(),
                    machine.tierFallbackBudget(), machine.compiledBlockExecutionsArray());
        } else {
            lastResult.transferToHost(
                    machine.status(), machine.trapCause(), machine.trapValue(),
                    machine.registers(), machine.pc(), machine.retiredInstructions(),
                    machine.csrs(), machine.reservations(), machine.memory(),
                    machine.decodedInstructionLengths(), machine.blockValid(),
                    machine.tierFallbackBudget());
        }
    }

    public boolean tiered() {
        return tiered;
    }

    public int instructionsPerQuantum() {
        return instructionsPerQuantum;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("session is closed");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        try {
            plan.close();
        } catch (TornadoExecutionPlanException exception) {
            throw new IllegalStateException("Unable to close TornadoVM RV32 session", exception);
        } finally {
            closed = true;
        }
    }
}
