/* SPDX-License-Identifier: Apache-2.0 */
package com.synexia.m3.cpugpu.rewrite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.text.PlainText;

final class M3CpuGpuPreviewBuildRecipeTest {
    @Test
    void ownsTwoExactTargetsAndReachesFixedPoint() {
        M3CpuGpuPreviewBuildRecipe recipe = new M3CpuGpuPreviewBuildRecipe();
        assertEquals(List.of(
                ".github/workflows/m3-cpu-gpu-bulk-provider.yml",
                "m3/cpu-gpu/pom.xml"), recipe.targetPaths());
        assertFalse(recipe.promotionAuthority());
    }
}
