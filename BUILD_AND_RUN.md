# TornadoVM build and run guide

This guide is for a source checkout of
[hsoliwal/synexia-tornadovm](https://github.com/hsoliwal/synexia-tornadovm), a fork of
[beehive-lab/TornadoVM](https://github.com/beehive-lab/TornadoVM).
Use [INSTALL_FROM_SOURCE.md](INSTALL_FROM_SOURCE.md) for platform installation
details and [CONTRIBUTING.md](CONTRIBUTING.md) for the fork/upstream review routes.
The commands below follow this checkout's [Makefile](Makefile),
[build driver](bin/compile) and [Maven profiles](pom.xml).

## 1. Choose a JDK and backend

Run build commands from the repository root.

- Set `JAVA_HOME` to the JDK that will actually compile the project. A profile
  selects compiler settings; it does not switch the JDK for you.
- `jdk21` requires JDK 21 and creates a preview-enabled Java 21 SDK.
  `jdk22plus` accepts a build JDK of 22 or newer and compiles with release 22.
  The profile is a source-level compatibility contract, not proof of execution
  on every JDK, OS, GPU or driver.
- Have Git, Python 3, Make and the selected backend's native compiler/CMake
  dependencies available. On Unix, the SDK launcher also uses Bash.
- The Python dependency checker can invoke `pip3` for missing `requests`,
  `tqdm`, `urllib3`, `wget`, `packaging` and `streamlit`. Use an isolated
  Python environment and account for those downloads before starting.
- The [Maven wrapper](.mvn/wrapper/maven-wrapper.properties) pins Maven 3.9.6.
  The initial build also fetches/stages Graal dependencies through
  [pull_graal_jars.py](bin/pull_graal_jars.py) and, for OpenCL, obtains Khronos
  headers. A clean checkout is not an offline build.

| Backend | Build selection | Device/runtime prerequisites |
| --- | --- | --- |
| OpenCL | `BACKEND=opencl` | An OpenCL implementation/driver for the selected device |
| CUDA | `BACKEND=cuda` | NVIDIA driver and CUDA Toolkit, including NVRTC |
| Metal | `BACKEND=metal` | macOS/Apple Silicon toolchain and runtime |

The Makefile accepts `opencl`, `cuda` and `metal`; it rejects an empty or
unknown backend. Prefer a single backend while qualifying a backend-specific
change so tests cannot quietly exercise a different installed backend.

## 2. Build the SDK with Make

Example for JDK 21 and OpenCL on a Unix shell:

```bash
export JAVA_HOME=/absolute/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
"$JAVA_HOME/bin/java" -version
make jdk21 BACKEND=opencl
source setvars.sh
tornado --devices
```

The build generates `setvars.sh` (or `setvars.cmd` on Windows) after a successful
Maven build. It is not a prerequisite that exists in a fresh clone. On Windows,
use the installation guide's toolchain setup and generated `setvars.cmd`.

To use a JDK 22+ installation, select the other profile explicitly:

```bash
export JAVA_HOME=/absolute/path/to/jdk-22-or-newer
export PATH="$JAVA_HOME/bin:$PATH"
make jdk22plus BACKEND=opencl
source setvars.sh
```

Plain `make` still selects the `jdk21` build target. The `sdk` target derives
its profile from `JAVA_HOME`, while `sdk-jdk21` and `sdk-jdk22plus` pin it.
For example, `make sdk-jdk21 BACKEND=cuda` creates SDK archives for that
selection. The convenience target `make metal` selects `metal,opencl`; use
`make jdk21 BACKEND=metal` when you want Metal alone.

Make delegates to `bin/compile`, which checks the JDK, performs Maven cleanup,
stages dependencies, builds with explicit profiles, and generates SDK paths and
argfiles. The SDK is assembled under `dist/`; `bin/sdk` is a generated link.
Use the generated `TORNADOVM_HOME` instead of hard-coding a versioned directory.

[update_paths.py](bin/update_paths.py) prefers an existing `full` SDK, then
the most recently modified candidate. If several builds exist, inspect the
reported SDK path and `$TORNADOVM_HOME/etc/tornado.backend` before testing.
Also check `JAVA_HOME` after sourcing an older `setvars.sh`, because that
file records the JDK used when it was generated.

### Maven and resource settings

[.mvn/maven.config](.mvn/maven.config) sets `-T1.5C`, colored output, skipped
Javadoc and timestamps. It does **not** select a JDK/backend profile or enable
tests. [pom.xml](pom.xml) defaults `skipTests` to `true`.

For the build driver's single-threaded path:

```bash
bin/compile --jdk jdk21 --backend opencl --mvn_single_threaded
```

Direct Maven is useful for focused module work after dependency staging. It does
not perform all the SDK setup and argfile generation done by `bin/compile`.
For example, this is the no-GPU reflection-test reactor used by the Makefile:

```bash
./mvnw -Pjdk21 -pl tornado-api,tornado-runtime -am clean test -DskipTests=false
```

Use `-Pjdk22plus` with a matching JDK 22+ environment. Missing
`tornado.graal` or `tornado.jvmci` artifacts on a fresh checkout indicate
that the dependency-staging route needs inspection; a plain Maven install is
not a substitute for that bootstrap. A successful install with tests skipped
does not establish runtime correctness.

## 3. Run with the launcher or a generated argfile

After sourcing the generated environment, the wrapper is the simplest route:

```bash
tornado --devices
tornado --printKernel --debug -m tornado.examples/uk.ac.manchester.tornado.examples.VectorAddInt --params="8192"
```

This uses the checked-in [VectorAddInt example](tornado-examples/src/main/java/uk/ac/manchester/tornado/examples/VectorAddInt.java)
and the same module/main-class form as `make example`. For an application on
the classpath:

```bash
tornado --jvm="-Xmx8g -Dtornado.debug=true" -cp app.jar Main
```

Replace `app.jar` and `Main` with your application. The wrapper supplies the
SDK module paths, native library paths, exports and JDK-specific flags.

For an IDE or direct Java launch, generate the argfile for the current JDK:

```bash
tornado --generate-argfile
"$JAVA_HOME/bin/java" @"$TORNADOVM_HOME/tornado-argfile" -cp app.jar Main
```

The generated file is **inside the selected SDK**, not at the repository root.
The [generator](tornado-assembly/src/bin/gen-tornado-argfile-template.py) derives
flags from the installed launcher and inlines export lists; Java does not expand
nested argfiles. The checked-in [example-argfile](example-argfile) is an example,
not the generated configuration for your machine.

Regenerate after changing JDK or relocating/selecting an SDK. JVMCI-related
flags differ between runtime JDKs. Check warnings: the launcher can fall back
to its shipped template if regeneration fails. In an IDE, select the same JDK,
use the expanded file's absolute path in VM options, and supply your application
classpath/main class separately.

## 4. Choose the test scope

After dependency staging, the Makefile exposes these no-GPU test routes:

```bash
make test-reflection JDK=jdk21
make test-reflection-only JDK=jdk21
make test-cuda-codegen JDK=jdk21
```

Use `JDK=jdk22plus` for that profile. These checks still need their compiler
and Maven dependencies. They cover reflection metadata or CUDA code generation;
they do not prove device execution.

For backend/device tests, first build the intended backend, source its environment
and inspect the available devices:

```bash
tornado --devices
tornado-test -V uk.ac.manchester.tornado.unittests.foundation.TestFloats
tornado-test --verbose --quickPass
```

The runner has no `--backend` switch: the installed SDK determines the backend.
Use its `--device` option when a test needs a particular device and retain that
selection in the result. `--quickPass` excludes heavy/memory coverage. The broader
`make tests` target also invokes a deliberate low-memory failure case and native
tests. See [the unit-test guide](tornado-unittests/README.md) for class/method
selection and runner options. Record failures, unsupported cases and skips
separately; device enumeration alone is not a kernel test.

## Source map and troubleshooting

- [tornado-api](tornado-api/): application-facing task graph and execution APIs
- [tornado-runtime](tornado-runtime/): runtime and scheduling implementation
- [tornado-drivers](tornado-drivers/): backend compilers and native integrations
- [tornado-assembly](tornado-assembly/): SDK packaging, launchers and export lists
- [tornado-examples](tornado-examples/) and [tornado-unittests](tornado-unittests/):
  runnable examples and device regression tests
- [docs/source](docs/source/): Sphinx documentation sources

For module/preview errors, verify the JDK profile, `JAVA_HOME`, selected SDK and
freshly generated argfile together. For native-library or missing-device errors,
check the backend, driver/toolkit and library paths. Preserve the first failing
command and log rather than reporting a later build or a different device as a
pass.

The [README license map](README.md#licenses-per-module),
[Apache-2.0 text](LICENSE_APACHE2), [GPLv2 with Classpath Exception text](LICENSE_GPLv2CE)
and per-file notices remain authoritative for their respective material. Read
the [M3/Synexia provenance notice](M3-SYNEXIA-RIGHTS.md) for first-party additions;
a fork does not relicense upstream runtime/driver code.

Include the commit, JDK, OS/architecture, backend, GPU/driver, SDK path, commands
and exact test scope with a result. No build, device tests, benchmarks or
documentation rendering were executed for this guide revision. These
source-checked instructions make no new portability or performance claim.
