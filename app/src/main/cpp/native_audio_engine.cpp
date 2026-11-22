#include <jni.h>
#include <oboe/Oboe.h>

#include <memory>
#include <mutex>

class NativeAudioEngine final : public oboe::AudioStreamDataCallback {
public:
    oboe::Result open() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (stream_) return oboe::Result::OK;

        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
               ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
               ->setSharingMode(oboe::SharingMode::Exclusive)
               ->setFormat(oboe::AudioFormat::Float)
               ->setChannelCount(oboe::ChannelCount::Stereo)
               ->setSampleRate(oboe::kUnspecified)
               ->setDataCallback(this);

        auto result = builder.openStream(stream_);
        if (result != oboe::Result::OK) {
            builder.setSharingMode(oboe::SharingMode::Shared);
            result = builder.openStream(stream_);
        }
        if (result != oboe::Result::OK || !stream_) return result;

        result = stream_->requestStart();
        if (result != oboe::Result::OK) {
            stream_->close();
            stream_.reset();
        }
        return result;
    }

    void close() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!stream_) return;
        stream_->requestStop();
        stream_->close();
        stream_.reset();
    }

    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream*, void* audioData, int32_t numFrames) override {
        // Safe silent callback: PCM transport will be connected in the next stage.
        auto* output = static_cast<float*>(audioData);
        for (int32_t i = 0; i < numFrames * 2; ++i) output[i] = 0.0f;
        return oboe::DataCallbackResult::Continue;
    }

    bool isOpen() {
        std::lock_guard<std::mutex> lock(mutex_);
        return stream_ != nullptr;
    }

private:
    std::mutex mutex_;
    std::shared_ptr<oboe::AudioStream> stream_;
};

static NativeAudioEngine gEngine;

extern "C" JNIEXPORT jboolean JNICALL
Java_br_com_celsomartins_audiostretch_AudioNative_nativeOpen(JNIEnv*, jobject) {
    return gEngine.open() == oboe::Result::OK ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_br_com_celsomartins_audiostretch_AudioNative_nativeClose(JNIEnv*, jobject) {
    gEngine.close();
}

extern "C" JNIEXPORT jstring JNICALL
Java_br_com_celsomartins_audiostretch_AudioNative_nativeStatus(JNIEnv* env, jobject) {
    const char* status = gEngine.isOpen()
        ? "Oboe 1.11.0: stream aberto; callback PCM ainda não conectado"
        : "Oboe 1.11.0: biblioteca carregada; stream fechado";
    return env->NewStringUTF(status);
}
