/*
 * Copyright (c) 2024, APT Group, Department of Computer Science,
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
package com.m3.tornado.unittests.api;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.m3.tornado.api.ImmutableTaskGraph;
import com.m3.tornado.api.TaskGraph;
import com.m3.tornado.api.TornadoExecutionPlan;
import com.m3.tornado.api.enums.DataTransferMode;
import com.m3.tornado.api.exceptions.TornadoExecutionPlanException;
import com.m3.tornado.api.types.HalfFloat;
import com.m3.tornado.api.types.arrays.ByteArray;
import com.m3.tornado.api.types.arrays.CharArray;
import com.m3.tornado.api.types.arrays.DoubleArray;
import com.m3.tornado.api.types.arrays.FloatArray;
import com.m3.tornado.api.types.arrays.HalfFloatArray;
import com.m3.tornado.api.types.arrays.IntArray;
import com.m3.tornado.api.types.arrays.LongArray;
import com.m3.tornado.api.types.arrays.ShortArray;
import com.m3.tornado.unittests.common.TornadoTestBase;

/**
 * How to run?
 *
 * <code>
 * $ tornado-test -V uk.ac.manchester.tornado.unittests.api.TestInitDataTypes
 * </code>
 */
public class TestInitDataTypes extends TornadoTestBase {

    @Test
    public void testInitByteArray() throws TornadoExecutionPlanException {
        ByteArray array = new ByteArray(1024 * 1024);
        TaskGraph taskGraph = new TaskGraph("graph") //
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, array) //
                .task("init", ByteArray::initialize, array, (byte) 2) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, array);

        ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();
        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph)) {
            executionPlan.execute();
        }

        for (int i = 0; i < array.getSize(); i++) {
            assertEquals((byte) 2, array.get(i));
        }
    }

    @Test
    public void testInitCharArray() throws TornadoExecutionPlanException {
        CharArray array = new CharArray(1024 * 1024);
        TaskGraph taskGraph = new TaskGraph("graph") //
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, array) //
                .task("init", CharArray::initialize, array, 'a') //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, array);

        ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();
        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph)) {
            executionPlan.execute();
        }

        for (int i = 0; i < array.getSize(); i++) {
            assertEquals('a', array.get(i));
        }
    }

    @Test
    public void testInitFloatArray() throws TornadoExecutionPlanException {
        FloatArray array = new FloatArray(1024 * 1024);
        TaskGraph taskGraph = new TaskGraph("graph") //
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, array) //
                .task("init", FloatArray::initialize, array, 2.0f) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, array);

        ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();
        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph)) {
            executionPlan.execute();
        }

        for (int i = 0; i < array.getSize(); i++) {
            assertEquals(2.0f, array.get(i), 0.001f);
        }
    }

    @Test
    public void testInitDoubleArray() throws TornadoExecutionPlanException {
        DoubleArray array = new DoubleArray(1024 * 1024);
        TaskGraph taskGraph = new TaskGraph("graph") //
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, array) //
                .task("init", DoubleArray::initialize, array, 2.0) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, array);

        ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();
        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph)) {
            executionPlan.execute();
        }

        for (int i = 0; i < array.getSize(); i++) {
            assertEquals(2.0, array.get(i), 0.001f);
        }
    }

    @Test
    public void testInitShortArray() throws TornadoExecutionPlanException {
        ShortArray array = new ShortArray(1024 * 1024);
        TaskGraph taskGraph = new TaskGraph("graph") //
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, array) //
                .task("init", ShortArray::initialize, array, (short) 2) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, array);

        ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();
        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph)) {
            executionPlan.execute();
        }

        for (int i = 0; i < array.getSize(); i++) {
            assertEquals((short) 2, array.get(i));
        }
    }

    @Test
    public void testInitIntArray() throws TornadoExecutionPlanException {
        IntArray array = new IntArray(1024 * 1024);
        TaskGraph taskGraph = new TaskGraph("graph") //
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, array) //
                .task("init", IntArray::initialize, array, 2) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, array);

        ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();
        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph)) {
            executionPlan.execute();
        }

        for (int i = 0; i < array.getSize(); i++) {
            assertEquals(2, array.get(i));
        }
    }

    @Test
    public void testInitLongArray() throws TornadoExecutionPlanException {
        LongArray array = new LongArray(1024 * 1024);
        TaskGraph taskGraph = new TaskGraph("graph") //
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, array) //
                .task("init", LongArray::initialize, array, (long) 2) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, array);

        ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();
        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph)) {
            executionPlan.execute();
        }

        for (int i = 0; i < array.getSize(); i++) {
            assertEquals(2, array.get(i));
        }
    }

    @Test
    public void testInitHalfFloatArray() throws TornadoExecutionPlanException {
        HalfFloatArray array = new HalfFloatArray(1024 * 1024);
        TaskGraph taskGraph = new TaskGraph("graph") //
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, array) //
                .task("init", HalfFloatArray::initialize, array, new HalfFloat(2.0f)) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, array);

        ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();
        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph)) {
            executionPlan.execute();
        }

        float f = new HalfFloat(2.0f).getFloat32();
        for (int i = 0; i < array.getSize(); i++) {
            assertEquals(f, array.get(i).getFloat32(), 0.001f);
        }
    }
}
