/*
 * Copyright (c) 2026, Hitesh Soliwal and contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * @test
 * @summary Run ordinary host task graphs using tornado-api without a runtime or driver
 * @modules tornado.api
 * @enablePreview
 * @run main/othervm --limit-modules java.base,java.management,tornado.api uk.ac.manchester.tornado.api.tests.HostTaskGraphTest
 */
package uk.ac.manchester.tornado.api.tests;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

import uk.ac.manchester.tornado.api.AccessorParameters;
import uk.ac.manchester.tornado.api.DataRange;
import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.ImmutableTaskGraph;
import uk.ac.manchester.tornado.api.KernelContext;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.TornadoExecutionResult;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.common.TaskPackage;
import uk.ac.manchester.tornado.api.common.TornadoFunctions.Task1;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.enums.ProfilerMode;
import uk.ac.manchester.tornado.api.exceptions.TornadoRuntimeException;
import uk.ac.manchester.tornado.api.exceptions.TornadoTaskRuntimeException;
import uk.ac.manchester.tornado.api.tile.TileContext;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/** Executable acceptance tests. Run with tornado-api alone; no runtime or driver module. */
public final class HostTaskGraphTest {
    private static int assertions;
    private static final RuntimeException KERNEL_FAILURE = new IllegalStateException("kernel failure");
    private static final Error KERNEL_ERROR = new AssertionError("kernel error");
    private static final IOException CHECKED_FAILURE = new IOException("checked kernel failure");

    private HostTaskGraphTest() {
    }

    @FunctionalInterface
    private interface TestAction {
        void run() throws Throwable;
    }

    public static void main(String[] args) throws Throwable {
        if (args.length != 0 && args[0].equals("named")) {
            check("tornado.api".equals(TaskGraph.class.getModule().getName()), "using the real named tornado.api module");
        }
        // A host graph must never instantiate even an explicitly configured runtime loader.
        System.setProperty("tornado.load.api.implementation", FailingRuntime.class.getName());
        System.setProperty("tornado.load.runtime.implementation", FailingRuntime.class.getName());
        System.setProperty("tornado.load.tornado.implementation", FailingRuntime.class.getName());
        expect(ClassNotFoundException.class,
                () -> Class.forName("uk.ac.manchester.tornado.runtime.tasks.TornadoTaskGraph"));

        if (args.length != 0) {
            TestAction targeted = switch (args[0]) {
                case "timed-warmup" -> HostTaskGraphTest::refuseTimedWarmUp;
                case "thread-metadata" -> HostTaskGraphTest::refuseThreadMetadata;
                case "cuda-unbind" -> HostTaskGraphTest::refuseCudaUnbind;
                case "mapping-selection" -> HostTaskGraphTest::preserveSelectionAfterRefusedMapping;
                case "registration-recovery" -> HostTaskGraphTest::registrationRecovery;
                case "empty-transfer" -> HostTaskGraphTest::refuseEmptyMultiGraphTransfer;
                case "partial-range" -> HostTaskGraphTest::preserveRefusedTransferRange;
                default -> null;
            };
            if (targeted != null) {
                run(args[0], targeted);
                System.out.println("PASS: " + assertions + " assertions");
                return;
            }
        }

        run("smoke", HostTaskGraphTest::smoke);
        if (args.length != 0 && args[0].equals("smoke")) {
            System.out.println("PASS: " + assertions + " assertions");
            return;
        }
        run("repeated execution and history", HostTaskGraphTest::repeatedExecution);
        run("native host arrays", HostTaskGraphTest::nativeHostArrays);
        run("snapshot task and binding isolation", HostTaskGraphTest::snapshotIsolation);
        run("multiple graph order", HostTaskGraphTest::multipleGraphOrder);
        run("kernel failures stop later tasks", HostTaskGraphTest::kernelFailures);
        run("method tasks and argument validation", HostTaskGraphTest::methodTasks);
        run("task arities zero through twenty", HostTaskGraphTest::allArities);
        run("duplicate identifiers", HostTaskGraphTest::duplicateIdentifiers);
        run("unsupported task registration", HostTaskGraphTest::unsupportedTasks);
        run("device transfer refusal", HostTaskGraphTest::refuseTransfers);
        run("device option refusal", HostTaskGraphTest::refuseDeviceOptions);
        run("profiler and lifecycle", HostTaskGraphTest::profilerAndLifecycle);
        run("timed warm-up refusal", HostTaskGraphTest::refuseTimedWarmUp);
        run("iteration warm-up caller and failure", HostTaskGraphTest::iterationWarmUp);
        run("thread metadata refusal", HostTaskGraphTest::refuseThreadMetadata);
        run("CUDA unbind refusal", HostTaskGraphTest::refuseCudaUnbind);
        run("refused mapping preserves graph selection", HostTaskGraphTest::preserveSelectionAfterRefusedMapping);
        run("rejected registration releases its identifier", HostTaskGraphTest::registrationRecovery);
        run("empty multi-graph transfer refusal", HostTaskGraphTest::refuseEmptyMultiGraphTransfer);
        run("partial transfer preserves range", HostTaskGraphTest::preserveRefusedTransferRange);
        System.out.println("PASS: " + assertions + " assertions; API-only host graphs");
    }

