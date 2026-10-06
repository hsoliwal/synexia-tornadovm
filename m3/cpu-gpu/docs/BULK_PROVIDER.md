<!-- SPDX-License-Identifier: Apache-2.0 -->
# RV32IM bulk provider

The CPU/GPU module already maps one TornadoVM work-item to one independent RV32IM virtual core.
This increment gives that execution model an explicit, content-addressed bulk-provider boundary.

```text
Rv32iBatch
  -> Rv32iBulkExecutor
      -> CPU oracle
      or
      -> TornadoRv32iSession
           -> WorkerGrid1D
           -> one work-item / virtual core
           -> synchronize full state
           -> independent CPU parity
  -> Rv32iBulkReceipt
```

## Admission

`TORNADO_VERIFIED` is the only accelerated receipt mode. It is emitted only after registers,
architectural state and complete private RAM agree with an independently executed
`CpuRv32iEngine` copy.

The receipt binds:

- executed mode;
- number of cores;
- instruction budget;
- local work size;
- exact input-state root;
- exact output-state root;
- CPU/Tornado verification root.

This is execution evidence, not a performance claim.

## Recipe-first custody

The model-assisted change is source-sealed by the standalone Maven/OpenRewrite crate:

`m3/cpu-gpu/recipe-bulk-provider`

The recipe was committed before the three production/test targets. Replaying it must produce no
change once those exact postimages are present.

## JDK integration boundary

The public JDK-shaped scheduler facade is owned in `hsoliwal/com.synexia` by
`synexia-tornadovm-m3`. This module is a candidate backend for that scheduler: it can run the
same bounded guest program over thousands of independent virtual cores.

No dependency from this TornadoVM fork back into Synexia is introduced. The provider remains
usable and testable as a standalone TornadoVM M3 module.
