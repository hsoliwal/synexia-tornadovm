package uk.ac.manchester.tornado.diffusion;

public interface DiffusionBackend {
    String name();

    void qSample(float[] clean, float[] noise, float sqrtAlphaBar, float sqrtOneMinusAlphaBar, float[] out);

    void ddimStep(float[] sample, float[] predictedNoise, float alphaBar, float alphaBarPrev,
                  float sigma, float[] stochasticNoise, float[] out);

    static DiffusionBackend cpu() {
        return CpuDiffusionBackend.INSTANCE;
    }
}
