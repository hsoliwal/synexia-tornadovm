<!-- SPDX-License-Identifier: Apache-2.0 -->
# Optional M3JDK21 vector add provider for TornadoVM

This standalone first-party Apache-2.0 adapter implements
`com.synexia.m3.compute.vector.VectorCompute.Provider`. It is deliberately
**outside TornadoVM's original Maven reactor**, preserving all upstream
runtime/source/build contracts. The identical canonical first-party source
is held in `hsoliwal/com.synexia/synexia-tornadovm-m3/jdk-vector-provider`;
this fork is the optional physical TornadoVM/device test consumer.

## Binding

```text
M3JDK21 optional module com.synexia.m3.compute.vector
  VectorCompute.addWithBackend(left, right, "tornadovm-vector-add")
    -> Java ServiceLoader provider descriptor (classpath JAR)
    -> TornadoVectorAddProvider.add(...)
    -> TornadoVM TaskGraph: upload a/b, add int vectors, download c
    -> independent VectorCompute.addVerified CPU parity
    -> copy-safe Result + SHA-256 receipt (or throws)
```

This uses the fork's existing TornadoVM `TaskGraph`, `@Parallel` and
`IntArray` APIs rather than inventing a GPU runtime. A nonzero vector
can only succeed if the configured runtime executes; a mismatched
result or mutated copy **fails closed**. Device selection is TornadoVM
host/runtime-owned, not supplied by user model text. The stable provider
name does not certify that a GPU or any particular device was used.
Zero-length vectors exit without dispatch and are not hardware evidence.

## Build as an optional application JAR

First install the **separate JPMS JAR**, not a JMOD file:

```sh
mvn -B -ntp -f synexia-m3-jmods/modules/com.synexia.m3.compute.vector/pom.xml install
```

Then, in this TornadoVM fork:

```sh
mvn -B -ntp -f m3/jdk-vector-provider/pom.xml test package
```

The Maven profile pins `io.github.beehive-lab:tornado-api` and
`tornado-runtime` to the matching released `6.1.0-jdk21` coordinates.
The pinned fork source commit is not claimed to reproduce those
published Maven artifacts; perform source/backend qualification first.
Supply the correct Tornado native drivers, JDK flags (including preview
FFM where required), and platform configuration externally. The optional
first-party provider JAR belongs on the application classpath and its
`META-INF/services/com.synexia.m3.compute.vector.VectorCompute$Provider`
entry selects it; do not embed device libraries, JVM internal
flags or native drivers into `java.base` or this source module.

A Maven metadata/ServiceLoader test requires the dependent JARs but no
physical GPU. Real device execution, complete parity across sizes
and overflow, process RSS/heap, throughput, and Windows/JDK21 packaging
are **not yet demonstrated**. No successful hardware check is implied by
the source and unit tests in this change.

## Recipe-first / provenance

Canonical OpenRewrite source recipe:
`com.synexia.rewrite.M3TornadoVectorProvider20261014`.
The source and service manifests must be pinned to exact preimages.
TornadoVM upstream API/runtime source remains under its own original
licenses; this separately-authored adapter is Apache-2.0.
