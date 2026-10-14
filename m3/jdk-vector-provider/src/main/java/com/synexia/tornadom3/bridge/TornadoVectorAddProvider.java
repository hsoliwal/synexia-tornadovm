// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package com.synexia.tornadom3.bridge;

import com.synexia.m3.compute.vector.VectorCompute;
import java.util.Objects;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Opt-in TornadoVM implementation of the external M3 vector computation SPI.
 *
 * <p>This class is a provider, not a new TornadoVM runtime or an OpenJDK patch.
 * {@link VectorCompute#addVerified} independently checks exact CPU parity and
 * input mutation before accepting a result. A device failure propagates; there
 * is no hidden successful CPU receipt for failed accelerator execution.</p>
 */
public final class TornadoVectorAddProvider implements VectorCompute.Provider {
    private static final int MAX_ELEMENTS = 1_000_000;

    /** Stable ServiceLoader selection name; it does not claim a GPU was used. */
    @Override public String id() {
        return "tornadovm-vector-add";
    }

    /** Execute the reviewed integer addition kernel as a single immutable task graph. */
    @Override public int[] add(int[] left, int[] right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        if (left.length != right.length || left.length > MAX_ELEMENTS)
            throw new IllegalArgumentException("vector length/limit");
        if (left.length == 0) return new int[0];
        IntArray a = IntArray.fromArray(left);
        IntArray b = IntArray.fromArray(right);
        IntArray c = new IntArray(left.length);
        TaskGraph graph = new TaskGraph("m3-verified-vector-add")
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, a, b)
                .task("add", TornadoVectorAddProvider::intVectorAdd, a, b, c)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, c);
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(graph.snapshot())) {
            plan.execute();
        }
        int[] result = new int[left.length];
        for (int i = 0; i < result.length; i++) result[i] = c.get(i);
        return result;
    }

    /** Exact Java signed-int wraparound arithmetic. Suitable for TornadoVM parallel lowering. */
    public static void intVectorAdd(IntArray a, IntArray b, IntArray c) {
        if (a.getSize() != b.getSize() || a.getSize() != c.getSize())
            throw new IllegalArgumentException("kernel vector dimensions");
        for (@Parallel int i = 0; i < c.getSize(); i++) {
            c.set(i, a.get(i) + b.get(i));
        }
    }
}
