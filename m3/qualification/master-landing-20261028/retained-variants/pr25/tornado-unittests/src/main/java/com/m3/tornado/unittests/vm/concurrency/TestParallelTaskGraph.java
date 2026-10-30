/*
 * Copyright (c) 2023, 2024 APT Group, Department of Computer Science,
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
package com.m3.tornado.unittests.vm.concurrency;

import static org.junit.Assert.assertEquals;

import java.util.Random;
import java.util.stream.IntStream;

import org.junit.Test;

import com.m3.tornado.api.ImmutableTaskGraph;
import com.m3.tornado.api.TaskGraph;
import com.m3.tornado.api.TornadoExecutionPlan;
import com.m3.tornado.api.annotations.Parallel;
import com.m3.tornado.api.common.TornadoDevice;
import com.m3.tornado.api.enums.DataTransferMode;
import com.m3.tornado.api.exceptions.TornadoExecutionPlanException;
import com.m3.tornado.api.runtime.TornadoRuntimeProvider;
import com.m3.tornado.api.types.arrays.FloatArray;
import com.m3.tornado.unittests.common.TornadoTestBase;
import com.m3.tornado.unittests.tools.Exceptions.UnsupportedConfigurationException;

/**
 *
 * <p>
 * How to test? This test requires at least two devices.
 * </p>
 *
 * <p>
 * <code>
 * tornado-test -V --printBytecode --threadInfo uk.ac.manchester.tornado.unittests.vm.concurrency.TestParallelTaskGraph#testTwoBackendsSerial
 * </code>
 * </p>
 */
public class TestParallelTaskGraph extends TornadoTestBase {

    final int SIZE = 1024;

    public static void init(FloatArray a) {
        for (@Parallel int i = 0; i < a.getSize(); i++) {
            a.set(i, i);
        }
    }

    public static void multiply(FloatArray a, float alpha) {
        for (@Parallel int i = 0; i < a.getSize(); i++) {
            float temp = (a.get(i) * i) + alpha;
            a.set(i, temp);
        }
    }

