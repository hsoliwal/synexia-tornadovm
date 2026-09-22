package uk.ac.manchester.tornado.diffusion;

public final class DiffusionSchedule {
    private final float[] beta;
    private final float[] alpha;
    private final float[] alphaBar;

    private DiffusionSchedule(float[] beta) {
        this.beta = beta;
        this.alpha = new float[beta.length];
        this.alphaBar = new float[beta.length];
        float product = 1.0f;
        for (int i = 0; i < beta.length; i++) {
            if (!(beta[i] > 0.0f && beta[i] < 1.0f)) {
                throw new IllegalArgumentException("beta must be in (0,1)");
            }
            alpha[i] = 1.0f - beta[i];
            product *= alpha[i];
            alphaBar[i] = product;
        }
    }

    public static DiffusionSchedule linear(int steps, float betaStart, float betaEnd) {
        if (steps < 2 || betaStart <= 0f || betaEnd <= betaStart || betaEnd >= 1f) {
            throw new IllegalArgumentException("Invalid linear schedule");
        }
        float[] beta = new float[steps];
        for (int i = 0; i < steps; i++) {
            beta[i] = betaStart + (betaEnd - betaStart) * i / (steps - 1.0f);
        }
        return new DiffusionSchedule(beta);
    }

    public static DiffusionSchedule cosine(int steps, double s) {
        if (steps < 2 || s < 0.0) throw new IllegalArgumentException("Invalid cosine schedule");
        float[] beta = new float[steps];
        double previous = cosineAlphaBar(0.0, s);
        for (int i = 0; i < steps; i++) {
            double current = cosineAlphaBar((i + 1.0) / steps, s);
            beta[i] = (float) Math.min(0.999, Math.max(1.0e-8, 1.0 - current / previous));
            previous = current;
        }
        return new DiffusionSchedule(beta);
    }

    private static double cosineAlphaBar(double t, double s) {
        double angle = ((t + s) / (1.0 + s)) * Math.PI * 0.5;
        double c = Math.cos(angle);
        return c * c;
    }

    public int steps() { return beta.length; }
    public float beta(int t) { return beta[t]; }
    public float alpha(int t) { return alpha[t]; }
    public float alphaBar(int t) { return alphaBar[t]; }
    public float alphaBarPrevious(int t) { return t == 0 ? 1.0f : alphaBar[t - 1]; }
}
