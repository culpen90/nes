// SPDX-License-Identifier: GPL-2.0-or-later
#include "nes_session.h"
#include <jni.h>
#include <new>
#include <stdexcept>

namespace {
std::string toString(JNIEnv* env, jstring text) {
    if (!text) return {};
    const char* chars = env->GetStringUTFChars(text, nullptr);
    if (!chars) return {};
    try {
        std::string result(chars);
        env->ReleaseStringUTFChars(text, chars);
        return result;
    } catch (...) {
        env->ReleaseStringUTFChars(text, chars);
        throw;
    }
}
void throwMemoryError(JNIEnv* env) {
    jclass type = env->FindClass("java/lang/OutOfMemoryError");
    if (type) env->ThrowNew(type, "Not enough memory for the emulator.");
}
jbyteArray byteArray(JNIEnv* env, const std::vector<uint8_t>& bytes) {
    if (bytes.empty()) return nullptr;
    jbyteArray array = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (array) env->SetByteArrayRegion(array, 0, static_cast<jsize>(bytes.size()),
                                      reinterpret_cast<const jbyte*>(bytes.data()));
    return array;
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_culpen_nes_NativeNes_load(JNIEnv* env, jclass, jstring path,
                                 jstring system, jstring saves) {
    try {
        const std::string romPath = toString(env, path);
        const std::string systemDir = toString(env, system);
        const std::string saveDir = toString(env, saves);
        if (env->ExceptionCheck()) return nullptr;
        const std::string error = nes::load(romPath, systemDir, saveDir);
        return error.empty() ? nullptr : env->NewStringUTF(error.c_str());
    } catch (const std::bad_alloc&) {
        nes::unload();
        return env->NewStringUTF("Not enough memory to load the ROM.");
    } catch (const std::exception&) {
        nes::unload();
        return env->NewStringUTF("The emulator could not load this ROM.");
    }
}
extern "C" JNIEXPORT void JNICALL
Java_com_culpen_nes_NativeNes_unload(JNIEnv*, jclass) { nes::unload(); }
extern "C" JNIEXPORT void JNICALL
Java_com_culpen_nes_NativeNes_reset(JNIEnv*, jclass) { nes::reset(); }
extern "C" JNIEXPORT jdouble JNICALL
Java_com_culpen_nes_NativeNes_fps(JNIEnv*, jclass) { return nes::fps(); }
extern "C" JNIEXPORT jint JNICALL
Java_com_culpen_nes_NativeNes_sampleRate(JNIEnv*, jclass) { return nes::sampleRate(); }
extern "C" JNIEXPORT jint JNICALL
Java_com_culpen_nes_NativeNes_width(JNIEnv*, jclass) { return nes::width(); }
extern "C" JNIEXPORT jint JNICALL
Java_com_culpen_nes_NativeNes_height(JNIEnv*, jclass) { return nes::height(); }
extern "C" JNIEXPORT void JNICALL
Java_com_culpen_nes_NativeNes_runFrame(JNIEnv* env, jclass, jint buttons) {
    try { nes::runFrame(static_cast<uint16_t>(buttons)); }
    catch (const std::bad_alloc&) { throwMemoryError(env); }
}
extern "C" JNIEXPORT void JNICALL
Java_com_culpen_nes_NativeNes_copyVideo(JNIEnv* env, jclass, jintArray pixels) {
    if (!pixels) return;
    const jsize capacity = env->GetArrayLength(pixels);
    if (capacity < nes::width() * nes::height()) {
        jclass type = env->FindClass("java/lang/IllegalArgumentException");
        if (type) env->ThrowNew(type, "Video buffer is smaller than the current frame.");
        return;
    }
    jint* target = env->GetIntArrayElements(pixels, nullptr);
    if (!target) return;
    nes::copyVideo(reinterpret_cast<int32_t*>(target), static_cast<size_t>(capacity));
    env->ReleaseIntArrayElements(pixels, target, 0);
}
extern "C" JNIEXPORT jint JNICALL
Java_com_culpen_nes_NativeNes_drainAudio(JNIEnv* env, jclass, jshortArray samples) {
    if (!samples) return 0;
    const jsize capacity = env->GetArrayLength(samples);
    jshort* target = env->GetShortArrayElements(samples, nullptr);
    if (!target) return 0;
    const size_t copied = nes::drainAudio(reinterpret_cast<int16_t*>(target), static_cast<size_t>(capacity));
    env->ReleaseShortArrayElements(samples, target, 0);
    return static_cast<jint>(copied);
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_culpen_nes_NativeNes_saveState(JNIEnv* env, jclass) {
    try { return byteArray(env, nes::saveState()); }
    catch (const std::bad_alloc&) { throwMemoryError(env); return nullptr; }
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_culpen_nes_NativeNes_loadState(JNIEnv* env, jclass, jbyteArray state) {
    if (!state) return JNI_FALSE;
    const jsize size = env->GetArrayLength(state);
    if (size < 16 || size > 4 * 1024 * 1024) return JNI_FALSE;
    jbyte* bytes = env->GetByteArrayElements(state, nullptr);
    if (!bytes) return JNI_FALSE;
    try {
        const bool success = nes::loadState(reinterpret_cast<uint8_t*>(bytes), static_cast<size_t>(size));
        env->ReleaseByteArrayElements(state, bytes, JNI_ABORT);
        return success ? JNI_TRUE : JNI_FALSE;
    } catch (const std::bad_alloc&) {
        env->ReleaseByteArrayElements(state, bytes, JNI_ABORT);
        throwMemoryError(env);
        return JNI_FALSE;
    }
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_culpen_nes_NativeNes_saveRam(JNIEnv* env, jclass) {
    try { return byteArray(env, nes::saveRam()); }
    catch (const std::bad_alloc&) { throwMemoryError(env); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL
Java_com_culpen_nes_NativeNes_loadRam(JNIEnv* env, jclass, jbyteArray ram) {
    if (!ram) return;
    const jsize size = env->GetArrayLength(ram);
    if (!size || size > 32 * 1024 * 1024) return;
    jbyte* bytes = env->GetByteArrayElements(ram, nullptr);
    if (!bytes) return;
    nes::loadRam(reinterpret_cast<uint8_t*>(bytes), static_cast<size_t>(size));
    env->ReleaseByteArrayElements(ram, bytes, JNI_ABORT);
}
