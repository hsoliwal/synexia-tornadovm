/* SPDX-License-Identifier: Apache-2.0 */
package com.synexia.m3.cpugpu.rewrite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.text.PlainText;

final class M3JdkRv32iBulkProviderRecipeTest {
    private static final String ROOT = "/m3jdk-rv32im-bulk-provider/";

    @Test
    void evolvesExactPreimagesThenReachesFixedPoint() {
        M3JdkRv32iBulkProviderRecipe recipe = new M3JdkRv32iBulkProviderRecipe();
        assertEquals(
                List.of(
                        "m3/jdk-bulk-provider/pom.xml",
                        "m3/jdk-bulk-provider/src/main/java/com/synexia/m3/cpugpu/M3JdkRv32iBulkProvider.java",
                        "m3/jdk-bulk-provider/src/main/java/com/synexia/m3/cpugpu/M3JdkRv32iVerifiedSession.java",
                        "m3/jdk-bulk-provider/src/test/java/com/synexia/m3/cpugpu/M3JdkRv32iBulkProviderTest.java"),
                recipe.targetPaths());
        assertFalse(recipe.promotionAuthority());

        SourceFile pom = source("m3/jdk-bulk-provider/pom.xml", "pom.xml.txt");
        List<SourceFile> before =
                List.of(
                        pom,
                        source(
                                "m3/jdk-bulk-provider/src/main/java/com/synexia/m3/cpugpu/M3JdkRv32iBulkProvider.java",
                                "before-M3JdkRv32iBulkProvider.java.txt"),
                        source(
                                "m3/jdk-bulk-provider/src/test/java/com/synexia/m3/cpugpu/M3JdkRv32iBulkProviderTest.java",
                                "before-M3JdkRv32iBulkProviderTest.java.txt"));

        var first =
                recipe.run(
                        new InMemoryLargeSourceSet(before),
                        new InMemoryExecutionContext());
        assertEquals(3, first.getChangeset().size());

        ArrayList<SourceFile> materialized = new ArrayList<>();
        materialized.add(pom);
        first.getChangeset().getAllResults().stream()
                .map(result -> result.getAfter())
                .forEach(materialized::add);

        var second =
                new M3JdkRv32iBulkProviderRecipe()
                        .run(
                                new InMemoryLargeSourceSet(materialized),
                                new InMemoryExecutionContext());
        assertEquals(0, second.getChangeset().size());
    }

    @Test
    void refusesUnknownSourceDrift() {
        SourceFile pom = source("m3/jdk-bulk-provider/pom.xml", "pom.xml.txt");
        SourceFile drifted =
                PlainText.builder()
                        .sourcePath(
                                Path.of(
                                        "m3/jdk-bulk-provider/src/main/java/com/synexia/m3/cpugpu/M3JdkRv32iBulkProvider.java"))
                        .text(resource("before-M3JdkRv32iBulkProvider.java.txt") + "\n// drift\n")
                        .build();
        SourceFile test =
                source(
                        "m3/jdk-bulk-provider/src/test/java/com/synexia/m3/cpugpu/M3JdkRv32iBulkProviderTest.java",
                        "before-M3JdkRv32iBulkProviderTest.java.txt");

        assertThrows(
                IllegalStateException.class,
                () ->
                        new M3JdkRv32iBulkProviderRecipe()
                                .run(
                                        new InMemoryLargeSourceSet(List.of(pom, drifted, test)),
                                        new InMemoryExecutionContext()));
    }

    private static SourceFile source(String path, String resource) {
        return PlainText.builder()
                .sourcePath(Path.of(path))
                .text(resource(resource))
                .build();
    }

    private static String resource(String name) {
        try (var stream =
                M3JdkRv32iBulkProviderRecipeTest.class.getResourceAsStream(ROOT + name)) {
            if (stream == null) {
                throw new IllegalStateException("missing resource: " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
