# Repository agent instructions


## Superset merge and history-audit rule (2026-11-18)

Read [.github/MERGE_RULE.md](.github/MERGE_RULE.md) before changing an existing implementation or integrating any branch or PR.
Preserve the supported additive API/behavior superset and select implementations by correctness, predictability, measured performance, then elegance.
Audit all reachable history back to every root, all branches/tags and all PR states for lost code or regressions; preserve append-only history.
Record actual UTC coverage and remaining work in [.github/HISTORY_AUDIT.tsv](.github/HISTORY_AUDIT.tsv); policy installation is not a completed source-history audit.
Prefer verified Apache-2.0 sources and independent implementations while preserving all existing third-party rights; LLM rewriting does not remove license obligations.
Detailed private recipe/proof records remain in canonical Synexia; the compact local policy and checkpoint make the rule discoverable here.
<!-- MERGE-SUPERSET-HISTORY-20261118-2 -->

## MOP SDK: catalogued atoms, patterns and typed unknowns (2026-11-18)

Apply the [canonical Synexia semantic-atom and pattern invariant](https://github.com/hsoliwal/com.synexia/blob/develop/docs/M3-SCALE/invariants/SYNEXIA-UNIVERSAL-API-ATOM-PATTERN-EVOLUTION-1.md) and [MOP-13](https://github.com/hsoliwal/com.synexia/blob/develop/docs/M3-SCALE/invariants/SYNEXIA-MOP-SDK-BIDIRECTIONAL-LATTICE-1.md) before modifying source. Descend repository -> module -> package -> file -> symbols and behavioral atoms; preserve public and binary APIs, effects, ordering, ownership, concurrency, native and resource contracts. Look up known algorithms and patterns by versioned catalogue identity and evidence, then compose verified atoms upward through only the affected parent and caller closure. For any absent or unproven match retain source and register `UNKNOWN_ALGORITHM`, `UNKNOWN_PATTERN` or `UNPROVEN_MATCH` in Synexia's machine-readable semantic inventory, linked from accurate documentation or an already supported annotation. Never manufacture uncompilable tags, force helper extraction, relicense donor expression or infer equivalence from a hash, LLM suggestion or label. Independent files may be analyzed in bounded parallel; canonical mutation and admission are serial, recipe-first and compiler/test/runtime proof-gated. This addition is a policy binding, not evidence that a full Git-history audit, atomization or behavior test has run. Product repositories remain clean postimage receivers, not private recipe or evidence stores.
