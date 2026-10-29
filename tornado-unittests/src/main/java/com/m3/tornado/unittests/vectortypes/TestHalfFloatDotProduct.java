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
package com.m3.tornado.unittests.vectortypes;

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
import com.m3.tornado.unittests.common.TornadoTestBase;

/**
 * fp16 dot product accumulated in local (shared) memory via a single-kernel tree reduction --
 * distinct in SHAPE from {@code TestHalfFloats#testDotProduct}, which uses a two-task-graph
 * map-then-reduce pipeline (separate elementwise-multiply task, then a separate serial-accumulate
 * task). Inputs/output are fp16 ({@link HalfFloat}); the partial sums accumulate in fp32 shared
 * memory, which is standard practice for dot products (precision loss from repeatedly rounding an
 * accumulator to fp16 compounds fast) and mirrors the shape of real fp16-dot-product kernels: fp16
 * storage, fp32 compute.
 * <p>
 * How to run?
 * </p>
 * <code>
 * tornado-test -V uk.ac.manchester.tornado.unittests.vectortypes.TestHalfFloatDotProduct
 * </code>
 */
public class TestHalfFloatDotProduct extends TornadoTestBase {

    private static final int SIZE = 256; // power of 2, fits one work-group

    private static void dotProductLocalReduction(KernelContext ctx, HalfFloatArray a, HalfFloatArray b, HalfFloatArray result) {
        float[] shared = ctx.allocateFloatLocalArray(SIZE);
        int tid = ctx.localIdx;

        float product = a.get(ctx.globalIdx).getFloat32() * b.get(ctx.globalIdx).getFloat32();
        shared[tid] = product;
        ctx.localBarrier();

        for (int stride = SIZE / 2; stride > 0; stride >>= 1) {
            if (tid < stride) {
                shared[tid] += shared[tid + stride];
            }
            ctx.localBarrier();
        }

        if (tid == 0) {
            result.set(0, new HalfFloat(shared[0]));
        }
    }

    @Test
    public void testFp16DotProductLocalReduction() throws TornadoExecutionPlanException {
        assertNotBackend(TornadoVMBackendType.OPENCL);
        assertNotBackend(TornadoVMBackendType.METAL);

        Random r = new Random(13);
        HalfFloatArray a = new HalfFloatArray(SIZE);
        HalfFloatArray b = new HalfFloatArray(SIZE);
        double expectedSum = 0.0;
        for (int i = 0; i < SIZE; i++) {
            float av = 1.0f + r.nextFloat();
            float bv = 1.0f + r.nextFloat();
            a.set(i, new HalfFloat(av));
            b.set(i, new HalfFloat(bv));
            // Recompute from the fp16-rounded values actually stored, not the fp32 originals.
            expectedSum += a.get(i).getFloat32() * b.get(i).getFloat32();
        }

        HalfFloatArray result = new HalfFloatArray(1);
        KernelContext context = new KernelContext();
        WorkerGrid worker = new WorkerGrid1D(SIZE);
        worker.setLocalWork(SIZE, 1, 1);
        GridScheduler grid = new GridScheduler("s0.t0", worker);

        TaskGraph taskGraph = new TaskGraph("s0") //
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, a, b) //
                .task("t0", TestHalfFloatDotProduct::dotProductLocalReduction, context, a, b, result) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, result);

        ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();
        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph)) {
            executionPlan.withGridScheduler(grid).execute();
        }

        assertEquals((float) expectedSum, result.get(0).getFloat32(), 1.0f);
    }

}
