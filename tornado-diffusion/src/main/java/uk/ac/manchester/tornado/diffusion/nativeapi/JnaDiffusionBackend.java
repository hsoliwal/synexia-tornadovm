package uk.ac.manchester.tornado.diffusion.nativeapi;

import uk.ac.manchester.tornado.diffusion.DiffusionBackend;

public final class JnaDiffusionBackend implements DiffusionBackend {
    private final NativeDiffusionLibrary library;

    public JnaDiffusionBackend() {
        this(NativeDiffusionLibrary.INSTANCE);
    }

    JnaDiffusionBackend(NativeDiffusionLibrary library) {
        this.library = library;
    }

    @Override public String name() { return "jna-native"; }

    @Override
    public void qSample(float[] clean, float[] noise, float sqrtAlphaBar, float sqrtOneMinusAlphaBar, float[] out) {
        check(library.td_q_sample(clean, noise, out.length, sqrtAlphaBar, sqrtOneMinusAlphaBar, out));
    }

    @Override
    public void ddimStep(float[] sample, float[] predictedNoise, float alphaBar, float alphaBarPrev,
                         float sigma, float[] stochasticNoise, float[] out) {
        check(library.td_ddim_step(sample, predictedNoise, alphaBar, alphaBarPrev,
                sigma, stochasticNoise, out.length, out));
    }

    private static void check(int rc) {
        if (rc != 0) throw new IllegalStateException("native diffusion call failed: " + rc);
    }
}
