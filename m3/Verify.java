/* SPDX-License-Identifier: Apache-2.0 */
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/** Java21 read-only source-custody gate. No GPU execution, fetching or promotion. */
public final class Verify {
    private final Path donor;
    private final Path output;
    private final List<String> stages = new ArrayList<>();
    private final Map<String, String> facts = new TreeMap<>();
    private int commands;

    private Verify(Path donor, Path output) { this.donor = donor; this.output = output; }

    public static void main(String[] args) throws Exception {
        int code = run(args);
        if (code != 0) System.exit(code);
    }

    /** Optional parent arguments bind the checkout to a committed and staged Git submodule. */
    public static int run(String[] args) throws Exception {
        if (args.length != 4 && args.length != 8) {
            System.err.println("Usage: Verify DONOR UPSTREAM_SHA FORK_SHA NEW_OUTPUT [HOST NAME PATH URL]");
            return 64;
        }
        if (Runtime.version().feature() != 21) throw new IllegalStateException("JDK21 required");
        requireSha(args[1]); requireSha(args[2]);
        Path donor = ordinaryDirectory(Path.of(args[0]));
        Path out = Path.of(args[3]).toAbsolutePath().normalize();
        Path parent = args.length == 8 ? ordinaryDirectory(Path.of(args[4])) : null;
        if (out.getParent() == null || !ordinaryDirectory(out.getParent()).equals(out.getParent())
                || out.startsWith(donor) || (parent != null && out.startsWith(parent))) {
            throw new IOException("new output must be outside donor and host under an existing ordinary parent");
        }
        Files.createDirectory(out);
        Verify check = new Verify(donor, out);
        boolean passed = false;
        try {
            check.verify(args, parent);
            passed = true;
            return 0;
        } catch (IOException | IllegalArgumentException failed) {
            check.write("failure.log", failed.toString() + "\n");
            return 2;
        } finally {
            check.report(passed);
        }
    }

    private void verify(String[] args, Path parent) throws Exception {
        String base = args[1], head = args[2];
        facts.put("upstream_commit", base); facts.put("fork_commit", head);
        facts.put("jdk", System.getProperty("java.runtime.version"));
        require(git(donor, "rev-parse", "--show-toplevel").strip().equals(donor.toString()), "donor root required");
        require(git(donor, "rev-parse", "HEAD").strip().equals(head), "checkout is not the pinned fork commit");
        require(git(donor, "cat-file", "-t", base).strip().equals("commit"), "upstream must be a commit");
        require(git(donor, "cat-file", "-t", head).strip().equals("commit"), "fork must be a commit");
        require(git(donor, "rev-parse", "--is-shallow-repository").strip().equals("false"), "complete history required");
        git(donor, "merge-base", "--is-ancestor", base, head);
        stages.add("PIN_AND_ANCESTRY\tPASS");
        String baseline = git(donor, "ls-tree", "-z", base);
        String candidate = git(donor, "ls-tree", "-z", head);
        Map<String, String> before = tree(baseline), after = tree(candidate);
        require(!before.containsKey("m3"), "upstream m3 namespace requires an explicit wider-scope policy");
        String overlay = after.remove("m3");
        require(overlay != null && overlay.startsWith("040000 tree "), "m3 must be an ordinary tree");
        require(before.equals(after), "upstream root entries changed outside m3/");
        facts.put("upstream_tree", git(donor, "rev-parse", base + "^{tree}").strip());
        facts.put("fork_tree", git(donor, "rev-parse", head + "^{tree}").strip());
        facts.put("overlay_tree", overlay.substring("040000 tree ".length()));
        write("UPSTREAM_ROOT.tsv", rootTable(before));
        write("FORK_ROOT.tsv", rootTable(tree(candidate)));
        git(donor, "diff", "--no-ext-diff", "--no-textconv", "--no-color", base, head, "--");
        stages.add("DIFF_UPSTREAM_PRESERVED\tPASS");
        require(git(donor, "status", "--porcelain=v1", "--untracked-files=all").isEmpty(), "dirty donor checkout");
        stages.add("WORKTREE\tPASS_TRACKED_AND_NONIGNORED");
        if (parent != null) verifyParent(args, parent, head);
        // Check custody again after all inspections. This is not an OS-level concurrent-writer lock.
        require(git(donor, "rev-parse", "HEAD").strip().equals(head), "donor HEAD changed during inspection");
        require(git(donor, "status", "--porcelain=v1", "--untracked-files=all").isEmpty(), "donor changed during inspection");
        stages.add("CUSTODY_RECHECK\tPASS");
    }

