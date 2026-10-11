/* SPDX-License-Identifier: Apache-2.0 */
package com.synexia.m3.cpugpu.rewrite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.text.PlainText;

/** Exact POM/workflow successor enabling Java 21 preview for TornadoVM 6.1 classfiles. */
public final class M3CpuGpuPreviewBuildRecipe
        extends ScanningRecipe<M3CpuGpuPreviewBuildRecipe.Inventory> {
    private static final String ROOT = "/m3-cpu-gpu-preview-build/";

    record Target(String path, String before, String after, String resource, String text) {}

    static final class Inventory {
        final List<Target> targets;
        final Map<String, String> seen = new HashMap<>();
        Inventory(List<Target> targets) { this.targets = targets; }
    }

    @Override public String getDisplayName() { return "Enable M3 CPU/GPU preview build"; }
    @Override public String getDescription() {
        return "Applies the exact reviewed POM and proof-workflow postimages required by TornadoVM "
                + "6.1 Java 21 preview classfiles.";
    }
    @Override public int maxCycles() { return 1; }
    public boolean promotionAuthority() { return false; }

    @Override public Inventory getInitialValue(ExecutionContext context) {
        return new Inventory(targets());
    }

    @Override public TreeVisitor<?, ExecutionContext> getScanner(Inventory inventory) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override public Tree preVisit(Tree tree, ExecutionContext context) {
                if (tree instanceof SourceFile file) {
                    stopAfterPreVisit();
                    String path = file.getSourcePath().normalize().toString().replace('\\', '/');
                    for (Target target : inventory.targets) {
                        if (!target.path().equals(path)) continue;
                        String hash = sha256(file.printAll());
                        if (inventory.seen.putIfAbsent(path, hash) != null) {
                            throw new IllegalStateException("duplicate target: " + path);
                        }
                        if (!hash.equals(target.before()) && !hash.equals(target.after())) {
                            throw new IllegalStateException("source drift: " + path);
                        }
                    }
                }
                return tree;
            }
        };
    }

    @Override public Collection<? extends SourceFile> generate(
            Inventory inventory, ExecutionContext context) {
        for (Target target : inventory.targets) {
            if (!inventory.seen.containsKey(target.path())) {
                throw new IllegalStateException("required source missing: " + target.path());
            }
        }
        return List.of();
    }

    @Override public TreeVisitor<?, ExecutionContext> getVisitor(Inventory inventory) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override public Tree preVisit(Tree tree, ExecutionContext context) {
                if (!(tree instanceof PlainText text)) return tree;
                stopAfterPreVisit();
                String path = text.getSourcePath().normalize().toString().replace('\\', '/');
                for (Target target : inventory.targets) {
                    if (!target.path().equals(path)) continue;
                    String current = sha256(text.getText());
                    if (current.equals(target.after())) return text;
                    if (!current.equals(target.before())
                            || !current.equals(inventory.seen.get(path))) {
                        throw new IllegalStateException("target changed after scan: " + path);
                    }
                    return text.withText(target.text());
                }
                return text;
            }
        };
    }

    public List<String> targetPaths() {
        return targets().stream().map(Target::path).toList();
    }

    private static List<Target> targets() {
        ArrayList<Target> result = new ArrayList<>();
        String previous = "";
        for (String line : resource(ROOT + "manifest.tsv").lines().toList()) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] cells = line.split("\t", -1);
            if (cells.length != 4
                    || cells[0].startsWith("/")
                    || cells[0].contains("..")
                    || previous.compareTo(cells[0]) >= 0
                    || !cells[1].matches("[0-9a-f]{64}")
                    || !cells[2].matches("[0-9a-f]{64}")
                    || !cells[3].matches("[A-Za-z0-9_.-]+")) {
                throw new IllegalStateException("invalid preview-build manifest");
            }
            String text = resource(ROOT + cells[3]);
            if (!sha256(text).equals(cells[2])) {
                throw new IllegalStateException("template hash drift: " + cells[0]);
            }
            result.add(new Target(cells[0], cells[1], cells[2], cells[3], text));
            previous = cells[0];
        }
        if (result.size() != 2) throw new IllegalStateException("preview-build target count");
        return List.copyOf(result);
    }

    private static String resource(String path) {
        try (var stream = M3CpuGpuPreviewBuildRecipe.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("missing resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("resource read failure", failure);
        }
    }

    private static String sha256(String value) {
        Objects.requireNonNull(value, "value");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }
}
