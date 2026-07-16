#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <whisper.h>
#include <mutex>
#include <chrono>

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "WhisperBridge", __VA_ARGS__)

extern "C" {

static std::mutex g_mutex;
static whisper_context *g_ctx = nullptr;

static void release_context_locked() {
    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }
}

JNIEXPORT void JNICALL
Java_dev_barrycade_voicecore_stt_WhisperBridge_loadModel(JNIEnv *env, jobject /*thiz*/, jstring modelPath) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto tStart = std::chrono::system_clock::now();
    LOGD(
        "loadModel called start t=%lld",
        (long long) std::chrono::duration_cast<std::chrono::milliseconds>(
            tStart.time_since_epoch()
        ).count()
    );

    release_context_locked();

    if (modelPath == nullptr) {
        LOGD("modelPath is null");
        return;
    }

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) {
        LOGD("path is null");
        return;
    }

    LOGD("Loading model from %s", path);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    g_ctx = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(modelPath, path);

    if (g_ctx == nullptr) {
        LOGD("Failed to initialize whisper context");
    } else {
        LOGD("Whisper context loaded");
    }

    auto tEnd = std::chrono::system_clock::now();
    auto dur = std::chrono::duration_cast<std::chrono::milliseconds>(tEnd - tStart).count();
    LOGD(
        "loadModel finished end t=%lld dur=%lld",
        (long long) std::chrono::duration_cast<std::chrono::milliseconds>(
            tEnd.time_since_epoch()
        ).count(),
        (long long) dur
    );
}

JNIEXPORT jstring JNICALL
Java_dev_barrycade_voicecore_stt_WhisperBridge_transcribe(JNIEnv *env, jobject /*thiz*/, jshortArray pcm) {
    auto tStart = std::chrono::system_clock::now();
    LOGD(
        "transcribe called start t=%lld",
        (long long) std::chrono::duration_cast<std::chrono::milliseconds>(
            tStart.time_since_epoch()
        ).count()
    );
    LOGD("transcribe called");
    if (pcm == nullptr) {
        LOGD("pcm is null");
        return env->NewStringUTF("");
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx == nullptr) {
        LOGD("transcribe called without loaded model");
        return env->NewStringUTF("");
    }

    jsize length = env->GetArrayLength(pcm);
    LOGD("PCM length: %d", length);
    if (length <= 0) {
        return env->NewStringUTF("");
    }

    jshort *samples = env->GetShortArrayElements(pcm, nullptr);
    if (samples == nullptr) {
        LOGD("Failed to get samples");
        return env->NewStringUTF("");
    }

    std::vector<float> pcmf32(length);
    for (jsize i = 0; i < length; ++i) {
        pcmf32[i] = static_cast<float>(samples[i]) / 32768.0f;
    }
    env->ReleaseShortArrayElements(pcm, samples, JNI_ABORT);

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress = false;
    wparams.print_realtime = false;
    wparams.translate = false;
    wparams.no_context = true;      // standalone utterance — no context accumulation across calls
    wparams.max_tokens = 128;       // or -1 for full decode

    LOGD("Starting whisper_full");
    int result = whisper_full(g_ctx, wparams, pcmf32.data(), static_cast<int>(pcmf32.size()));
    LOGD("whisper_full returned %d", result);

    std::string text;
    if (result == 0) {
        const int n_segments = whisper_full_n_segments(g_ctx);
        LOGD("Number of segments: %d", n_segments);
        for (int i = 0; i < n_segments; ++i) {
            const char *segment = whisper_full_get_segment_text(g_ctx, i);
            if (segment != nullptr) {
                text += segment;
            }
        }
    }

    auto tEnd = std::chrono::system_clock::now();
    auto dur = std::chrono::duration_cast<std::chrono::milliseconds>(tEnd - tStart).count();
    LOGD(
        "transcribe finished end t=%lld dur=%lld",
        (long long) std::chrono::duration_cast<std::chrono::milliseconds>(
            tEnd.time_since_epoch()
        ).count(),
        (long long) dur
    );

    if (text.empty()) {
        LOGD("Result text is empty");
        return env->NewStringUTF("");
    }

    LOGD("Result text: %s", text.c_str());
    return env->NewStringUTF(text.c_str());
}

JNIEXPORT void JNICALL
Java_dev_barrycade_voicecore_stt_WhisperBridge_unloadModel(JNIEnv * /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    release_context_locked();
    LOGD("Model unloaded");
}

}  // extern "C"
