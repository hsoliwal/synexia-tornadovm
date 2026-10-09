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
import org.openrewrite.SourceFile;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.text.PlainText;

/** Hash-pinned installer/evolver for the M3JDK RV32IM bulk-provider adapter module. */
public final class M3JdkRv32iBulkProviderRecipe
        extends ScanningRecipe<M3JdkRv32iBulkProviderRecipe.Inventory> {
    private static final String ROOT = "/m3jdk-rv32im-bulk-provider/";

    record Target(String path, String before, String after, String resource, String text) {}

    static final class Inventory {
        final List<Target> targets;
        final Map<String, String> seen = new HashMap<>();
        Inventory(List<Target> targets) {
            this.targets = targets;
        }
    }

    @Override
    public String getDisplayName() {
        return "Install/evolve M3JDK RV32IM bulk provider adapter";
    }

    @Override
    public String getDescription() {
        return "Applies reviewed adapter postimages only from exact preimages or already-converged "
                + "outputs, generates explicit ABSENT targets, and refuses source drift.";
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
                        String previous = inventory.seen.putIfAbsent(path, hash);
                        if (previous != null) {
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

    @Override
    public Collection<? extends SourceFile> generate(
            Inventory inventory,
            ExecutionContext context) {
        requireAdmissible(inventory);
        ArrayList<SourceFile> generated = new ArrayList<>();
        for (Target target : inventory.targets) {
            if (!inventory.seen.containsKey(target.path())) {
                if (!"ABSENT".equals(target.before())) {
                    throw new IllegalStateException("required target missing: " + target.path());
                }
                generated.add(template(target));
            }
        }
        return List.copyOf(generated);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Inventory inventory) {
        requireAdmissible(inventory);
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree preVisit(Tree tree, ExecutionContext context) {
                if (!(tree instanceof SourceFile file)) {
                    return tree;
                }
                stopAfterPreVisit();
                String path = normalize(file.getSourcePath());
                for (Target target : inventory.targets) {
                    if (!target.path().equals(path)) continue;
                    String current = sha256(file.printAll());
                    String scanned = inventory.seen.get(path);
                    if (scanned == null || !current.equals(scanned)) {
                        throw new IllegalStateException("target changed after scan: " + path);
                    }
                    if (current.equals(target.after())) {
                        return tree;
                    }
                    SourceFile replacement =
                            template(target)
                                    .withId(file.getId())
                                    .withSourcePath(file.getSourcePath())
                                    .withMarkers(file.getMarkers())
                                    .withFileAttributes(file.getFileAttributes())
                                    .withCharset(file.getCharset())
                                    .withCharsetBomMarked(file.isCharsetBomMarked())
                                    .withChecksum(null);
                    return replacement;
                }
                return tree;
            }
        };
    }

    public List<String> targetPaths() {
        return targets().stream().map(Target::path).toList();
    }

    public boolean promotionAuthority() {
        return false;
    }

    private static void requireAdmissible(Inventory inventory) {
        for (Target target : inventory.targets) {
            if (!"ABSENT".equals(target.before())
                    && !inventory.seen.containsKey(target.path())) {
                throw new IllegalStateException("required target missing: " + target.path());
            }
        }
    }

    private static PlainText template(Target target) {
        return PlainText.builder()
                .sourcePath(Path.of(target.path()))
                .text(target.text())
                .build();
    }

    private static List<Target> targets() {
        ArrayList<Target> targets = new ArrayList<>();
        String previous = "";
        for (String line : resource(ROOT + "manifest.tsv").lines().toList()) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] cells = line.split("\t", -1);
            if (cells.length != 4
                    || cells[0].startsWith("/")
                    || cells[0].contains("..")
                    || previous.compareTo(cells[0]) >= 0
                    || !("ABSENT".equals(cells[1]) || cells[1].matches("[0-9a-f]{64}"))
                    || !cells[2].matches("[0-9a-f]{64}")
                    || !cells[3].matches("[A-Za-z0-9_.-]+")) {
                throw new IllegalStateException("invalid adapter manifest");
            }
            String text = resource(ROOT + cells[3]);
            if (!sha256(text).equals(cells[2])) {
                throw new IllegalStateException("template hash drift: " + cells[0]);
            }
            targets.add(new Target(cells[0], cells[1], cells[2], cells[3], text));
            previous = cells[0];
        }
        if (targets.size() != 4) throw new IllegalStateException("adapter target count");
        return List.copyOf(targets);
    }

    private static String resource(String name) {
        try (var stream = M3JdkRv32iBulkProviderRecipe.class.getResourceAsStream(name)) {
            if (stream == null) throw new IllegalStateException("missing resource: " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read resource: " + name, failure);
        }
    }

    private static String normalize(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }

    static String sha256(String value) {
        Objects.requireNonNull(value, "value");
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }
}
