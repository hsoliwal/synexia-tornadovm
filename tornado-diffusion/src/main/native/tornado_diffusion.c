#include "tornado_diffusion.h"
#include <math.h>
int td_q_sample(const float *clean, const float *noise, int length,
                float sqrt_alpha_bar, float sqrt_one_minus_alpha_bar, float *out) {
    if (!clean || !noise || !out || length < 0) return 1;
    for (int i = 0; i < length; ++i) out[i] = sqrt_alpha_bar * clean[i] + sqrt_one_minus_alpha_bar * noise[i];
    return 0;
}
int td_ddim_step(const float *sample, const float *predicted_noise,
                 float alpha_bar, float alpha_bar_prev, float sigma,
                 const float *stochastic_noise, int length, float *out) {
    if (!sample || !predicted_noise || !stochastic_noise || !out || length < 0) return 1;
    if (!(alpha_bar > 0.0f) || !(alpha_bar_prev > 0.0f)) return 2;
    const float sqrt_ab = sqrtf(alpha_bar);
    const float sqrt_1m_ab = sqrtf(fmaxf(0.0f, 1.0f - alpha_bar));
    const float sqrt_ab_prev = sqrtf(alpha_bar_prev);
    const float direction = sqrtf(fmaxf(0.0f, 1.0f - alpha_bar_prev - sigma * sigma));
    for (int i = 0; i < length; ++i) {
        const float x0 = (sample[i] - sqrt_1m_ab * predicted_noise[i]) / sqrt_ab;
        out[i] = sqrt_ab_prev * x0 + direction * predicted_noise[i] + sigma * stochastic_noise[i];
    }
    return 0;
}
