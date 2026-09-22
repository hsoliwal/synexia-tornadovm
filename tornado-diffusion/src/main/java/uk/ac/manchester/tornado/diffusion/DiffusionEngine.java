package uk.ac.manchester.tornado.diffusion;

import java.util.Arrays;
import java.util.Objects;

public final class DiffusionEngine {
    public enum Sampler { DDPM, DDIM }

    private final DiffusionSchedule schedule;
    private final DiffusionBackend backend;

    public DiffusionEngine(DiffusionSchedule schedule, DiffusionBackend backend) {
        this.schedule = Objects.requireNonNull(schedule, "schedule");
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    public Tensor addNoise(Tensor clean, int timestep, long seed) {
        checkTimestep(timestep);
        float[] noise = new float[clean.size()];
        new GaussianRandom(seed).fill(noise);
        Tensor out = new Tensor(clean.channels(), clean.height(), clean.width());
        float alphaBar = schedule.alphaBar(timestep);
        backend.qSample(clean.data(), noise, (float) Math.sqrt(alphaBar),
                (float) Math.sqrt(1.0f - alphaBar), out.data());
        return out;
    }

    public Tensor sample(int channels, int height, int width, NoisePredictor predictor,
                         Condition condition, Sampler sampler, long seed, float eta) {
        Tensor x = new Tensor(channels, height, width);
        GaussianRandom rng = new GaussianRandom(seed);
        rng.fill(x.data());
        return reverse(x, schedule.steps() - 1, predictor, condition, sampler, rng, eta);
    }

    public Tensor imageToImage(Tensor image, float strength, NoisePredictor predictor,
                               Condition condition, Sampler sampler, long seed, float eta) {
        if (strength < 0f || strength > 1f) throw new IllegalArgumentException("strength must be in [0,1]");
        int start = Math.min(schedule.steps() - 1, Math.max(0, Math.round(strength * (schedule.steps() - 1))));
        Tensor x = addNoise(image, start, seed);
        return reverse(x, start, predictor, condition, sampler,
                new GaussianRandom(seed ^ 0x9E3779B97F4A7C15L), eta);
    }

    private Tensor reverse(Tensor x, int start, NoisePredictor predictor, Condition condition,
                           Sampler sampler, GaussianRandom rng, float eta) {
        Objects.requireNonNull(predictor, "predictor");
        Objects.requireNonNull(condition, "condition");
        float[] predictedNoise = new float[x.size()];
        float[] stochasticNoise = new float[x.size()];
        float[] next = new float[x.size()];

        for (int t = start; t >= 0; t--) {
            predictor.predict(x, t, condition, predictedNoise);
            if (sampler == Sampler.DDIM) {
                float alphaBar = schedule.alphaBar(t);
                float alphaBarPrev = schedule.alphaBarPrevious(t);
                float sigma = ddimSigma(alphaBar, alphaBarPrev, eta);
                if (t > 0 && sigma > 0f) rng.fill(stochasticNoise);
                else Arrays.fill(stochasticNoise, 0f);
                backend.ddimStep(x.data(), predictedNoise, alphaBar, alphaBarPrev, sigma, stochasticNoise, next);
            } else {
                ddpmStep(x.data(), predictedNoise, t, rng, next);
            }
            System.arraycopy(next, 0, x.data(), 0, next.length);
        }
        x.clamp(-1f, 1f);
        return x;
    }

    private void ddpmStep(float[] sample, float[] predictedNoise, int t, GaussianRandom rng, float[] out) {
        float beta = schedule.beta(t);
        float alpha = schedule.alpha(t);
        float alphaBar = schedule.alphaBar(t);
        float alphaBarPrev = schedule.alphaBarPrevious(t);
        float coefficient = beta / (float) Math.sqrt(1.0f - alphaBar);
        float meanScale = 1.0f / (float) Math.sqrt(alpha);
        float posteriorVariance = beta * (1.0f - alphaBarPrev) / (1.0f - alphaBar);
        float std = t == 0 ? 0.0f : (float) Math.sqrt(Math.max(0.0f, posteriorVariance));
        for (int i = 0; i < sample.length; i++) {
            float z = t == 0 ? 0.0f : rng.nextFloat();
            out[i] = meanScale * (sample[i] - coefficient * predictedNoise[i]) + std * z;
        }
    }

    private static float ddimSigma(float alphaBar, float alphaBarPrev, float eta) {
        if (eta == 0f) return 0f;
        double left = (1.0 - alphaBarPrev) / (1.0 - alphaBar);
        double right = 1.0 - alphaBar / alphaBarPrev;
        return (float) (eta * Math.sqrt(Math.max(0.0, left * right)));
    }

    private void checkTimestep(int timestep) {
        if (timestep < 0 || timestep >= schedule.steps()) {
            throw new IllegalArgumentException("timestep out of range: " + timestep);
        }
    }
}
