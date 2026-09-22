package uk.ac.manchester.tornado.diffusion.nativeapi;

import uk.ac.manchester.tornado.diffusion.DiffusionBackend;

public final class JniDiffusionBackend implements DiffusionBackend {
    @Override public String name() { return "jni-native"; }

    @Override
    public void qSample(float[] clean, float[] noise, float sqrtAlphaBar, float sqrtOneMinusAlphaBar, float[] out) {
        check(JniDiffusionBridge.qSample(clean, noise, out.length, sqrtAlphaBar, sqrtOneMinusAlphaBar, out));
    }

    @Override
    public void ddimStep(float[] sample, float[] predictedNoise, float alphaBar, float alphaBarPrev,
                         float sigma, float[] stochasticNoise, float[] out) {
        check(JniDiffusionBridge.ddimStep(sample, predictedNoise, alphaBar, alphaBarPrev,
                sigma, stochasticNoise, out.length, out));
    }

    private static void check(int rc) {
        if (rc != 0) throw new IllegalStateException("JNI diffusion call failed: " + rc);
    }
}
