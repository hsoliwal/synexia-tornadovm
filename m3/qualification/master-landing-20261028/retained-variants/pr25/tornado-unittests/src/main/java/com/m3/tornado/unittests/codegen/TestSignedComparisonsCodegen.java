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
import com.m3.tornado.api.types.arrays.IntArray;
import com.m3.tornado.unittests.common.TornadoTestBase;

import static org.junit.Assert.assertArrayEquals;

/**
 * <p>
 * How to test?
 * </p>
 * <code>
 * tornado-test --printKernel -V uk.ac.manchester.tornado.unittests.codegen.TestSignedComparisonsCodegen
 * </code>
 */
public class TestSignedComparisonsCodegen extends TornadoTestBase {

    private static void testKernel01(KernelContext context, IntArray array) {
        int idx = context.globalIdx;

        if (0 < idx && idx < array.getSize()) {
            array.set(idx, 1);
        }
    }

    private static void testKernel02(KernelContext context, IntArray array) {
        int idx = context.globalIdx;

        if (idx > 2 && (idx % 2 == 0)) {
            array.set(idx, 1);
        }
    }

    private static void testKernel03(KernelContext context, IntArray array) {
        int idx = context.globalIdx;

        if (idx > 2 || (idx % 2 == 0)) {
            array.set(idx, 1);
        }
    }

    private static void testKernel04(KernelContext context, IntArray array) {
        int idx = context.globalIdx;
        int i = 5 - idx;

        boolean isInBounds = 0 < idx && idx < array.getSize() && 0 < i && i < array.getSize();
        if (isInBounds) {
            array.set(idx, 1);
        }
    }

    @Test
    public void testSigned01() {
        int size = 8;
        IntArray testArr = new IntArray(size);
        testArr.init(0);

        WorkerGrid workerGrid = new WorkerGrid1D(size / 2);
        GridScheduler gridScheduler = new GridScheduler("testSigned.testKernel01", workerGrid);
        KernelContext context = new KernelContext();

        TaskGraph taskGraph = new TaskGraph("testSigned") //
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, testArr) //
                .task("testKernel01", TestSignedComparisonsCodegen::testKernel01, context, testArr) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, testArr);

        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(taskGraph.snapshot())) {
            executionPlan.withGridScheduler(gridScheduler).execute();
        } catch (TornadoExecutionPlanException e) {
            throw new RuntimeException(e);
        }

        assertArrayEquals(new int[] { 0, 1, 1, 1, 0, 0, 0, 0 }, testArr.toHeapArray());
    }

    @Test
    public void testSigned02() {
        int size = 8;
        IntArray testArr = new IntArray(size);
        testArr.init(0);

        WorkerGrid workerGrid = new WorkerGrid1D(size);
        GridScheduler gridScheduler = new GridScheduler("testSigned.testKernel02", workerGrid);
        KernelContext context = new KernelContext();

        TaskGraph taskGraph = new TaskGraph("testSigned") //
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, testArr) //
                .task("testKernel02", TestSignedComparisonsCodegen::testKernel02, context, testArr) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, testArr);

        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(taskGraph.snapshot())) {
            executionPlan.withGridScheduler(gridScheduler).execute();
        } catch (TornadoExecutionPlanException e) {
            throw new RuntimeException(e);
        }

        assertArrayEquals(new int[] { 0, 0, 0, 0, 1, 0, 1, 0 }, testArr.toHeapArray());
    }

    @Test
    public void testSigned03() {
        int size = 8;
        IntArray testArr = new IntArray(size);
        testArr.init(0);

        WorkerGrid workerGrid = new WorkerGrid1D(size / 2);
        GridScheduler gridScheduler = new GridScheduler("testSigned.testKernel03", workerGrid);
        KernelContext context = new KernelContext();

        TaskGraph taskGraph = new TaskGraph("testSigned") //
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, testArr) //
                .task("testKernel03", TestSignedComparisonsCodegen::testKernel03, context, testArr) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, testArr);

        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(taskGraph.snapshot())) {
            executionPlan.withGridScheduler(gridScheduler).execute();
        } catch (TornadoExecutionPlanException e) {
            throw new RuntimeException(e);
        }

        assertArrayEquals(new int[] { 1, 0, 1, 1, 0, 0, 0, 0 }, testArr.toHeapArray());
    }

    @Test
    public void testSigned04() {
        int size = 8;
        IntArray testArr = new IntArray(size);

        WorkerGrid workerGrid = new WorkerGrid1D(size);
        GridScheduler gridScheduler = new GridScheduler("testSigned.testKernel04", workerGrid);
        KernelContext context = new KernelContext();

        TaskGraph taskGraph = new TaskGraph("testSigned").transferToDevice(DataTransferMode.FIRST_EXECUTION, testArr) //
                .task("testKernel04", TestSignedComparisonsCodegen::testKernel04, context, testArr)   //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, testArr);

        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(taskGraph.snapshot())) {
            executionPlan.withGridScheduler(gridScheduler).execute();
        } catch (TornadoExecutionPlanException e) {
            throw new RuntimeException(e);
        }

        assertArrayEquals(new int[] { 0, 1, 1, 1, 1, 0, 0, 0 }, testArr.toHeapArray());
    }

}