    private static void run(String name, TestAction action) throws Throwable {
        action.run();
        System.out.println("PASS " + name);
    }

    private static TaskGraph host(String name) throws Throwable {
        // Reflection lets the red test compile against the pristine API. This still exercises
        // the public factory and all of the real graph/plan implementation, without a stub.
        try {
            return (TaskGraph) TaskGraph.class.getMethod("host", String.class).invoke(null, name);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("TaskGraph.host(String) must support API-only CPU execution", missing);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static void smoke() throws Throwable {
        int[] left = { 1, 2, 3 };
        int[] right = { 4, 5, 6 };
        int[] output = new int[3];
        TaskGraph graph = host("host-smoke")
                .task("add", HostTaskGraphTest::add, left, right, output)
                .task("scale", HostTaskGraphTest::scale, output, 3);
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(graph.snapshot())) {
            TornadoExecutionResult result = plan.execute();
            equal(new int[] { 15, 21, 27 }, output, "dependent Java tasks run in order");
            check(result.isReady(), "synchronous result is ready");
            same(result, plan.getPlanResult(0), "normal plan result is retained");
            check(plan.getCurrentDeviceMemoryUsage() == 0, "host graphs own no device buffers");
        }
    }

    private static void repeatedExecution() throws Throwable {
        int[] source = { 4 };
        int[] output = { 0 };
        TaskGraph graph = host("repeat").task("copy", HostTaskGraphTest::copy, source, output);
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(graph.snapshot()).withPlanResultsHistory()) {
            TornadoExecutionResult first = plan.execute();
            equal(new int[] { 4 }, output, "first host input");
            source[0] = 9;
            TornadoExecutionResult second = plan.execute();
            equal(new int[] { 9 }, output, "payload contents remain shared across executions");
            check(first != second, "each execution returns its own result object");
            same(first, plan.getPlanResult(0), "first history entry");
            same(second, plan.getPlanResult(1), "second history entry");
            expect(TornadoRuntimeException.class, () -> plan.getPlanResult(2));
        }
    }

