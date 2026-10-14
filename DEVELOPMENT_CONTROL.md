# Synexia development control

Status: **ACTIVE**. Updated: 2026-10-14.

This document is the readable handoff for development across com.synexia and its related repositories. The ongoing storage-protection goal stays active after audits, commits and PRs.

## Current priorities

1. Protect storage and preserve canonical source, Git objects, transcripts, live databases and unique proofs.
2. Preserve and qualify the current working changes, then publish reviewable topic PRs.
3. Combine compatible changes from related repositories using existing recipes, source hashes, executed tests and provenance.
4. Keep this document and the development queue readable in agent memory, scratch and repository review locations.

The registered UI goal is still the original ACTIVE storage goal. The broader development scope is saved in durable task records; replacing the UI objective remains pending because the available goal tool cannot edit its text. Do not mark the storage goal complete to replace it.

## Storage rules

- S: is the confirmed scratch destination. Its roughly 1 TB capacity is not free space.
- Remeasure before and during output. Retain at least 8 GiB free after planned output; the default build budget is 512 MiB.
- Every Windows shell uses cmd.exe with login disabled and explicitly sets TEMP/TMP to K:\codex-work\agent-temp before children. Check K: capacity, then verify native GetTempPathW. Python uses -I -S -B.
- No new build, test, download or cache scratch on C:, D: or E:. Small agent settings and memory updates on C: are permitted.
- G: and I: have 1 MiB exFAT clusters. Avoid small-file source/build/cache trees there.
- Keep the C:\m3-work-c output lane stopped until its actual output/cache routing and capacity gates pass.
- Do not rerun completed recovery helpers. Their pins describe historical states.
- Saved configuration is not proof of effective routing. PowerShell diagnostics remain stopped until their startup/cache routing is verified.

## Development method

Inventory before generation. A substantial capability requires a FOSS_REUSE_DECISION.tsv row before new architecture: search, inventory, classify, prove fit, attribute, then implement only the missing gap.

Use deterministic CPU leaves and provisioned offline donors first. Reuse worktrees and caches; avoid full clones, repository snapshots and unbounded fanout. A recipe name or merged PR is not proof of execution, behavioral equivalence or hosted CI.

Preserve current APIs and unique variants. Conflicting changes require an explicit composition and proof. Recency alone does not choose the best implementation. Publish changes on topic PRs; merge and promotion are separate decisions.

## Verified findings and current queue

| Work | Evidence and next action |
| --- | --- |
| Storage protection | ACTIVE. All mounted local/removable roots were inventoried. D:/E:/F: remain below reserve; A: is unavailable/unknown. File-tree recipe coverage is bounded and incomplete. |
| Current checkout | Local HEAD object is missing. Full index and candidate metadata are preserved on S:. No blind add-all, lazy fetch or inferred deletion. Compare against pinned remote content. |
| Current source | 152 verified byte differences and 1,057 source-like untracked candidates were classified. Missing tracked files are preserved as unresolved custody, not deletions. |
| Current PR unit | Standalone source-coverage gate and eight original tests are being qualified. Legacy binding/proof/Java gates require owner integration: current canonical provenance and two Java owners are missing. |
| Recipes | Git fan-in and GitHub publication planners passed 52 focused tests. Existing best-of-best and repository-superset OpenRewrite recipes were found and their contracts inspected. |
| Summarizer | All 131 source-only manifest destinations already match current GitHub source. Local task-crate scratch needs no duplicate publication. |
| Related donor | FileLLM from m3-tooling-apache2 is staged as a self-contained project under synexia-m3-tooling/filellm. Its 18 production Python modules are unchanged. One fixture explicitly uses LF newlines: 19 tests pass, one Windows rename case is skipped. |
| Donor execution | Current hash-pinned text recipe execution and combined package proof are pending. Preserve unsupported original filenames through separately hash-verified Git transport; do not widen the recipe's path policy silently. |
| Review | Use one source-aware autoreview with command execution disabled and verified runtime/cache routing. Tests and review provide different evidence. |

## Durable records

The storage status is S:\codex-work\storage-recovery-20261013\STORAGE_PROTECTION_ACTIVE.json.
Readable control and task-scope receipts live beside it. Source inventory, publication plans and recipe catalogue are there; FileLLM staging/proofs live in S:\codex-work\recipe-superset-20261014.

These are observations and pending work, not permission to bypass holds. Update this handoff as proofs and PRs land; retain original failures, skips and provenance.
