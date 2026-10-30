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
package com.m3.tornado.unittests.foundation;

import org.junit.Test;
import com.m3.tornado.api.GridScheduler;
import com.m3.tornado.api.ImmutableTaskGraph;
import com.m3.tornado.api.KernelContext;
import com.m3.tornado.api.TaskGraph;
import com.m3.tornado.api.TornadoExecutionPlan;
import com.m3.tornado.api.WorkerGrid;
import com.m3.tornado.api.WorkerGrid1D;
import com.m3.tornado.api.annotations.Parallel;
import com.m3.tornado.api.enums.DataTransferMode;
import com.m3.tornado.api.enums.TornadoVMBackendType;
import com.m3.tornado.api.exceptions.TornadoExecutionPlanException;
import com.m3.tornado.api.types.HalfFloat;
import com.m3.tornado.api.types.arrays.FloatArray;
import com.m3.tornado.api.types.arrays.HalfFloatArray;
import com.m3.tornado.unittests.common.TornadoTestBase;

import java.util.Random;

import static org.junit.Assert.assertEquals;

/**
 * <p>
 * How to test?
 * </p>
 * <code>
 * tornado-test -V uk.ac.manchester.tornado.unittests.foundation.TestHalfFloats
 * </code>
 */
public class TestHalfFloats extends TornadoTestBase {

    public static void convertFP32toFP16v1(KernelContext context, FloatArray wrapX, HalfFloatArray x) {
        int i = context.globalIdx;
        float valInput = wrapX.get(i);
        HalfFloat val = new HalfFloat(valInput);
        x.set(i,val);
    }

    public static void convertFP32toFP16v2(KernelContext context, FloatArray wrapX, HalfFloatArray x) {
        int i = context.globalIdx;
        HalfFloat val = new HalfFloat(wrapX.get(i));
        x.set(i,val);
    }


    public static void convertFP32toFP16Parallel(FloatArray wrapX, HalfFloatArray x) {
        for (@Parallel int i = 0; i < x.getSize(); i++) {
            float valInput = wrapX.get(i);
            HalfFloat val = new HalfFloat(valInput);
            x.set(i,val);
        }
    }

    public static void matrixVectorGenericOptimized(KernelContext context, HalfFloatArray x, FloatArray output, HalfFloatArray weights, int dim1, int dim0, int localWorkGroupSize) {

        // One row per workgroup
        int rowId = context.groupIdx;
        int localId = context.localIdx;

        // Early exit if this workgroup is beyond output dimension
        if (rowId >= dim0) {
            return;
        }

        float sum = matrixVectorRowMajorOptimized(context, localWorkGroupSize, x, weights, dim1);

        // Thread 0 writes the result
        if (localId == 0) {
            output.set(rowId, sum);
        }
    }

    public static float matrixVectorRowMajorOptimized(KernelContext context, int localSize, HalfFloatArray x, HalfFloatArray w, int n) {
        int rowId = context.groupIdx;
        int localId = context.localIdx;

        // Allocate local memory for reduction
        float[] localSum = context.allocateFloatLocalArray(localSize);

        int rowOffset = rowId * n;

        HalfFloat partialSum = new HalfFloat(0f);
        for (int j = localId; j < n; j += localSize) {
            int matrixIdx = rowOffset + j;
            HalfFloat mul = HalfFloat.mult(w.get(matrixIdx), x.get(j));
            partialSum = HalfFloat.add(partialSum, mul);
        }


        // Store partial sum in local memory
        localSum[localId] = partialSum.getFloat32();
        context.localBarrier();

        // Parallel reduction within workgroup
        for (int stride = localSize / 2; stride > 0; stride >>= 1) {
            if (localId < stride) {
                localSum[localId] += localSum[localId + stride];
            }
            context.localBarrier();
        }

        return localSum[0];
    }

    private void matrixVectorSequentialHalf(FloatArray output, HalfFloatArray weights, HalfFloatArray input, int n, int d) {
        for (int i = 0; i < d; i++) {
            float sum = 0.0f;
            for (int j = 0; j < n; j++) {
                sum += HalfFloat.mult(weights.get(i * n + j), input.get(j)).getFloat32();
            }
            output.set(i, sum);
        }
    }

    private static void fillRandomDataFp16(HalfFloatArray array, float min, float max, Random random) {
        float range = max - min;
        for (int i = 0; i < array.getSize(); i++) {
            array.set(i, new HalfFloat(min + random.nextInt() * range));
        }
    }

