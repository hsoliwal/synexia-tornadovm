/*
 * Copyright (c) 2026, APT Group, Department of Computer Science,
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
import com.m3.tornado.api.exceptions.TornadoExecutionPlanException;
import com.m3.tornado.api.types.arrays.FloatArray;
import com.m3.tornado.api.types.arrays.IntArray;
import com.m3.tornado.unittests.common.TornadoTestBase;

import static org.junit.Assert.assertEquals;

/**
 * A private array allocated with an ordinary {@code new T[n]} must observe Java's
 * zero-initialization, even when the kernel reads an element before it writes it. See issue #1088.
 *
 * <p>
 * How to test?
 * </p>
 * <code>
 * tornado-test --printKernel -V uk.ac.manchester.tornado.unittests.codegen.TestPrivateArrayZeroInit
 * </code>
 */
public class TestPrivateArrayZeroInit extends TornadoTestBase {

    private static final int THREADS = 256;
    private static final int SLOTS = 8;

    /**
     * Every slot is read before it is written, so the result is only correct if the freshly
     * allocated array starts out zeroed.
     */
    private static void floatAccumulate(KernelContext context, FloatArray out) {
        int id = context.globalIdx;
        float[] acc = new float[SLOTS];
        for (int t = 0; t < SLOTS; t++) {
            acc[t] += 1.0f;
        }
        float sum = 0.0f;
        for (int t = 0; t < SLOTS; t++) {
            sum += acc[t];
        }
        out.set(id, sum);
    }

    /** The workaround from the bug report: zeroing by hand must keep producing the same answer. */
    private static void floatAccumulateZeroedByHand(KernelContext context, FloatArray out) {
        int id = context.globalIdx;
        float[] acc = new float[SLOTS];
        for (int t = 0; t < SLOTS; t++) {
            acc[t] = 0.0f;
        }
        for (int t = 0; t < SLOTS; t++) {
            acc[t] += 1.0f;
        }
        float sum = 0.0f;
        for (int t = 0; t < SLOTS; t++) {
            sum += acc[t];
        }
        out.set(id, sum);
    }

    private static void intAccumulate(KernelContext context, IntArray out) {
        int id = context.globalIdx;
        int[] acc = new int[SLOTS];
        for (int t = 0; t < SLOTS; t++) {
            acc[t] += t;
        }
        int sum = 0;
        for (int t = 0; t < SLOTS; t++) {
            sum += acc[t];
        }
        out.set(id, sum);
    }

    /**
     * Every element is definitely assigned before it is read, so zeroing the array is redundant
     * here and the device compiler is free to drop it again. The answer must not change either way.
     */
    private static void fullyOverwritten(KernelContext context, IntArray out) {
        int id = context.globalIdx;
        int[] scratch = new int[SLOTS];
        for (int t = 0; t < SLOTS; t++) {
            scratch[t] = id + t;
        }
        int sum = 0;
        for (int t = 0; t < SLOTS; t++) {
            sum += scratch[t];
        }
        out.set(id, sum);
    }

    private static GridScheduler schedulerFor(String taskName) {
        WorkerGrid worker = new WorkerGrid1D(THREADS);
        worker.setLocalWork(64, 1, 1);
        return new GridScheduler("priv." + taskName, worker);
    }

    @Test
    public void testFloatReadBeforeWrite() throws TornadoExecutionPlanException {
        FloatArray out = new FloatArray(THREADS);
        out.init(-1.0f);

        TaskGraph taskGraph = new TaskGraph("priv") //
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, out) //
                .task("floatAccumulate", TestPrivateArrayZeroInit::floatAccumulate, new KernelContext(), out) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, out);

        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(taskGraph.snapshot())) {
            executionPlan.withGridScheduler(schedulerFor("floatAccumulate")).execute();
        }

        for (int i = 0; i < THREADS; i++) {
            assertEquals(SLOTS, out.get(i), 0.0f);
        }
    }

    @Test
    public void testFloatZeroedByHand() throws TornadoExecutionPlanException {
        FloatArray out = new FloatArray(THREADS);
        out.init(-1.0f);

        TaskGraph taskGraph = new TaskGraph("priv") //
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, out) //
                .task("floatAccumulateZeroedByHand", TestPrivateArrayZeroInit::floatAccumulateZeroedByHand, new KernelContext(), out) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, out);

        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(taskGraph.snapshot())) {
            executionPlan.withGridScheduler(schedulerFor("floatAccumulateZeroedByHand")).execute();
        }

        for (int i = 0; i < THREADS; i++) {
            assertEquals(SLOTS, out.get(i), 0.0f);
        }
    }

    @Test
    public void testIntReadBeforeWrite() throws TornadoExecutionPlanException {
        IntArray out = new IntArray(THREADS);
        out.init(-1);

        TaskGraph taskGraph = new TaskGraph("priv") //
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, out) //
                .task("intAccumulate", TestPrivateArrayZeroInit::intAccumulate, new KernelContext(), out) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, out);

        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(taskGraph.snapshot())) {
            executionPlan.withGridScheduler(schedulerFor("intAccumulate")).execute();
        }

        int expected = SLOTS * (SLOTS - 1) / 2;
        for (int i = 0; i < THREADS; i++) {
            assertEquals(expected, out.get(i));
        }
    }

    @Test
    public void testFullyOverwrittenArray() throws TornadoExecutionPlanException {
        IntArray out = new IntArray(THREADS);
        out.init(-1);

        TaskGraph taskGraph = new TaskGraph("priv") //
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, out) //
                .task("fullyOverwritten", TestPrivateArrayZeroInit::fullyOverwritten, new KernelContext(), out) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, out);

        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(taskGraph.snapshot())) {
            executionPlan.withGridScheduler(schedulerFor("fullyOverwritten")).execute();
        }

        for (int i = 0; i < THREADS; i++) {
            assertEquals(SLOTS * i + (SLOTS * (SLOTS - 1) / 2), out.get(i));
        }
    }
}
