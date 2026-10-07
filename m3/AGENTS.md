<!-- SPDX-License-Identifier: Apache-2.0 -->
# M3 overlay rules

Scope: this directory, not permission to change the rest of TornadoVM.

- Read upstream.properties and README.md before proposing a change.
- Preserve upstream bytes, paths, licenses, histories and supported backends/JDKs.
- Java 21; deterministic, bounded, mechanical-first. Reuse Git and upstream APIs.
- Keep source custody separate from runtime dependency selection and hardware qualification.
- Prove diff -> lint -> compile -> tests -> runtime in that order, recording NOT_EXECUTED honestly.
- Do not add private email, credentials, machine paths or private source archives to this public fork.
- Do not fetch, install software, initialize all submodules, enable upstream release workflows,
  publish packages, update master, merge PRs or force-push without explicit authorization.
- Never claim a source pin establishes safe execution, correct output or acceleration.
