/*
 * Copyright (c) 2025, APT Group, Department of Computer Science,
 * The University of Manchester.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.m3.tornado.unittests.codegen;

import org.junit.Test;

import com.m3.tornado.api.GridScheduler;
import com.m3.tornado.api.KernelContext;
import com.m3.tornado.api.TaskGraph;
import com.m3.tornado.api.TornadoExecutionPlan;
import com.m3.tornado.api.WorkerGrid;
import com.m3.tornado.api.WorkerGrid1D;
import com.m3.tornado.api.enums.DataTransferMode;
import com.m3.tornado.api.enums.TornadoVMBackendType;
import com.m3.tornado.api.exceptions.TornadoExecutionPlanException;
import com.m3.tornado.api.exceptions.TornadoInternalError;
import com.m3.tornado.api.types.arrays.IntArray;
import com.m3.tornado.unittests.common.TornadoTestBase;

import static org.junit.Assert.assertEquals;

/**
 * <p>
 * How to test?
 * </p>
 * <code>
 * tornado-test --printKernel -V uk.ac.manchester.tornado.unittests.codegen.TestShortCircuits
 * </code>
 */
public class TestShortCircuits extends TornadoTestBase {

    private static void runShortCircuitOrNode(KernelContext context, IntArray array) {
        int idx = context.globalIdx;

        int i = 1 - idx;
        int j = 1 + idx;

        boolean isInBounds = i < array.getSize() || j < array.getSize();
        array.set(idx, isInBounds ? 1 : 0);
    }

    private static void runShortCircuitAndNode(KernelContext context, IntArray array) {
        int idx = context.globalIdx;

        int i = 1 - idx;
        int j = 1 + idx;

        boolean isInBounds = i < array.getSize() && j < array.getSize();
        array.set(idx, isInBounds ? 1 : 0);
    }

    @Test
    public void testShortCircuitOrNode() {
        int size = 8;
        IntArray testArr = new IntArray(size);

        testArr.init(-1);

        WorkerGrid workerGrid = new WorkerGrid1D(size);
        GridScheduler gridScheduler = new GridScheduler("s0.runShortCircuitOrNode", workerGrid);
        KernelContext context = new KernelContext();

        TaskGraph taskGraph = new TaskGraph("s0") //
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, testArr) //
                .task("runShortCircuitOrNode", TestShortCircuits::runShortCircuitOrNode, context, testArr) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, testArr);

        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(taskGraph.snapshot())) {
            executionPlan.withGridScheduler(gridScheduler).execute();
        } catch (TornadoInternalError e) {
            System.out.println(e.getMessage());
        } catch (TornadoExecutionPlanException e) {
            throw new RuntimeException(e);
        }
        for (int i = 0; i < size; i++) {
            assertEquals(1, testArr.get(i));
        }
    }

    @Test
    public void testShortCircuitAndNode() {
        int size = 8;
        IntArray testArr = new IntArray(size);

        testArr.init(-1);

        WorkerGrid workerGrid = new WorkerGrid1D(size);
        GridScheduler gridScheduler = new GridScheduler("s0.runShortCircuitAndNode", workerGrid);
        KernelContext context = new KernelContext();

        TaskGraph taskGraph = new TaskGraph("s0") //
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, testArr) //
                .task("runShortCircuitAndNode", TestShortCircuits::runShortCircuitAndNode, context, testArr) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, testArr);

        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(taskGraph.snapshot())) {
            executionPlan.withGridScheduler(gridScheduler).execute();
        } catch (TornadoExecutionPlanException e) {
            throw new RuntimeException(e);
        }
        for (int i = 0; i < size; i++) {
            if (i == size - 1) {
                assertEquals(0, testArr.get(i));
            } else {
                assertEquals(1, testArr.get(i));
            }
        }
    }
}
