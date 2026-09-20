# TornadoVM M3 integration

SPDX-License-Identifier: Apache-2.0

## Task and boundary

Java21, mechanical-first, one worker/shard, review-branch changes only. The upstream
source commit is 65f06c5d162c36022f9d5724dd38b9784d85cfcc and its root tree is
17698b0fe211b0579d50646974f6ec8ca00c56d1. Only m3/ is added. Upstream source, build
scripts, public APIs, supported backends/JDKs, license files and history are retained.
No donor copying, model invocation, automatic synchronization or promotion occurs.

The actual fork is hsoliwal/synexia-tornadovm. Its master gained M3 work independently
during this integration. This branch preserves that observed work and our original
integration as two parents; this task does not update master. See CONVERGENCE.md.
The parent must pin the final exact fork commit, not a moving branch or the earlier
pre-convergence pin. Follow the reviewed parent lock when initializing a checkout.

## Compatible source checks

Both existing Java entrypoints are retained without changing their source or tests.
They implement complementary source checks, not GPU or build verdicts:

- M3DonorVerifier: recursively compares all upstream entries, requires new entries
  to be ordinary mode-100644 files under m3/, and checks a properties-based parent
  lock. Its original guide is retained in DONOR_VERIFIER_GUIDE.md; that guide's
  historical master-branch statement is superseded by this README.
- Verify: compares upstream root/subtree identities, verifies exact checked-out
  upstream/fork ancestry and committed/staged/working parent binding, and writes
  create-only M3 evidence. See USAGE.md for its arguments and output contract.

Run both checks for the consuming integration. No check fetches, initializes,
repairs, stages or promotes code. Only Verify's explicit fresh evidence directory
receives files. Use a full, clean, trusted exclusive checkout; ignored files,
assume-unchanged flags and hostile concurrent writers are not attested.

## M3 execution model

Inventory before mutation; documentation before code. Work from bounded leaves,
preserve source/test oracles, and verify diff -> lint -> compile -> tests -> runtime.
Keep a prioritized TODO ledger. Required evidence includes STATUS.tsv,
FINAL_REPORT.md, RUN_CONTEXT.tsv, PROVENANCE.tsv, VERIFY_CONTRACT.tsv and
OUTPUT_CONTRACT.tsv. Stop on unsupported scope, pin drift or failed verification.

TornadoVM owns compilation and device execution. M3 owns host-side contracts,
partitioning, custody and admission. Only bounded primitive data kernels cross the
device boundary. COP identity, permissions, storage, context graphs, journal writes
and canonical publication stay on the host. CPU reference semantics remain the
oracle. Hash/SimHash prefilters are not cryptographic or semantic equivalence proof.
An LLM may propose a residual change; it does not authorize that change or its own
proof. Parallel candidate production does not permit parallel canonical promotion.

## Independent artifact and license records

The pinned source POM is 6.1.1-jdk21-dev. The existing optional consuming Maven
profile selects 6.1.0-jdk21; runtime.matches.source=false explicitly preserves that
distinction. A source gitlink is not a binary build receipt or hardware qualification.
Preserve upstream LICENSE_APACHE2, LICENSE_GPLv2CE, LICENSE_MIT and individual
notices. API/application-facing modules and runtime/drivers have different licenses;
the latter use GPLv2 with Classpath Exception. New m3/ files carry Apache-2.0 headers,
not a blanket relicensing of TornadoVM. Do not copy private host sources here.

## Executed versus open gates

All four verifier Java files compiled together with --release 21 -Xlint:all -Werror.
The retained VerifyTest suite passed 26 cases and the imported M3DonorVerifierTest
passed all 35 assertions on JDK21 using real local Git repositories. Their sources
and test oracles are unchanged. See evidence/CONVERGENCE.tsv and convergence-tests.log.

A full remote clone was blocked by DNS in the execution container. GitHub object
inspection is separate evidence. TornadoVM compilation, Maven resolution, actual
CUDA/OpenCL/Metal execution, parity and performance remain unexecuted here. Source
custody does not imply any of those results. Prior evidence is retained as historical.
