package com.vibemusic.speechtotext.noise

import android.util.Log

/**
 * JNI bridge declarations for the native DeepFilterNet Rust / C++ shared library (libdeepfilter_jni.so).
 *
 * Checks at runtime whether the compiled native library is present in apk/jniLibs.
 * If not present, gracefully marks isLibraryAvailable = false without throwing UnsatisfiedLinkError crashes.
 */
object NativeNoiseProcessor {
    private const val TAG = "NativeNoiseProcessor"
    private const val LIB_NAME = "deepfilter_jni"

    private var isLibraryAvailable = false

    init {
        try {
            System.loadLibrary(LIB_NAME)
            isLibraryAvailable = true
            Log.i(TAG, "Successfully loaded native library: lib$LIB_NAME.so")
        } catch (_: UnsatisfiedLinkError) {
            isLibraryAvailable = false
            Log.i(TAG, "Native library lib$LIB_NAME.so is not present. Operating with fallback noise suppressor.")
        } catch (e: Exception) {
            isLibraryAvailable = false
            Log.e(TAG, "Unexpected error loading lib$LIB_NAME.so", e)
        }
    }

    /**
     * @return true if libdeepfilter_jni.so is present and loaded.
     */
    fun isLibraryLoaded(): Boolean = isLibraryAvailable

    // JNI bindings to Rust / C++ DeepFilterNet implementation
    external fun nativeInit(modelPath: String): Long
    external fun nativeProcess(handle: Long, inputSamples: ShortArray, outputSamples: ShortArray): Int
    external fun nativeFree(handle: Long)
}
