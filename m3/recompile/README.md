<!-- SPDX-License-Identifier: Apache-2.0 -->
# TornadoVM source rebuild for the M3 licensed-model track

The existing TornadoVM Maven wrapper is the build authority. This additive
script inventories the wrapper + reactor/model POMs and produces an explicit
build plan; no compilation or model loading occurs without `--build`.

```
python3 m3/recompile/source_rebuild.py
python3 m3/recompile/source_rebuild.py --build --scope targeted
python3 m3/recompile/source_rebuild.py --build --scope full
```

`targeted` runs `mvnw -B -ntp -pl synexia-cpu-model -am verify`, including
required reactor parents/dependencies and tests; `full` runs the complete
Maven reactor verification. Both can require toolchains and drivers absent
from a host. No CUDA, OpenCL or model-quality claim follows from compilation.
Do not modify the existing TornadoVM parent/module interfaces or donor NOTICE.

Canonical numerical distillation mathematics, bounded student training, and
SHA-256-pinned JNI output checks live in `hsoliwal/com.synexia` PR #11348
(`com.synexia.m3.model.distill`). Its CPU/JNI JMOD is not a Tornado GPU kernel.
A Tornado TaskGraph student acceleration must be separately reviewed against
`M3KernelRegistry` host-owned kernel/device policy and the CPU gradient/SGD
oracle. Proprietary Chrome weights are not read or redistributed.

Open gates: Maven full build on supported hosts, actual device selection,
float numerical parity, external licensed teacher/tokenizer/checkpoint,
student training/evaluation, and exact M3JDK21 receiving branch.
