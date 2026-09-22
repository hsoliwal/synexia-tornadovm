# Tornado Diffusion

A deterministic, LLM-free image-diffusion runtime for Java 21 with pure-Java, JNA and JNI execution paths.

Implemented:
- linear and cosine noise schedules
- forward q-sampling
- DDPM and DDIM reverse samplers
- deterministic seeded Gaussian noise
- unconditioned, class-conditioned and image-conditioned contracts
- primitive `float[]` NCHW tensors
- Java CPU backend
- JNA C-ABI backend
- JNI backend
- native C implementation and CMake build
- dependency-free PPM CLI demo
- JVM tests for schedules, arithmetic and deterministic sampling

A trained denoiser is intentionally separate behind `NoisePredictor`. That denoiser can be a U-Net, DiT, TornadoVM kernel graph, cuDNN pipeline, ONNX/DJL adapter, or custom implementation. No language model or text encoder is required.

## Build and test

```bash
mvn -pl tornado-diffusion -am test
```

## Pure Java demo

```bash
mvn -pl tornado-diffusion -am package
java -cp tornado-diffusion/target/tornado-diffusion-*.jar \
  uk.ac.manchester.tornado.diffusion.cli.DiffusionCli \
  --width 256 --height 256 --steps 50 --seed 42 --out diffusion.ppm
```

## Native library

```bash
cmake -S tornado-diffusion/src/main/native -B tornado-diffusion/target/native
cmake --build tornado-diffusion/target/native --config Release
```

Use `-Djava.library.path=...` for JNI or `-Djna.library.path=...` for JNA.
