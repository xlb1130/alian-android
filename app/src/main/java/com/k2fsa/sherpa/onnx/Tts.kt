// Copyright (c)  2023  Xiaomi Corporation
package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager
import android.util.Log

data class OfflineTtsVitsModelConfig(
    var model: String = "",
    var lexicon: String = "",
    var tokens: String = "",
    var dataDir: String = "",
    var dictDir: String = "", // unused
    var noiseScale: Float = 0.667f,
    var noiseScaleW: Float = 0.8f,
    var lengthScale: Float = 1.0f,
)

data class OfflineTtsMatchaModelConfig(
    var acousticModel: String = "",
    var vocoder: String = "",
    var lexicon: String = "",
    var tokens: String = "",
    var dataDir: String = "",
    var dictDir: String = "", // unused
    var noiseScale: Float = 1.0f,
    var lengthScale: Float = 1.0f,
)

data class OfflineTtsKokoroModelConfig(
    var model: String = "",
    var voices: String = "",
    var tokens: String = "",
    var dataDir: String = "",
    var lexicon: String = "",
    var lang: String = "",
    var dictDir: String = "", // unused
    var lengthScale: Float = 1.0f,
)

data class OfflineTtsKittenModelConfig(
    var model: String = "",
    var voices: String = "",
    var tokens: String = "",
    var dataDir: String = "",
    var lengthScale: Float = 1.0f,
)

data class OfflineTtsZipvoiceModelConfig(
    var tokens: String = "",
    var encoder: String = "",
    var decoder: String = "",
    var vocoder: String = "",
    var dataDir: String = "",
    var lexicon: String = "",
    var featScale: Float = 0.1f,
    var tShift: Float = 0.5f,
    var targetRms: Float = 0.1f,
    var guidanceScale: Float = 1.0f,
)

/**
 * Configuration for Pocket TTS models.
 *
 * See https://k2-fsa.github.io/sherpa/onnx/tts/pocket/index.html for details.
 *
 * @property lmFlow Path to the LM flow model (.onnx)
 * @property lmMain Path to the LM main model (.onnx)
 * @property encoder Path to the encoder model (.onnx)
 * @property decoder Path to the decoder model (.onnx)
 * @property textConditioner Path to the text conditioner model (.onnx)
 * @property vocabJson Path to vocabulary JSON file
 * @property tokenScoresJson Path to token scores JSON file
 */
data class OfflineTtsPocketModelConfig(
  var lmFlow: String = "",
  var lmMain: String = "",
  var encoder: String = "",
  var decoder: String = "",
  var textConditioner: String = "",
  var vocabJson: String = "",
  var tokenScoresJson: String = "",
  var voiceEmbeddingCacheCapacity: Int = 50,
)

data class OfflineTtsModelConfig(
    var vits: OfflineTtsVitsModelConfig = OfflineTtsVitsModelConfig(),
    var matcha: OfflineTtsMatchaModelConfig = OfflineTtsMatchaModelConfig(),
    var kokoro: OfflineTtsKokoroModelConfig = OfflineTtsKokoroModelConfig(),
    var zipvoice: OfflineTtsZipvoiceModelConfig = OfflineTtsZipvoiceModelConfig(),
    var kitten: OfflineTtsKittenModelConfig = OfflineTtsKittenModelConfig(),
    val pocket: OfflineTtsPocketModelConfig = OfflineTtsPocketModelConfig(),

    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
)

data class OfflineTtsConfig(
    var model: OfflineTtsModelConfig = OfflineTtsModelConfig(),
    var ruleFsts: String = "",
    var ruleFars: String = "",
    var maxNumSentences: Int = 1,
    var silenceScale: Float = 0.2f,
)

class GeneratedAudio(
    val samples: FloatArray,
    val sampleRate: Int,
) {
    fun save(filename: String) =
        saveImpl(filename = filename, samples = samples, sampleRate = sampleRate)

    private external fun saveImpl(
        filename: String,
        samples: FloatArray,
        sampleRate: Int
    ): Boolean
}

