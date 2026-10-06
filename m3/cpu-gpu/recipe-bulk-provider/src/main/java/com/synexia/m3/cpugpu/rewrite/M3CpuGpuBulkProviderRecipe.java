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

/**
 * Exact absent-to-postimage recipe for the M3 RV32IM bulk provider.
 *
 * <p>This isolated recipe exists so model-assisted source creation remains recipe-first even in the
 * TornadoVM donor fork. It owns only the three additive files in its manifest.</p>
 */
public final class M3CpuGpuBulkProviderRecipe
        extends ScanningRecipe<M3CpuGpuBulkProviderRecipe.Inventory> {

    private static final String ROOT = "/m3-cpu-gpu-bulk-provider/";

    record Target(String path, String sha256, String resource, String text) {}

    static final class Inventory {
        final List<Target> targets;
        final Map<String, String> seen = new HashMap<>();

        Inventory(List<Target> targets) {
            this.targets = targets;
        }
    }

    @Override
    public String getDisplayName() {
        return "Install M3 RV32IM bulk provider";
    }

    @Override
    public String getDescription() {
        return "Creates the exact reviewed RV32IM bulk executor, receipt and test postimages, "
                + "refusing drift and leaving runtime/GPU verification authoritative.";
    }

    @Override
    public int maxCycles() {
        return 1;
    }

    @Override
    public Inventory getInitialValue(ExecutionContext context) {
        return new Inventory(targets());
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Inventory inventory) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree preVisit(Tree tree, ExecutionContext context) {
                if (tree instanceof SourceFile file) {
                    stopAfterPreVisit();
                    String path = normalize(file.getSourcePath());
                    for (Target target : inventory.targets) {
                        if (!target.path().equals(path)) continue;
                        String hash = sha256(file.printAll());
                        String old = inventory.seen.putIfAbsent(path, hash);
                        if (old != null) {
                            throw new IllegalStateException("duplicate target: " + path);
                        }
                        if (!hash.equals(target.sha256())) {
                            throw new IllegalStateException("source drift: " + path);
                        }
                    }
                }
                return tree;
            }
        };
    }

    @Override
    public Collection<? extends SourceFile> generate(
            Inventory inventory,
            ExecutionContext context) {
        ArrayList<SourceFile> generated = new ArrayList<>();
        for (Target target : inventory.targets) {
            if (inventory.seen.containsKey(target.path())) continue;
            generated.add(
                    PlainText.builder()
                            .sourcePath(Path.of(target.path()))
                            .text(target.text())
                            .build());
        }
        return List.copyOf(generated);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Inventory inventory) {
        return TreeVisitor.noop();
    }

    public List<String> targetPaths() {
        return targets().stream().map(Target::path).toList();
    }

    public boolean promotionAuthority() {
        return false;
    }

    private static List<Target> targets() {
        String manifest = resource(ROOT + "manifest.tsv");
        ArrayList<Target> targets = new ArrayList<>();
        String previous = "";
        for (String line : manifest.lines().toList()) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] cells = line.split("\t", -1);
            if (cells.length != 3
                    || cells[0].startsWith("/")
                    || cells[0].contains("..")
                    || !cells[0].endsWith(".java")
                    || previous.compareTo(cells[0]) >= 0
                    || !cells[1].matches("[0-9a-f]{64}")
                    || !cells[2].matches("[A-Za-z0-9_.-]+")) {
                throw new IllegalStateException("invalid bulk-provider manifest");
            }
            String text = resource(ROOT + cells[2]);
            if (!sha256(text).equals(cells[1])) {
                throw new IllegalStateException("template hash drift: " + cells[0]);
            }
            targets.add(new Target(cells[0], cells[1], cells[2], text));
            previous = cells[0];
        }
        if (targets.size() != 3) {
            throw new IllegalStateException("bulk-provider target count");
        }
        return List.copyOf(targets);
    }

    private static String resource(String name) {
        try (var stream =
                M3CpuGpuBulkProviderRecipe.class.getResourceAsStream(name)) {
            if (stream == null) {
                throw new IllegalStateException("missing resource: " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read resource: " + name, failure);
        }
    }

    private static String normalize(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }

    private static String sha256(String value) {
        Objects.requireNonNull(value, "value");
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }
}
