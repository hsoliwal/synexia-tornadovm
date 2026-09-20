/* SPDX-License-Identifier: Apache-2.0 */
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Real local Git fixtures for the source gate. No TornadoVM or GPU execution is simulated. */
public final class VerifyTest {
    private static int checks;
    private static int sequence;
    private static Path root;
    private static Path host;
    private static Path donor;
    private static String base;
    private static String fork;
    private static String parent;
    private static final String URL = "https://github.com/example/tornado-fork.git";
    private VerifyTest() {}

    public static void main(String[] args) throws Exception {
        root = Files.createDirectory(Path.of(args[0]).toAbsolutePath());
        host = Files.createDirectory(root.resolve("host"));
        Files.createDirectory(host.resolve("donors"));
        donor = Files.createDirectory(host.resolve("donors/tornadovm"));
        init(donor);
        Files.writeString(donor.resolve("README.md"), "unchanged upstream\n");
        Files.writeString(donor.resolve("LICENSE"), "upstream notice\n");
        base = commit(donor);
        Files.createDirectory(donor.resolve("m3"));
        Files.writeString(donor.resolve("m3/policy.txt"), "additive M3 policy\n");
        fork = commit(donor);
        init(host);
        Files.writeString(host.resolve(".gitmodules"), config(URL));
        git(host, "add", ".gitmodules");
        stageLink(fork);
        git(host, "commit", "-qm", "pinned parent fixture");
        parent = git(host, "rev-parse", "HEAD").strip();
        byte[] donorIndex = Files.readAllBytes(donor.resolve(".git/index"));
        byte[] hostIndex = Files.readAllBytes(host.resolve(".git/index"));
        String[] good = arguments(base, fork, true);
        check(Verify.run(good) == 0, "real Git fork and parent binding passes");
        String[] replay = arguments(base, fork, true);
        check(Verify.run(replay) == 0 && Files.readString(Path.of(good[3], "RESULT.tsv"))
                .equals(Files.readString(Path.of(replay[3], "RESULT.tsv"))), "same pins give identical source receipt");
        check(java.util.Arrays.equals(donorIndex, Files.readAllBytes(donor.resolve(".git/index")))
                && java.util.Arrays.equals(hostIndex, Files.readAllBytes(host.resolve(".git/index"))),
                "inspection leaves donor and parent index bytes unchanged");
        check(git(host, "rev-parse", "HEAD").strip().equals(parent)
                && git(donor, "rev-parse", "HEAD").strip().equals(fork), "inspection leaves refs unchanged");
        check(Verify.run(arguments(base, fork, false)) == 0, "standalone donor verification");
        reject(() -> Verify.run(good), "existing evidence cannot be overwritten");
        reject(() -> Verify.run(arguments("HEAD", fork, false)), "symbolic upstream ref rejected");
        reject(() -> Verify.run(arguments(base.substring(0, 12), fork, false)), "abbreviated pin rejected");
        held(arguments(base, base, false), "different checkout head rejected");
        held(arguments("0".repeat(40), fork, false), "missing upstream history rejected");
        String[] inside = arguments(base, fork, true); inside[3] = donor.resolve("forbidden").toString();
        reject(() -> Verify.run(inside), "output cannot mutate donor");
        inside[3] = host.resolve("forbidden").toString();
        reject(() -> Verify.run(inside), "output cannot mutate host");
        Files.writeString(donor.resolve("README.md"), "dirty\n");
        held(arguments(base, fork, true), "tracked donor modification rejected");
        git(donor, "checkout", "--", "README.md");
        Files.writeString(donor.resolve("m3/untracked.txt"), "untracked\n");
        held(arguments(base, fork, true), "uncommitted overlay rejected");
        Files.delete(donor.resolve("m3/untracked.txt"));
        Files.writeString(donor.resolve(".git/shallow"), base + "\n");
        held(arguments(base, fork, true), "shallow source history rejected");
        Files.delete(donor.resolve(".git/shallow"));
        Files.writeString(donor.resolve("README.md"), "unauthorized upstream edit\n");
        String changed = commit(donor);
        held(arguments(base, changed, false), "committed upstream edit rejected by tree projection");
        git(donor, "reset", "--hard", fork);
        Files.writeString(donor.resolve("outside.txt"), "unauthorized extra namespace\n");
        changed = commit(donor);
        held(arguments(base, changed, false), "new root namespace outside m3 rejected");
        git(donor, "reset", "--hard", fork);
        held(arguments(fork, fork, false), "base already owning m3 requires explicit new policy");
        Files.writeString(host.resolve(".gitmodules"), config("https://github.com/example/wrong.git"));
        held(arguments(base, fork, true), "dirty parent submodule configuration rejected");
        git(host, "add", ".gitmodules");
        Files.writeString(host.resolve(".gitmodules"), config(URL));
        held(arguments(base, fork, true), "staged parent configuration drift rejected");
        git(host, "reset", "--hard", parent);
        stageLink(base);
        held(arguments(base, fork, true), "staged gitlink drift rejected");
        git(host, "reset", "--hard", parent);
        String[] wrongUrl = arguments(base, fork, true); wrongUrl[7] = "https://github.com/example/wrong.git";
        held(wrongUrl, "unexpected parent fork URL rejected");
        Files.writeString(host.resolve(".gitmodules"), config(URL) + "\turl = " + URL + "\n");
        git(host, "add", ".gitmodules"); git(host, "commit", "-qm", "duplicate URL fixture");
        held(arguments(base, fork, true), "duplicate config key rejected even when values agree");
        git(host, "reset", "--hard", parent);
        String[] badPath = arguments(base, fork, true); badPath[6] = "donors/../tornadovm";
        held(badPath, "noncanonical submodule path rejected");
        check(Verify.run(arguments(base, fork, true)) == 0, "clean restored fixture verifies again");
        check(Files.readString(Path.of(good[3], "STATUS.tsv")).contains("gpu_execution\tNOT_EXECUTED")
                && Files.readString(Path.of(good[3], "STATUS.tsv")).contains("NOT_AUTHORIZED"),
                "source pass does not claim device execution or promotion");
        System.out.println("PASS " + checks + " checks; real_git=true; gpu_execution=NOT_EXECUTED");
    }
    private static String config(String url) {
        return "[submodule \"other\"]\n\tpath = other\n\turl = https://github.com/example/other.git\n"
                + "[submodule \"tornadovm\"]\n\tpath = donors/tornadovm\n\turl = " + url + "\n";
    }
    private static String[] arguments(String upstream, String head, boolean withParent) {
        List<String> args = new ArrayList<>(List.of(donor.toString(), upstream, head,
                root.resolve("proof-" + ++sequence).toString()));
        if (withParent) args.addAll(List.of(host.toString(), "tornadovm", "donors/tornadovm", URL));
        return args.toArray(String[]::new);
    }
    private static void init(Path path) throws Exception {
        git(path, "init", "-q"); git(path, "config", "user.name", "Fixture");
        git(path, "config", "user.email", "fixture@example.invalid");
    }
    private static String commit(Path path) throws Exception {
        git(path, "add", "."); git(path, "commit", "-qm", "fixture");
        return git(path, "rev-parse", "HEAD").strip();
    }
    private static void stageLink(String sha) throws Exception {
        git(host, "update-index", "--add", "--cacheinfo", "160000," + sha + ",donors/tornadovm");
    }
    private static String git(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-c", "core.hooksPath=/dev/null", "-C", repo.toString()));
        command.addAll(List.of(args));
        Path log = root.resolve("fixture-command.log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        process.getOutputStream().close();
        if (!process.waitFor(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly(); throw new IOException("fixture deadline");
        }
        String output = Files.readString(log);
        if (process.exitValue() != 0) throw new IOException(output);
        return output;
    }
    private static void held(String[] args, String label) throws Exception {
        check(Verify.run(args) == 2 && Files.isRegularFile(Path.of(args[3], "failure.log")), label);
    }
    private static void reject(Action action, String label) throws Exception {
        try { action.run(); } catch (IOException | IllegalArgumentException expected) { check(true, label); return; }
        throw new AssertionError(label);
    }
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        System.out.println("PASS " + ++checks + " " + label);
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
}
