package uk.ac.manchester.tornado.diffusion.cli;

import uk.ac.manchester.tornado.diffusion.Condition;
import uk.ac.manchester.tornado.diffusion.DiffusionBackend;
import uk.ac.manchester.tornado.diffusion.DiffusionEngine;
import uk.ac.manchester.tornado.diffusion.DiffusionSchedule;
import uk.ac.manchester.tornado.diffusion.NoisePredictor;
import uk.ac.manchester.tornado.diffusion.Tensor;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DiffusionCli {
    private DiffusionCli() {}

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        DiffusionSchedule schedule = DiffusionSchedule.cosine(config.steps, 0.008);
        DiffusionEngine engine = new DiffusionEngine(schedule, DiffusionBackend.cpu());
        Tensor image = engine.sample(3, config.height, config.width,
                proceduralPredictor(config.width, config.height), Condition.none(),
                DiffusionEngine.Sampler.DDIM, config.seed, 0f);
        writePpm(image, Path.of(config.out));
        System.out.println("wrote " + config.out + " using Java diffusion core");
    }

    private static NoisePredictor proceduralPredictor(int width, int height) {
        return (sample, timestep, condition, out) -> {
            float phase = timestep * 0.071f;
            int plane = width * height;
            for (int i = 0; i < out.length; i++) {
                int p = i % plane;
                int x = p % width;
                int y = p / width;
                int c = i / plane;
                float nx = (x - width * 0.5f) / width;
                float ny = (y - height * 0.5f) / height;
                float target = (float) (0.55 * Math.sin((nx * (7 + c * 2) + phase) * Math.PI)
                        + 0.45 * Math.cos((ny * (9 - c) - phase) * Math.PI));
                out[i] = sample.data()[i] - target;
            }
        };
    }

    private static void writePpm(Tensor tensor, Path path) throws IOException {
        if (tensor.channels() < 3) throw new IllegalArgumentException("PPM output requires at least 3 channels");
        int plane = tensor.width() * tensor.height();
        try (BufferedOutputStream out = new BufferedOutputStream(Files.newOutputStream(path))) {
            out.write(("P6\n" + tensor.width() + " " + tensor.height() + "\n255\n").getBytes(StandardCharsets.US_ASCII));
            for (int p = 0; p < plane; p++) {
                for (int c = 0; c < 3; c++) {
                    float v = tensor.data()[c * plane + p];
                    out.write(Math.max(0, Math.min(255, Math.round((v + 1f) * 127.5f))));
                }
            }
        }
    }

    private record Config(int width, int height, int steps, long seed, String out) {
        static Config parse(String[] args) {
            int width = 256, height = 256, steps = 50;
            long seed = 42L;
            String out = "diffusion.ppm";
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--width" -> width = Integer.parseInt(args[++i]);
                    case "--height" -> height = Integer.parseInt(args[++i]);
                    case "--steps" -> steps = Integer.parseInt(args[++i]);
                    case "--seed" -> seed = Long.parseLong(args[++i]);
                    case "--out" -> out = args[++i];
                    default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
                }
            }
            return new Config(width, height, steps, seed, out);
        }
    }
}
