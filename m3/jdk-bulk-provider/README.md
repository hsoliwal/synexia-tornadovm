# M3JDK RV32IM bulk-provider adapter

This optional module connects the internal M3JDK bulk scheduler contract to the existing verified
TornadoVM RV32IM batch engine.

```text
jdk.internal.vm.parallel.BulkScheduler
  -> M3JdkRv32iBulkProvider
      -> Rv32iBulkExecutor
          -> TornadoRv32iSession
          -> one GPU work-item per independent RV32IM core
          -> full CPU registers/state/RAM parity
          -> TORNADO_VERIFIED receipt
      -> jdk.internal.vm.parallel.BulkExecution
```

The exact admitted operation is `rv32im-slice`.

Inputs:
- `batch`: `Rv32iBatch`
- `instructionBudget`: `Integer`

Outputs:
- `batch`: the executed batch
- `receipt`: `Rv32iBulkReceipt`

`BulkTask.workItems()` must equal the batch core count. A zero local-work request selects the
provider default (up to 64); an explicit value is passed to the existing TornadoVM worker grid.

There is no CPU fallback in this adapter. TornadoVM/device/parity failure means the provider call
fails and no JDK bulk receipt is returned.

The integration module is intentionally not in the upstream TornadoVM reactor. It is compiled only
against the exact M3JDK internal contract pinned by [SOURCE_LOCK.json](SOURCE_LOCK.json). This keeps
TornadoVM independently buildable and keeps `java.base` free of a TornadoVM dependency.

Physical GPU execution is opt-in and only counts when the existing RV32IM implementation returns a
`TORNADO_VERIFIED` receipt after complete CPU differential parity.
