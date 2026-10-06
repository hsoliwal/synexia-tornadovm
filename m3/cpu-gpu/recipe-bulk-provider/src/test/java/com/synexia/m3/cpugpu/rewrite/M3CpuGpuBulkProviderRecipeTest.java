/* SPDX-License-Identifier: Apache-2.0 */
package com.synexia.m3.cpugpu.rewrite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.internal.InMemoryLargeSourceSet;

final class M3CpuGpuBulkProviderRecipeTest {

    @Test
    void generatesExactThreePostimagesAndThenReachesFixedPoint() {
        M3CpuGpuBulkProviderRecipe recipe = new M3CpuGpuBulkProviderRecipe();
        assertEquals(
                List.of(
                        "m3/cpu-gpu/src/main/java/com/synexia/m3/cpugpu/Rv32iBulkExecutor.java",
                        "m3/cpu-gpu/src/main/java/com/synexia/m3/cpugpu/Rv32iBulkReceipt.java",
                        "m3/cpu-gpu/src/test/java/com/synexia/m3/cpugpu/Rv32iBulkExecutorTest.java"),
                recipe.targetPaths());
        assertFalse(recipe.promotionAuthority());

        var first =
                recipe.run(
                        new InMemoryLargeSourceSet(List.of()),
                        new InMemoryExecutionContext());
        assertEquals(3, first.getChangeset().size());

        var generated =
                first.getChangeset().getAllResults().stream()
                        .map(result -> result.getAfter())
                        .toList();
        var second =
                new M3CpuGpuBulkProviderRecipe()
                        .run(
                                new InMemoryLargeSourceSet(generated),
                                new InMemoryExecutionContext());
        assertEquals(0, second.getChangeset().size());
    }
}
