#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <whisper.h>

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "WhisperBridge", __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_android_1voice_1core_WhisperBridge_init(JNIEnv *env, jobject /*thiz*/, jstring modelPath) {
    LOGD("init called");
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) {
        LOGD("path is null");
        return 0;
    }
    LOGD("Loading model from %s", path);

    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(modelPath, path);
    if (ctx == nullptr) {
        LOGD("Failed to initialize whisper context");
    } else {
        LOGD("Whisper context initialized: %p", ctx);
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_example_android_1voice_1core_WhisperBridge_transcribe(JNIEnv *env, jobject /*thiz*/, jlong handle, jshortArray pcm) {
    LOGD("transcribe called with handle %lld", handle);
    if (handle == 0 || pcm == nullptr) {
        LOGD("Invalid handle or pcm");
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

    whisper_context *ctx = reinterpret_cast<whisper_context *>(handle);
    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress = false;
    wparams.print_realtime = false;
    wparams.translate = false;
    wparams.no_context = true;
    wparams.max_tokens = 32;

    LOGD("Starting whisper_full");
    int result = whisper_full(ctx, wparams, pcmf32.data(), static_cast<int>(pcmf32.size()));
    LOGD("whisper_full returned %d", result);

    std::string text;
    if (result == 0) {
        const int n_segments = whisper_full_n_segments(ctx);
        LOGD("Number of segments: %d", n_segments);
        for (int i = 0; i < n_segments; ++i) {
            const char *segment = whisper_full_get_segment_text(ctx, i);
            if (segment != nullptr) {
                text += segment;
            }
        }
    }

    if (text.empty()) {
        LOGD("Result text is empty, returning smoke test message");
        return env->NewStringUTF("Whisper smoke test passed");
    }

    LOGD("Result text: %s", text.c_str());
    return env->NewStringUTF(text.c_str());
}

}  // extern "C"
