/* SPDX-License-Identifier: Apache-2.0 */
package com.synexia.m3.cpugpu.rewrite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.internal.InMemoryLargeSourceSet;

final class M3JdkRv32iBulkProviderRecipeTest {
    @Test
    void exactThreePostimagesThenFixedPoint() {
        M3JdkRv32iBulkProviderRecipe recipe = new M3JdkRv32iBulkProviderRecipe();
        assertEquals(
                List.of(
                        "m3/jdk-bulk-provider/pom.xml",
                        "m3/jdk-bulk-provider/src/main/java/com/synexia/m3/cpugpu/M3JdkRv32iBulkProvider.java",
                        "m3/jdk-bulk-provider/src/test/java/com/synexia/m3/cpugpu/M3JdkRv32iBulkProviderTest.java"),
                recipe.targetPaths());
        assertFalse(recipe.promotionAuthority());
        var first = recipe.run(
                new InMemoryLargeSourceSet(List.of()),
                new InMemoryExecutionContext());
        assertEquals(3, first.getChangeset().size());
        var generated = first.getChangeset().getAllResults().stream()
                .map(result -> result.getAfter())
                .toList();
        var second = new M3JdkRv32iBulkProviderRecipe().run(
                new InMemoryLargeSourceSet(generated),
                new InMemoryExecutionContext());
        assertEquals(0, second.getChangeset().size());
    }
}
