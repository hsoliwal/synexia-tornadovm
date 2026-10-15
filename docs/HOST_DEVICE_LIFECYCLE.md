# Host/device execution, buffers and native-library lifecycle

Source baseline: `859491173b9940a2e4617a37fa799f828e971299`. This is a focused source review of the paths below, not proof of backend portability, thread safety, performance or passing tests. [Component map](COMPONENT_GUIDE.md) · [Coverage ledger](COMPONENT_INVENTORY.md)

## 1. Build a graph on the host

`TaskGraph` is a mutable builder. `task(...)` records a Java method and arguments; `libraryTask(...)` invokes the supplied descriptor factory on the host and records the resulting library task. Duplicate task IDs cause `TornadoTaskRuntimeException`. Transfer declarations describe graph I/O; they are not immediate device copies. `snapshot()` asks the runtime to create an immutable graph representation and wraps it in `ImmutableTaskGraph`. It does not promise a deep copy of all application array contents or automatic synchronization of later host writes.

Evidence: [TaskGraph.task, libraryTask, checkTaskName and snapshot](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/tornado-api/src/main/java/uk/ac/manchester/tornado/api/TaskGraph.java), [ImmutableTaskGraph](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/tornado-api/src/main/java/uk/ac/manchester/tornado/api/ImmutableTaskGraph.java), [runtime transfer implementation](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/tornado-runtime/src/main/java/uk/ac/manchester/tornado/runtime/tasks/TornadoTaskGraph.java).

Create `TornadoExecutionPlan` from one or more snapshots, select the intended device/grid when needed, and reuse the plan for repeated work. `execute()` delegates through `TornadoExecutor`, records a result and returns `TornadoExecutionResult`. The execution-plan object and its result list are mutable: no shared-plan thread-safety guarantee is established by this review. Distinct execution-plan IDs also key native contexts.

Evidence: [plan constructor and execute](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/tornado-api/src/main/java/uk/ac/manchester/tornado/api/TornadoExecutionPlan.java), [executor](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/tornado-api/src/main/java/uk/ac/manchester/tornado/api/TornadoExecutor.java).

## 2. Choose when host and device copies become authoritative

| API / mode | Source-backed meaning | Caller consequence |
| --- | --- | --- |
| `transferToDevice(FIRST_EXECUTION, ...)` | Initial graph upload rather than re-streaming every execution | Later host mutations need a deliberate upload strategy; do not expect them to appear automatically |
| `transferToDevice(EVERY_EXECUTION, ...)` | Add objects to the graph's streamed inputs | Suitable when host inputs change between executions; transfer cost is part of the workload |
| `transferToHost(EVERY_EXECUTION, ...)` | Add results to normal graph output transfers | Read the host result after the requested execution/transfer boundary |
| `transferToHost(UNDER_DEMAND, ...)` | Register output for explicit retrieval and retain device-side buffer state | Request needed results with `TornadoExecutionResult.transferToHost(...)`; execution alone does not update these host copies |

Evidence: [DataTransferMode](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/tornado-api/src/main/java/uk/ac/manchester/tornado/api/enums/DataTransferMode.java), [transferToDevice / transferToHost](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/tornado-runtime/src/main/java/uk/ac/manchester/tornado/runtime/tasks/TornadoTaskGraph.java), [result transfer API](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/tornado-api/src/main/java/uk/ac/manchester/tornado/api/TornadoExecutionResult.java).

The runtime rejects a null upload argument, skips boxed numeric upload values, and rejects ordinary scalar output values in `transferToHost`; outputs should be supported arrays/vector types. This is not an assertion that every Java object or arbitrary `MemorySegment` is a valid kernel argument. Per-type and backend validation still applies. The runtime's current raw-MemorySegment check is another reason to use the supported Tornado array types rather than assuming all off-heap pointers are interchangeable.

## 3. Distinguish host allocation from device allocation

`FloatArray` stores host-side data in `TornadoMemorySegment`. The allocation constructor uses `Arena.ofAuto()` and stores an element count in its header. `getSegment()` exposes the data slice after the Tornado array header; `getSegmentWithHeader()` exposes the complete host segment. A returned segment address is not a portable device pointer. Header size is chosen by `TornadoNativeArray.ARRAY_HEADER`, including its property/JVM-flag rules; code must not hard-code 16 bytes for every configuration.

Evidence: [FloatArray construction/access](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/tornado-api/src/main/java/uk/ac/manchester/tornado/api/types/arrays/FloatArray.java), [host segment allocation](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/tornado-api/src/main/java/uk/ac/manchester/tornado/api/types/arrays/TornadoMemorySegment.java), [header and array contract](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/tornado-api/src/main/java/uk/ac/manchester/tornado/api/types/arrays/TornadoNativeArray.java).

