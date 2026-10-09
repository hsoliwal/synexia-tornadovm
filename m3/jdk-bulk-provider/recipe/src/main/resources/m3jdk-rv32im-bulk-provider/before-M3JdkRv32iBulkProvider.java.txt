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
 * <p>The provider supports one exact reviewed operation. It emits a JDK bulk execution only after
 * {@link Rv32iBulkExecutor} has produced a {@link Rv32iBulkReceipt.Mode#TORNADO_VERIFIED} receipt.
 * No CPU fallback is performed here.</p>
 */
public final class M3JdkRv32iBulkProvider implements BulkExecutorProvider {
    public static final String ID = "tornadovm-rv32im";
    public static final String OPERATION = "rv32im-slice";
    public static final String BATCH = "batch";
    public static final String INSTRUCTION_BUDGET = "instructionBudget";
    public static final String RECEIPT = "receipt";
    public static final int DEFAULT_LOCAL_WORK = 64;

    private final Rv32iBulkExecutor executor = new Rv32iBulkExecutor();
    private final int priority;

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
    public boolean supports(BulkTask task) {
        Objects.requireNonNull(task, "task");
        return OPERATION.equals(task.operationId())
                && task.inputs().equals(List.of(BATCH, INSTRUCTION_BUDGET))
                && task.outputs().equals(List.of(BATCH, RECEIPT));
    }

    @Override
    public BulkExecution execute(
            BulkTask task,
            Map<String, Object> values) {
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
            Rv32iBulkReceipt receipt =
                    executor.executeVerifiedTornado(
                            batch,
                            budget,
                            localWork);
            if (receipt.mode() != Rv32iBulkReceipt.Mode.TORNADO_VERIFIED) {
                throw new IllegalStateException("unverified TornadoVM receipt");
            }
            return new BulkExecution(
                    Map.of(BATCH, batch, RECEIPT, receipt),
                    id(),
                    receipt.root());
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "TornadoVM RV32IM bulk execution failed",
                    failure);
        }
    }
}
