#include <jni.h>
#include <oboe/Oboe.h>

#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <algorithm>

namespace {
constexpr uint64_t kCapacitySamples = 1u << 17; // 65536 stereo frames
constexpr uint64_t kMask = kCapacitySamples - 1;
constexpr int32_t kJniChunkSamples = 2048;

// Single-producer/single-consumer ring. The decoder thread writes; Oboe reads.
// No locks, allocations, or JNI calls occur in the real-time audio callback.
class PcmRingBuffer {
public:
    PcmRingBuffer() : samples_(new float[kCapacitySamples]()) {}

    int32_t write(const float* source, int32_t count) {
        if (!source || count <= 0) return 0;
        const uint64_t head = head_.load(std::memory_order_relaxed);
        const uint64_t tail = tail_.load(std::memory_order_acquire);
        const uint64_t available = kCapacitySamples - (head - tail);
        const uint64_t accepted = std::min<uint64_t>(available, static_cast<uint64_t>(count));
        for (uint64_t i = 0; i < accepted; ++i) {
            samples_[(head + i) & kMask] = source[i];
        }
        head_.store(head + accepted, std::memory_order_release);
        return static_cast<int32_t>(accepted);
    }

    int32_t read(float* destination, int32_t count) {
        if (!destination || count <= 0) return 0;
        const uint64_t tail = tail_.load(std::memory_order_relaxed);
        const uint64_t head = head_.load(std::memory_order_acquire);
        const uint64_t available = head - tail;
        const uint64_t copied = std::min<uint64_t>(available, static_cast<uint64_t>(count));
        for (uint64_t i = 0; i < copied; ++i) {
            destination[i] = samples_[(tail + i) & kMask];
        }
        tail_.store(tail + copied, std::memory_order_release);
        return static_cast<int32_t>(copied);
    }

    uint64_t queuedSamples() const {
        return head_.load(std::memory_order_acquire) -
               tail_.load(std::memory_order_acquire);
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

    int32_t writePcm(const float* samples, int32_t count) {
        return ring_.write(samples, count);
    }

    uint64_t queuedSamples() const { return ring_.queuedSamples(); }
    uint64_t underruns() const { return underruns_.load(std::memory_order_relaxed); }

    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream*, void* audioData, int32_t numFrames) override {
        auto* output = static_cast<float*>(audioData);
        const int32_t requested = numFrames * 2; // Oboe stream is stereo Float.
        const int32_t copied = ring_.read(output, requested);
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
    std::shared_ptr<oboe::AudioStream> stream_;
    PcmRingBuffer ring_;
    std::atomic<uint64_t> underruns_{0};
};

NativeAudioEngine gEngine;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_br_com_celsomartins_audiostretch_AudioNative_nativeOpen(JNIEnv*, jobject) {
    return gEngine.open() == oboe::Result::OK ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_br_com_celsomartins_audiostretch_AudioNative_nativeClose(JNIEnv*, jobject) {
    gEngine.close();
}

extern "C" JNIEXPORT jint JNICALL
Java_br_com_celsomartins_audiostretch_AudioNative_nativeWritePcm(
        JNIEnv* env, jobject, jfloatArray samples, jint length) {
    if (samples == nullptr || length <= 0) return 0;
    const jsize arrayLength = env->GetArrayLength(samples);
    const jint requested = std::min<jint>(length, arrayLength);
    std::array<jfloat, kJniChunkSamples> chunk{};
    jint totalWritten = 0;
    while (totalWritten < requested) {
        const jint count = std::min<jint>(kJniChunkSamples, requested - totalWritten);
        env->GetFloatArrayRegion(samples, totalWritten, count, chunk.data());
        if (env->ExceptionCheck()) return totalWritten;
        const int32_t written = gEngine.writePcm(chunk.data(), count);
        totalWritten += written;
        if (written < count) break; // Caller retries after the callback drains data.
    }
    return totalWritten;
}

extern "C" JNIEXPORT jlong JNICALL
Java_br_com_celsomartins_audiostretch_AudioNative_nativeQueuedSamples(JNIEnv*, jobject) {
    return static_cast<jlong>(gEngine.queuedSamples());
}

extern "C" JNIEXPORT jstring JNICALL
Java_br_com_celsomartins_audiostretch_AudioNative_nativeStatus(JNIEnv* env, jobject) {
    char status[160];
    std::snprintf(status, sizeof(status),
        "Oboe 1.11.0: %s; PCM em buffer: %llu amostras; underruns: %llu",
        gEngine.isOpen() ? "stream aberto" : "stream fechado",
        static_cast<unsigned long long>(gEngine.queuedSamples()),
        static_cast<unsigned long long>(gEngine.underruns()));
    return env->NewStringUTF(status);
}