`DeviceBufferState`/`XPUDeviceBufferState` separately track an `XPUBuffer`, content, locking, partial-copy and reuse flags. These fields are bookkeeping, not a proof that host/device contents agree. Backend allocation is owned by backend contexts and buffer providers. For example, `CUDAMemoryManager` delegates buffer allocation/release to the CUDA platform context and keeps kernel-stack frames by execution-plan ID. The common `TornadoBufferProvider` maintains free/used buffers; do not equate dropping a Java reference with releasing a native buffer.

Evidence: [buffer-state interface](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/tornado-api/src/main/java/uk/ac/manchester/tornado/api/memory/DeviceBufferState.java), [state implementation](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/tornado-runtime/src/main/java/uk/ac/manchester/tornado/runtime/common/XPUDeviceBufferState.java), [CUDA memory manager](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/tornado-drivers/cuda/src/main/java/uk/ac/manchester/tornado/drivers/cuda/mm/CUDAMemoryManager.java), [buffer provider](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/tornado-drivers/drivers-common/src/main/java/uk/ac/manchester/tornado/drivers/common/TornadoBufferProvider.java).

`TornadoExecutionPlan.close()` calls the executor's `freeDeviceMemory()`. The runtime graph cleanup destroys execution graphs, destroys plan-owned library contexts, releases I/O device buffers and resets the device context for that plan. Use try-with-resources for bounded ownership. Closing the plan is distinct from the garbage-collected host segment lifetime. This review does not establish that arbitrary concurrent execute/close calls are safe.

Evidence: [close](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/tornado-api/src/main/java/uk/ac/manchester/tornado/api/TornadoExecutionPlan.java), [free and releaseObjectFromDeviceMemory](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/tornado-runtime/src/main/java/uk/ac/manchester/tornado/runtime/tasks/TornadoTaskGraph.java).

## 4. Native-library tasks are host dispatch of device work

The `runtime.library.spi` package defines the provider boundary. `LibraryTask.shouldCompile()` is false: these tasks do not become Java JIT kernels. The interpreter resolves constants to boxed Java values and reference arguments to device-buffer addresses past `ARRAY_HEADER`, then passes a `LibraryInvocation` to the provider. Batch library launches are explicitly rejected when `batchThreads != 0` in the reviewed interpreter path.

Evidence: [LibraryTask](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/tornado-runtime/src/main/java/uk/ac/manchester/tornado/runtime/tasks/LibraryTask.java), [executeLibraryLaunch](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/tornado-runtime/src/main/java/uk/ac/manchester/tornado/runtime/interpreter/TornadoVMInterpreter.java), [argument representation](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/tornado-runtime/src/main/java/uk/ac/manchester/tornado/runtime/library/spi/LibraryInvocation.java).

`LibraryRegistry` discovers `TornadoLibraryProvider` implementations with `ServiceLoader`. It matches both library name and `canHandle(device)`, reports missing providers or incompatible devices with `TornadoRuntimeException`, and caches a context per `(library name, device, execution plan ID)`. The provider owns native resources and implements `createContext`, `dispatch` and `destroyContext`.

The optional `prepare` hook runs before launch/capture regions. Its documented contract requires repeated calls to be safe; shape-dependent plans/workspaces that allocate device memory must be prepared before CUDA graph capture. `LibraryInvocation.isCapturing()` tells dispatch whether capture restrictions apply. A service registration and context cache do not by themselves prove every provider satisfies these restrictions.

Evidence: [registry](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/tornado-runtime/src/main/java/uk/ac/manchester/tornado/runtime/library/LibraryRegistry.java), [provider lifecycle](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/tornado-runtime/src/main/java/uk/ac/manchester/tornado/runtime/library/spi/TornadoLibraryProvider.java), [opaque context](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/tornado-runtime/src/main/java/uk/ac/manchester/tornado/runtime/library/spi/LibraryContext.java).

### Concrete cuBLAS ownership trace

- `CuBlas` factories create descriptors naming operations and access modes; they do not immediately compute a matrix result
- `CuBlasLibraryProvider.canHandle` checks for `TornadoNativeStreamSupport`; `createContext` loads the binding, obtains the plan's native stream, creates a cuBLAS handle and binds it to that stream
- `prepare` can allocate the requested workspace before capture; `dispatch` selects the function, applies options, checks native status and restores per-call options in `finally`
- Device pointers are read from `LibraryInvocation`; matrix dimensions, transpose/layout, leading dimensions and strides still have operation-specific meaning
- `destroyContext` frees the provider workspace and destroys the native handle. The registry cleanup is keyed by execution plan

