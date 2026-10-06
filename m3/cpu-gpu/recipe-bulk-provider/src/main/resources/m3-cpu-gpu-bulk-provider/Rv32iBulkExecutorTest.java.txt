/* SPDX-License-Identifier: Apache-2.0 */
package com.synexia.m3.cpugpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class Rv32iBulkExecutorTest {

    @Test
    void cpuReceiptsAreDeterministicForEquivalentBatches() {
        Rv32iBulkExecutor executor = new Rv32iBulkExecutor();
        Rv32iBatch first = batch(64);
        Rv32iBatch second = batch(64);

        Rv32iBulkReceipt a = executor.executeCpu(first, 1024);
        Rv32iBulkReceipt b = executor.executeCpu(second, 1024);

        assertEquals(a, b);
        assertEquals(Rv32iBulkReceipt.Mode.CPU, a.mode());
        assertEquals(64, a.cores());
        assertEquals(5050, first.exitCode(0));
        assertTrue(a.root().matches("[0-9a-f]{64}"));
    }

    @Test
    void rejectsInvalidBudgetsBeforeExecution() {
        Rv32iBulkExecutor executor = new Rv32iBulkExecutor();
        assertThrows(
                IllegalArgumentException.class,
                () -> executor.executeCpu(batch(1), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> executor.executeVerifiedTornado(batch(1), 1, 0));
    }

    @Test
    void tornadoModeRequiresFullCpuParityWhenHardwareGateIsEnabled() throws Exception {
        Assumptions.assumeTrue(
                Boolean.getBoolean("m3.gpu"),
                "qualified TornadoVM device not requested");

        Rv32iBatch actual = batch(256);
        Rv32iBulkReceipt receipt =
                new Rv32iBulkExecutor()
                        .executeVerifiedTornado(actual, 1024, 64);

        assertEquals(Rv32iBulkReceipt.Mode.TORNADO_VERIFIED, receipt.mode());
        assertEquals(5050, actual.exitCode(0));
        assertEquals(64, receipt.localWork());
        assertTrue(receipt.verificationRoot().matches("[0-9a-f]{64}"));
    }

    private static Rv32iBatch batch(int cores) {
        Rv32iBatch batch = new Rv32iBatch(cores, 4096);
        int[] program =
                Rv32iAssembler.words(
                        Rv32iAssembler.addi(1, 0, 0),
                        Rv32iAssembler.addi(2, 0, 1),
                        Rv32iAssembler.addi(3, 0, 101),
                        Rv32iAssembler.add(1, 1, 2),
                        Rv32iAssembler.addi(2, 2, 1),
                        Rv32iAssembler.blt(2, 3, -8),
                        Rv32iAssembler.addi(10, 1, 0),
                        Rv32iAssembler.halt());
        batch.loadWordsAllCores(0, program);
        for (int core = 0; core < cores; core++) {
            batch.setProgramCounter(core, 0);
        }
        return batch;
    }
}
