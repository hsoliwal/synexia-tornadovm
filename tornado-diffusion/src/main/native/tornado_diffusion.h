#ifndef TORNADO_DIFFUSION_H
#define TORNADO_DIFFUSION_H
#if defined(_WIN32)
#define TD_API __declspec(dllexport)
#else
#define TD_API __attribute__((visibility("default")))
#endif
#ifdef __cplusplus
extern "C" {
#endif
TD_API int td_q_sample(const float *clean, const float *noise, int length,
                       float sqrt_alpha_bar, float sqrt_one_minus_alpha_bar, float *out);
TD_API int td_ddim_step(const float *sample, const float *predicted_noise,
                        float alpha_bar, float alpha_bar_prev, float sigma,
                        const float *stochastic_noise, int length, float *out);
#ifdef __cplusplus
}
#endif
#endif
