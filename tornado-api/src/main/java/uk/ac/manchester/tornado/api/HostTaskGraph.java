/*
 * Copyright (c) 2026, Hitesh Soliwal and contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package uk.ac.manchester.tornado.api;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import uk.ac.manchester.tornado.api.common.LibraryTaskDescriptor;
import uk.ac.manchester.tornado.api.common.SchedulableTask;
import uk.ac.manchester.tornado.api.common.TaskPackage;
import uk.ac.manchester.tornado.api.common.TornadoDevice;
import uk.ac.manchester.tornado.api.common.TornadoFunctions;
import uk.ac.manchester.tornado.api.enums.ProfilerMode;
import uk.ac.manchester.tornado.api.enums.TornadoVMBackendType;
import uk.ac.manchester.tornado.api.exceptions.TornadoTaskRuntimeException;
import uk.ac.manchester.tornado.api.runtime.ExecutorFrame;
import uk.ac.manchester.tornado.api.runtime.TaskContextInterface;
import uk.ac.manchester.tornado.api.tile.TileContext;
import uk.ac.manchester.tornado.api.types.arrays.TornadoNativeArray;

/**
 * Ordered Java execution for {@link TaskGraph#host(String)}. This implementation
 * owns task bindings, not payload storage. It has no runtime or driver dependency.
 * Device-specific operations are refused instead of being silently approximated.
 */
final class HostTaskGraph implements TornadoTaskGraphInterface {
    private final String name;
    private final boolean mutable;
    private final List<HostTask> tasks = new ArrayList<>();
    private final Set<String> taskNames = new HashSet<>();
    private final Set<Object> arguments = Collections.newSetFromMap(new IdentityHashMap<>());
    private volatile boolean finished;
    private volatile long totalTime;
    private ProfilerMode profilerMode;

    HostTaskGraph(String name) {
        this(name, true);
    }

    private HostTaskGraph(String name, boolean mutable) {
        this.name = requireName(name);
        this.mutable = mutable;
    }

    static UnsupportedOperationException unsupported(String operation) {
        return new UnsupportedOperationException(operation + " is unavailable for a host task graph; "
                + "host tasks use ordinary Java memory and execution");
    }

    private static String requireName(String value) {
        Objects.requireNonNull(value, "name");
        if (value.isBlank()) {
            throw new IllegalArgumentException("host graph and task names must not be blank");
        }
        return value;
    }

    /** Private bindings never escape; the referenced payload objects remain caller-owned. */
    private record HostTask(String id, int arity, Method method, Object[] parameters) {
    }

    private static HostTask capture(TaskPackage task) {
        Objects.requireNonNull(task, "task");
        if (task.isPrebuiltTask()) {
            throw unsupported("prebuilt kernels");
        }
        if (task.getNumThreadsToRun() != 0) {
            throw unsupported("device thread counts");
        }
        String id = requireName(task.getId());
        Object[] parameters = Objects.requireNonNull(task.getTaskParameters(), "task parameters").clone();
        int arity = task.getTaskType();
        Method method = task.getMethod();
        int firstArgument;
        if (task.isMethodTask()) {
            validateMethod(Objects.requireNonNull(method, "method"), parameters);
            firstArgument = 0;
        } else {
            if (arity < 0 || arity > 20 || parameters.length != arity + 1
                    || !hasTaskType(arity, parameters[0])) {
                throw new IllegalArgumentException("invalid Java task representation: " + id);
            }
            if (method != null) {
                throw new IllegalArgumentException("a Java function task must not also specify a Method");
            }
            firstArgument = 1;
        }
        for (int i = firstArgument; i < parameters.length; i++) {
            if (parameters[i] instanceof KernelContext || parameters[i] instanceof TileContext) {
                throw unsupported("device-context arguments");
            }
            if (parameters[i] instanceof TornadoNativeArray array && array.isCUDADeviceView()) {
                throw unsupported("device-only array views");
            }
        }
        return new HostTask(id, arity, method, parameters);
    }

