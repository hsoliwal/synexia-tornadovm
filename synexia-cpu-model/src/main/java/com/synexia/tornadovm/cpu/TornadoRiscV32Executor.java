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
 * TornadoVM backend for {@link RiscV32Kernel}.
 *
 * <p>Architectural state is uploaded once and remains device-resident. The host polls only the
 * small status/trap vectors, and by default does that once every eight kernel quanta rather than
 * after every dispatch. Cores that halt before the next poll are cheap: later dispatches see their
 * device-resident non-running status and immediately skip them.
 */
public final class TornadoRiscV32Executor implements RiscV32Executor {

    private static final AtomicLong GRAPH_IDS = new AtomicLong();
    private static final int DEFAULT_STATUS_POLL_INTERVAL = 8;

    private final TornadoDevice device;
    private final int statusPollInterval;

    public TornadoRiscV32Executor() {
        this(null, DEFAULT_STATUS_POLL_INTERVAL);
    }

    public TornadoRiscV32Executor(TornadoDevice device) {
        this(device, DEFAULT_STATUS_POLL_INTERVAL);
    }

    public TornadoRiscV32Executor(TornadoDevice device, int statusPollInterval) {
        if (statusPollInterval <= 0) {
            throw new IllegalArgumentException("statusPollInterval must be positive");
        }
        this.device = device;
        this.statusPollInterval = statusPollInterval;
    }

    @Override
    public RiscV32ExecutionResult execute(RiscV32Machine machine, int instructionsPerQuantum, int maxQuanta) {
        JvmRiscV32Executor.validate(machine, instructionsPerQuantum, maxQuanta);

        String graphName = "synexia-rv32imac-" + GRAPH_IDS.incrementAndGet();
        TaskGraph graph = new TaskGraph(graphName)
                .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                        machine.registers(), machine.pc(), machine.status(), machine.trapCause(), machine.trapValue(),
                        machine.retiredInstructions(), machine.csrs(), machine.reservations(), machine.memory())
                .task("run-quantum", RiscV32Kernel::runQuantum,
                        machine.registers(), machine.pc(), machine.status(), machine.trapCause(), machine.trapValue(),
                        machine.retiredInstructions(), machine.csrs(), machine.reservations(), machine.memory(),
                        machine.wordsPerCore(), machine.executionFlags(), instructionsPerQuantum)
                .transferToHost(DataTransferMode.UNDER_DEMAND,
                        machine.status(), machine.trapCause(), machine.trapValue(),
                        machine.registers(), machine.pc(), machine.retiredInstructions(),
                        machine.csrs(), machine.reservations(), machine.memory());

        ImmutableTaskGraph immutableGraph = graph.snapshot();
        TornadoExecutionPlan plan = new TornadoExecutionPlan(immutableGraph);
        if (device != null) {
            plan = plan.withDevice(device);
        }

        long start = System.nanoTime();
        int quanta = 0;
        TornadoExecutionResult lastResult = null;
        try {
            while (quanta < maxQuanta) {
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
                        machine.csrs(), machine.reservations(), machine.memory());
            }

            long elapsed = System.nanoTime() - start;
            return new RiscV32ExecutionResult("TORNADO", quanta, machine.allStopped(), machine.runningCores(),
                    machine.totalRetiredInstructions(), elapsed);
        } finally {
            close(plan);
        }
    }

    private static void close(TornadoExecutionPlan plan) {
        try {
            plan.close();
        } catch (TornadoExecutionPlanException exception) {
            throw new IllegalStateException("Unable to close TornadoVM execution plan", exception);
        }
    }
}
