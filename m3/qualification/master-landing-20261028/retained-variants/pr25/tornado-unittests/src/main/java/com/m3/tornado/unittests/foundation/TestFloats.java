/*
 * Copyright (c) 2021, 2022, APT Group, Department of Computer Science,
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

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.m3.tornado.api.ImmutableTaskGraph;
import com.m3.tornado.api.TaskGraph;
import com.m3.tornado.api.TornadoExecutionPlan;
import com.m3.tornado.api.enums.DataTransferMode;
import com.m3.tornado.api.exceptions.TornadoExecutionPlanException;
import com.m3.tornado.api.types.arrays.FloatArray;
import com.m3.tornado.unittests.common.TornadoTestBase;

/**
 * <p>
 * How to test?
 * </p>
 * <code>
 * tornado-test -V uk.ac.manchester.tornado.unittests.foundation.TestFloats
 * </code>
 */
public class TestFloats extends TornadoTestBase {

    @Test
    public void testFloatsCopy() throws TornadoExecutionPlanException {
        final int numElements = 256;
        FloatArray a = new FloatArray(numElements);

        TaskGraph taskGraph = new TaskGraph("s0") //
                .task("t0", TestKernels::testFloatCopy, a) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, a);

        ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();
        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph)) {
            executionPlan.execute();
        }

        assertEquals(a.get(0), 50.0f, 0.01f);
    }

    @Test
    public void testVectorFloatAdd() throws TornadoExecutionPlanException {

        final int numElements = 256;
        FloatArray a = new FloatArray(numElements);
        FloatArray b = new FloatArray(numElements);
        FloatArray c = new FloatArray(numElements);

        b.init(100);
        c.init(200);

        FloatArray expected = new FloatArray(numElements);
        for (int i = 0; i < numElements; i++) {
            expected.set(i, b.get(i) + c.get(i));
        }

        TaskGraph taskGraph = new TaskGraph("s0") //
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, b, c) //
                .task("t0", TestKernels::vectorAddFloatCompute, a, b, c) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, a);

        ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();
        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph)) {
            executionPlan.execute();
        }

        for (int i = 0; i < numElements; i++) {
            assertEquals(expected.get(i), a.get(i), 0.01f);
        }
    }

    @Test
    public void testVectorFloatSub() throws TornadoExecutionPlanException {

        final int numElements = 256;
        FloatArray a = new FloatArray(numElements);
        FloatArray b = new FloatArray(numElements);
        FloatArray c = new FloatArray(numElements);

        b.init(200);
        c.init(100);

        FloatArray expected = new FloatArray(numElements);
        for (int i = 0; i < numElements; i++) {
            expected.set(i, b.get(i) - c.get(i));
        }

        TaskGraph taskGraph = new TaskGraph("s0") //
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, b, c) //
                .task("t0", TestKernels::vectorSubFloatCompute, a, b, c) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, a);

        ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();
        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph)) {
            executionPlan.execute();
        }

        for (int i = 0; i < numElements; i++) {
            assertEquals(expected.get(i), a.get(i), 0.01f);
        }

    }

    @Test
    public void testVectorFloatMul() throws TornadoExecutionPlanException {

        final int numElements = 256;
        FloatArray a = new FloatArray(numElements);
        FloatArray b = new FloatArray(numElements);
        FloatArray c = new FloatArray(numElements);

        b.init(100.0f);
        c.init(5.0f);

        FloatArray expected = new FloatArray(numElements);
        for (int i = 0; i < numElements; i++) {
            expected.set(i, b.get(i) * c.get(i));
        }

        TaskGraph taskGraph = new TaskGraph("s0") //
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, b, c) //
                .task("t0", TestKernels::vectorMulFloatCompute, a, b, c) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, a);

        ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();
        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph)) {
            executionPlan.execute();
        }

        for (int i = 0; i < numElements; i++) {
            assertEquals(expected.get(i), a.get(i), 0.01f);
        }
    }

    @Test
    public void testVectorFloatDiv() throws TornadoExecutionPlanException {

        final int numElements = 256;
        FloatArray a = new FloatArray(numElements);
        FloatArray b = new FloatArray(numElements);
        FloatArray c = new FloatArray(numElements);

        b.init(100.0f);
        c.init(5.0f);

        FloatArray expected = new FloatArray(numElements);
        for (int i = 0; i < numElements; i++) {
            expected.set(i, b.get(i) / c.get(i));
        }

        TaskGraph taskGraph = new TaskGraph("s0") //
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, b, c) //
                .task("t0", TestKernels::vectorDivFloatCompute, a, b, c) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, a);

        ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();
        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph)) {
            executionPlan.execute();
        }

        for (int i = 0; i < numElements; i++) {
            assertEquals(expected.get(i), a.get(i), 0.01f);
        }
    }
}
