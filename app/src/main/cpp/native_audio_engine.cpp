#include <jni.h>

// Phase 1 native boundary placeholder.
// Real-time DSP and Oboe/AAudio are not integrated in this commit.
extern "C" JNIEXPORT jstring JNICALL
Java_br_com_celsomartins_audiostretch_AudioNative_nativeStatus(JNIEnv* env, jobject) {
    return env->NewStringUTF("Native DSP engine not integrated");
}
