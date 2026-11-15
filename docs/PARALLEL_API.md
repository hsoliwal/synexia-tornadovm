<!-- SPDX-FileCopyrightText: 2026 Hitesh Soliwal and contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# Tornado parallel API for M3JDK21

`hsoliwal/synexia-tornadovm` is the source fork for M3JDK21's parallel task API.
M3JDK21 owns the delivered SDK and installed runtime. Synexia owns the canonical
recipes that produce and qualify changes. The public API retains the existing
`tornado.api` module and `uk.ac.manchester.tornado.api` package identities.

## Run a task graph on the host CPU

`TaskGraph.host(name)` executes ordinary Java task methods through the existing
`TaskGraph`, `ImmutableTaskGraph`, and `TornadoExecutionPlan` API. This explicit
mode requires only `tornado.api`; it does not initialize a Tornado runtime, probe
a device, compile a device kernel, or load a backend driver.

```java
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;

public class HostAdd {
    static void add(int[] left, int[] right, int[] output) {
        for (int i = 0; i < output.length; i++) {
            output[i] = left[i] + right[i];
        }
    }

    public static void main(String[] args) throws Exception {
        int[] left = {1, 2, 3};
        int[] right = {4, 5, 6};
        int[] output = new int[3];
        var graph = TaskGraph.host("host-add")
                .task("add", HostAdd::add, left, right, output)
                .snapshot();
        try (var plan = new TornadoExecutionPlan(graph)) {
            plan.execute();
        }
        System.out.println(java.util.Arrays.toString(output)); // [5, 7, 9]
    }
}
```

Tasks run in insertion order. Graphs in a plan retain the execution plan's graph
order. Repeated execution runs the captured tasks again against the same host
payload objects. A snapshot freezes task membership and argument bindings;
arrays, native host arrays, and other argument objects remain shared with the
caller. Captured state inside a task function is also ordinary shared Java
state. Callers must provide any required synchronization.

The `@Parallel` annotation does not schedule host threads in this mode. A Java
loop executes according to its ordinary Java body. Applications that need
accelerator execution continue to use `new TaskGraph(name)` and the existing
matching Tornado runtime/backend. The host factory does not silently fall back
after a device or kernel failure.

Host graphs support ordinary task functions with zero through twenty arguments
and the existing public static `Method` task overload. A thrown task exception
stops subsequent tasks. Device transfers, device views, prebuilt kernels, native
library tasks, `KernelContext`, `TileContext`, device selection, and device-only
memory or scheduling options are rejected. Host results are already in the
caller's objects; there is no transfer-back step.

## Run the API-only acceptance test

Use a JDK 21 installation. The API uses Java 21 preview foreign-memory classes,
so compilation and execution require `--enable-preview`.

```sh
HOST_API_BUILD=$(mktemp -d)
find tornado-api/src/main/java -name '*.java' > "$HOST_API_BUILD/sources.list"
javac --enable-preview --release 21 -d "$HOST_API_BUILD/api" \
    @"$HOST_API_BUILD/sources.list"
javac --enable-preview --release 21 -Xlint:all,-preview -Werror \
    --module-path "$HOST_API_BUILD/api" --add-modules tornado.api \
    -d "$HOST_API_BUILD/tests" \
    tornado-api/src/test/java/uk/ac/manchester/tornado/api/tests/HostTaskGraphTest.java
java --enable-preview -ea --module-path "$HOST_API_BUILD/api" \
    --add-modules tornado.api --class-path "$HOST_API_BUILD/tests" \
    uk.ac.manchester.tornado.api.tests.HostTaskGraphTest
```

This test intentionally excludes Tornado runtime and driver modules. Accelerator
qualification requires a matching runtime version, backend, native libraries,
and hardware. An API-only pass does not establish device parity or a speedup.

## Version compatibility

This fork's current API is 8.1.0. M3JDK21's historical optional runtime lane is
pinned to Tornado 7.2.0. Those versions must be qualified separately: newer API
interfaces add runtime obligations, and 8.1.0 removed the old execution-plan
`DEFAULT_DEVICE` and `planResults` fields. The existing current API accessor and
execution-result history options remain the contracts for new integrations.
