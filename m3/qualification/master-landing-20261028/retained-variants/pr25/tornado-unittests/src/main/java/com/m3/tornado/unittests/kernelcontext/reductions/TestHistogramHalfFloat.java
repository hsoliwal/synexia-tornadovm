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
package com.m3.tornado.unittests.kernelcontext.reductions;

import static org.junit.Assert.assertEquals;

import java.util.Random;

import org.junit.Test;

import com.m3.tornado.api.GridScheduler;
import com.m3.tornado.api.ImmutableTaskGraph;
import com.m3.tornado.api.KernelContext;
import com.m3.tornado.api.TaskGraph;
import com.m3.tornado.api.TornadoExecutionPlan;
import com.m3.tornado.api.WorkerGrid;
import com.m3.tornado.api.WorkerGrid1D;
import com.m3.tornado.api.enums.DataTransferMode;
import com.m3.tornado.api.enums.TornadoVMBackendType;
import com.m3.tornado.api.exceptions.TornadoExecutionPlanException;
import com.m3.tornado.api.types.HalfFloat;
import com.m3.tornado.api.types.arrays.HalfFloatArray;
import com.m3.tornado.api.types.arrays.IntArray;
import com.m3.tornado.unittests.common.TornadoTestBase;

/**
 * {@code TestHistogram}'s global-memory bucketed {@code atomicAdd}, bucketing
 * {@link HalfFloatArray} values by range instead of {@code int} values by modulo -- fp16
 * bucket-boundary comparisons (division, cast-to-int, clamping under fp16-rounded inputs) are a
 * distinct code shape from int bucketing. Uses the confirmed-correct global-array
 * {@code atomicAdd} path (see {@code TestHistogram}'s javadoc for the local-array dynamic-index
 * bug this deliberately avoids).
 * <p>
 * How to run?
 * </p>
 * <code>
 * tornado-test -V uk.ac.manchester.tornado.unittests.kernelcontext.reductions.TestHistogramHalfFloat
 * </code>
 */
public class TestHistogramHalfFloat extends TornadoTestBase {

    private static final int NUM_BINS = 8;
    private static final float RANGE_MAX = 100.0f;
    private static final float BIN_WIDTH = RANGE_MAX / NUM_BINS;
    private static final int THREADS = 256;
    private static final int BLOCKS = 8;
    private static final int SIZE = THREADS * BLOCKS;

    private static void histogramHalfFloatKernel(KernelContext ctx, HalfFloatArray input, IntArray globalHist) {
        float value = input.get(ctx.globalIdx).getFloat32();
        int bin = (int) (value / BIN_WIDTH);
        if (bin >= NUM_BINS) {
            bin = NUM_BINS - 1;
        }
        if (bin < 0) {
            bin = 0;
        }
        ctx.atomicAdd(globalHist, bin, 1);
    }

    @Test
    public void testHistogramHalfFloatBinCounts() throws TornadoExecutionPlanException {
        assertNotBackend(TornadoVMBackendType.OPENCL);
        assertNotBackend(TornadoVMBackendType.METAL);

        Random r = new Random(17);
        HalfFloatArray input = new HalfFloatArray(SIZE);
        int[] expected = new int[NUM_BINS];
        for (int i = 0; i < SIZE; i++) {
            HalfFloat value = new HalfFloat(r.nextFloat() * RANGE_MAX);
            input.set(i, value);
            int bin = (int) (value.getFloat32() / BIN_WIDTH);
            if (bin >= NUM_BINS) {
                bin = NUM_BINS - 1;
            }
            if (bin < 0) {
                bin = 0;
            }
            expected[bin]++;
        }

        IntArray globalHist = new IntArray(NUM_BINS);
        globalHist.init(0);
        KernelContext context = new KernelContext();

        WorkerGrid worker = new WorkerGrid1D(SIZE);
        worker.setLocalWork(THREADS, 1, 1);
        GridScheduler grid = new GridScheduler("s0.t0", worker);

        TaskGraph taskGraph = new TaskGraph("s0") //
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, input, globalHist) //
                .task("t0", TestHistogramHalfFloat::histogramHalfFloatKernel, context, input, globalHist) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, globalHist);

        ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();
        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph)) {
            executionPlan.withGridScheduler(grid).execute();
        }

        int totalCounted = 0;
        for (int b = 0; b < NUM_BINS; b++) {
            assertEquals("bin " + b, expected[b], globalHist.get(b));
            totalCounted += globalHist.get(b);
        }
        assertEquals(SIZE, totalCounted);
    }

}