data class GenerationConfig(
    var silenceScale: Float = 0.2f,
    var speed: Float = 1.0f,
    var sid: Int = 0,
    var referenceAudio: FloatArray? = null,
    var referenceSampleRate: Int = 0,
    var referenceText: String? = null,
    var numSteps: Int = 5,
    var extra: Map<String, String>? = null
)

class OfflineTts(
    assetManager: AssetManager? = null,
    var config: OfflineTtsConfig,
) {
    private val lock = Any()
    private var ptr: Long

    init {
        if (!ensureNativeLoaded()) {
            throw IllegalStateException(
                "sherpa-onnx native load failed",
                nativeLoadError
            )
        }
        ptr = if (assetManager != null) {
            newFromAsset(assetManager, config)
        } else {
            newFromFile(config)
        }
    }

    fun sampleRate(): Int = synchronized(lock) {
        getSampleRate(currentPtrLocked())
    }

    fun numSpeakers(): Int = synchronized(lock) {
        getNumSpeakers(currentPtrLocked())
    }

    fun generate(
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f
    ): GeneratedAudio = synchronized(lock) {
        generateImpl(currentPtrLocked(), text = text, sid = sid, speed = speed)
    }

    fun generateWithCallback(
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f,
        callback: (samples: FloatArray) -> Int
    ): GeneratedAudio = synchronized(lock) {
        generateWithCallbackImpl(
            currentPtrLocked(),
            text = text,
            sid = sid,
            speed = speed,
            callback = callback
        )
    }

    fun generateWithConfig(
      text: String,
      config: GenerationConfig
    ): GeneratedAudio = synchronized(lock) {
        generateWithConfigImpl(currentPtrLocked(), text, config, null)
    }

    fun generateWithConfigAndCallback(
        text: String,
        config: GenerationConfig,
        callback: (samples: FloatArray) -> Int
    ): GeneratedAudio = synchronized(lock) {
        generateWithConfigImpl(currentPtrLocked(), text, config, callback)
    }

    fun allocate(assetManager: AssetManager? = null) {
        synchronized(lock) {
            if (ptr == 0L) {
                if (!ensureNativeLoaded()) {
                    throw IllegalStateException(
                        "sherpa-onnx native load failed",
                        nativeLoadError
                    )
                }
                ptr = if (assetManager != null) {
                    newFromAsset(assetManager, config)
                } else {
                    newFromFile(config)
                }
            }
        }
    }

    fun free() {
        synchronized(lock) {
            if (ptr != 0L) {
                delete(ptr)
                ptr = 0
            }
        }
    }

    protected fun finalize() {
        free()
    }

    fun release() = free()

    private fun currentPtrLocked(): Long {
        val current = ptr
        if (current == 0L) {
            throw IllegalStateException("OfflineTts has been released")
        }
        return current
    }

    private external fun newFromAsset(
        assetManager: AssetManager,
        config: OfflineTtsConfig,
    ): Long

    private external fun newFromFile(
        config: OfflineTtsConfig,
    ): Long

    private external fun delete(ptr: Long)
    private external fun getSampleRate(ptr: Long): Int
    private external fun getNumSpeakers(ptr: Long): Int

    // The returned array has two entries:
    //  - the first entry is an 1-D float array containing audio samples.
    //    Each sample is normalized to the range [-1, 1]
    //  - the second entry is the sample rate
    private external fun generateImpl(
        ptr: Long,
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f
    ): GeneratedAudio

    private external fun generateWithCallbackImpl(
        ptr: Long,
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f,
        callback: (samples: FloatArray) -> Int
    ): GeneratedAudio


    private external fun generateWithConfigImpl(
        ptr: Long,
        text: String,
        config: GenerationConfig,
        callback: ((samples: FloatArray) -> Int)?
    ): GeneratedAudio

    companion object {
        private const val TAG = "SherpaOnnxTts"

        @Volatile
        private var nativeLoaded = false

        @Volatile
        private var nativeLoadError: Throwable? = null

        @Synchronized
        @JvmStatic
        fun ensureNativeLoaded(): Boolean {
            if (nativeLoaded) return true
            if (nativeLoadError != null) return false
            return try {
                System.loadLibrary("sherpa-onnx-jni")
                nativeLoaded = true
                true
            } catch (t: Throwable) {
                nativeLoadError = t
                Log.e(TAG, "Failed to load sherpa-onnx-jni", t)
                false
            }
        }

        @JvmStatic
        fun nativeLoadErrorMessage(): String? {
            val error = nativeLoadError ?: return null
            val message = error.message?.trim().orEmpty()
            return if (message.isNotEmpty()) {
                "${error::class.java.simpleName}: $message"
            } else {
                error.toString()
            }
        }
    }
}

