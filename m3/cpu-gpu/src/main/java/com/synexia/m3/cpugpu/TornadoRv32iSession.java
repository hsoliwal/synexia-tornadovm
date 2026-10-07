/* SPDX-License-Identifier: Apache-2.0 */
package com.synexia.m3.cpugpu;

import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.ImmutableTaskGraph;
import uk.ac.manchester.tornado.api.KernelContext;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.TornadoExecutionResult;
import uk.ac.manchester.tornado.api.WorkerGrid1D;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.exceptions.TornadoExecutionPlanException;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Persistent TornadoVM execution session. Architectural state stays on-device between slices;
 * registers/state are copied back after each slice, while RAM is copied back only on demand.
 */
public final class TornadoRv32iSession implements AutoCloseable {
    private static final String GRAPH_ID = "m3rv32i";
    private static final String TASK_ID = "execute";
    private static final int DEFAULT_LOCAL_WORK_SIZE = 64;

    private final Rv32iBatch batch;
    private final IntArray memory;
    private final IntArray registers;
    private final IntArray state;
    private final IntArray config;
    private final TornadoExecutionPlan plan;
    private TornadoExecutionResult lastResult;

    public TornadoRv32iSession(Rv32iBatch batch) {
        this(batch, DEFAULT_LOCAL_WORK_SIZE);
    }

    public TornadoRv32iSession(Rv32iBatch batch, int localWorkSize) {
        if (batch == null) {
            throw new NullPointerException("batch");
        }
        if (localWorkSize <= 0) {
            throw new IllegalArgumentException("localWorkSize must be > 0");
        }
        this.batch = batch;
        this.memory = IntArray.fromArray(batch.rawMemory());
        this.registers = IntArray.fromArray(batch.rawRegisters());
        this.state = IntArray.fromArray(batch.rawState());
        this.config = IntArray.fromElements(batch.coreCount(), batch.memoryWordsPerCore(), 1);

        KernelContext kernelContext = new KernelContext();
        TaskGraph graph = new TaskGraph(GRAPH_ID)
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, memory, registers, state)
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, config)
                .task(TASK_ID, Rv32iKernel::execute, kernelContext, memory, registers, state, config)
                .transferToHost(DataTransferMode.UNDER_DEMAND, memory, registers, state);
        ImmutableTaskGraph immutable = graph.snapshot();

        int globalWork = roundUp(batch.coreCount(), localWorkSize);
        WorkerGrid1D grid = new WorkerGrid1D(globalWork);
        grid.setLocalWork(localWorkSize, 1, 1);
        GridScheduler scheduler = new GridScheduler(GRAPH_ID + "." + TASK_ID, grid);
        this.plan = new TornadoExecutionPlan(immutable).withGridScheduler(scheduler);
    }

    public Rv32iBatch runSlice(int instructionBudget) {
        if (instructionBudget <= 0) {
            throw new IllegalArgumentException("instructionBudget must be > 0");
        }
        config.set(Rv32i.CONFIG_INSTRUCTION_BUDGET, instructionBudget);
        lastResult = plan.execute();
        lastResult.transferToHost(registers, state);
        batch.replaceRegistersAndState(registers.toHeapArray(), state.toHeapArray());
        return batch;
    }

    /**
     * Copies device RAM back to the batch. Call only when the host needs guest memory contents;
     * omitting this transfer is important for large resident batches.
     */
    public Rv32iBatch downloadMemory() {
        requireExecution();
        lastResult.transferToHost(memory);
        batch.replaceMemory(memory.toHeapArray());
        return batch;
    }

    public Rv32iBatch synchronizeAll() {
        requireExecution();
        lastResult.transferToHost(memory, registers, state);
        batch.replaceMemory(memory.toHeapArray());
        batch.replaceRegistersAndState(registers.toHeapArray(), state.toHeapArray());
        return batch;
    }

    public TornadoExecutionPlan executionPlan() {
        return plan;
    }

    @Override
    public void close() throws TornadoExecutionPlanException {
        plan.close();
    }

    private void requireExecution() {
        if (lastResult == null) {
            throw new IllegalStateException("runSlice must execute before downloading device state");
        }
    }

    private static int roundUp(int value, int quantum) {
        long rounded = ((long) value + quantum - 1L) / quantum * quantum;
        if (rounded > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("grid size exceeds WorkerGrid1D integer range");
        }
        return (int) rounded;
    }
}