Evidence: [descriptor factories](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/tornado-cublas/src/main/java/uk/ac/manchester/tornado/cublas/CuBlas.java), [provider implementation](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/tornado-cublas/src/main/java/uk/ac/manchester/tornado/cublas/provider/CuBlasLibraryProvider.java).

This is a cuBLAS review, not a blanket semantic review of cuBLASLt, cuFFT, cuDNN, cuRAND, cuSPARSE or CUTLASS. Their declared modules/providers are in the inventory; their operation-specific contracts remain open.

## 5. CPU-model names and execution location

The exported `com.synexia.tornadovm.cpu` package models guest RV32 machine state. `JvmRiscV32Executor.execute(machine, instructionsPerQuantum, maxQuanta)` validates non-null state and positive limits, calls `RiscV32Kernel.runQuantum` as ordinary Java, and stops when all cores stop or the quantum bound is reached. The guest's instruction set is not the host processor's execution mechanism.

`TornadoRiscV32Session` instead builds graph tasks, uploads architectural state with `FIRST_EXECUTION` and requests host results with `UNDER_DEMAND`. It can select a device and precompile a plan. `syncControlToDevice` / `syncAllToDevice` explicitly publish host edits; `syncStatusFromDevice` / `syncFromDevice` explicitly retrieve results after execution. Its `close()` is guarded by a closed flag and delegates to plan cleanup. Do not apply those session guarantees to all unrelated plan objects.

Evidence: [JVM reference executor](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/synexia-cpu-model/src/main/java/com/synexia/tornadovm/cpu/JvmRiscV32Executor.java), [persistent session](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/synexia-cpu-model/src/main/java/com/synexia/tornadovm/cpu/TornadoRiscV32Session.java), [engine selection](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/synexia-cpu-model/src/main/java/com/synexia/tornadovm/cpu/RiscV32ExecutionEngine.java).

## 6. Example and test entrypoints

The existing [VectorAddInt example](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/tornado-examples/src/main/java/uk/ac/manchester/tornado/examples/VectorAddInt.java) shows an `@Parallel` loop, `EVERY_EXECUTION` uploads/downloads, a graph snapshot, repeated execution and a direct Java reference call. Calling its method directly is host Java execution; recording it as a task and executing the plan takes the offload path.

After the SDK/setup steps in [BUILD_AND_RUN.md](../BUILD_AND_RUN.md), the source's documented invocation is:

```sh
tornado --enableProfiler console -m tornado.examples/uk.ac.manchester.tornado.examples.VectorAddInt 256
```

This command was not run. It needs a usable configured backend/device. The example itself does not use try-with-resources; production callers should make plan cleanup explicit rather than copying that omission.

Relevant test sources, linked as navigation rather than passing-test receipts:

- [tornado-unittests/src/main/java/uk/ac/manchester/tornado/unittests/tasks/TestLibraryTaskArity.java](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/tornado-unittests/src/main/java/uk/ac/manchester/tornado/unittests/tasks/TestLibraryTaskArity.java)
- [tornado-unittests/src/main/java/uk/ac/manchester/tornado/unittests/api/TestMemorySegmentsAsType.java](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/tornado-unittests/src/main/java/uk/ac/manchester/tornado/unittests/api/TestMemorySegmentsAsType.java)
- [tornado-unittests/src/main/java/uk/ac/manchester/tornado/unittests/memory/leak/TestMemoryLeak.java](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/tornado-unittests/src/main/java/uk/ac/manchester/tornado/unittests/memory/leak/TestMemoryLeak.java)
- [tornado-unittests/src/main/java/uk/ac/manchester/tornado/unittests/streams/TestCUDAStreams.java](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/tornado-unittests/src/main/java/uk/ac/manchester/tornado/unittests/streams/TestCUDAStreams.java)
- [tornado-unittests/src/main/java/uk/ac/manchester/tornado/unittests/multithreaded/TestMultiThreadedExecutionPlans.java](https://github.com/hsoliwal/synexia-tornadovm/blob/859491173b9940a2e4617a37fa799f828e971299/tornado-unittests/src/main/java/uk/ac/manchester/tornado/unittests/multithreaded/TestMultiThreadedExecutionPlans.java)

## Known semantic-documentation gaps

- Public inline docs were inspected selectively and remain unchanged. `execute()` has a stale `@return` type in prose, `FloatArray.get(int)` has an empty return description, and additional argument/range/exception contracts need reconciliation
- Snapshot object aliasing, all backend buffer reuse policies, cancellation, cross-device ownership, concurrent plan use and every native operation were not exhaustively reviewed
- Device/kernel numerical correctness, hardware support, native ABI compatibility and performance require execution on the intended host/device; no such checks were run
- The inventory counts source identities and manifest declarations. It is not a claim that every package is semantically documented