    private void verifyParent(String[] args, Path parent, String head) throws Exception {
        String name = args[5], path = args[6], url = args[7];
        require(name.matches("[A-Za-z0-9_-]+"), "ordinary submodule name required");
        require(path.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*"), "ordinary submodule path required");
        require(url.matches("https://github[.]com/[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+[.]git"), "explicit GitHub HTTPS URL required");
        require(ordinaryDirectory(parent.resolve(path)).equals(donor), "donor is not the declared host submodule");
        require(git(parent, "rev-parse", "--show-toplevel").strip().equals(parent.toString()), "host root required");
        String hostHead = git(parent, "rev-parse", "HEAD").strip();
        Path config = parent.resolve(".gitmodules");
        require(Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS) && Files.size(config) <= 1_048_576,
                "ordinary bounded .gitmodules required");
        String actual = Files.readString(config, StandardCharsets.UTF_8);
        require(actual.equals(git(parent, "show", hostHead + ":.gitmodules")), "working .gitmodules differs from commit");
        require(actual.equals(git(parent, "show", ":.gitmodules")), "staged .gitmodules differs from commit");
        require(git(parent, "config", "--no-includes", "--null", "--file", ".gitmodules", "--get-all",
                "submodule." + name + ".path").equals(path + "\0"), "submodule path mismatch or duplicate");
        require(git(parent, "config", "--no-includes", "--null", "--file", ".gitmodules", "--get-all",
                "submodule." + name + ".url").equals(url + "\0"), "submodule URL mismatch or duplicate");
        String expected = "160000 commit " + head + "\t" + path + "\0";
        require(git(parent, "ls-tree", "-z", hostHead, "--", path).equals(expected), "parent gitlink mismatch");
        require(git(parent, "ls-files", "--stage", "-z", "--", path)
                .equals("160000 " + head + " 0\t" + path + "\0"), "staged gitlink mismatch");
        require(git(parent, "rev-parse", "HEAD").strip().equals(hostHead), "host HEAD changed during inspection");
        facts.put("host_commit", hostHead); facts.put("submodule_path", path); facts.put("fork_url", url);
        stages.add("PARENT_BINDING\tPASS");
    }

    private String git(Path repo, String... args) throws Exception {
        String prefix = String.format(java.util.Locale.ROOT, "%02d", ++commands);
        Path log = output.resolve(prefix + ".log");
        List<String> command = new ArrayList<>(List.of("git", "--no-pager", "--no-lazy-fetch",
                "--no-replace-objects", "-c", "core.hooksPath=" + output.resolve("absent-hooks"),
                "-c", "core.fsmonitor=false", "-c", "protocol.allow=never", "-C", repo.toString()));
        command.addAll(List.of(args));
        write(prefix + ".argv", String.join("\n", command) + "\n");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
        builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_CONFIG_GLOBAL", output.resolve("absent-global").toString());
        builder.environment().put("LC_ALL", "C");
        Process process = builder.start();
        process.getOutputStream().close();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        try {
            while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                if (System.nanoTime() >= deadline || Files.size(log) > 8_388_608) {
                    throw new IOException("Git deadline/output budget: " + args[0]);
                }
            }
            write(prefix + ".exit", process.exitValue() + "\n");
            require(process.exitValue() == 0 && Files.size(log) <= 8_388_608, "Git command failed: " + args[0]);
            return Files.readString(log, StandardCharsets.UTF_8);
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    private static Map<String, String> tree(String listing) throws IOException {
        Map<String, String> result = new TreeMap<>();
        for (String entry : listing.split("\0", -1)) {
            if (entry.isEmpty()) continue;
            int tab = entry.indexOf('\t');
            require(tab > 0 && result.putIfAbsent(entry.substring(tab + 1), entry.substring(0, tab)) == null,
                    "invalid or duplicate tree entry");
        }
        return result;
    }
    private static String rootTable(Map<String, String> entries) {
        return "path\tmode_type_object\n" + entries.entrySet().stream()
                .map(e -> e.getKey() + "\t" + e.getValue() + "\n").collect(java.util.stream.Collectors.joining());
    }
    private static Path ordinaryDirectory(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        require(Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS) && absolute.toRealPath().equals(absolute),
                "ordinary canonical directory required");
        return absolute;
    }
    private static void requireSha(String sha) {
        if (sha == null || !sha.matches("[a-f0-9]{40}")) throw new IllegalArgumentException("full SHA1 commit required");
    }
    private static void require(boolean value, String message) throws IOException {
        if (!value) throw new IOException(message);
    }
    private void write(String name, String value) throws IOException {
        Files.writeString(output.resolve(name), value, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
    private void report(boolean passed) throws Exception {
        String status = passed ? "SOURCE_CUSTODY_VERIFIED" : "HOLD";
        write("STATUS.tsv", "item\tstatus\nsource\t" + status
                + "\ngpu_execution\tNOT_EXECUTED\ncanonical_promotion\tNOT_AUTHORIZED\n");
        write("PROVENANCE.tsv", rootTable(facts));
        write("VERIFY_CONTRACT.tsv", "stage\tstatus\n" + String.join("\n", stages) + "\n");
        write("RUN_CONTEXT.tsv", "key\tvalue\nprofile\tJAVA21_SOURCE_CUSTODY_ONLY\nworkers\t1\nshards\t1\n");
        write("TODO.tsv", "status\ttask\nOPEN\tSDK build and actual device parity are independent gates\n");
        write("FINAL_REPORT.md", "# Source custody\n\nStatus: " + status
                + ". See ordered command logs, source roots and pin binding. No source changed.\n\n"
                + "No compiler/SDK/device validation or semantic equivalence follows from this source check. "
                + "Ignored files and concurrent hostile writers are not attested. No remote is contacted.\n");
        write("OUTPUT_CONTRACT.tsv", "artifact\tstatus\n" + List.of("STATUS.tsv", "FINAL_REPORT.md",
                "PROVENANCE.tsv", "VERIFY_CONTRACT.tsv", "RUN_CONTEXT.tsv", "OUTPUT_CONTRACT.tsv", "TODO.tsv")
                .stream().map(name -> name + "\tPRESENT\n").collect(java.util.stream.Collectors.joining()));
        String root = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(rootTable(facts).getBytes(StandardCharsets.UTF_8)));
        write("RESULT.tsv", "status\t" + status + "\nsource_identity\t" + root + "\n");
        System.out.println(status + " identity=" + root);
    }
}
