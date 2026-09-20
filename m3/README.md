<!-- SPDX-License-Identifier: Apache-2.0 -->
# TornadoVM: M3 additive donor fork

This directory is an additive source-custody overlay. The original TornadoVM source, build files,
module layout, history, notices and licenses are unchanged. The overlay was reviewed on
`m3/java21-donor-20260920` and merged into this fork's `master` as an additive `m3/` lane only.
The upstream source commit/tree recorded in `upstream.properties` remains the immutable donor preimage.

## Identities are separate

`upstream.properties` records the exact upstream commit and complete Git tree. A consuming
superproject pins a **fork commit** with a real mode-160000 gitlink. It must not mistake that fork
commit for the upstream revision or a Maven artifact. The optional application adapter currently
selects `6.1.0-jdk21`; the donor source is an explicitly dated master commit, NOT a verified
source-to-binary reproduction of that published artifact. `runtime.matches.source=false` records
that distinction. Java 21 is the application/verifier baseline; no upstream multi-JDK support is removed.

## Mechanical source preservation

```sh
# From a full, clean checkout of this fork (not a shallow clone):
java m3/M3DonorVerifier.java donor .
```

The Java 21 verifier invokes fixed, read-only Git operations, never a shell or a build script. It
requires full commit hashes, the expected upstream tree, actual ancestry, a normal clean working
copy, unchanged mode/type/object IDs for EVERY upstream entry, and only new regular files below
`m3/`. It rejects edits/deletions of donor files, changes to executable bits, symlink overlays,
additions elsewhere, shallow history, dirty checkouts and unrelated histories. The verifier does
not fetch. It disables replacement objects, optional Git locks and fsmonitor; output is bounded and
Git processes have deadlines. This is a trusted local-repository tool, not an OS sandbox or a
signature verifier. Git's normal ignored-file/assume-unchanged semantics still apply to worktree
cleanliness; the authoritative preservation comparison is between committed Git objects.

Parent mode also checks the committed gitlink, `.gitmodules` URL/path, actual submodule HEAD,
source/fork lock agreement and scoped worktree/index cleanliness:

```sh
java donors/tornadovm/m3/M3DonorVerifier.java parent . path/to/tornadovm-fork.properties
```

Successful source custody prints `runtime.compile=NOT_EXECUTED` and `device.parity=NOT_EXECUTED`.
A green custody check never claims a working GPU backend or performance benefit.

## M3 engineering sequence

1. Inventory exact source, licenses, public contracts, scope and proposed patch.
2. Work leaf-first: bounded numeric kernels, then adapters, modules, and integration. Reuse TornadoVM
   TaskGraph/device/transfer APIs; do not build another GPU runtime or rewrite the Java compiler.
3. Preserve donor behavior. This initial fork permits only `m3/` additions. A future upstream-code
   change requires an explicitly reviewed policy change, old/new source custody, contract and test
   evidence; do not weaken this gate merely to admit a conflicting patch.
4. Apply proof gates in order: diff -> lint -> compile -> tests -> runtime. Model suggestions are
   proposals, not approval or evidence. CPU reference results remain the independent oracle.
5. Promote serially through review. Preserve exact preimages and unresolved conflicts; never use
   silent "accept both", force-pushes, or moving-branch submodule updates as proof of equivalence.

Keep COP identity, authority, state publication, journal commits and orchestration on the host.
Only explicitly bounded data projections cross the accelerator boundary. No SQLite connection,
VarHandle, arbitrary context graph or permission-bearing object belongs in a numeric kernel.
FNV/SimHash-style prefilters are not cryptographic identities. Hardware qualification must compare
results against an independent CPU implementation; mismatch is a rejection, not a silent success.

## License preservation

The pinned upstream README's per-module table identifies Tornado-API and several application-facing
modules as Apache-2.0, and Tornado-Runtime/Tornado-Drivers as GPLv2 with Classpath Exception. The tree
also includes LICENSE_MIT. Preserve all original files and component notices; this is NOT an
Apache-only relicensing of the repository. New files in this directory are Apache-2.0. Inspect the
actual component licenses for a distribution; this record is not a legal opinion.

## Verification performed

The verifier and its test compile with `javac --release 21 -Xlint:all -Werror`. The test uses genuine
local temporary Git repositories (including a local submodule), not a mocked Git parser or a GPU.
35 assertions passed on OpenJDK 21.0.11 before merge. The consuming parent repository must rerun
source-custody verification against its exact pinned fork commit. No TornadoVM runtime build, Maven resolution, device code
generation, CUDA/OpenCL/Metal execution or speedup was tested by this overlay.

```sh
out=$(mktemp -d)
javac --release 21 -Xlint:all -Werror -d "$out" m3/M3DonorVerifier.java m3/M3DonorVerifierTest.java
java -cp "$out" M3DonorVerifierTest
rm -rf "$out"
```

The test creates/deletes ONLY its own temporary fixture directory. Do not put compiler outputs
inside the donor checkout. Upstream build/runtime instructions remain in INSTALL_FROM_SOURCE.md.
