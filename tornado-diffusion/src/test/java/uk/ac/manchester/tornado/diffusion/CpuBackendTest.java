package uk.ac.manchester.tornado.diffusion;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class CpuBackendTest {
    @Test
    void qSampleMatchesEquation() {
        float[] clean = {1f, -1f, 0.5f};
        float[] noise = {0.25f, 0.5f, -0.25f};
        float[] out = new float[3];
        DiffusionBackend.cpu().qSample(clean, noise, 0.8f, 0.6f, out);
        assertArrayEquals(new float[]{0.95f, -0.5f, 0.25f}, out, 1.0e-6f);
    }
}
