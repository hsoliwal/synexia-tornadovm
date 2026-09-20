# Verify source custody

SPDX-License-Identifier: Apache-2.0

Prerequisites: JDK21, Git with --no-lazy-fetch support (tested Git 2.47.3), a full
ordinary fork checkout, and an existing directory outside the checkout for output.
The integration source branch is pinned by the consuming repository's gitlink.
The upstream source pin is not a released binary: its POM says 6.1.1-jdk21-dev.
An application's separate 6.1.0-jdk21 Maven dependency is not built or changed by
this fork/submodule operation. Do not call those artifacts byte-equivalent.

From this fork checkout, compile the guard and exercise its native Git fixtures:

```sh
javac --release 21 -encoding UTF-8 -Xlint:all -Werror \
  -d /tmp/m3-fork-classes m3/Verify.java m3/VerifyTest.java
java -ea -cp /tmp/m3-fork-classes VerifyTest /tmp/NEW_FIXTURES
```

Then inspect a declared pinned fork, using a fresh output directory:

```sh
java -cp /tmp/m3-fork-classes Verify \
  /absolute/fork 65f06c5d162c36022f9d5724dd38b9784d85cfcc \
  EXPECTED_FORK_COMMIT /tmp/NEW_SOURCE_PROOF
```

Optional parent binding adds four arguments: HOST_ROOT SUBMODULE_NAME
SUBMODULE_PATH EXPECTED_HTTPS_URL. The donor directory must be exactly the parent
submodule directory. The checker compares committed/staged gitlinks and the
committed/staged/working .gitmodules, rejecting duplicate URL/path keys. The
expected fork commit and URL must come from a reviewed parent lock, not from the
unverified checkout itself.

The guard requires an exact checkout HEAD and full ancestry, checks every
upstream root entry's mode/type/object identity, and permits only the additional
m3/ tree. Identical subtree object IDs transitively preserve inherited files.
It rejects tracked changes and nonignored untracked files. Ignored files and
hostile concurrent writers are not attested; use an exclusive trusted checkout.
It never fetches, checks out, resets, stages, compiles TornadoVM or executes GPU
code. Only the explicit new output directory receives files. A source pass does
not imply compiler, Maven, device-parity or performance proof.

Outputs include all mandatory M3 status/provenance/verification/TODO records,
exact Git argv/exits/logs, root manifests and a deterministic source identity.
Failure returns exit 2 with HOLD when an evidence directory can be created;
invalid command usage returns 64. Invalid paths/pins may fail before output
creation. Provider and device verification remain separate admission gates.