    @Test
    public void testTwoDevicesSerial() throws TornadoExecutionPlanException {

        FloatArray a = new FloatArray(SIZE);
        FloatArray b = new FloatArray(SIZE);
        FloatArray refB = new FloatArray(SIZE);
        FloatArray refA = new FloatArray(SIZE);
        float alpha = 0.12f;

        Random r = new Random(31);
        IntStream.range(0, SIZE).forEach(i -> {
            a.set(i, r.nextFloat());
            b.set(i, r.nextFloat());
            refA.set(i, a.get(i));
            refB.set(i, b.get(i));
        });

        TaskGraph taskGraph = new TaskGraph("graph") //
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, a, b) //
                .task("task0", TestParallelTaskGraph::init, a) //
                .task("task1", TestParallelTaskGraph::multiply, b, alpha) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, a, b); //

        ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();
        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph)) {

            // Assume that the first drivers finds, at least two devices
            int deviceCount = TornadoRuntimeProvider.getTornadoRuntime().getBackend(0).getNumDevices();
            if (deviceCount < 2) {
                throw new UnsupportedConfigurationException("Test requires at least two devices");
            }

            TornadoDevice device0 = TornadoRuntimeProvider.getTornadoRuntime().getBackend(0).getDevice(0);
            TornadoDevice device1 = TornadoRuntimeProvider.getTornadoRuntime().getBackend(0).getDevice(1);

            // Extension for multi-device: This will run one task after the other
            // (sequentially)
            executionPlan.withDevice("graph.task0", device0) //
                    .withDevice("graph.task1", device1); //

            executionPlan.execute();
        }

        for (int i = 0; i < a.getSize(); i++) {
            assertEquals(i, a.get(i), DELTA);
            assertEquals((refB.get(i) * i) + alpha, b.get(i), DELTA);
        }

    }

    @Test
    public void testTwoDevicesSerial1() throws TornadoExecutionPlanException {

        FloatArray a = new FloatArray(SIZE);
        FloatArray b = new FloatArray(SIZE);
        FloatArray refB = new FloatArray(SIZE);
        FloatArray refA = new FloatArray(SIZE);
        float alpha = 0.12f;

        Random r = new Random(31);
        IntStream.range(0, SIZE).forEach(i -> {
            a.set(i, r.nextFloat());
            b.set(i, r.nextFloat());
            refA.set(i, a.get(i));
            refB.set(i, b.get(i));
        });

        TaskGraph taskGraph = new TaskGraph("graph") //
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, a, b) //
                .task("task0", TestParallelTaskGraph::init, a) //
                .task("task1", TestParallelTaskGraph::multiply, b, alpha) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, a, b); //

        ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();
        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph)) {

            // Assume that the first drivers finds, at least two devices
            int deviceCount = TornadoRuntimeProvider.getTornadoRuntime().getBackend(0).getNumDevices();
            if (deviceCount < 3) {
                throw new UnsupportedConfigurationException("Test requires at least three devices");
            }

            TornadoDevice device0 = TornadoRuntimeProvider.getTornadoRuntime().getBackend(0).getDevice(0);
            TornadoDevice device1 = TornadoRuntimeProvider.getTornadoRuntime().getBackend(0).getDevice(1);
            TornadoDevice device2 = TornadoRuntimeProvider.getTornadoRuntime().getBackend(0).getDevice(2);

            // Extension for multi-device: This will run one task after the other
            // (sequentially)
            executionPlan.withDevice("graph.task0", device0) //
                    .withDevice("graph.task1", device1);

            executionPlan.execute();

            executionPlan.withDevice("graph.task0", device2) //
                    .withDevice("graph.task1", device2);

            executionPlan.execute();
        }

        multiply(refB, alpha);
        multiply(refB, alpha);

        for (int i = 0; i < a.getSize(); i++) {
            assertEquals(i, a.get(i), DELTA);
            assertEquals(refB.get(i), b.get(i), DELTA_05);
        }

    }

    @Test
    public void testTwoDevicesSerial2() throws TornadoExecutionPlanException {

        FloatArray a = new FloatArray(SIZE);
        FloatArray b = new FloatArray(SIZE);
        FloatArray refB = new FloatArray(SIZE);
        FloatArray refA = new FloatArray(SIZE);
        float alpha = 0.12f;

        Random r = new Random(31);
        IntStream.range(0, SIZE).forEach(i -> {
            a.set(i, r.nextFloat());
            b.set(i, r.nextFloat());
            refA.set(i, a.get(i));
            refB.set(i, b.get(i));
        });

        TaskGraph taskGraph = new TaskGraph("graph") //
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, a, b) //
                .task("task0", TestParallelTaskGraph::init, a) //
                .task("task1", TestParallelTaskGraph::multiply, b, alpha) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, a, b); //

        ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();
        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph)) {

            // Assume that the first drivers finds, at least two devices
            int deviceCount = TornadoRuntimeProvider.getTornadoRuntime().getBackend(0).getNumDevices();
            if (deviceCount < 2) {
                throw new UnsupportedConfigurationException("Test requires at least two devices");
            }

            TornadoDevice device0 = TornadoRuntimeProvider.getTornadoRuntime().getBackend(0).getDevice(0);
            TornadoDevice device1 = TornadoRuntimeProvider.getTornadoRuntime().getBackend(0).getDevice(1);

            // Extension for multi-device: This will run one task after the other
            // (sequentially)
            executionPlan.withDevice("graph.task0", device0) //
                    .withDevice("graph.task1", device1);

            executionPlan.execute();

            executionPlan.withDevice("graph.task0", device1) //
                    .withDevice("graph.task1", device0);

            executionPlan.execute();
        }
        multiply(refB, alpha);
        multiply(refB, alpha);

        for (int i = 0; i < a.getSize(); i++) {
            assertEquals(i, a.get(i), DELTA);
            assertEquals(refB.get(i), b.get(i), DELTA_05);
        }

    }

    @Test
    public void testTwoDevicesConcurrent() throws TornadoExecutionPlanException {

        FloatArray a = new FloatArray(SIZE);
        FloatArray b = new FloatArray(SIZE);
        FloatArray refB = new FloatArray(SIZE);
        FloatArray refA = new FloatArray(SIZE);
        float alpha = 0.12f;

        Random r = new Random(31);
        IntStream.range(0, SIZE).forEach(i -> {
            a.set(i, r.nextFloat());
            b.set(i, r.nextFloat());
            refA.set(i, a.get(i));
            refB.set(i, b.get(i));
        });
        TaskGraph taskGraph = new TaskGraph("graph") //
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, a, b) //
                .task("task0", TestParallelTaskGraph::init, a) //
                .task("task1", TestParallelTaskGraph::multiply, b, alpha) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, a, b); //

        ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();
        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph)) {

            // Assume that the first drivers finds, at least two devices
            int deviceCount = TornadoRuntimeProvider.getTornadoRuntime().getBackend(0).getNumDevices();
            if (deviceCount < 2) {
                throw new UnsupportedConfigurationException("Test requires at least two devices");
            }

            TornadoDevice device0 = TornadoRuntimeProvider.getTornadoRuntime().getBackend(0).getDevice(0);
            TornadoDevice device1 = TornadoRuntimeProvider.getTornadoRuntime().getBackend(0).getDevice(1);

            // Extension for multi-device: This will run one task after the other in
            // parallel
            executionPlan.withConcurrentDevices() //
                    .withDevice("graph.task0", device0) //
                    .withDevice("graph.task1", device1);

            executionPlan.execute();
        }

        for (int i = 0; i < a.getSize(); i++) {
            assertEquals(i, a.get(i), DELTA);
            assertEquals((refB.get(i) * i) + alpha, b.get(i), DELTA);
        }
    }

    @Test
    public void testTwoDevicesConcurrentOnAndOff() throws TornadoExecutionPlanException {

        FloatArray a = new FloatArray(SIZE);
        FloatArray b = new FloatArray(SIZE);
        FloatArray refB = new FloatArray(SIZE);
        FloatArray refA = new FloatArray(SIZE);
        float alpha = 0.12f;

        Random r = new Random(31);
        IntStream.range(0, SIZE).forEach(i -> {
            a.set(i, r.nextFloat());
            b.set(i, r.nextFloat());
            refA.set(i, a.get(i));
            refB.set(i, b.get(i));
        });

        TaskGraph taskGraph = new TaskGraph("graph") //
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, a, b) //
                .task("task0", TestParallelTaskGraph::init, a) //
                .task("task1", TestParallelTaskGraph::multiply, b, alpha) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, a, b); //

        ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();
        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph)) {

            // Assume that the first drivers finds, at least two devices
            int deviceCount = TornadoRuntimeProvider.getTornadoRuntime().getBackend(0).getNumDevices();
            if (deviceCount < 2) {
                throw new UnsupportedConfigurationException("Test requires at least two devices");
            }

            TornadoDevice device0 = TornadoRuntimeProvider.getTornadoRuntime().getBackend(0).getDevice(0);
            TornadoDevice device1 = TornadoRuntimeProvider.getTornadoRuntime().getBackend(0).getDevice(1);

            // Extension for multi-device: This will run one task after the other in
            // parallel
            executionPlan.withConcurrentDevices() //
                    .withDevice("graph.task0", device0) //
                    .withDevice("graph.task1", device1);

            // Blocking call
            executionPlan.execute();

            // Disable concurrent devices
            executionPlan.withoutConcurrentDevices() //
                    .execute();
        }
        multiply(refB, alpha);
        multiply(refB, alpha);

        for (int i = 0; i < a.getSize(); i++) {
            assertEquals(i, a.get(i), DELTA);
            assertEquals(refB.get(i), b.get(i), DELTA_05);
        }
    }
}
