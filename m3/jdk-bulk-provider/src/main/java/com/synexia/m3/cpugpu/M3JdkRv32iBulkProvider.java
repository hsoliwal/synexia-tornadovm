/* SPDX-License-Identifier: Apache-2.0 */
package com.synexia.m3.cpugpu;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import jdk.internal.vm.parallel.BulkExecution;
import jdk.internal.vm.parallel.BulkExecutorProvider;
import jdk.internal.vm.parallel.BulkTask;

/**
 * M3JDK bulk-provider adapter for the verified TornadoVM RV32IM execution engine.
 *
 * <p>The provider supports one exact reviewed operation. For repeated slices over the same batch
 * and local-work geometry it reuses one persistent TornadoVM execution plan. Every slice still
 * requires complete CPU parity before a JDK {@link BulkExecution} is emitted. No CPU fallback is
 * performed here.</p>
 */
public final class M3JdkRv32iBulkProvider
        implements BulkExecutorProvider, AutoCloseable {
    public static final String ID = "tornadovm-rv32im";
    public static final String OPERATION = "rv32im-slice";
    public static final String BATCH = "batch";
    public static final String INSTRUCTION_BUDGET = "instructionBudget";
    public static final String RECEIPT = "receipt";
    public static final int DEFAULT_LOCAL_WORK = 64;

    private final int priority;
    private M3JdkRv32iVerifiedSession session;
    private Rv32iBatch sessionBatch;
    private int sessionLocalWork;
    private int sessionCreations;
    private boolean closed;

    public M3JdkRv32iBulkProvider() {
        this(100);
    }

    public M3JdkRv32iBulkProvider(int priority) {
        this.priority = priority;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public synchronized boolean supports(BulkTask task) {
        Objects.requireNonNull(task, "task");
        return !closed
                && OPERATION.equals(task.operationId())
                && task.inputs().equals(List.of(BATCH, INSTRUCTION_BUDGET))
                && task.outputs().equals(List.of(BATCH, RECEIPT));
    }

    @Override
    public synchronized BulkExecution execute(
            BulkTask task,
            Map<String, Object> values) {
        requireOpen();
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(values, "values");
        if (!supports(task)) {
            throw new IllegalArgumentException("unsupported M3JDK bulk task");
        }

        Object batchValue = values.get(BATCH);
        Object budgetValue = values.get(INSTRUCTION_BUDGET);
        if (!(batchValue instanceof Rv32iBatch batch)) {
            throw new IllegalArgumentException("batch must be Rv32iBatch");
        }
        if (!(budgetValue instanceof Integer budget)) {
            throw new IllegalArgumentException("instructionBudget must be Integer");
        }
        if (batch.coreCount() != task.workItems()) {
            throw new IllegalArgumentException("workItems must equal RV32IM core count");
        }

        int localWork =
                task.localWork() == 0
                        ? Math.min(DEFAULT_LOCAL_WORK, batch.coreCount())
                        : Math.toIntExact(task.localWork());
        try {
            M3JdkRv32iVerifiedSession active = session(batch, localWork);
            Rv32iBulkReceipt receipt = active.runVerifiedSlice(budget);
            if (receipt.mode() != Rv32iBulkReceipt.Mode.TORNADO_VERIFIED) {
                throw new IllegalStateException("unverified TornadoVM receipt");
            }
            return new BulkExecution(
                    Map.of(BATCH, batch, RECEIPT, receipt),
                    id(),
                    receipt.root());
        } catch (RuntimeException failure) {
            discardSession(failure);
            throw failure;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeCurrent();
    }

    synchronized int sessionCreations() {
        return sessionCreations;
    }

    synchronized long verifiedSlices() {
        return session == null ? 0L : session.verifiedSlices();
    }

    private M3JdkRv32iVerifiedSession session(Rv32iBatch batch, int localWork) {
        if (session != null && sessionBatch == batch && sessionLocalWork == localWork) {
            return session;
        }
        closeCurrent();
        session = new M3JdkRv32iVerifiedSession(batch, localWork);
        sessionBatch = batch;
        sessionLocalWork = localWork;
        sessionCreations++;
        return session;
    }

    private void closeCurrent() {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } finally {
            session = null;
            sessionBatch = null;
            sessionLocalWork = 0;
        }
    }

    private void discardSession(RuntimeException original) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (RuntimeException closeFailure) {
            original.addSuppressed(closeFailure);
        } finally {
            session = null;
            sessionBatch = null;
            sessionLocalWork = 0;
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("M3JDK RV32IM provider is closed");
        }
    }
}
