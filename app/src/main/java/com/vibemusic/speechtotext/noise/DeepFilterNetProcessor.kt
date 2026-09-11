package com.vibemusic.speechtotext.noise

import android.content.Context
import android.util.Log
import java.io.File

/**
 * DeepFilterNet Noise Suppressor integration.
 *
 * Provides real-time deep-learning-based noise suppression. If the compiled native library
 * (libdeepfilter_jni.so) or model file is absent, it transparently routes through the
 * fallback filter and signals its availability status to the UI.
 */
class DeepFilterNetProcessor(
    private val context: Context,
    private val modelAssetPath: String = "deepfilter_model"
) : NoiseSuppressor {

    companion object {
        private const val TAG = "DeepFilterNetProcessor"
    }

    override val name: String = "DeepFilterNet"

    override var isAvailable: Boolean = false
        private set

    override var isEnabled: Boolean = false
        set(value) {
            field = value
            fallback.isEnabled = value
        }

    override var statusDescription: String = "Not initialized"
        private set

    private var nativeHandle: Long = 0L
    private val fallback = PassthroughNoiseSuppressor()

    override fun initialize(): Boolean {
        fallback.initialize()

        // 1. Verify native library presence
        if (!NativeNoiseProcessor.isLibraryLoaded()) {
            isAvailable = false
            statusDescription = "Noise suppression is unavailable (libdeepfilter_jni.so not present). Using fallback."
            Log.w(TAG, statusDescription)
            return false
        }

        // 2. Verify model file presence
        val modelFile = File(context.filesDir, modelAssetPath)
        if (!modelFile.exists()) {
            // Check if model file can be unpacked from assets
            val hasAsset = try {
                context.assets.list(modelAssetPath)?.isNotEmpty() == true
            } catch (_: Exception) {
                false
            }

            if (!hasAsset) {
                isAvailable = false
                statusDescription = "DeepFilterNet model file ($modelAssetPath) is missing. Using fallback."
                Log.w(TAG, statusDescription)
                return false
            }
        }

        // 3. Initialize native model handle
        return try {
            nativeHandle = NativeNoiseProcessor.nativeInit(modelFile.absolutePath)
            if (nativeHandle != 0L) {
                isAvailable = true
                isEnabled = true
                statusDescription = "DeepFilterNet active"
                Log.i(TAG, "DeepFilterNet initialized successfully with native handle $nativeHandle")
                true
            } else {
                isAvailable = false
                statusDescription = "Failed to initialize DeepFilterNet native model instance."
                false
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error initializing DeepFilterNet native library", e)
            isAvailable = false
            statusDescription = "DeepFilterNet error: ${e.localizedMessage ?: "Native init failed"}"
            false
        }
    }

    override fun process(pcmFrame: ShortArray): ShortArray {
        if (!isAvailable || !isEnabled || nativeHandle == 0L) {
            return fallback.process(pcmFrame)
        }

        return try {
            val output = ShortArray(pcmFrame.size)
            val result = NativeNoiseProcessor.nativeProcess(nativeHandle, pcmFrame, output)
            if (result == 0) output else fallback.process(pcmFrame)
        } catch (e: Exception) {
            Log.e(TAG, "Error in native DeepFilterNet processing", e)
            fallback.process(pcmFrame)
        }
    }

    override fun release() {
        if (nativeHandle != 0L) {
            try {
                NativeNoiseProcessor.nativeFree(nativeHandle)
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing DeepFilterNet native handle", e)
            } finally {
                nativeHandle = 0L
            }
        }
        fallback.release()
        isAvailable = false
        statusDescription = "Released"
    }
}
