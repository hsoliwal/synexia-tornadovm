package uk.ac.manchester.tornado.diffusion;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiffusionScheduleTest {
    @Test
    void alphaBarIsStrictlyDecreasing() {
        DiffusionSchedule schedule = DiffusionSchedule.linear(100, 0.0001f, 0.02f);
        float previous = 1f;
        for (int t = 0; t < schedule.steps(); t++) {
            assertTrue(schedule.alphaBar(t) < previous);
            assertTrue(schedule.alphaBar(t) > 0f);
            previous = schedule.alphaBar(t);
        }
    }

    @Test
    void cosineScheduleIsValid() {
        DiffusionSchedule schedule = DiffusionSchedule.cosine(100, 0.008);
        for (int t = 0; t < schedule.steps(); t++) {
            assertTrue(schedule.beta(t) > 0f);
            assertTrue(schedule.beta(t) < 1f);
        }
    }
}
