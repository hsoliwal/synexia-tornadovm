# M3 integration branch

SPDX-License-Identifier: Apache-2.0

## Task packet

Task: maintain a pinned TornadoVM fork with a small, additive M3 integration boundary.
Mode: apply to this review branch only. Java baseline: 21. Workers/shards: 1/1.
Upstream: beehive-lab/TornadoVM at 65f06c5d162c36022f9d5724dd38b9784d85cfcc.
Upstream root tree: 17698b0fe211b0579d50646974f6ec8ca00c56d1.
Allowed delta: new files under m3/ only. Public APIs, upstream source, build scripts,
licenses and default branch remain unchanged. No vendor copying, cloud calls,
model invocation, automatic upstream synchronization or canonical promotion.

Verification order: diff -> source lint -> Java21 compile -> tests -> runtime.
Outputs: STATUS.tsv, FINAL_REPORT.md, RUN_CONTEXT.tsv, PROVENANCE.tsv,
VERIFY_CONTRACT.tsv, OUTPUT_CONTRACT.tsv and TODO.tsv. Stop on unexpected source
change, missing history, pin mismatch, dirty checkout or failed verification.
These checks establish source custody only, not GPU correctness or performance.

## Ownership and execution

TornadoVM remains the compiler/device-runtime implementation. M3 remains the
host-side contract, partition, verification and admission layer. Independent,
bounded primitive-array kernels may run on devices; repository mutation, AST
analysis, context ownership, storage and canonical promotion stay on the host.
CPU reference semantics are retained. Hash/similarity prefilters do not establish
semantic equivalence. A device failure or parity mismatch is not successful GPU
execution; any permitted fallback must be explicit in the host result.

The fork master branch remains an upstream snapshot. This integration branch
adds m3/ without rewriting TornadoVM. Parent repositories must pin an exact Git
commit rather than depend on a moving branch. Source pin, Maven artifact version,
installed SDK identity and executed device evidence are separate records.
Updating one does not silently prove or update the others.

## License boundary

Preserve all upstream headers and license files. Upstream identifies the API and
listed application-facing modules as Apache-2.0, and runtime/drivers as GPLv2 with
Classpath Exception. This fork is not a blanket Apache-2.0 relicensing. New m3/
files carry their own Apache-2.0 notices. See upstream README.md and LICENSE_*.

## Source custody tool

The Java21 verifier added next will check exact donor and fork commit identities,
upstream ancestry, an unchanged upstream root-tree projection, a clean checkout,
and optional parent .gitmodules/gitlink binding. It is read-only except for a new
explicit evidence directory. It must reject missing/uninitialized submodules and
never fetch, checkout, repair, stage or promote anything automatically.
