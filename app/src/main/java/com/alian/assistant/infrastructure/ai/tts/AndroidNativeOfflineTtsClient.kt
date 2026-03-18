package com.alian.assistant.infrastructure.ai.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.UUID

/**
 * Android 原生离线 TTS 客户端（TextToSpeech）
 */
class AndroidNativeOfflineTtsClient(
    private val appContext: Context,
    private val speed: Float = 1.0f,
    private val volume: Int = 50
) : OfflineTtsClient {

    companion object {
        private const val TAG = "AndroidNativeTts"
        private const val INIT_TIMEOUT_MS = 4_000L
        private const val PLAYBACK_TIMEOUT_MS = 120_000L
    }

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var initFailure: Throwable? = null

    @Volatile
    private var isPlaying = false

    private val initMutex = Mutex()
    private val synthMutex = Mutex()
    private val playbackLock = Any()

    private var currentUtteranceId: String? = null
    private var playbackDeferred: CompletableDeferred<Result<Unit>>? = null

    override suspend fun synthesizeAndPlay(text: String, context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        val normalized = text
            .replace("\r", "\n")
            .replace(Regex("[\\t ]+"), " ")
            .replace(Regex("\\n+"), "\n")
            .trim()
        if (normalized.isBlank()) {
            return@withContext Result.success(Unit)
        }

        synthMutex.withLock {
            val engine = ensureTts().getOrElse { error ->
                return@withLock Result.failure(error)
            }

            cancelPlayback()
            val utteranceId = "android-native-${UUID.randomUUID()}"
            val deferred = CompletableDeferred<Result<Unit>>()

            synchronized(playbackLock) {
                currentUtteranceId = utteranceId
                playbackDeferred = deferred
                isPlaying = false
            }

            val speakResult = withContext(Dispatchers.Main) {
                runCatching {
                    engine.setSpeechRate(speed.coerceIn(0.5f, 2.0f))
                    val params = Bundle().apply {
                        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, (volume.coerceIn(0, 100) / 100f))
                    }
                    engine.speak(normalized, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
                }.getOrDefault(TextToSpeech.ERROR)
            }

            if (speakResult == TextToSpeech.ERROR) {
                synchronized(playbackLock) {
                    playbackDeferred = null
                    currentUtteranceId = null
                    isPlaying = false
                }
                return@withLock Result.failure(IllegalStateException("Android 原生离线 TTS 播放失败"))
            }

            val playbackResult = withTimeoutOrNull(PLAYBACK_TIMEOUT_MS) {
                deferred.await()
            } ?: Result.failure(IllegalStateException("Android 原生离线 TTS 播放超时"))

            if (playbackResult.isFailure) {
                cancelPlayback()
            }
            playbackResult
        }
    }

    override fun cancelPlayback() {
        synchronized(playbackLock) {
            playbackDeferred?.complete(
                Result.failure(OfflineTtsPlaybackInterruptedException("Android 原生离线 TTS 播放已取消"))
            )
            playbackDeferred = null
            currentUtteranceId = null
            isPlaying = false
        }
        runCatching { tts?.stop() }
    }

    override fun isCurrentlyPlaying(): Boolean = isPlaying

    override suspend fun isAudioTrackStillPlaying(): Boolean = isCurrentlyPlaying()

    override fun setOnAudioDataCallback(callback: ((ByteArray) -> Unit)?) {
        if (callback != null) {
            Log.d(TAG, "Android 原生 TTS 不支持原始 PCM 回调，已忽略 onAudioDataCallback")
        }
    }

    override fun release() {
        cancelPlayback()
        runCatching { tts?.shutdown() }
        tts = null
        initFailure = null
    }

    private suspend fun ensureTts(): Result<TextToSpeech> {
        tts?.let { return Result.success(it) }
        initFailure?.let { return Result.failure(it) }

        return initMutex.withLock {
            tts?.let { return@withLock Result.success(it) }
            initFailure?.let { return@withLock Result.failure(it) }

            runCatching {
                val engine = createAndInitEngine()
                configureOfflineVoice(engine)
                attachUtteranceListener(engine)
                tts = engine
                engine
            }.onFailure { error ->
                initFailure = error
                Log.e(TAG, "初始化 Android 原生离线 TTS 失败", error)
            }
        }
    }

    private suspend fun createAndInitEngine(): TextToSpeech = withContext(Dispatchers.Main) {
        val initResult = CompletableDeferred<Int>()
        val engine = TextToSpeech(appContext) { status ->
            if (!initResult.isCompleted) {
                initResult.complete(status)
            }
        }

        val status = withTimeoutOrNull(INIT_TIMEOUT_MS) {
            initResult.await()
        } ?: throw IllegalStateException("Android 原生离线 TTS 初始化超时")

        if (status != TextToSpeech.SUCCESS) {
            runCatching { engine.shutdown() }
            throw IllegalStateException("Android 原生离线 TTS 初始化失败（status=$status）")
        }

        engine
    }

    private fun configureOfflineVoice(engine: TextToSpeech) {
        val preferredLocale = Locale.getDefault()
        val offlineVoice = selectBestOfflineVoice(engine, preferredLocale)
            ?: throw IllegalStateException("未检测到可用的安卓原生离线语音，请先在系统 TTS 中下载离线语音数据")

        val voiceSet = runCatching {
            engine.voice = offlineVoice
            true
        }.getOrDefault(false)
        if (!voiceSet) {
            throw IllegalStateException("设置安卓原生离线音色失败：${offlineVoice.name}")
        }

        val languageResult = runCatching { engine.setLanguage(offlineVoice.locale) }
            .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)

        if (languageResult == TextToSpeech.LANG_NOT_SUPPORTED || languageResult == TextToSpeech.LANG_MISSING_DATA) {
            throw IllegalStateException("安卓原生离线语音语言不支持或缺少数据：${offlineVoice.locale}")
        }

        Log.d(TAG, "已启用安卓原生离线音色: ${offlineVoice.name}, locale=${offlineVoice.locale}")
    }

    private fun attachUtteranceListener(engine: TextToSpeech) {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                synchronized(playbackLock) {
                    if (utteranceId != null && utteranceId == currentUtteranceId) {
                        isPlaying = true
                    }
                }
            }

            override fun onDone(utteranceId: String?) {
                finishUtterance(utteranceId, Result.success(Unit))
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                finishUtterance(
                    utteranceId,
                    Result.failure(IllegalStateException("Android 原生离线 TTS 播放错误"))
                )
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                finishUtterance(
                    utteranceId,
                    Result.failure(IllegalStateException("Android 原生离线 TTS 播放错误：$errorCode"))
                )
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                finishUtterance(
                    utteranceId,
                    Result.failure(
                        OfflineTtsPlaybackInterruptedException(
                            if (interrupted) "Android 原生离线 TTS 被中断" else "Android 原生离线 TTS 已停止"
                        )
                    )
                )
            }
        })
    }

    private fun finishUtterance(utteranceId: String?, result: Result<Unit>) {
        synchronized(playbackLock) {
            val currentId = currentUtteranceId
            if (currentId == null) {
                return
            }
            if (utteranceId != null && currentId != utteranceId) {
                return
            }
            playbackDeferred?.complete(result)
            playbackDeferred = null
            currentUtteranceId = null
            isPlaying = false
        }
    }

    private fun selectBestOfflineVoice(engine: TextToSpeech, preferredLocale: Locale): Voice? {
        val voices = runCatching { engine.voices?.toList().orEmpty() }
            .getOrDefault(emptyList())

        return voices
            .asSequence()
            .filter { it.isOfflineCapable() }
            .sortedWith(compareBy<Voice> { voiceLocaleScore(it.locale, preferredLocale) }.thenBy { it.name })
            .firstOrNull()
    }

    private fun Voice.isOfflineCapable(): Boolean {
        val features = this.features.orEmpty()
        if (TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in features) return false

        val hasEmbedded = TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS in features
        val hasNetwork = TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS in features

        // 某些厂商实现不会声明 embedded 标识，此时只要不要求 network，也视为可离线。
        return hasEmbedded || !hasNetwork
    }

    private fun voiceLocaleScore(voiceLocale: Locale?, preferredLocale: Locale): Int {
        if (voiceLocale == null) return 3
        return when {
            voiceLocale == preferredLocale -> 0
            voiceLocale.language == preferredLocale.language && voiceLocale.country == preferredLocale.country -> 0
            voiceLocale.language == preferredLocale.language -> 1
            voiceLocale.language == Locale.CHINESE.language -> 2
            else -> 3
        }
    }
}
