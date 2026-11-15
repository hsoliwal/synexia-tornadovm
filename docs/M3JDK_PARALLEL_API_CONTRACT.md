<!-- SPDX-License-Identifier: Apache-2.0 -->
# TornadoVM fork → M3JDK21 parallel API: integration contract

Status: **proposal only**. This document does not claim an implementation, a completed port, a qualified backend, or a passing build.

## Ownership and dependency direction

- **hsoliwal/synexia-tornadovm** retains the upstream TornadoVM-compatible `tornado.api` namespace, task graphs, execution plans, explicit transfers, and backend/provider architecture. Do not rename or silently replace exported TornadoVM types.
- **hsoliwal/com.synexia** exclusively owns reusable analysis, recipes, transformation executions, fixtures, evidence, and donor convergence. Any source transformation used for target intake is authored, verified and recorded there.
- **hsoliwal/M3jdk21** owns the eventual JDK-integrated product postimage and target-specific tests. Do not introduce a runtime dependency from `java.base` or HotSpot onto the standalone TornadoVM fork, or copy recipe tooling into M3JDK21.
- M3JDK21's receiving order is String → arrays → collections → AST/compiler → remaining families. This proposal **does not advance** that gate or authorize a parallel API landing in the target today.
- Use the existing Java/JDK owner for functionality already owned by a standard JDK API; do not create an alternative `jdk.m3.*` class or module just to duplicate it.

## Proposed API behavior (not implemented)

An additive M3 parallel operation will be eligible for offload only when a mechanically checked, pure-data kernel can preserve the specified Java contract. CPU execution is the reference path, not a degraded fallback. API design must state: input/output aliasing, exception timing, memory visibility, cancellation, interrupt behavior, result ordering, overflow and IEEE-754 requirements, supported data layouts, and plan/device ownership.

TornadoVM's existing `TaskGraph` → `snapshot()` → `TornadoExecutionPlan` model remains available without wrapping or altering it. An M3 caller should be able to opt into a backend policy while leaving the existing TornadoVM API unchanged. No claimed automatic GPU conversion of arbitrary Streams, collectors, `java.util` methods, Strings, JNI pointers, or object graphs.

## Admission invariants

1. **Compatibility first:** frozen public signatures, original exception and observable behavior, unchanged JPMS exports; prove additions separately from refactors.
2. **CPU oracle:** compare against the original Java algorithm, including edge cases and deterministically generated adversarial fixtures.
3. **Eligibility:** accept only independently proven kernels and supported data types; reject unsafe aliasing, mutable shared state, unsupported callbacks, or uncertain purity rather than guessing.
4. **Execution plan:** make snapshot, input/output transfer frequency, explicit result retrieval, lifetime and `close()` behavior visible. A snapshot is not a deep copy of every input.
5. **Failure policy:** prohibit silent fallback after a partially committed externally observable side effect. Record whether failure was before launch, during execution, during transfer, or after publication.
6. **Numerics:** do not claim bit-for-bit floating-point equivalence across devices unless tested for the selected precision, operations, compiler and device. Reduction order must be explicit.
7. **Cost model:** offload only when measured end-to-end cost (kernel preparation + transfers + synchronization + execution + retained memory) improves upon an independently measured CPU baseline.
8. **Backend qualification:** OpenCL, CUDA and Metal availability is discovered and tested on actual hardware; a POM profile or device listing alone proves no kernel parity.
9. **Provenance:** preserve TornadoVM's original copyright and license, donor licensing obligations, source seals, and separate product-level acceptance.
10. **Fixed point:** any Synexia recipe must show deterministic replay, refusal on incompatible preimages and an idempotent second application before target intake.

## Smallest executable leaf, once approved

A dedicated *test-first* CPU range kernel with a pure primitive-array transform and an explicitly specified result-order contract. Add an optional TornadoVM `TaskGraph` adapter **only after** the CPU leaf's API, memory behavior and tests pass. Compare CPU and device results for empty, singleton, odd-sized, large and boundary inputs; check input immutability, output aliasing, multiple executions, host mutation between runs, under-demand transfers and plan closure. Avoid exposing new public types until API review.

## Verification order / stop conditions

1. Inventory exact current source commits, exported APIs, module graph, required JDK and build profiles.
2. Put contract, ownership, license mapping and negative cases into Synexia's canonical work item.
3. Generate the implementation candidate using the reviewed Synexia recipe; retain before/after file hashes.
4. Patch the isolated leaf, then lint, compile, test, run on CPU, and only then run differential accelerator tests. Preserve logs and precise command outcomes.
5. Qualify target-specific jtreg/HotSpot/JNI tests only if a JDK postimage is admitted. Benchmark only after correctness and matched inputs are established.
6. Stop on unsupported behavior, missing hardware, mismatched signatures, incomplete evidence, or violation of the M3JDK21 receiving gate. Never promote a passing source-level or donor test as a release claim.

## Existing source maps

- [Fork component guide](COMPONENT_GUIDE.md) and [module inventory](COMPONENT_INVENTORY.md)
- [Host/device execution and transfer lifecycle](HOST_DEVICE_LIFECYCLE.md)
- [Build and test commands](../BUILD_AND_RUN.md)
- [M3JDK21 receiving instructions](https://github.com/hsoliwal/M3jdk21/blob/master/AGENTS.md)
- [M3JDK21 porting invariant](https://github.com/hsoliwal/M3jdk21/blob/master/m3/docs/m3jdk21-porting-invariant.md)

**Explicitly not done by this proposal:** no Java or JNI changes, no kernel build, no test result, no benchmark, no PR merge into M3JDK21 and no claim of JDK compatibility.
