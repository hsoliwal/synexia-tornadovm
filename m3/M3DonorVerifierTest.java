// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Synexia
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Real temporary Git-repository tests. No network, GPU, upstream code or third-party dependencies. */
public final class M3DonorVerifierTest {
    private static int assertions;
    private M3DonorVerifierTest() { }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("m3-git-verifier-");
        try {
            Path donor = Files.createDirectory(temp.resolve("donor"));
            git(donor, "init", "-q");
            Files.writeString(donor.resolve("LICENSE"), "fixture license\n");
            Files.writeString(donor.resolve("test\ttab\nline.java"), "class Fixture {}\n");
            String base = commit(donor);
            String tree = git(donor, "rev-parse", "HEAD^{tree}").strip();
            Properties lock = new Properties();
            lock.setProperty("upstream.commit", base); lock.setProperty("upstream.tree", tree);
            Path overlay = Files.createDirectory(donor.resolve("m3"));
            Files.writeString(overlay.resolve("upstream.properties"), "upstream.commit=" + base + "\nupstream.tree=" + tree + "\n");
            Files.writeString(overlay.resolve("overlay.java"), "class Additive {}\n");
            String head = commit(donor);
            var receipt = M3DonorVerifier.donor(donor, lock, head);
            check(receipt.preserved() == 2 && receipt.added() == 2);
            reject(() -> M3DonorVerifier.donor(donor, lock, base));
            Properties wrong = new Properties(); wrong.putAll(lock); wrong.setProperty("upstream.tree", "a".repeat(40));
            reject(() -> M3DonorVerifier.donor(donor, wrong, head));
            Files.writeString(donor.resolve("LICENSE"), "changed\n");
            reject(() -> M3DonorVerifier.donor(donor, lock, null));
            commit(donor);
            reject(() -> M3DonorVerifier.donor(donor, lock, null));
            git(donor, "reset", "--hard", head);
            Files.delete(donor.resolve("LICENSE")); commit(donor);
            reject(() -> M3DonorVerifier.donor(donor, lock, null));
            git(donor, "reset", "--hard", head);
            git(donor, "update-index", "--chmod=+x", "LICENSE");
            git(donor, "-c", "user.name=M3 Test", "-c", "user.email=m3@example.invalid", "-c", "commit.gpgSign=false",
                    "commit", "-qm", "mode change");
            reject(() -> M3DonorVerifier.donor(donor, lock, null));
            git(donor, "reset", "--hard", head);
            Files.writeString(donor.resolve("extra.txt"), "outside overlay\n"); commit(donor);
            reject(() -> M3DonorVerifier.donor(donor, lock, null));
            git(donor, "reset", "--hard", head);
            Files.createSymbolicLink(overlay.resolve("link"), Path.of("../LICENSE")); commit(donor);
            reject(() -> M3DonorVerifier.donor(donor, lock, null));
            git(donor, "reset", "--hard", head);
            Files.writeString(overlay.resolve("pending"), "untracked\n");
            reject(() -> M3DonorVerifier.donor(donor, lock, null));
            Files.delete(overlay.resolve("pending"));
            check(M3DonorVerifier.donor(donor, lock, head).checkout().equals(head));

            Path parent = Files.createDirectory(temp.resolve("parent")); git(parent, "init", "-q");
            git(parent, "-c", "protocol.file.allow=always", "submodule", "add", "--name", "tornadovm",
                    donor.toString(), "donors/tornadovm");
            String url = "https://github.com/fixture/tornadovm.git";
            git(parent, "config", "--file", ".gitmodules", "submodule.tornadovm.url", url);
            lock.setProperty("submodule.path", "donors/tornadovm");
            lock.setProperty("submodule.name", "tornadovm");
            lock.setProperty("fork.repository", url); lock.setProperty("fork.commit", head);
            try (var out = Files.newBufferedWriter(parent.resolve("pin.properties"))) { lock.store(out, "fixture"); }
            commit(parent);
            check(M3DonorVerifier.parent(parent, lock, "pin.properties").checkout().equals(head));
            git(parent, "config", "--file", ".gitmodules", "submodule.tornadovm.url", "https://github.com/wrong/wrong.git");
            reject(() -> M3DonorVerifier.parent(parent, lock, "pin.properties"));
            git(parent, "checkout", "--", ".gitmodules");
            Path child = parent.resolve("donors/tornadovm");
            Files.writeString(child.resolve("m3/pending"), "dirty\n");
            reject(() -> M3DonorVerifier.parent(parent, lock, "pin.properties"));
            commit(child);
            reject(() -> M3DonorVerifier.parent(parent, lock, "pin.properties"));
            git(child, "reset", "--hard", head);
            Properties badPin = new Properties(); badPin.putAll(lock); badPin.setProperty("fork.commit", base);
            reject(() -> M3DonorVerifier.parent(parent, badPin, "pin.properties"));
            for (String path : List.of("../escape", "/absolute", "a//b", "a/./b", "-option", "C:\\x", "a:b", ""))
                reject(() -> M3DonorVerifier.relative(path));
            for (String sha : List.of("HEAD", "--help", "a".repeat(39), "A".repeat(40), "a".repeat(41)))
                reject(() -> M3DonorVerifier.sha(sha));
            String entry = "100644 blob " + "a".repeat(40) + "\tfile\0";
            check(M3DonorVerifier.inventory(entry).size() == 1);
            reject(() -> M3DonorVerifier.inventory(entry + entry));
            reject(() -> M3DonorVerifier.inventory(entry.substring(0, entry.length() - 1)));
            reject(() -> M3DonorVerifier.verifyEntries(Map.of(), Map.of()));
            Path shallow = temp.resolve("shallow");
            git(temp, "clone", "-q", "--depth=1", donor.toUri().toString(), shallow.toString());
            reject(() -> M3DonorVerifier.donor(shallow, lock, head));
            git(donor, "checkout", "--orphan", "unrelated"); commit(donor);
            reject(() -> M3DonorVerifier.donor(donor, lock, null));
            System.out.println("PASS assertions=" + assertions + " java=" + Runtime.version());
        } finally {
            List<Path> paths;
            try (var stream = Files.walk(temp)) { paths = stream.sorted(Comparator.reverseOrder()).toList(); }
            for (Path path : paths) Files.deleteIfExists(path);
        }
    }
    private static String git(Path root, String... args) throws Exception { return M3DonorVerifier.git(root, args); }
    private static String commit(Path root) throws Exception {
        git(root, "add", "--all");
        git(root, "-c", "user.name=M3 Test", "-c", "user.email=m3@example.invalid", "-c", "commit.gpgSign=false",
                "commit", "-qm", "fixture");
        return git(root, "rev-parse", "HEAD").strip();
    }
    private static void check(boolean condition) {
        assertions++; if (!condition) throw new AssertionError("assertion " + assertions);
    }
    private static void reject(Checked test) throws Exception {
        assertions++;
        try { test.run(); } catch (IllegalStateException | java.io.IOException expected) { return; }
        throw new AssertionError("Expected fail-closed rejection: " + assertions);
    }
}
