// JNI bridge between Kotlin (com.variableduck.inkwell.transcriber.whisper.WhisperLib) and whisper.cpp.
// Based on whisper.cpp/examples/whisper.android/lib/src/main/jni/whisper/jni.c (MIT).

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <mutex>
#include <string>

#include "ggml-backend.h"
#include "whisper.h"

#define TAG "InkwellNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

std::atomic<bool> g_abort{false};
std::once_flag g_backends_once;

void log_callback(enum ggml_log_level level, const char *text, void * /*user_data*/) {
    int prio;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO;  break;
        default: return; // drop debug/continuation noise
    }
    __android_log_write(prio, TAG, text);
}

bool abort_callback(void * /*user_data*/) {
    return g_abort.load(std::memory_order_relaxed);
}

std::string jstring_to_std(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars != nullptr ? chars : "";
    if (chars != nullptr) env->ReleaseStringUTFChars(s, chars);
    return out;
}

jbyteArray to_byte_array(JNIEnv *env, const std::string &s) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(s.size()));
    if (arr != nullptr && !s.empty()) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(s.size()),
                                reinterpret_cast<const jbyte *>(s.data()));
    }
    return arr;
}

} // namespace

extern "C" {

// Loads the ggml CPU backend best suited to this phone from the app's native library dir.
// Must be called once before any model is loaded. Returns whisper's system info string.
JNIEXPORT jstring JNICALL
Java_com_variableduck_inkwell_transcriber_whisper_WhisperLib_nativeInit(
        JNIEnv *env, jclass /*clazz*/, jstring native_lib_dir) {
    std::string dir = jstring_to_std(env, native_lib_dir);
    std::call_once(g_backends_once, [&dir]() {
        whisper_log_set(log_callback, nullptr);
        ggml_log_set(log_callback, nullptr);
        ggml_backend_load_all_from_path(dir.c_str());
        LOGI("ggml backends loaded from %s, devices: %zu", dir.c_str(), ggml_backend_dev_count());
    });
    std::string info = whisper_print_system_info();
    return env->NewStringUTF(info.c_str());
}

JNIEXPORT jint JNICALL
Java_com_variableduck_inkwell_transcriber_whisper_WhisperLib_nativeDeviceCount(
        JNIEnv * /*env*/, jclass /*clazz*/) {
    return static_cast<jint>(ggml_backend_dev_count());
}

JNIEXPORT jlong JNICALL
Java_com_variableduck_inkwell_transcriber_whisper_WhisperLib_nativeInitContext(
        JNIEnv *env, jclass /*clazz*/, jstring model_path) {
    std::string path = jstring_to_std(env, model_path);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    if (ctx == nullptr) {
        LOGW("Failed to load model %s", path.c_str());
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_variableduck_inkwell_transcriber_whisper_WhisperLib_nativeFreeContext(
        JNIEnv * /*env*/, jclass /*clazz*/, jlong ctx_ptr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctx_ptr);
    if (ctx != nullptr) whisper_free(ctx);
}

JNIEXPORT jboolean JNICALL
Java_com_variableduck_inkwell_transcriber_whisper_WhisperLib_nativeIsMultilingual(
        JNIEnv * /*env*/, jclass /*clazz*/, jlong ctx_ptr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctx_ptr);
    return ctx != nullptr && whisper_is_multilingual(ctx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_variableduck_inkwell_transcriber_whisper_WhisperLib_nativeSetAbort(
        JNIEnv * /*env*/, jclass /*clazz*/, jboolean abort) {
    g_abort.store(abort == JNI_TRUE, std::memory_order_relaxed);
}

// Transcribes one chunk of 16 kHz mono float PCM. Returns 0 on success.
// language: "auto" or an ISO code such as "en". prompt: tail of the previous chunk's text
// (may be empty) so sentences join up across chunks.
JNIEXPORT jint JNICALL
Java_com_variableduck_inkwell_transcriber_whisper_WhisperLib_nativeTranscribe(
        JNIEnv *env, jclass /*clazz*/, jlong ctx_ptr, jfloatArray audio,
        jint n_threads, jstring language, jstring prompt) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctx_ptr);
    if (ctx == nullptr || audio == nullptr) return -100;

    std::string lang = jstring_to_std(env, language);
    std::string initial_prompt = jstring_to_std(env, prompt);
    if (lang.empty()) lang = "auto";

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = n_threads > 0 ? n_threads : 4;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = lang.c_str();
    params.detect_language = false;
    // Context across chunks is supplied explicitly via initial_prompt.
    params.no_context = true;
    params.single_segment = false;
    params.suppress_blank = true;
    // Keep punctuation and capitalisation exactly as Whisper produces them.
    params.suppress_nst = false;
    params.initial_prompt = initial_prompt.empty() ? nullptr : initial_prompt.c_str();
    params.abort_callback = abort_callback;
    params.abort_callback_user_data = nullptr;

    jsize n_samples = env->GetArrayLength(audio);
    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    if (samples == nullptr) return -101;

    whisper_reset_timings(ctx);
    int rc = whisper_full(ctx, params, samples, n_samples);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);

    if (rc != 0) {
        LOGW("whisper_full returned %d", rc);
    }
    return rc;
}

// Text of all segments from the last transcription, as UTF-8 bytes. Returned as bytes rather
// than a jstring because NewStringUTF aborts on the invalid UTF-8 whisper can emit when a
// multi-byte character is split across tokens.
JNIEXPORT jbyteArray JNICALL
Java_com_variableduck_inkwell_transcriber_whisper_WhisperLib_nativeGetText(
        JNIEnv *env, jclass /*clazz*/, jlong ctx_ptr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctx_ptr);
    std::string out;
    if (ctx != nullptr) {
        const int n = whisper_full_n_segments(ctx);
        for (int i = 0; i < n; ++i) {
            const char *text = whisper_full_get_segment_text(ctx, i);
            if (text != nullptr) out += text;
        }
    }
    return to_byte_array(env, out);
}

JNIEXPORT jstring JNICALL
Java_com_variableduck_inkwell_transcriber_whisper_WhisperLib_nativeVersion(
        JNIEnv *env, jclass /*clazz*/) {
    return env->NewStringUTF(whisper_version());
}

} // extern "C"
