/* SPDX-License-Identifier: Apache-2.0 */
package com.synexia.m3.cpugpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import jdk.internal.vm.parallel.BulkExecution;
import jdk.internal.vm.parallel.BulkTask;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class M3JdkRv32iBulkProviderTest {
    @Test
    void admitsOnlyTheExactReviewedTaskShape() {
        M3JdkRv32iBulkProvider provider = new M3JdkRv32iBulkProvider(123);
        assertEquals(M3JdkRv32iBulkProvider.ID, provider.id());
        assertEquals(123, provider.priority());
        assertTrue(provider.supports(task(4, 0)));
        assertFalse(
                provider.supports(
                        new BulkTask(
                                "other",
                                4,
                                0,
                                List.of("batch", "instructionBudget"),
                                List.of("batch", "receipt"))));
        assertFalse(
                provider.supports(
                        new BulkTask(
                                "rv32im-slice",
                                4,
                                0,
                                List.of("batch"),
                                List.of("batch", "receipt"))));
    }

    @Test
    void rejectsShapeAndValuesBeforeTouchingTornadoVm() {
        M3JdkRv32iBulkProvider provider = new M3JdkRv32iBulkProvider();
        assertThrows(
                IllegalArgumentException.class,
                () -> provider.execute(
                        task(2, 0),
                        Map.of(
                                "batch",
                                batch(1),
                                "instructionBudget",
                                64)));
        assertThrows(
                IllegalArgumentException.class,
                () -> provider.execute(
                        task(1, 0),
                        Map.of(
                                "batch",
                                "not-a-batch",
                                "instructionBudget",
                                64)));
        assertThrows(
                IllegalArgumentException.class,
                () -> provider.execute(
                        task(1, 0),
                        Map.of(
                                "batch",
                                batch(1),
                                "instructionBudget",
                                "64")));
    }

    @Test
    void qualifiedHardwareRunProducesVerifiedJdkReceipt() {
        Assumptions.assumeTrue(
                Boolean.getBoolean("m3.gpu"),
                "qualified TornadoVM device not requested");

        Rv32iBatch batch = batch(256);
        BulkExecution result =
                new M3JdkRv32iBulkProvider()
                        .execute(
                                task(256, 64),
                                Map.of(
                                        "batch",
                                        batch,
                                        "instructionBudget",
                                        1024));

        assertEquals(M3JdkRv32iBulkProvider.ID, result.provider());
        assertEquals(batch, result.outputs().get("batch"));
        Rv32iBulkReceipt receipt =
                (Rv32iBulkReceipt) result.outputs().get("receipt");
        assertEquals(Rv32iBulkReceipt.Mode.TORNADO_VERIFIED, receipt.mode());
        assertEquals(receipt.root(), result.receiptRoot());
        assertEquals(5050, batch.exitCode(0));
    }

    private static BulkTask task(long workItems, long localWork) {
        return new BulkTask(
                M3JdkRv32iBulkProvider.OPERATION,
                workItems,
                localWork,
                List.of(
                        M3JdkRv32iBulkProvider.BATCH,
                        M3JdkRv32iBulkProvider.INSTRUCTION_BUDGET),
                List.of(
                        M3JdkRv32iBulkProvider.BATCH,
                        M3JdkRv32iBulkProvider.RECEIPT));
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