    private static void validateMethod(Method method, Object[] parameters) {
        if (!Modifier.isStatic(method.getModifiers()) || !Modifier.isPublic(method.getModifiers())
                || !Modifier.isPublic(method.getDeclaringClass().getModifiers()) || !method.canAccess(null)) {
            throw new IllegalArgumentException("host method tasks require an accessible public static method: " + method);
        }
        Class<?>[] types = method.getParameterTypes();
        if (types.length != parameters.length) {
            throw new IllegalArgumentException("wrong number of arguments for host method: " + method);
        }
        for (int i = 0; i < types.length; i++) {
            if (KernelContext.class.isAssignableFrom(types[i]) || TileContext.class.isAssignableFrom(types[i])) {
                throw unsupported("device-context arguments");
            }
            if (!accepts(types[i], parameters[i])) {
                throw new IllegalArgumentException("invalid argument " + i + " for host method: " + method);
            }
        }
    }

    /** The unboxing and widening conversions accepted by Method.invoke. */
    private static boolean accepts(Class<?> type, Object value) {
        if (!type.isPrimitive()) {
            return value == null || type.isInstance(value);
        }
        if (type == boolean.class) {
            return value instanceof Boolean;
        }
        if (type == char.class) {
            return value instanceof Character;
        }
        if (type == byte.class) {
            return value instanceof Byte;
        }
        if (type == short.class) {
            return value instanceof Byte || value instanceof Short;
        }
        boolean integral = value instanceof Byte || value instanceof Short || value instanceof Character || value instanceof Integer;
        if (type == int.class) {
            return integral;
        }
        if (type == long.class) {
            return integral || value instanceof Long;
        }
        if (type == float.class) {
            return integral || value instanceof Long || value instanceof Float;
        }
        return type == double.class && (integral || value instanceof Long || value instanceof Float || value instanceof Double);
    }

    private static boolean hasTaskType(int arity, Object code) {
        return switch (arity) {
            case 0 -> code instanceof TornadoFunctions.Task;
            case 1 -> code instanceof TornadoFunctions.Task1<?>;
            case 2 -> code instanceof TornadoFunctions.Task2<?, ?>;
            case 3 -> code instanceof TornadoFunctions.Task3<?, ?, ?>;
            case 4 -> code instanceof TornadoFunctions.Task4<?, ?, ?, ?>;
            case 5 -> code instanceof TornadoFunctions.Task5<?, ?, ?, ?, ?>;
            case 6 -> code instanceof TornadoFunctions.Task6<?, ?, ?, ?, ?, ?>;
            case 7 -> code instanceof TornadoFunctions.Task7<?, ?, ?, ?, ?, ?, ?>;
            case 8 -> code instanceof TornadoFunctions.Task8<?, ?, ?, ?, ?, ?, ?, ?>;
            case 9 -> code instanceof TornadoFunctions.Task9<?, ?, ?, ?, ?, ?, ?, ?, ?>;
            case 10 -> code instanceof TornadoFunctions.Task10<?, ?, ?, ?, ?, ?, ?, ?, ?, ?>;
            case 11 -> code instanceof TornadoFunctions.Task11<?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?>;
            case 12 -> code instanceof TornadoFunctions.Task12<?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?>;
            case 13 -> code instanceof TornadoFunctions.Task13<?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?>;
            case 14 -> code instanceof TornadoFunctions.Task14<?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?>;
            case 15 -> code instanceof TornadoFunctions.Task15<?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?>;
            case 16 -> code instanceof TornadoFunctions.Task16<?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?>;
            case 17 -> code instanceof TornadoFunctions.Task17<?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?>;
            case 18 -> code instanceof TornadoFunctions.Task18<?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?>;
            case 19 -> code instanceof TornadoFunctions.Task19<?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?>;
            case 20 -> code instanceof TornadoFunctions.Task20<?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?>;
            default -> false;
        };
    }

