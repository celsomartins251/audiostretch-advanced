#include <jni.h>
#include <oboe/Oboe.h>
#include <array>
#include <atomic>
#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <mutex>

namespace {
constexpr uint64_t kCapacitySamples = 1u << 17;
constexpr uint64_t kMask = kCapacitySamples - 1;
constexpr int32_t kJniChunkSamples = 2048;
constexpr int64_t kStateTimeoutNs = 1'000'000'000LL;
class PcmRingBuffer {
public:
    PcmRingBuffer() : samples_(new float[kCapacitySamples]()) {}
    int32_t write(const float* source, int32_t count) {
        if (!source || count <= 0) return 0;
        const uint64_t head = head_.load(std::memory_order_relaxed);
        const uint64_t tail = tail_.load(std::memory_order_acquire);
        const uint64_t accepted = std::min<uint64_t>(kCapacitySamples - (head - tail), count);
        for (uint64_t i = 0; i < accepted; ++i) samples_[(head + i) & kMask] = source[i];
        head_.store(head + accepted, std::memory_order_release);
        return static_cast<int32_t>(accepted);
    }
    int32_t read(float* destination, int32_t count) {
        if (!destination || count <= 0) return 0;
        const uint64_t tail = tail_.load(std::memory_order_relaxed);
        const uint64_t head = head_.load(std::memory_order_acquire);
        const uint64_t copied = std::min<uint64_t>(head - tail, count);
        for (uint64_t i = 0; i < copied; ++i) destination[i] = samples_[(tail + i) & kMask];
        tail_.store(tail + copied, std::memory_order_release);
        return static_cast<int32_t>(copied);
    }
    void clear() {
        head_.store(0, std::memory_order_relaxed);
        tail_.store(0, std::memory_order_relaxed);
    }
    uint64_t queuedSamples() const {
        return head_.load(std::memory_order_acquire) - tail_.load(std::memory_order_acquire);
    }
private:
    std::unique_ptr<float[]> samples_;
    alignas(64) std::atomic<uint64_t> head_{0};
    alignas(64) std::atomic<uint64_t> tail_{0};
};
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
    bool pause() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!stream_) return false;
        const auto state = stream_->getState();
        if (state == oboe::StreamState::Paused) return true;
        if (state != oboe::StreamState::Started) return false;
        if (stream_->requestPause() != oboe::Result::OK) return false;
        oboe::StreamState nextState = stream_->getState();
        const auto result = stream_->waitForStateChange(
            oboe::StreamState::Pausing, &nextState, kStateTimeoutNs);
        return result == oboe::Result::OK && nextState == oboe::StreamState::Paused;
    }
    bool resume() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!stream_) return false;
        const auto state = stream_->getState();
        if (state == oboe::StreamState::Started) return true;
        if (state != oboe::StreamState::Paused) return false;
        return stream_->requestStart() == oboe::Result::OK;
    }
    int32_t sampleRate() {
        std::lock_guard<std::mutex> lock(mutex_);
        return stream_ ? stream_->getSampleRate() : 0;
    }
    bool clearBuffer() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (stream_ && stream_->getState() != oboe::StreamState::Paused) return false;
        std::lock_guard<std::mutex> producerLock(producerMutex_);
        ring_.clear();
        return true;
    }
    int32_t writePcm(const float* samples, int32_t count) {
        std::lock_guard<std::mutex> producerLock(producerMutex_);
        return ring_.write(samples, count);
    }
    uint64_t queuedSamples() const { return ring_.queuedSamples(); }
    uint64_t underruns() const { return underruns_.load(std::memory_order_relaxed); }
    void setGain(float gain) { gain_.store(std::clamp(gain, 0.0f, 1.0f), std::memory_order_relaxed); }
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* audioData, int32_t frames) override {
        auto* output = static_cast<float*>(audioData);
        const int32_t requested = frames * 2;
        const int32_t copied = ring_.read(output, requested);
        const float gain = gain_.load(std::memory_order_relaxed);
        if (gain != 1.0f) {
            for (int32_t i = 0; i < copied; ++i) output[i] *= gain;
        }
        if (copied < requested) {
            std::fill(output + copied, output + requested, 0.0f);
            underruns_.fetch_add(1, std::memory_order_relaxed);
        }
        return oboe::DataCallbackResult::Continue;
    }
    bool isOpen() {
        std::lock_guard<std::mutex> lock(mutex_);
        return stream_ != nullptr;
    }
private:
    std::mutex mutex_;
    std::mutex producerMutex_;
    std::shared_ptr<oboe::AudioStream> stream_;
    PcmRingBuffer ring_;
    std::atomic<uint64_t> underruns_{0};
    std::atomic<float> gain_{1.0f};
};
NativeAudioEngine gEngine;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_br_com_celsomartins_audiostretch_AudioNative_nativeOpen(JNIEnv*, jobject) {
    return gEngine.open() == oboe::Result::OK ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL
Java_br_com_celsomartins_audiostretch_AudioNative_nativeClose(JNIEnv*, jobject) { gEngine.close(); }
extern "C" JNIEXPORT jboolean JNICALL
Java_br_com_celsomartins_audiostretch_AudioNative_nativePause(JNIEnv*, jobject) {
    return gEngine.pause() ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_br_com_celsomartins_audiostretch_AudioNative_nativeResume(JNIEnv*, jobject) {
    return gEngine.resume() ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jint JNICALL
Java_br_com_celsomartins_audiostretch_AudioNative_nativeSampleRate(JNIEnv*, jobject) {
    return gEngine.sampleRate();
}
extern "C" JNIEXPORT void JNICALL
Java_br_com_celsomartins_audiostretch_AudioNative_nativeSetGain(JNIEnv*, jobject, jfloat gain) {
    gEngine.setGain(gain);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_br_com_celsomartins_audiostretch_AudioNative_nativeClearBuffer(JNIEnv*, jobject) {
    return gEngine.clearBuffer() ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jint JNICALL
Java_br_com_celsomartins_audiostretch_AudioNative_nativeWritePcm(
        JNIEnv* env, jobject, jfloatArray samples, jint length) {
    if (!samples || length <= 0) return 0;
    const jint requested = std::min<jint>(length, env->GetArrayLength(samples));
    std::array<jfloat, kJniChunkSamples> chunk{};
    jint total = 0;
    while (total < requested) {
        const jint count = std::min<jint>(kJniChunkSamples, requested - total);
        env->GetFloatArrayRegion(samples, total, count, chunk.data());
        if (env->ExceptionCheck()) return total;
        const jint written = gEngine.writePcm(chunk.data(), count);
        total += written;
        if (written < count) break;
    }
    return total;
}
extern "C" JNIEXPORT jlong JNICALL
Java_br_com_celsomartins_audiostretch_AudioNative_nativeQueuedSamples(JNIEnv*, jobject) {
    return static_cast<jlong>(gEngine.queuedSamples());
}
extern "C" JNIEXPORT jstring JNICALL
Java_br_com_celsomartins_audiostretch_AudioNative_nativeStatus(JNIEnv* env, jobject) {
    char status[192];
    std::snprintf(status, sizeof(status),
        "Oboe 1.11.0: %s; taxa: %d Hz; PCM: %llu amostras; underruns: %llu",
        gEngine.isOpen() ? "stream aberto" : "stream fechado", gEngine.sampleRate(),
        static_cast<unsigned long long>(gEngine.queuedSamples()),
        static_cast<unsigned long long>(gEngine.underruns()));
    return env->NewStringUTF(status);
}
