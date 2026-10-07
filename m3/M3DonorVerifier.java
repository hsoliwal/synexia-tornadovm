// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Synexia
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Read-only, offline Git custody proof. Does not build or execute the donor runtime. */
public final class M3DonorVerifier {
    private static final int MAX_OUTPUT = 16 * 1024 * 1024;
    private M3DonorVerifier() { }

    public record Entry(String mode, String type, String object) { }
    public record Receipt(String upstream, String upstreamTree, String checkout, int preserved, int added) { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("donor <root> | parent <root> <lock-file>");
        Path root = Path.of(args[1]).toRealPath();
        Receipt result;
        if (args[0].equals("donor") && args.length == 2) {
            result = donor(root, read(root.resolve("m3/upstream.properties")), null);
        } else if (args[0].equals("parent") && args.length == 3) {
            result = parent(root, read(inside(root, relative(args[2]))), relative(args[2]));
        } else throw new IllegalArgumentException("Invalid verifier arguments");
        System.out.println("M3_SOURCE_CUSTODY=PASS");
        System.out.println("upstream.commit=" + result.upstream());
        System.out.println("upstream.tree=" + result.upstreamTree());
        System.out.println("fork.commit=" + result.checkout());
        System.out.println("upstream.entries.preserved=" + result.preserved());
        System.out.println("overlay.entries=" + result.added());
        System.out.println("runtime.compile=NOT_EXECUTED");
        System.out.println("device.parity=NOT_EXECUTED");
    }

    static Receipt donor(Path root, Properties lock, String expectedHead) throws Exception {
        require(Path.of(git(root, "rev-parse", "--show-toplevel").strip()).toRealPath().equals(root),
                "NOT_DONOR_ROOT");
        require(git(root, "rev-parse", "--is-shallow-repository").strip().equals("false"), "SHALLOW_DONOR");
        String base = sha(lock.getProperty("upstream.commit"));
        String tree = sha(lock.getProperty("upstream.tree"));
        String head = sha(git(root, "rev-parse", "HEAD").strip());
        if (expectedHead != null) require(head.equals(sha(expectedHead)), "CHECKOUT_PIN_MISMATCH");
        require(git(root, "rev-parse", base + "^{tree}").strip().equals(tree), "UPSTREAM_TREE_MISMATCH");
        git(root, "merge-base", "--is-ancestor", base, head);
        require(git(root, "status", "--porcelain=v1", "-z", "--untracked-files=all",
                "--ignore-submodules=none").isEmpty(), "DIRTY_DONOR");
        Map<String, Entry> original = inventory(git(root, "ls-tree", "-rz", "--full-tree", base));
        Map<String, Entry> candidate = inventory(git(root, "ls-tree", "-rz", "--full-tree", head));
        verifyEntries(original, candidate);
        return new Receipt(base, tree, head, original.size(), candidate.size() - original.size());
    }

    static Receipt parent(Path root, Properties lock, String lockPath) throws Exception {
        String path = relative(lock.getProperty("submodule.path"));
        String name = lock.getProperty("submodule.name");
        require(name != null && name.matches("[a-zA-Z0-9_-]{1,64}"), "INVALID_SUBMODULE_NAME");
        String repository = lock.getProperty("fork.repository");
        require(repository != null && repository.matches("https://github[.]com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+[.]git"),
                "INVALID_FORK_URL");
        String pin = sha(lock.getProperty("fork.commit"));
        require(Path.of(git(root, "rev-parse", "--show-toplevel").strip()).toRealPath().equals(root),
                "NOT_PARENT_ROOT");
        String config = git(root, "config", "--no-includes", "--file", ".gitmodules", "--get-all",
                "submodule." + name + ".url").strip();
        require(config.equals(repository), "FORK_URL_MISMATCH");
        require(git(root, "config", "--no-includes", "--file", ".gitmodules", "--get-all",
                "submodule." + name + ".path").strip().equals(path), "SUBMODULE_PATH_MISMATCH");
        Entry link = inventory(git(root, "ls-tree", "-z", "HEAD", "--", path)).get(path);
        require(new Entry("160000", "commit", pin).equals(link), "GITLINK_PIN_MISMATCH");
        require(git(root, "status", "--porcelain=v1", "-z", "--untracked-files=all", "--",
                ".gitmodules", path, relative(lockPath)).isEmpty(), "DIRTY_PARENT_BINDING");
        Path donorRoot = inside(root, path).toRealPath();
        Properties source = read(donorRoot.resolve("m3/upstream.properties"));
        require(lock.getProperty("upstream.commit").equals(source.getProperty("upstream.commit"))
                && lock.getProperty("upstream.tree").equals(source.getProperty("upstream.tree")),
                "UPSTREAM_LOCK_MISMATCH");
        return donor(donorRoot, lock, pin);
    }