    @SuppressWarnings("unchecked")
    private static void invoke(HostTask task) {
        Object[] parameters = task.parameters();
        if (task.method() != null) {
            try {
                task.method().invoke(null, parameters);
            } catch (InvocationTargetException failure) {
                HostTaskGraph.<RuntimeException>rethrow(failure.getCause());
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("host method is no longer accessible: " + task.method(), failure);
            }
            return;
        }
        switch (task.arity()) {
            case 0 -> ((TornadoFunctions.Task) parameters[0]).apply();
            case 1 -> ((TornadoFunctions.Task1<Object>) parameters[0]).apply(parameters[1]);
            case 2 -> ((TornadoFunctions.Task2<Object, Object>) parameters[0]).apply(parameters[1], parameters[2]);
            case 3 -> ((TornadoFunctions.Task3<Object, Object, Object>) parameters[0]).apply(parameters[1], parameters[2], parameters[3]);
            case 4 -> ((TornadoFunctions.Task4<Object, Object, Object, Object>) parameters[0]).apply(parameters[1], parameters[2], parameters[3], parameters[4]);
            case 5 -> ((TornadoFunctions.Task5<Object, Object, Object, Object, Object>) parameters[0]).apply(parameters[1], parameters[2], parameters[3], parameters[4], parameters[5]);
            case 6 -> ((TornadoFunctions.Task6<Object, Object, Object, Object, Object, Object>) parameters[0]).apply(parameters[1], parameters[2], parameters[3], parameters[4], parameters[5], parameters[6]);
            case 7 -> ((TornadoFunctions.Task7<Object, Object, Object, Object, Object, Object, Object>) parameters[0]).apply(parameters[1], parameters[2], parameters[3], parameters[4], parameters[5], parameters[6], parameters[7]);
            case 8 -> ((TornadoFunctions.Task8<Object, Object, Object, Object, Object, Object, Object, Object>) parameters[0]).apply(parameters[1], parameters[2], parameters[3], parameters[4], parameters[5], parameters[6], parameters[7], parameters[8]);
            case 9 -> ((TornadoFunctions.Task9<Object, Object, Object, Object, Object, Object, Object, Object, Object>) parameters[0]).apply(parameters[1], parameters[2], parameters[3], parameters[4], parameters[5], parameters[6], parameters[7], parameters[8], parameters[9]);
            case 10 -> ((TornadoFunctions.Task10<Object, Object, Object, Object, Object, Object, Object, Object, Object, Object>) parameters[0]).apply(parameters[1], parameters[2], parameters[3], parameters[4], parameters[5], parameters[6], parameters[7], parameters[8], parameters[9], parameters[10]);
            case 11 -> ((TornadoFunctions.Task11<Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object>) parameters[0]).apply(parameters[1], parameters[2], parameters[3], parameters[4], parameters[5], parameters[6], parameters[7], parameters[8], parameters[9], parameters[10], parameters[11]);
            case 12 -> ((TornadoFunctions.Task12<Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object>) parameters[0]).apply(parameters[1], parameters[2], parameters[3], parameters[4], parameters[5], parameters[6], parameters[7], parameters[8], parameters[9], parameters[10], parameters[11], parameters[12]);
            case 13 -> ((TornadoFunctions.Task13<Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object>) parameters[0]).apply(parameters[1], parameters[2], parameters[3], parameters[4], parameters[5], parameters[6], parameters[7], parameters[8], parameters[9], parameters[10], parameters[11], parameters[12], parameters[13]);
            case 14 -> ((TornadoFunctions.Task14<Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object>) parameters[0]).apply(parameters[1], parameters[2], parameters[3], parameters[4], parameters[5], parameters[6], parameters[7], parameters[8], parameters[9], parameters[10], parameters[11], parameters[12], parameters[13], parameters[14]);
            case 15 -> ((TornadoFunctions.Task15<Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object>) parameters[0]).apply(parameters[1], parameters[2], parameters[3], parameters[4], parameters[5], parameters[6], parameters[7], parameters[8], parameters[9], parameters[10], parameters[11], parameters[12], parameters[13], parameters[14], parameters[15]);
            case 16 -> ((TornadoFunctions.Task16<Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object>) parameters[0]).apply(parameters[1], parameters[2], parameters[3], parameters[4], parameters[5], parameters[6], parameters[7], parameters[8], parameters[9], parameters[10], parameters[11], parameters[12], parameters[13], parameters[14], parameters[15], parameters[16]);
            case 17 -> ((TornadoFunctions.Task17<Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object>) parameters[0]).apply(parameters[1], parameters[2], parameters[3], parameters[4], parameters[5], parameters[6], parameters[7], parameters[8], parameters[9], parameters[10], parameters[11], parameters[12], parameters[13], parameters[14], parameters[15], parameters[16], parameters[17]);
            case 18 -> ((TornadoFunctions.Task18<Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object>) parameters[0]).apply(parameters[1], parameters[2], parameters[3], parameters[4], parameters[5], parameters[6], parameters[7], parameters[8], parameters[9], parameters[10], parameters[11], parameters[12], parameters[13], parameters[14], parameters[15], parameters[16], parameters[17], parameters[18]);
            case 19 -> ((TornadoFunctions.Task19<Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object>) parameters[0]).apply(parameters[1], parameters[2], parameters[3], parameters[4], parameters[5], parameters[6], parameters[7], parameters[8], parameters[9], parameters[10], parameters[11], parameters[12], parameters[13], parameters[14], parameters[15], parameters[16], parameters[17], parameters[18], parameters[19]);
            case 20 -> ((TornadoFunctions.Task20<Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object>) parameters[0]).apply(parameters[1], parameters[2], parameters[3], parameters[4], parameters[5], parameters[6], parameters[7], parameters[8], parameters[9], parameters[10], parameters[11], parameters[12], parameters[13], parameters[14], parameters[15], parameters[16], parameters[17], parameters[18], parameters[19], parameters[20]);
            default -> throw new IllegalStateException("invalid captured Java task arity");
        }
    }

