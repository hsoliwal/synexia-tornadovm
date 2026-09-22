package uk.ac.manchester.tornado.diffusion;

public sealed interface Condition permits Condition.None, Condition.ClassId, Condition.Image {
    record None() implements Condition {}

    record ClassId(int id) implements Condition {
        public ClassId {
            if (id < 0) throw new IllegalArgumentException("class id must be >= 0");
        }
    }

    record Image(Tensor image, float strength) implements Condition {
        public Image {
            if (image == null) throw new IllegalArgumentException("image is required");
            if (strength < 0f || strength > 1f) throw new IllegalArgumentException("strength must be in [0,1]");
        }
    }

    static Condition none() { return new None(); }
}
