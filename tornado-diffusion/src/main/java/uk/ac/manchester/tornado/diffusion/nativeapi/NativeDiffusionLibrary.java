package uk.ac.manchester.tornado.diffusion.nativeapi;

import com.sun.jna.Library;
import com.sun.jna.Native;

public interface NativeDiffusionLibrary extends Library {
    NativeDiffusionLibrary INSTANCE = Native.load("tornado_diffusion", NativeDiffusionLibrary.class);

    int td_q_sample(float[] clean, float[] noise, int length,
                    float sqrtAlphaBar, float sqrtOneMinusAlphaBar, float[] out);

    int td_ddim_step(float[] sample, float[] predictedNoise, float alphaBar, float alphaBarPrev,
                     float sigma, float[] stochasticNoise, int length, float[] out);
}
