package uk.ac.manchester.tornado.diffusion;

import java.util.Arrays;
import java.util.Objects;

public final class Tensor {
    private final int channels;
    private final int height;
    private final int width;
    private final float[] data;

    public Tensor(int channels, int height, int width) {
        this(channels, height, width, new float[Math.multiplyExact(channels, Math.multiplyExact(height, width))]);
    }

    public Tensor(int channels, int height, int width, float[] data) {
        if (channels <= 0 || height <= 0 || width <= 0) {
            throw new IllegalArgumentException("Tensor dimensions must be positive");
        }
        int expected = Math.multiplyExact(channels, Math.multiplyExact(height, width));
        if (Objects.requireNonNull(data, "data").length != expected) {
            throw new IllegalArgumentException("Expected " + expected + " values, got " + data.length);
        }
        this.channels = channels;
        this.height = height;
        this.width = width;
        this.data = data;
    }

    public int channels() { return channels; }
    public int height() { return height; }
    public int width() { return width; }
    public int size() { return data.length; }
    public float[] data() { return data; }

    public Tensor copy() {
        return new Tensor(channels, height, width, Arrays.copyOf(data, data.length));
    }

    public void clamp(float min, float max) {
        for (int i = 0; i < data.length; i++) {
            data[i] = Math.max(min, Math.min(max, data[i]));
        }
    }
}
