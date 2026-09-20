# M3 fork source-custody implementation

SPDX-License-Identifier: Apache-2.0

## Files changed

Only m3/ is added on the integration branch. Inherited source, upstream compiler,
POMs, workflows and license files are not replaced. The upstream snapshot remains
65f06c5d162c36022f9d5724dd38b9784d85cfcc. New Java tool source commit is
cd131bc9c55f2cfd3dcb9d757401e4e7534f543c; documentation preceded implementation.

## Exact delta

A dependency-free Java21 verifier checks exact fork/upstream commits, complete
ancestry, unchanged upstream root entry hashes outside m3/, clean tracked and
nonignored source, optional parent committed/staged gitlinks and exact submodule
URL/path configuration. It rejects mismatches, writes only a new explicit proof
directory, and never fetches, mutates source or authorizes promotion. Source,
Maven artifacts, SDK build and device execution have separate authority.

## Exact verification

Two runs passed 26 checks each using real local Git repositories, JDK21 compiler
and command execution. Fresh second-pass diff/whitespace checks and source lint
preceded warning-as-error compilation. Tests cover positive parent binding,
identical repeated receipts, unchanged index/ref bytes, stale pins, dirty trees,
shallow history, upstream changes, unexpected root namespaces, staged gitlink and
configuration drift, duplicate URL keys, unsafe output and malformed paths.
Both Java blobs were read back from GitHub and match tested bytes. The committed
second-pass log is tests.log; complete fixture evidence is in the delivery bundle.

## Exact blockers

A direct remote clone attempt failed because github.com DNS resolution was not
available in the execution container. GitHub connector reads/writes work, but
that does not constitute an actual full-checkout clone run. Tests exercised local
fixtures. No TornadoVM build, GPU code generation, hardware parity or performance
was executed. Ignored files and hostile concurrent writers are not attested.

## Artifacts

m3/evidence contains required M3 ledgers, source hashes and the final regression
log. m3/USAGE.md documents the standalone and optional parent-bound commands.
