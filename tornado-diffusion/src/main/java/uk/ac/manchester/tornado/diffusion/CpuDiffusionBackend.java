package uk.ac.manchester.tornado.diffusion;

enum CpuDiffusionBackend implements DiffusionBackend {
    INSTANCE;

    @Override
    public String name() { return "java-cpu"; }

    @Override
    public void qSample(float[] clean, float[] noise, float sqrtAlphaBar, float sqrtOneMinusAlphaBar, float[] out) {
        requireSameLength(clean, noise, out);
        for (int i = 0; i < out.length; i++) {
            out[i] = sqrtAlphaBar * clean[i] + sqrtOneMinusAlphaBar * noise[i];
        }
    }

    @Override
    public void ddimStep(float[] sample, float[] predictedNoise, float alphaBar, float alphaBarPrev,
                         float sigma, float[] stochasticNoise, float[] out) {
        requireSameLength(sample, predictedNoise, stochasticNoise, out);
        float sqrtAlphaBar = (float) Math.sqrt(alphaBar);
        float sqrtOneMinusAlphaBar = (float) Math.sqrt(Math.max(0f, 1f - alphaBar));
        float sqrtAlphaBarPrev = (float) Math.sqrt(alphaBarPrev);
        float directionScale = (float) Math.sqrt(Math.max(0f, 1f - alphaBarPrev - sigma * sigma));
        for (int i = 0; i < out.length; i++) {
            float predictedX0 = (sample[i] - sqrtOneMinusAlphaBar * predictedNoise[i]) / sqrtAlphaBar;
            out[i] = sqrtAlphaBarPrev * predictedX0
                    + directionScale * predictedNoise[i]
                    + sigma * stochasticNoise[i];
        }
    }

    private static void requireSameLength(float[]... arrays) {
        int n = arrays[0].length;
        for (float[] array : arrays) {
            if (array.length != n) throw new IllegalArgumentException("array lengths differ");
        }
    }
}
