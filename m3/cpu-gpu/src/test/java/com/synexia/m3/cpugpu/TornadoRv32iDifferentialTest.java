/* SPDX-License-Identifier: Apache-2.0 */
package com.synexia.m3.cpugpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

final class TornadoRv32iDifferentialTest {
    @Test
    @EnabledIfSystemProperty(named = "m3.gpu", matches = "true")
    void matchesIndependentCpuOracleAcrossManyCores() throws Exception {
        Rv32iBatch actual = Rv32iDemo.newBatch(256);
        Rv32iBatch expected = actual.copy();
        new CpuRv32iEngine().runSlice(expected, 1024);

        try (TornadoRv32iSession session = new TornadoRv32iSession(actual)) {
            session.runSlice(1024);
            session.synchronizeAll();
        }

        assertArrayEquals(expected.snapshotRegisters(), actual.snapshotRegisters());
        assertArrayEquals(expected.snapshotState(), actual.snapshotState());
        assertArrayEquals(expected.snapshotMemory(), actual.snapshotMemory());
    }
}