    @Test
    public void testConvertFP32toFP16v1() throws TornadoExecutionPlanException {
        FloatArray x = new FloatArray(1024);
        HalfFloatArray y = new HalfFloatArray(1024);

        x.init(new Random().nextFloat());

        KernelContext context = new KernelContext();

        TaskGraph tg = new TaskGraph("s0")
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, x)
                .task("t0", TestHalfFloats::convertFP32toFP16v1,context, x, y)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, y);

        ImmutableTaskGraph immutableTaskGraph = tg.snapshot();

        WorkerGrid workerGrid  = new WorkerGrid1D(1024);
        workerGrid.setLocalWork(32,1,1);

        GridScheduler scheduler = new GridScheduler("s0.t0", workerGrid);

        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph)) {
            executionPlan.withGridScheduler(scheduler).execute();
        }

        for (int i = 0; i < 1024; i++) {
            assertEquals(x.get(i), y.get(i).getFloat32(), 0.001f);
        }
    }

    @Test
    public void testConvertFP32toFP16v2() throws TornadoExecutionPlanException {
        FloatArray x = new FloatArray(1024);
        HalfFloatArray y = new HalfFloatArray(1024);

        x.init(new Random().nextFloat());

        KernelContext context = new KernelContext();

        TaskGraph tg = new TaskGraph("s0")
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, x)
                .task("t0", TestHalfFloats::convertFP32toFP16v2, context, x, y)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, y);

        ImmutableTaskGraph immutableTaskGraph = tg.snapshot();
        WorkerGrid workerGrid  = new WorkerGrid1D(1024);
        workerGrid.setLocalWork(32,1,1);

        GridScheduler scheduler = new GridScheduler("s0.t0", workerGrid);

        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph)) {
            executionPlan.withGridScheduler(scheduler).execute();
        }

        for (int i = 0; i < 1024; i++) {
            assertEquals(x.get(i), y.get(i).getFloat32(), 0.001f);
        }
    }

    @Test
    public void testConvertFP32toFP16Parallel() throws TornadoExecutionPlanException {
        FloatArray x = new FloatArray(1024);
        HalfFloatArray y = new HalfFloatArray(1024);

        x.init(new Random().nextFloat());

        TaskGraph tg = new TaskGraph("s0")
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, x)
                .task("t0", TestHalfFloats::convertFP32toFP16Parallel, x, y)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, y);

        ImmutableTaskGraph immutableTaskGraph = tg.snapshot();

        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph)) {
            executionPlan.execute();
        }

        for (int i = 0; i < 1024; i++) {
            assertEquals(x.get(i), y.get(i).getFloat32(), 0.001f);
        }
    }

    @Test
    public void testMatrixVectorHalfFloatOptimized() throws TornadoExecutionPlanException {

        Random random = new Random(42);
        int localWorkgroupSize = 64;
        int inputDim = 8192;   // Default input dimension (columns)
        int outputDim = 2048; // Default output dimension (rows)

        HalfFloatArray input = new HalfFloatArray(inputDim);
        HalfFloatArray weights = new HalfFloatArray(inputDim * outputDim);
        FloatArray output = new FloatArray(outputDim);
        FloatArray outputSeq = new FloatArray(outputDim);
        fillRandomDataFp16(input, -1.0f, 1.0f, random);
        fillRandomDataFp16(weights, -0.1f, 0.1f, random);

        WorkerGrid1D hybridWorker = new WorkerGrid1D(outputDim * localWorkgroupSize);
        hybridWorker.setLocalWork(localWorkgroupSize, 1, 1);
        GridScheduler scheduler = new GridScheduler("s0.t0", hybridWorker);
        TaskGraph taskGraph = new TaskGraph("s0")
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, input, weights)
                .task("t0", TestHalfFloats::matrixVectorGenericOptimized, new KernelContext(), input, output, weights, inputDim, outputDim, localWorkgroupSize)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, output);

        ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();

        try (TornadoExecutionPlan tornadoExecutor = new TornadoExecutionPlan(immutableTaskGraph)) {
            tornadoExecutor.withGridScheduler(scheduler).execute();
        }

        matrixVectorSequentialHalf(outputSeq, weights, input, inputDim, outputDim);

        for (int i = 0; i < output.getSize(); i++) {
            assertEquals(outputSeq.get(i), output.get(i), 0.1f);
        }

    }

}
