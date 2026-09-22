#include <jni.h>
#include "tornado_diffusion.h"

JNIEXPORT jint JNICALL Java_uk_ac_manchester_tornado_diffusion_nativeapi_JniDiffusionBridge_qSample(
        JNIEnv *env, jclass clazz, jfloatArray clean, jfloatArray noise, jint length,
        jfloat sqrt_alpha_bar, jfloat sqrt_one_minus_alpha_bar, jfloatArray out) {
    (void) clazz;
    jfloat *c = (*env)->GetFloatArrayElements(env, clean, NULL);
    jfloat *n = (*env)->GetFloatArrayElements(env, noise, NULL);
    jfloat *o = (*env)->GetFloatArrayElements(env, out, NULL);
    if (!c || !n || !o) return 3;
    int rc = td_q_sample(c, n, length, sqrt_alpha_bar, sqrt_one_minus_alpha_bar, o);
    (*env)->ReleaseFloatArrayElements(env, clean, c, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, noise, n, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, out, o, 0);
    return rc;
}

JNIEXPORT jint JNICALL Java_uk_ac_manchester_tornado_diffusion_nativeapi_JniDiffusionBridge_ddimStep(
        JNIEnv *env, jclass clazz, jfloatArray sample, jfloatArray predicted_noise,
        jfloat alpha_bar, jfloat alpha_bar_prev, jfloat sigma,
        jfloatArray stochastic_noise, jint length, jfloatArray out) {
    (void) clazz;
    jfloat *s = (*env)->GetFloatArrayElements(env, sample, NULL);
    jfloat *p = (*env)->GetFloatArrayElements(env, predicted_noise, NULL);
    jfloat *z = (*env)->GetFloatArrayElements(env, stochastic_noise, NULL);
    jfloat *o = (*env)->GetFloatArrayElements(env, out, NULL);
    if (!s || !p || !z || !o) return 3;
    int rc = td_ddim_step(s, p, alpha_bar, alpha_bar_prev, sigma, z, length, o);
    (*env)->ReleaseFloatArrayElements(env, sample, s, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, predicted_noise, p, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, stochastic_noise, z, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, out, o, 0);
    return rc;
}
