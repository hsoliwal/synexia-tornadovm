// SPDX-License-Identifier: Apache-2.0
package com.synexia.m3.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Parser;
import org.openrewrite.SourceFile;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.java.JavaParser;

/** Real OpenRewrite SDK execution of the identical Synexia read-only recipe. */
class M3CanonicalSharedBootstrap20261018Test {
    @TempDir Path scratch;

    @Test void sourceInventoryIsASecondPassFixedPoint() {
        var recipe = new M3BootstrapInventoryRecipe();
        assertFalse(recipe.mutationAuthority());
        var context = new InMemoryExecutionContext(t -> {
            throw new IllegalStateException(t);
        });
        String code = "package example; class Probe { "
                + "String quoted = \"class Ghost { void run() {} }\"; "
                + "int value(int n) { return n + 1; } }";
        try (var stream = JavaParser.fromJavaVersion().build().parseInputs(
                List.of(Parser.Input.fromString(Path.of("example/Probe.java"), code)),
                null, context)) {
            List<SourceFile> inputs = stream.toList();
            assertEquals(1, inputs.size());
            var first = recipe.run(new InMemoryLargeSourceSet(inputs), context, 1);
            assertTrue(first.getChangeset().getAllResults().isEmpty());
            var second = recipe.run(new InMemoryLargeSourceSet(inputs), context, 1);
            assertTrue(second.getChangeset().getAllResults().isEmpty());
            assertEquals(code, inputs.getFirst().printAll());
        }
    }

    @Test void contentAddressedTaskGateRefusesDrift() throws Exception {
        Path file = Files.writeString(scratch.resolve("task.md"), "source-sealed task");
        String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        String savedFile = System.getProperty("m3.llm.taskCrateFile");
        String savedHash = System.getProperty("m3.llm.taskCrateRoot");
        try {
            System.clearProperty("m3.llm.taskCrateFile");
            System.clearProperty("m3.llm.taskCrateRoot");
            assertThrows(IllegalArgumentException.class, M3BootstrapTaskRecipe::verify);
            System.setProperty("m3.llm.taskCrateFile", file.toString());
            System.setProperty("m3.llm.taskCrateRoot", hash);
            M3BootstrapTaskRecipe.verify();
            assertEquals(1, new M3BootstrapTaskRecipe().getRecipeList().size());
            Files.writeString(file, "changed task");
            assertThrows(IllegalStateException.class, M3BootstrapTaskRecipe::verify);
            assertFalse(new M3BootstrapTaskRecipe().directTargetFileMutationAuthority());
        } finally {
            if (savedFile == null) System.clearProperty("m3.llm.taskCrateFile");
            else System.setProperty("m3.llm.taskCrateFile", savedFile);
            if (savedHash == null) System.clearProperty("m3.llm.taskCrateRoot");
            else System.setProperty("m3.llm.taskCrateRoot", savedHash);
        }
    }
}
