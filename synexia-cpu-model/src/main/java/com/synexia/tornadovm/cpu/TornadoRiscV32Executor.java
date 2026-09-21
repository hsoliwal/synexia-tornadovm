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
 * <p>All architectural arrays are copied to the accelerator once. Only the tiny status/cause
 * vectors are copied back after each quantum; register files, PCs, counters and packed RAM remain
 * device-resident until the final under-demand transfer.
 */
public final class TornadoRiscV32Executor implements RiscV32Executor {

    private static final AtomicLong GRAPH_IDS = new AtomicLong();

    private final TornadoDevice device;

    public TornadoRiscV32Executor() {
        this(null);
    }

    public TornadoRiscV32Executor(TornadoDevice device) {
        this.device = device;
    }

    @Override
    public RiscV32ExecutionResult execute(RiscV32Machine machine, int instructionsPerQuantum, int maxQuanta) {
        JvmRiscV32Executor.validate(machine, instructionsPerQuantum, maxQuanta);

        String graphName = "synexia-rv32im-" + GRAPH_IDS.incrementAndGet();
        TaskGraph graph = new TaskGraph(graphName)
                .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                        machine.registers(), machine.pc(), machine.status(), machine.trapCause(),
                        machine.retiredInstructions(), machine.memory())
                .task("run-quantum", RiscV32Kernel::runQuantum,
                        machine.registers(), machine.pc(), machine.status(), machine.trapCause(),
                        machine.retiredInstructions(), machine.memory(), machine.wordsPerCore(), instructionsPerQuantum)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, machine.status(), machine.trapCause())
                .transferToHost(DataTransferMode.UNDER_DEMAND,
                        machine.registers(), machine.pc(), machine.retiredInstructions(), machine.memory());

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
            }

            if (lastResult != null) {
                lastResult.transferToHost(
                        machine.registers(), machine.pc(), machine.retiredInstructions(), machine.memory());
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
