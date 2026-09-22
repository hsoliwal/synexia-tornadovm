package uk.ac.manchester.tornado.diffusion;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiffusionEngineTest {
    @Test
    void seededSamplingIsDeterministic() {
        DiffusionEngine engine = new DiffusionEngine(
                DiffusionSchedule.linear(16, 0.0001f, 0.02f), DiffusionBackend.cpu());
        NoisePredictor predictor = (sample, t, condition, out) -> {
            for (int i = 0; i < out.length; i++) out[i] = sample.data()[i] * 0.125f;
        };
        Tensor a = engine.sample(3, 8, 8, predictor, Condition.none(),
                DiffusionEngine.Sampler.DDIM, 1234L, 0f);
        Tensor b = engine.sample(3, 8, 8, predictor, Condition.none(),
                DiffusionEngine.Sampler.DDIM, 1234L, 0f);
        assertArrayEquals(a.data(), b.data(), 0f);
    }

    @Test
    void addNoisePreservesShapeAndFiniteValues() {
        Tensor clean = new Tensor(3, 4, 5);
        DiffusionEngine engine = new DiffusionEngine(
                DiffusionSchedule.linear(10, 0.0001f, 0.02f), DiffusionBackend.cpu());
        Tensor noisy = engine.addNoise(clean, 5, 99L);
        assertEquals(clean.size(), noisy.size());
        for (float v : noisy.data()) assertTrue(Float.isFinite(v));
    }
}
