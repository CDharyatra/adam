/**
 * mlc_llm_jni.cpp
 *
 * JNI bridge between Kotlin LlmEngine and the MLC LLM C++ library.
 *
 * Build requirements:
 *   1. Download the MLC LLM Android AAR from https://github.com/mlc-ai/mlc-llm/releases
 *      and place it at app/libs/mlc4j-release.aar
 *   2. The AAR bundles libmlc_llm.so and mlc_llm/llm_chat.h
 *   3. Add to app/build.gradle.kts:
 *        implementation(files("libs/mlc4j-release.aar"))
 *
 * MLC LLM C API reference: https://github.com/mlc-ai/mlc-llm/blob/main/cpp/llm_chat.h
 */

#include <jni.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "AdamLLM"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// MLC LLM C API — provided by the AAR's bundled header.
// The AAR extracts its headers to the build intermediates; CMake finds them
// via the include path set in CMakeLists.txt.
#ifdef MLC_LLM_AVAILABLE
#include "mlc_llm/llm_chat.h"
#endif

extern "C" {

/**
 * Creates an MLC LLM chat engine.
 * @param modelDir     absolute path to the compiled model directory on-device
 * @param systemPrompt system prompt prepended to every conversation
 * @return opaque handle (non-zero on success), or 0 if the library is absent
 */
JNIEXPORT jlong JNICALL
Java_com_adam_assistant_llm_LlmEngine_nativeCreateEngine(
        JNIEnv *env,
        jobject /* this */,
        jstring modelDir,
        jstring systemPrompt) {

#ifdef MLC_LLM_AVAILABLE
    const char *dir = env->GetStringUTFChars(modelDir, nullptr);
    const char *sys = env->GetStringUTFChars(systemPrompt, nullptr);

    try {
        // MLCChat is the public C++ class exposed by the MLC LLM AAR.
        // Reload() loads the compiled model weights and tokenizer from `dir`
        // and seeds the KV-cache with the system prompt.
        auto *chat = new mlc::llm::MLCChat();
        chat->Reload(std::string(dir), std::string(sys));
        env->ReleaseStringUTFChars(modelDir, dir);
        env->ReleaseStringUTFChars(systemPrompt, sys);
        return reinterpret_cast<jlong>(chat);
    } catch (const std::exception &e) {
        LOGE("nativeCreateEngine failed: %s", e.what());
        env->ReleaseStringUTFChars(modelDir, dir);
        env->ReleaseStringUTFChars(systemPrompt, sys);
        return 0L;
    }
#else
    (void)env; (void)modelDir; (void)systemPrompt;
    return 0L; // MLC LLM not linked — Kotlin falls back to regex parser
#endif
}

/**
 * Sends a user message to the loaded model and returns its raw text response.
 * The model is instructed (via the system prompt) to always reply with JSON.
 */
JNIEXPORT jstring JNICALL
Java_com_adam_assistant_llm_LlmEngine_nativeChat(
        JNIEnv *env,
        jobject /* this */,
        jlong handle,
        jstring userMessage) {

    if (handle == 0L) {
        return env->NewStringUTF("{\"action\":\"UNKNOWN\",\"target\":\"\",\"params\":{}}");
    }

#ifdef MLC_LLM_AVAILABLE
    const char *msg = env->GetStringUTFChars(userMessage, nullptr);
    auto *chat = reinterpret_cast<mlc::llm::MLCChat *>(handle);

    try {
        // Generate() runs a single forward pass and returns the decoded string.
        std::string response = chat->Generate(std::string(msg));
        env->ReleaseStringUTFChars(userMessage, msg);
        return env->NewStringUTF(response.c_str());
    } catch (const std::exception &e) {
        LOGE("nativeChat failed: %s", e.what());
        env->ReleaseStringUTFChars(userMessage, msg);
        return env->NewStringUTF("{\"action\":\"UNKNOWN\",\"target\":\"\",\"params\":{}}");
    }
#else
    (void)handle; (void)userMessage;
    return env->NewStringUTF("{\"action\":\"UNKNOWN\",\"target\":\"\",\"params\":{}}");
#endif
}

/**
 * Resets the conversation history without reloading model weights.
 * Call this between independent user sessions to clear the KV-cache.
 */
JNIEXPORT void JNICALL
Java_com_adam_assistant_llm_LlmEngine_nativeResetChat(
        JNIEnv * /* env */,
        jobject /* this */,
        jlong handle) {

#ifdef MLC_LLM_AVAILABLE
    if (handle == 0L) return;
    reinterpret_cast<mlc::llm::MLCChat *>(handle)->ResetChat();
#else
    (void)handle;
#endif
}

/**
 * Destroys the engine and frees all native memory.
 */
JNIEXPORT void JNICALL
Java_com_adam_assistant_llm_LlmEngine_nativeDestroyEngine(
        JNIEnv * /* env */,
        jobject /* this */,
        jlong handle) {

#ifdef MLC_LLM_AVAILABLE
    if (handle == 0L) return;
    delete reinterpret_cast<mlc::llm::MLCChat *>(handle);
#else
    (void)handle;
#endif
}

} // extern "C"
