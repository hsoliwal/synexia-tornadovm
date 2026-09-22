package uk.ac.manchester.tornado.diffusion.nativeapi;

public final class JniDiffusionBridge {
    static {
        System.loadLibrary("tornado_diffusion");
    }

    private JniDiffusionBridge() {}

    public static native int qSample(float[] clean, float[] noise, int length,
                                     float sqrtAlphaBar, float sqrtOneMinusAlphaBar, float[] out);

    public static native int ddimStep(float[] sample, float[] predictedNoise,
                                      float alphaBar, float alphaBarPrev, float sigma,
                                      float[] stochasticNoise, int length, float[] out);
}
