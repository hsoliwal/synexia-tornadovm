# Concurrent M3 contribution convergence

SPDX-License-Identifier: Apache-2.0

The integration branch started from upstream 65f06c5d162c36022f9d5724dd38b9784d85cfcc.
During this run master independently advanced to 792ff6865c4ca68ecd55e1db66693f1803f17cf2,
adding M3DonorVerifier, its tests, AGENTS.md, README and upstream properties.
The earlier statement that master remained an upstream-only snapshot is superseded.
This task did not update master.

Preserve both histories through a two-parent commit on the integration branch.
Retain M3DonorVerifier.java, M3DonorVerifierTest.java and AGENTS.md byte-for-byte.
Retain its original README as DONOR_VERIFIER_GUIDE.md; revise the shared README to
explain both public entrypoints. Union the compatible upstream.properties keys,
reject conflicting pin values, and keep every inherited upstream entry unchanged.
No source/runtime API is renamed, removed or replaced.

The recursive M3DonorVerifier validates every inherited entry and new regular-file
modes. Verify adds create-only M3 evidence, exact staged/working parent binding,
and repeated custody checks. Both are source checks, not GPU gates. They may be
run together; neither result elevates to compiler, binary or hardware authority.
The consuming host will use the final converged commit, not the earlier 2d6504a pin.

Read the complete imported sources before composition. Compile all four verifier
sources with Java21 warnings as errors and execute both existing test suites.
Retain the original 26-case and imported 35-assertion tests unchanged. Full remote
clone and real-device gates remain unexecuted unless actual tools complete them.
All changes stay under m3/ and no default-branch or upstream PR merge is performed.
