package uk.ac.manchester.tornado.diffusion;

import java.util.SplittableRandom;

public final class GaussianRandom {
    private final SplittableRandom random;
    private boolean hasSpare;
    private double spare;

    public GaussianRandom(long seed) {
        random = new SplittableRandom(seed);
    }

    public float nextFloat() {
        if (hasSpare) {
            hasSpare = false;
            return (float) spare;
        }
        double u, v, s;
        do {
            u = random.nextDouble(-1.0, 1.0);
            v = random.nextDouble(-1.0, 1.0);
            s = u * u + v * v;
        } while (s <= 0.0 || s >= 1.0);
        double multiplier = Math.sqrt(-2.0 * Math.log(s) / s);
        spare = v * multiplier;
        hasSpare = true;
        return (float) (u * multiplier);
    }

    public void fill(float[] target) {
        for (int i = 0; i < target.length; i++) target[i] = nextFloat();
    }
}
