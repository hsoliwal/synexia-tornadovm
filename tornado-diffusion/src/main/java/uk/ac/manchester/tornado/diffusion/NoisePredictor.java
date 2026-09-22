package uk.ac.manchester.tornado.diffusion;

@FunctionalInterface
public interface NoisePredictor {
    void predict(Tensor noisySample, int timestep, Condition condition, float[] predictedNoise);
}