    static void verifyEntries(Map<String, Entry> original, Map<String, Entry> candidate) {
        require(!original.isEmpty(), "EMPTY_UPSTREAM");
        original.forEach((path, entry) -> require(entry.equals(candidate.get(path)), "UPSTREAM_ENTRY_CHANGED"));
        candidate.forEach((path, entry) -> {
            if (!original.containsKey(path)) {
                require(path.startsWith("m3/"), "ADDITION_OUTSIDE_OVERLAY");
                require(entry.mode().equals("100644") && entry.type().equals("blob"), "OVERLAY_NOT_REGULAR_DATA");
            }
        });
    }

    static Map<String, Entry> inventory(String output) {
        Map<String, Entry> result = new TreeMap<>();
        if (output.isEmpty()) return result;
        require(output.endsWith("\0"), "TRUNCATED_GIT_TREE");
        String[] entries = output.substring(0, output.length() - 1).split("\0", -1);
        require(entries.length <= 200_000, "TREE_ENTRY_BOUND");
        for (String entry : entries) {
            int tab = entry.indexOf('\t');
            require(tab > 0, "INVALID_GIT_TREE_ENTRY");
            String[] header = entry.substring(0, tab).split(" ");
            require(header.length == 3 && header[0].matches("[0-7]{6}"), "INVALID_GIT_TREE_HEADER");
            String path = entry.substring(tab + 1);
            require(!path.isEmpty(), "EMPTY_GIT_PATH");
            Entry value = new Entry(header[0], header[1], sha(header[2]));
            require(result.putIfAbsent(path, value) == null, "DUPLICATE_GIT_PATH");
        }
        return Map.copyOf(result);
    }

    static Properties read(Path path) throws IOException {
        require(!Files.isSymbolicLink(path), "SYMLINK_LOCK");
        Properties result = new Properties();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { result.load(reader); }
        return result;
    }

    private static Path inside(Path root, String path) {
        Path cursor = root;
        for (Path part : Path.of(path)) {
            cursor = cursor.resolve(part);
            require(!Files.isSymbolicLink(cursor), "SYMLINK_PATH");
        }
        return cursor;
    }

    static String relative(String path) {
        require(path != null && !path.isEmpty() && path.length() < 1024 && !path.startsWith("/")
                && !path.contains("\\") && !path.contains(":") && !path.startsWith("-")
                && path.chars().noneMatch(c -> c < 32)
                && Arrays.stream(path.split("/", -1)).noneMatch(p -> p.isEmpty() || p.equals(".") || p.equals("..")),
                "INVALID_RELATIVE_PATH");
        return path;
    }

    static String sha(String value) {
        require(value != null && value.matches("[0-9a-f]{40}"), "FULL_SHA1_REQUIRED");
        return value;
    }

    static String git(Path root, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "--no-replace-objects", "-c", "core.fsmonitor=false", "-C", root.toString()));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
        builder.environment().put("GIT_NO_LAZY_FETCH", "1");
        builder.environment().put("GIT_CONFIG_GLOBAL", root.resolve(".m3-unused-git-config").toString());
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        Process process = builder.start();
        process.getOutputStream().close();
        CompletableFuture<byte[]> output = new CompletableFuture<>();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var input = process.getInputStream(); var bytes = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[8192]; int n;
                while ((n = input.read(chunk)) != -1) {
                    if (bytes.size() + n > MAX_OUTPUT) throw new IOException("GIT_OUTPUT_BOUND");
                    bytes.write(chunk, 0, n);
                }
                output.complete(bytes.toByteArray());
            } catch (IOException failure) { output.completeExceptionally(failure); process.destroyForcibly(); }
        });
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) throw new IOException("GIT_TIMEOUT");
            if (process.exitValue() != 0) throw new IOException("GIT_COMMAND_FAILED: " + args[0]);
            byte[] bytes = output.get(5, TimeUnit.SECONDS);
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (ExecutionException | TimeoutException failure) {
            throw new IOException("GIT_READ_FAILED", failure);
        } finally {
            process.destroyForcibly();
            reader.interrupt();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