    /** Reflection must not replace a kernel's exception, including a checked exception. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable failure) throws T {
        throw (T) failure;
    }

    @Override
    public SchedulableTask getTask(String taskNameID) {
        throw unsupported("getTask");
    }

    @Override
    public TornadoDevice getDevice() {
        throw unsupported("getDevice");
    }

    @Override
    public void setDevice(TornadoDevice device) {
        throw unsupported("setDevice");
    }

    @Override
    public void updatePersistedObjectState() {
        // Device persistence is refused at graph construction, so no state exists to update.
    }

    @Override
    public void setDevice(String taskName, TornadoDevice device) {
        throw unsupported("setDevice");
    }

    @Override
    public TornadoDevice getDeviceForTask(String id) {
        throw unsupported("getDeviceForTask");
    }

    @Override
    public void addInner(SchedulableTask task) {
        throw unsupported("addInner");
    }

    @Override
    public boolean isLastDeviceListEmpty() {
        return true;
    }

    @Override
    public void scheduleInner() {
        throw unsupported("scheduleInner");
    }

    @Override
    public void transferDataToDevice(ExecutorFrame executionPackage) {
        throw unsupported("transferDataToDevice");
    }

    @Override
    public void transferDataToDevice(ExecutorFrame executionPackage, Object... objects) {
        throw unsupported("transferDataToDevice");
    }

    @Override
    public void withBatch(String batchSize) {
        throw unsupported("withBatch");
    }

    @Override
    public void withCUDAGraph() {
        throw unsupported("withCUDAGraph");
    }

    @Override
    public void withCUDAPendingLaunchCount(int count) {
        throw unsupported("withCUDAPendingLaunchCount");
    }

    @Override
    public void withMemoryLimit(String memoryLimit) {
        throw unsupported("withMemoryLimit");
    }

    @Override
    public void withoutMemoryLimit() {
        // This device feature is always disabled for host task graphs.
    }

    @Override
    public void apply(Consumer<SchedulableTask> consumer) {
        throw unsupported("apply");
    }

    @Override
    public void mapAllToInner(TornadoDevice device) {
        throw unsupported("mapAllToInner");
    }

    @Override
    public void dumpTimes() {
        System.out.println("Host execution time (ns): " + totalTime);
    }

    @Override
    public void dumpProfiles() {
        System.out.println(getProfileLog());
    }

    @Override
    public void dumpEvents() {
        throw unsupported("dumpEvents");
    }

    @Override
    public void clearProfiles() {
        totalTime = 0;
    }

    @Override
    public void waitOn() {
        // Host execution is synchronous.
    }

    @Override
    public void transferToDevice(int mode, Object... objects) {
        throw unsupported("transferToDevice");
    }

    @Override
    public void transferToHost(int mode, Object... objects) {
        throw unsupported("transferToHost");
    }

    @Override
    public void consumeFromDevice(String uniqueTaskGraphName, Object... objects) {
        throw unsupported("consumeFromDevice");
    }

    @Override
    public void consumeFromDevice(Object... objects) {
        throw unsupported("consumeFromDevice");
    }

    @Override
    public void consumeFromUnifiedMemory(Object... objects) {
        throw unsupported("consumeFromUnifiedMemory");
    }

    @Override
    public void dump() {
        System.out.println("Host task graph " + name + ": " + tasks.stream().map(HostTask::id).toList());
    }

    @Override
    public void withPreCompilation(ExecutorFrame executionPackage) {
        throw unsupported("withPreCompilation");
    }

    @Override
    public void freeDeviceMemory() {
        // The host graph owns no device allocation and never closes caller-owned payloads.
    }

    @Override
    public void syncRuntimeTransferToHost(Object... objects) {
        throw unsupported("syncRuntimeTransferToHost");
    }

    @Override
    public void syncRuntimeTransferToHost(Object objects, long offset, long partialCopySize) {
        throw unsupported("syncRuntimeTransferToHost");
    }

    @Override
    public String getId() {
        return name;
    }

    @Override
    public TaskContextInterface meta() {
        throw unsupported("meta");
    }

    @Override
    public synchronized TornadoTaskGraphInterface execute(ExecutorFrame executionPackage) {
        Objects.requireNonNull(executionPackage, "execution frame");
        ProfilerMode selectedProfiler = executionPackage.getProfilerMode() == null ? profilerMode : executionPackage.getProfilerMode();
        boolean profile = selectedProfiler != null;
        long start = profile ? System.nanoTime() : 0;
        finished = false;
        try {
            for (HostTask task : tasks) {
                invoke(task);
            }
            finished = true;
            return this;
        } finally {
            totalTime = profile ? System.nanoTime() - start : 0;
            if (selectedProfiler == ProfilerMode.CONSOLE) {
                dumpProfiles();
            }
        }
    }

    @Override
    public synchronized void addTask(TaskPackage taskPackage) {
        if (!mutable) {
            throw new IllegalStateException("a host task-graph snapshot cannot be mutated");
        }
        HostTask captured = capture(taskPackage);
        if (!taskNames.add(captured.id())) {
            throw new TornadoTaskRuntimeException("duplicate host task name: " + captured.id());
        }
        tasks.add(captured);
        int first = captured.method() == null ? 1 : 0;
        for (int i = first; i < captured.parameters().length; i++) {
            arguments.add(captured.parameters()[i]);
        }
    }

    @Override
    public void addPrebuiltTask(TaskPackage taskPackage) {
        throw unsupported("addPrebuiltTask");
    }

    @Override
    public void addLibraryTask(String id, LibraryTaskDescriptor libraryTaskDescriptor) {
        throw unsupported("addLibraryTask");
    }

    @Override
    public String getTaskGraphName() {
        return name;
    }

    @Override
    public void useDefaultThreadScheduler(boolean use) {
        throw unsupported("useDefaultThreadScheduler");
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public Set<Object> getArgumentsLookup() {
        return Collections.unmodifiableSet(arguments);
    }

    @Override
    public Collection<?> getOutputs() {
        return List.of();
    }

    @Override
    public synchronized TornadoTaskGraphInterface createImmutableTaskGraph() {
        HostTaskGraph snapshot = new HostTaskGraph(name, false);
        snapshot.tasks.addAll(tasks);
        snapshot.taskNames.addAll(taskNames);
        snapshot.arguments.addAll(arguments);
        snapshot.profilerMode = profilerMode;
        return snapshot;
    }

    @Override
    public void enableProfiler(ProfilerMode profilerMode) {
        this.profilerMode = Objects.requireNonNull(profilerMode, "profiler mode");
    }

    @Override
    public void disableProfiler() {
        this.profilerMode = null;
    }

    @Override
    public void withConcurrentDevices() {
        throw unsupported("withConcurrentDevices");
    }

    @Override
    public void withoutConcurrentDevices() {
        // This device feature is always disabled for host task graphs.
    }

    @Override
    public void withThreadInfo() {
        throw unsupported("withThreadInfo");
    }

    @Override
    public void withoutThreadInfo() {
        // This device feature is always disabled for host task graphs.
    }

    @Override
    public void withPrintKernel() {
        throw unsupported("withPrintKernel");
    }

    @Override
    public void withoutPrintKernel() {
        // This device feature is always disabled for host task graphs.
    }

    @Override
    public void withGridScheduler(GridScheduler gridScheduler) {
        throw unsupported("withGridScheduler");
    }

    @Override
    public long getCurrentDeviceMemoryUsage() {
        return 0;
    }

    @Override
    public Map<String, List<Object>> getPersistedTaskToObjectsMap() {
        return Map.of();
    }

    @Override
    public void withCompilerFlags(TornadoVMBackendType backendType, String compilerFlags) {
        throw unsupported("withCompilerFlags");
    }

    @Override
    public void withStrictFloatingPoint() {
        // Java 21 always uses strict floating-point evaluation.
    }

    @Override
    public void withDynamicShapes() {
        throw unsupported("withDynamicShapes");
    }

    @Override
    public void mapOnDeviceMemoryRegion(Object destArray, Object srcArray, long offset, TornadoTaskGraphInterface taskGraphSrc) {
        throw unsupported("mapOnDeviceMemoryRegion");
    }

    @Override
    public void updateObjectAccess() {
        // Sequential tasks share ordinary Java objects; there is no device access table.
    }

    @Override
    public void setLastExecutedTaskGraph(TornadoTaskGraphInterface lastExecutedTaskGraph) {
        if (!(lastExecutedTaskGraph instanceof HostTaskGraph)) {
            throw unsupported("mixing host and device task graphs");
        }
        // Earlier host writes are already visible to the next graph on this thread.
    }

    @Override
    public void setPlanTaskGraphs(java.util.List<TornadoTaskGraphInterface> planTaskGraphs) {
        Objects.requireNonNull(planTaskGraphs, "plan task graphs");
        for (TornadoTaskGraphInterface graph : planTaskGraphs) {
            if (!(graph instanceof HostTaskGraph)) {
                throw unsupported("mixing host and device task graphs");
            }
        }
    }

    @Override
    public boolean isGridRegistered() {
        return false;
    }

    @Override
    public void withIntraPlanConcurrency() {
        throw unsupported("withIntraPlanConcurrency");
    }

    @Override
    public void withoutIntraPlanConcurrency() {
        // This device feature is always disabled for host task graphs.
    }

    @Override
    public void withStagedTransfers() {
        throw unsupported("withStagedTransfers");
    }

    @Override
    public void withoutStagedTransfers() {
        // This device feature is always disabled for host task graphs.
    }

    @Override
    public long getTotalTime() {
        return totalTime;
    }

    @Override
    public long getCompileTime() {
        return 0;
    }

    @Override
    public long getTornadoCompilerTime() {
        return 0;
    }

    @Override
    public long getDriverInstallTime() {
        return 0;
    }

    @Override
    public long getDataTransfersTime() {
        return 0;
    }

    @Override
    public long getDeviceWriteTime() {
        return 0;
    }

    @Override
    public long getDeviceReadTime() {
        return 0;
    }

    @Override
    public long getDataTransferDispatchTime() {
        return 0;
    }

    @Override
    public long getKernelDispatchTime() {
        return 0;
    }

    @Override
    public long getDeviceKernelTime() {
        return 0;
    }

    @Override
    public String getProfileLog() {
        return "{\"hostTimeNanos\":" + totalTime + "}";
    }

    @Override
    public long getTotalBytesCopyIn() {
        return 0;
    }

    @Override
    public long getTotalBytesCopyOut() {
        return 0;
    }

    @Override
    public long getTotalBytesTransferred() {
        return 0;
    }

    @Override
    public long getTotalDeviceMemoryUsage() {
        return 0;
    }

}