// please refer to
// https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/index.html
// to download models
fun getOfflineTtsConfig(
    modelDir: String,
    modelName: String, // for VITS
    acousticModelName: String, // for Matcha
    vocoder: String, // for Matcha
    voices: String, // for Kokoro or kitten
    lexicon: String,
    dataDir: String,
    dictDir: String, // unused
    ruleFsts: String,
    ruleFars: String,
    numThreads: Int? = null,
    isKitten: Boolean = false
): OfflineTtsConfig {
    // For Matcha TTS, please set
    // acousticModelName, vocoder

    // For Kokoro TTS, please set
    // modelName, voices

    // For Kitten TTS, please set
    // modelName, voices, isKitten

    // For VITS, please set
    // modelName

    val numberOfThreads = if (numThreads != null) {
        numThreads
    } else if (voices.isNotEmpty()) {
        // for Kokoro and Kitten TTS models, we use more threads
        4
    } else {
        2
    }

    if (modelName.isEmpty() && acousticModelName.isEmpty()) {
        throw IllegalArgumentException("Please specify a TTS model")
    }

    if (modelName.isNotEmpty() && acousticModelName.isNotEmpty()) {
        throw IllegalArgumentException("Please specify either a VITS or a Matcha model, but not both")
    }

    if (acousticModelName.isNotEmpty() && vocoder.isEmpty()) {
        throw IllegalArgumentException("Please provide vocoder for Matcha TTS")
    }

    val vits = if (modelName.isNotEmpty() && voices.isEmpty()) {
        OfflineTtsVitsModelConfig(
            model = "$modelDir/$modelName",
            lexicon = "$modelDir/$lexicon",
            tokens = "$modelDir/tokens.txt",
            dataDir = dataDir,
            dictDir = dictDir,
        )
    } else {
        OfflineTtsVitsModelConfig()
    }

    val matcha = if (acousticModelName.isNotEmpty()) {
        OfflineTtsMatchaModelConfig(
            acousticModel = "$modelDir/$acousticModelName",
            vocoder = vocoder,
            lexicon = "$modelDir/$lexicon",
            tokens = "$modelDir/tokens.txt",
            dataDir = dataDir,
            dictDir = dictDir,
        )
    } else {
        OfflineTtsMatchaModelConfig()
    }

    val kokoro = if (voices.isNotEmpty() && !isKitten) {
        OfflineTtsKokoroModelConfig(
            model = "$modelDir/$modelName",
            voices = "$modelDir/$voices",
            tokens = "$modelDir/tokens.txt",
            dataDir = dataDir,
            lexicon = when {
                lexicon == "" -> lexicon
                "," in lexicon -> lexicon
                else -> "$modelDir/$lexicon"
            },
            dictDir = dictDir,
        )
    } else {
        OfflineTtsKokoroModelConfig()
    }

    val kitten = if (isKitten) {
        OfflineTtsKittenModelConfig(
            model = "$modelDir/$modelName",
            voices = "$modelDir/$voices",
            tokens = "$modelDir/tokens.txt",
            dataDir = dataDir,
        )
    } else {
        OfflineTtsKittenModelConfig()
    }

    val zipvoice = OfflineTtsZipvoiceModelConfig()

    return OfflineTtsConfig(
        model = OfflineTtsModelConfig(
            vits = vits,
            matcha = matcha,
            kokoro = kokoro,
            zipvoice = zipvoice,
            kitten = kitten,
            numThreads = numberOfThreads,
            debug = true,
            provider = "cpu",
        ),
        ruleFsts = ruleFsts,
        ruleFars = ruleFars,
    )
}