    private static void nativeHostArrays() throws Throwable {
        IntArray left = IntArray.fromArray(new int[] { 1, 2, 3 });
        IntArray right = IntArray.fromArray(new int[] { 10, 20, 30 });
        IntArray output = new IntArray(3);
        TaskGraph graph = host("native-array").task("add", HostTaskGraphTest::addNative, left, right, output);
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(graph.snapshot())) {
            plan.execute();
            equal(new int[] { 11, 22, 33 }, output.toHeapArray(), "real Tornado native arrays use host memory");
        }
        output.set(0, 99);
        equal(new int[] { 99, 22, 33 }, output.toHeapArray(), "closing a plan does not close caller-owned memory");
    }

    private static void snapshotIsolation() throws Throwable {
        int[] original = { 2 };
        int[] replacement = { 50 };
        int[] output = { 0 };
        Method copy = HostTaskGraphTest.class.getMethod("copy", int[].class, int[].class);
        Object[] bindings = { original, output };
        TaskGraph graph = host("snapshot").task("copy", copy, bindings);
        ImmutableTaskGraph first = graph.snapshot();
        bindings[0] = replacement;
        graph.task("scale", HostTaskGraphTest::scale, output, 10);
        original[0] = 7;
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(first)) {
            plan.execute();
            equal(new int[] { 7 }, output, "snapshot excludes later tasks and freezes argument bindings");
        }
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(graph.snapshot())) {
            plan.execute();
            equal(new int[] { 70 }, output, "new snapshot includes appended task");
        }

        int[] firstTarget = { 0 };
        int[] secondTarget = { 0 };
        TaskPackage task = TaskPackage.createPackage("inc", (Task1<int[]>) HostTaskGraphTest::increment, firstTarget);
        TaskGraph explicit = host("package-snapshot").addTask(task);
        ImmutableTaskGraph frozen = explicit.snapshot();
        task.getTaskParameters()[1] = secondTarget;
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(frozen)) {
            plan.execute();
        }
        equal(new int[] { 1 }, firstTarget, "TaskPackage binding remains frozen");
        equal(new int[] { 0 }, secondTarget, "mutating TaskPackage cannot retarget a snapshot");
    }

    private static void multipleGraphOrder() throws Throwable {
        int[] value = { 3 };
        TaskGraph first = host("first").task("scale", HostTaskGraphTest::scale, value, 4);
        TaskGraph second = host("second").task("increment", HostTaskGraphTest::increment, value);
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(first.snapshot(), second.snapshot())) {
            plan.execute();
            equal(new int[] { 13 }, value, "plans preserve graph order and shared host data");
            plan.execute();
            equal(new int[] { 53 }, value, "multi-graph state is visible on repeated execution");
        }
    }

    private static void kernelFailures() throws Throwable {
        int[] calls = { 0 };
        TaskGraph graph = host("failure")
                .task("first", HostTaskGraphTest::increment, calls)
                .task("fail", HostTaskGraphTest::throwRuntime)
                .task("last", HostTaskGraphTest::increment, calls);
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(graph.snapshot())) {
            same(KERNEL_FAILURE, expect(RuntimeException.class, plan::execute), "runtime failure is not wrapped");
            equal(new int[] { 1 }, calls, "failure prevents later tasks from executing");
            expect(TornadoRuntimeException.class, () -> plan.getPlanResult(0));
        }
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(
                host("error").task("error", HostTaskGraphTest::throwError).snapshot())) {
            same(KERNEL_ERROR, expect(Error.class, plan::execute), "error identity is preserved");
        }
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(
                host("method-failure").task("fail", HostTaskGraphTest.class.getMethod("throwRuntime")).snapshot())) {
            same(KERNEL_FAILURE, expect(RuntimeException.class, plan::execute), "reflection unwraps the kernel exception");
        }
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(
                host("checked-failure").task("fail", HostTaskGraphTest.class.getMethod("throwChecked")).snapshot())) {
            same(CHECKED_FAILURE, expect(IOException.class, plan::execute), "checked reflection failures retain identity");
        }
    }

    private static void methodTasks() throws Throwable {
        int[] output = { 0 };
        Method widened = HostTaskGraphTest.class.getMethod("widened", long.class, int[].class);
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(
                host("method").task("widen", widened, Integer.valueOf(12), output).snapshot())) {
            plan.execute();
        }
        equal(new int[] { 12 }, output, "reflection supports Java primitive widening");
        expect(IllegalArgumentException.class, () -> host("method-type").task("bad", widened, "wrong", output));
        expect(IllegalArgumentException.class, () -> host("method-arity").task("bad", widened, Integer.valueOf(1)));
        expect(IllegalArgumentException.class, () -> host("method-null").task("bad", widened, null, output));
        expect(IllegalArgumentException.class,
                () -> host("method-access").task("bad", HostTaskGraphTest.class.getDeclaredMethod("hidden")));
        expect(TornadoTaskRuntimeException.class,
                () -> host("method-instance").task("bad", HostTaskGraphTest.class.getMethod("instanceKernel")));
    }

    private static void allArities() throws Throwable {
        int[] output = new int[21];
        TaskGraph graph = host("arities");
        graph.task("arity0", () -> output[0] = 1);
        graph.task("arity1", (int[] target) -> target[1] = 1, output);
        graph.task("arity2", (Integer a1, int[] target) -> target[2] = a1, 1, output);
        graph.task("arity3", (Integer a1, Integer a2, int[] target) -> target[3] = a1 + a2, 1, 2, output);
        graph.task("arity4", (Integer a1, Integer a2, Integer a3, int[] target) -> target[4] = a1 + a2 + a3, 1, 2, 3, output);
        graph.task("arity5", (Integer a1, Integer a2, Integer a3, Integer a4, int[] target) -> target[5] = a1 + a2 + a3 + a4, 1, 2, 3, 4, output);
        graph.task("arity6", (Integer a1, Integer a2, Integer a3, Integer a4, Integer a5, int[] target) -> target[6] = a1 + a2 + a3 + a4 + a5, 1, 2, 3, 4, 5, output);
        graph.task("arity7", (Integer a1, Integer a2, Integer a3, Integer a4, Integer a5, Integer a6, int[] target) -> target[7] = a1 + a2 + a3 + a4 + a5 + a6, 1, 2, 3, 4, 5, 6, output);
        graph.task("arity8", (Integer a1, Integer a2, Integer a3, Integer a4, Integer a5, Integer a6, Integer a7, int[] target) -> target[8] = a1 + a2 + a3 + a4 + a5 + a6 + a7, 1, 2, 3, 4, 5, 6, 7, output);
        graph.task("arity9", (Integer a1, Integer a2, Integer a3, Integer a4, Integer a5, Integer a6, Integer a7, Integer a8, int[] target) -> target[9] = a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8, 1, 2, 3, 4, 5, 6, 7, 8, output);
        graph.task("arity10", (Integer a1, Integer a2, Integer a3, Integer a4, Integer a5, Integer a6, Integer a7, Integer a8, Integer a9, int[] target) -> target[10] = a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9, 1, 2, 3, 4, 5, 6, 7, 8, 9, output);
        graph.task("arity11", (Integer a1, Integer a2, Integer a3, Integer a4, Integer a5, Integer a6, Integer a7, Integer a8, Integer a9, Integer a10, int[] target) -> target[11] = a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9 + a10, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, output);
        graph.task("arity12", (Integer a1, Integer a2, Integer a3, Integer a4, Integer a5, Integer a6, Integer a7, Integer a8, Integer a9, Integer a10, Integer a11, int[] target) -> target[12] = a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9 + a10 + a11, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, output);
        graph.task("arity13", (Integer a1, Integer a2, Integer a3, Integer a4, Integer a5, Integer a6, Integer a7, Integer a8, Integer a9, Integer a10, Integer a11, Integer a12, int[] target) -> target[13] = a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9 + a10 + a11 + a12, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, output);
        graph.task("arity14", (Integer a1, Integer a2, Integer a3, Integer a4, Integer a5, Integer a6, Integer a7, Integer a8, Integer a9, Integer a10, Integer a11, Integer a12, Integer a13, int[] target) -> target[14] = a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9 + a10 + a11 + a12 + a13, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, output);
        graph.task("arity15", (Integer a1, Integer a2, Integer a3, Integer a4, Integer a5, Integer a6, Integer a7, Integer a8, Integer a9, Integer a10, Integer a11, Integer a12, Integer a13, Integer a14, int[] target) -> target[15] = a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9 + a10 + a11 + a12 + a13 + a14, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, output);
        graph.task("arity16", (Integer a1, Integer a2, Integer a3, Integer a4, Integer a5, Integer a6, Integer a7, Integer a8, Integer a9, Integer a10, Integer a11, Integer a12, Integer a13, Integer a14, Integer a15, int[] target) -> target[16] = a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9 + a10 + a11 + a12 + a13 + a14 + a15, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, output);
        graph.task("arity17", (Integer a1, Integer a2, Integer a3, Integer a4, Integer a5, Integer a6, Integer a7, Integer a8, Integer a9, Integer a10, Integer a11, Integer a12, Integer a13, Integer a14, Integer a15, Integer a16, int[] target) -> target[17] = a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9 + a10 + a11 + a12 + a13 + a14 + a15 + a16, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, output);
        graph.task("arity18", (Integer a1, Integer a2, Integer a3, Integer a4, Integer a5, Integer a6, Integer a7, Integer a8, Integer a9, Integer a10, Integer a11, Integer a12, Integer a13, Integer a14, Integer a15, Integer a16, Integer a17, int[] target) -> target[18] = a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9 + a10 + a11 + a12 + a13 + a14 + a15 + a16 + a17, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, output);
        graph.task("arity19", (Integer a1, Integer a2, Integer a3, Integer a4, Integer a5, Integer a6, Integer a7, Integer a8, Integer a9, Integer a10, Integer a11, Integer a12, Integer a13, Integer a14, Integer a15, Integer a16, Integer a17, Integer a18, int[] target) -> target[19] = a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9 + a10 + a11 + a12 + a13 + a14 + a15 + a16 + a17 + a18, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, output);
        graph.task("arity20", (Integer a1, Integer a2, Integer a3, Integer a4, Integer a5, Integer a6, Integer a7, Integer a8, Integer a9, Integer a10, Integer a11, Integer a12, Integer a13, Integer a14, Integer a15, Integer a16, Integer a17, Integer a18, Integer a19, int[] target) -> target[20] = a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9 + a10 + a11 + a12 + a13 + a14 + a15 + a16 + a17 + a18 + a19, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, output);
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(graph.snapshot())) {
            plan.execute();
        }
        equal(new int[] { 1, 1, 1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 66, 78, 91, 105, 120, 136, 153, 171, 190 },
                output, "every supported task arity dispatches the right arguments");
    }

    private static void duplicateIdentifiers() throws Throwable {
        int[] calls = { 0 };
        TaskGraph graph = host("duplicates").task("same", HostTaskGraphTest::increment, calls);
        expect(TornadoTaskRuntimeException.class, () -> graph.task("same", HostTaskGraphTest::increment, calls));
        TaskGraph packages = host("package-duplicates");
        packages.addTask(TaskPackage.createPackage("same", (Task1<int[]>) HostTaskGraphTest::increment, calls));
        expect(TornadoTaskRuntimeException.class,
                () -> packages.addTask(TaskPackage.createPackage("same", (Task1<int[]>) HostTaskGraphTest::increment, calls)));
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(packages.snapshot())) {
            plan.execute();
        }
        equal(new int[] { 1 }, calls, "rejected duplicate is not appended");
    }

    private static void unsupportedTasks() throws Throwable {
        int[] effects = { 0 };
        TaskGraph graph = host("unsupported").task("ordinary", HostTaskGraphTest::increment, effects);
        expect(UnsupportedOperationException.class,
                () -> graph.task("kernel-context", (KernelContext context) -> effects[0]++, new KernelContext()));
        expect(UnsupportedOperationException.class,
                () -> graph.task("tile-context", (TileContext context) -> effects[0]++, new TileContext()));
        expect(UnsupportedOperationException.class,
                () -> graph.task("typed-null-context", HostTaskGraphTest.class.getMethod("contextKernel", KernelContext.class), (Object) null));
        expect(UnsupportedOperationException.class,
                () -> graph.task("device-view", values -> effects[0]++, IntArray.cudaDeviceView(3)));
        expect(UnsupportedOperationException.class,
                () -> graph.prebuiltTask("prebuilt", "kernel", "missing.cl", new AccessorParameters(0)));
        expect(UnsupportedOperationException.class,
                () -> graph.addTask(TaskPackage.createPrebuiltTask("prebuilt-package", "kernel", "missing.cl", new AccessorParameters(0))));
        expect(UnsupportedOperationException.class, () -> graph.libraryTask("library", value -> {
            effects[0]++;
            throw new AssertionError("native descriptor factory must not be invoked");
        }, effects));
        equal(new int[] { 0 }, effects, "unsupported registration runs no user code");
    }

    private static void refuseTransfers() throws Throwable {
        int[] output = { 0 };
        TaskGraph graph = host("transfers").task("increment", HostTaskGraphTest::increment, output);
        for (int mode : new int[] { DataTransferMode.FIRST_EXECUTION, DataTransferMode.EVERY_EXECUTION, DataTransferMode.UNDER_DEMAND }) {
            expect(UnsupportedOperationException.class, () -> graph.transferToDevice(mode, output));
            expect(UnsupportedOperationException.class, () -> graph.transferToHost(mode, output));
        }
        expect(UnsupportedOperationException.class, () -> graph.consumeFromDevice("producer", output));
        expect(UnsupportedOperationException.class, () -> graph.consumeFromUnifiedMemory(output));
        expect(UnsupportedOperationException.class, () -> graph.persistOnDevice(output));
        equal(new int[] { 0 }, output, "refused transfers do not execute work");
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(graph.snapshot())) {
            expect(UnsupportedOperationException.class, plan::transferToDevice);
            TornadoExecutionResult result = plan.execute();
            expect(UnsupportedOperationException.class, () -> result.transferToHost(output));
            equal(new int[] { 1 }, output, "host result is already directly visible");
        }
    }

    private static void refuseDeviceOptions() throws Throwable {
        int[] output = { 0 };
        TaskGraph graph = host("options").task("increment", HostTaskGraphTest::increment, output);
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(graph.snapshot())) {
            expect(UnsupportedOperationException.class, plan::withCUDAGraph);
            expect(UnsupportedOperationException.class, plan::withConcurrentDevices);
            expect(UnsupportedOperationException.class, () -> plan.withGridScheduler(new GridScheduler()));
            expect(UnsupportedOperationException.class, () -> plan.withDevice(null));
            expect(UnsupportedOperationException.class, () -> plan.withMemoryLimit("1GB"));
            equal(new int[] { 0 }, output, "unsupported options fail before execution");
        }
    }

    private static void profilerAndLifecycle() throws Throwable {
        int[] output = new int[100_000];
        TaskGraph graph = host("profile").task("fill", values -> {
            for (int i = 0; i < values.length; i++) {
                values[i] = i;
            }
        }, output);
        TornadoExecutionPlan plan = new TornadoExecutionPlan(graph.snapshot()).withProfiler(ProfilerMode.SILENT);
        try {
            TornadoExecutionResult result = plan.execute();
            check(output[99_999] == 99_999, "profiled kernel ran");
            check(result.getProfilerResult().getTotalTime() > 0, "host elapsed time is reported");
            check(result.getProfilerResult().getDeviceKernelTime() == 0, "host time is not labelled device kernel time");
            check(result.getProfilerResult().getTotalBytesCopyIn() == 0, "no upload bytes");
            check(result.getProfilerResult().getTotalBytesCopyOut() == 0, "no download bytes");
            check(result.getProfilerResult().getTotalDeviceMemoryUsage() == 0, "no device memory");
            check(result.getProfilerResult().getCompileTime() == 0, "host path does not report device compilation");
            plan.freeDeviceMemory();
            plan.execute();
            plan.close();
            plan.close();
        } finally {
            plan.close();
        }
    }

    private static void refuseTimedWarmUp() throws Throwable {
        Thread[] observedThread = { null };
        int[] laterCalls = { 0 };
        TaskGraph graph = host("timed-warmup").task("fail", () -> {
            observedThread[0] = Thread.currentThread();
            throw KERNEL_FAILURE;
        }).task("later", HostTaskGraphTest::increment, laterCalls);
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(graph.snapshot())) {
            expect(UnsupportedOperationException.class, () -> plan.withWarmUpTime(25));
            check(observedThread[0] == null, "refused timed warm-up starts no task on any thread");
            equal(new int[] { 0 }, laterCalls, "refused timed warm-up performs no work");
        }
    }

    private static void iterationWarmUp() throws Throwable {
        Thread caller = Thread.currentThread();
        Thread[] observedThread = { null };
        int[] laterCalls = { 0 };
        TaskGraph graph = host("iteration-warmup").task("fail", () -> {
            observedThread[0] = Thread.currentThread();
            throw KERNEL_FAILURE;
        }).task("later", HostTaskGraphTest::increment, laterCalls);
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(graph.snapshot())) {
            same(KERNEL_FAILURE, expect(RuntimeException.class, () -> plan.withWarmUpIterations(2)), "iteration warm-up propagates the kernel failure");
            same(caller, observedThread[0], "iteration warm-up uses the caller thread");
            equal(new int[] { 0 }, laterCalls, "failure stops iteration warm-up's later work");
        }
    }

    private static void refuseThreadMetadata() throws Throwable {
        int[] calls = { 0 };
        TaskPackage packageWithThreads = TaskPackage.createPackage("threaded", (Task1<int[]>) HostTaskGraphTest::increment, calls);
        packageWithThreads.setNumThreadsToRun(8);
        TaskGraph graph = host("thread-metadata");
        expect(UnsupportedOperationException.class, () -> graph.addTask(packageWithThreads));
        graph.task("ordinary", HostTaskGraphTest::increment, calls);
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(graph.snapshot())) {
            plan.execute();
        }
        equal(new int[] { 1 }, calls, "rejected device thread metadata does not register work");
    }

    private static void refuseCudaUnbind() throws Throwable {
        int[] output = { 0 };
        IntArray view = IntArray.cudaDeviceView(3);
        view.cudaDeviceViewBinding().setLogicalLength(2);
        TaskGraph graph = host("cuda-unbind").task("ordinary", HostTaskGraphTest::increment, output);
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(graph.snapshot())) {
            expect(UnsupportedOperationException.class, () -> plan.unbindCUDADeviceBuffer(view));
            check(view.cudaDeviceViewBinding().logicalLength() == 2, "refused unbind does not alter device-view metadata");
            equal(new int[] { 0 }, output, "refused unbind performs no host work");
        }
    }

    private static void preserveSelectionAfterRefusedMapping() throws Throwable {
        int[] excluded = { 0 };
        int[] selected = { 0 };
        ImmutableTaskGraph first = host("mapping-first").task("inc", HostTaskGraphTest::increment, excluded).snapshot();
        ImmutableTaskGraph second = host("mapping-second").task("inc", HostTaskGraphTest::increment, selected).snapshot();
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(first, second).withGraph(1)) {
            expect(UnsupportedOperationException.class, () -> plan.mapOnDeviceMemoryRegion(selected, excluded, 0, 0, 1));
            plan.execute();
            equal(new int[] { 0 }, excluded, "rejected mapping leaves excluded graph unselected");
            equal(new int[] { 1 }, selected, "rejected mapping preserves selected graph");
        }
    }

    private static void registrationRecovery() throws Throwable {
        int[] calls = { 0 };
        TaskGraph graph = host("registration-recovery");
        expect(UnsupportedOperationException.class,
                () -> graph.task("context", (KernelContext context) -> calls[0]++, new KernelContext()));
        graph.task("context", HostTaskGraphTest::increment, calls);
        expect(UnsupportedOperationException.class,
                () -> graph.prebuiltTask("prebuilt", "kernel", "missing.cl", new AccessorParameters(0)));
        graph.task("prebuilt", HostTaskGraphTest::increment, calls);
        expect(IllegalArgumentException.class,
                () -> graph.task("method", HostTaskGraphTest.class.getMethod("widened", long.class, int[].class), "wrong", calls));
        graph.task("method", HostTaskGraphTest::increment, calls);
        expect(UnsupportedOperationException.class, () -> graph.libraryTask("library", value -> {
            throw new AssertionError("native factory must not execute");
        }, calls));
        graph.task("library", HostTaskGraphTest::increment, calls);
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(graph.snapshot())) {
            plan.execute();
        }
        equal(new int[] { 4 }, calls, "failed registration preserves names for valid replacements");
    }

    private static void refuseEmptyMultiGraphTransfer() throws Throwable {
        int[] values = { 0 };
        ImmutableTaskGraph first = host("empty-copy-first").task("inc", HostTaskGraphTest::increment, values).snapshot();
        ImmutableTaskGraph second = host("empty-copy-second").task("inc", HostTaskGraphTest::increment, values).snapshot();
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(first, second)) {
            TornadoExecutionResult result = plan.execute();
            expect(UnsupportedOperationException.class, result::transferToHost);
            equal(new int[] { 2 }, values, "refused empty transfer executes no extra work");
        }
    }

    private static void preserveRefusedTransferRange() throws Throwable {
        IntArray values = new IntArray(3);
        DataRange range = new DataRange(values).withOffset(1).withSize(1);
        expect(TornadoRuntimeException.class, range::getOffset);
        TaskGraph graph = host("partial-copy").task("fill", array -> array.set(0, 7), values);
        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(graph.snapshot())) {
            TornadoExecutionResult result = plan.execute();
            expect(UnsupportedOperationException.class, () -> result.transferToHost(range));
            expect(TornadoRuntimeException.class, range::getOffset);
            check(values.get(0) == 7, "refused partial transfer leaves host output intact");
        }
    }

    public static void add(int[] a, int[] b, int[] output) {
        for (@Parallel int i = 0; i < output.length; i++) {
            output[i] = a[i] + b[i];
        }
    }

    public static void scale(int[] values, int scale) {
        for (@Parallel int i = 0; i < values.length; i++) {
            values[i] *= scale;
        }
    }

    public static void addNative(IntArray left, IntArray right, IntArray output) {
        for (@Parallel int i = 0; i < output.getSize(); i++) {
            output.set(i, left.get(i) + right.get(i));
        }
    }

    public static void copy(int[] input, int[] output) {
        output[0] = input[0];
    }

    public static void increment(int[] output) {
        output[0]++;
    }

    public static void widened(long value, int[] output) {
        output[0] = (int) value;
    }

    public static void throwRuntime() {
        throw KERNEL_FAILURE;
    }

    public static void throwError() {
        throw KERNEL_ERROR;
    }

    public static void throwChecked() throws IOException {
        throw CHECKED_FAILURE;
    }

    public static void contextKernel(KernelContext context) {
        throw new AssertionError("device-context task must be rejected");
    }

    private static void hidden() {
        throw new AssertionError("private method must be rejected");
    }

    public void instanceKernel() {
        throw new AssertionError("instance method must be rejected");
    }

    private static void equal(int[] expected, int[] actual, String reason) {
        check(Arrays.equals(expected, actual), reason + ": expected " + Arrays.toString(expected) + ", actual " + Arrays.toString(actual));
    }

    private static void same(Object expected, Object actual, String reason) {
        check(expected == actual, reason);
    }

    private static void check(boolean condition, String reason) {
        assertions++;
        if (!condition) {
            throw new AssertionError(reason);
        }
    }

    private static <T extends Throwable> T expect(Class<T> type, TestAction action) throws Throwable {
        assertions++;
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) {
                return type.cast(failure);
            }
            throw new AssertionError("expected " + type.getName() + ", received " + failure, failure);
        }
        throw new AssertionError("expected " + type.getName());
    }

    /** A real forbidden loader: loading this class would fail before any host task runs. */
    public static final class FailingRuntime {
        static {
            if (System.getProperty("java.version") != null) {
                throw new AssertionError("host graph initialized a runtime loader");
            }
        }
    }
}
