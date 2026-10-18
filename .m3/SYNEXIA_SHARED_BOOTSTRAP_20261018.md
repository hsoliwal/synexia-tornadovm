<!-- SPDX-License-Identifier: Apache-2.0 -->
# Canonical Synexia bootstrap — hsoliwal/synexia-tornadovm

Public receiver pinned to `4df8619f2bb7ac13c0c7e8d56456bada9ebd465a`.
The first-party, exact-source recipes in this fork are already identical
to the reusable canonical Java21 OpenRewrite implementations from
[Synexia #12184](https://github.com/hsoliwal/com.synexia/pull/12184).
Inventory blob: `8f1018a1bcfa203420253d59a8473ef5a18e19b6`.
Task-admission blob: `cfb9fb8befe2c5ccde5035e96ae7c99b89b5f490`.

This scoped receiving recipe test executes the actual OpenRewrite SDK
`Recipe.run` on Java21 source with misleading code-like string literals,
requires a second complete source fixed point and verifies SHA-256 task
input admission/refusal. The inventory is intentionally **read-only**:
application must return no source changes. No original runtime, widget,
JNI/native, compiler or IDE APIs are rewritten here.

Keep the original target-local Maven/OpenRewrite adapter until its ABI and
dependency compatibility is qualified; shared canonical recipe semantics
remain in Synexia, with each upstream license and NOTICE intact.
This proof does not mean all other unrelated source-changing recipes
are safe to apply to this fork. Require exact-source and contract review
for each later composition.
