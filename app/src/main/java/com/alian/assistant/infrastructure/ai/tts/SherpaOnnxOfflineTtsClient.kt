package com.alian.assistant.infrastructure.ai.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * sherpa-onnx 离线 TTS 客户端（实验性）
 */
class SherpaOnnxOfflineTtsClient(
    private val appContext: Context,
    private val speed: Float = 1.0f,
    private val volume: Int = 50,
    private val preferredModelDir: String = DEFAULT_MODEL_DIR
) : OfflineTtsClient {
    companion object {
        private const val TAG = "SherpaOnnxOfflineTts"
        const val DEFAULT_MODEL_DIR = "sherpa-onnx-tts"
        private const val DEFAULT_SPEAKER_ID = 0
        private const val NATURAL_DEFAULT_SPEED = 0.92f
        private const val SEGMENT_MAX_CHARS = 70
        private const val SEGMENT_PAUSE_MS = 70
        private const val OUTPUT_CHUNK_MS = 20
        private const val RUNTIME_MODEL_ROOT = "sherpa-onnx-tts-runtime"
        private const val ALLOWED_PUNCTUATION =
            "，。！？；：、,.!?;:()（）[]【】{}<>《》“”‘’\"'`~·-—…_+=*/\\|@#$%^&"
    }

    private data class ResolvedModel(
        val modelDir: String,
        val modelName: String,
        val acousticModelName: String,
        val vocoder: String,
        val voices: String,
        val lexicon: String,
        val dataDir: String
    )

    @Volatile
    private var offlineTts: OfflineTts? = null

    @Volatile
    private var currentAudioTrack: AudioTrack? = null

    @Volatile
    private var isPlaying = false

    @Volatile
    private var stopRequested = false

    @Volatile
    private var initFailure: Throwable? = null

    @Volatile
    private var preparedModel: ResolvedModel? = null

    private val trackLock = Any()
    private val synthMutex = Mutex()

    @Volatile
    private var isSynthesizing = false

    @Volatile
    private var totalFramesWritten: Long = 0L

    private var onAudioDataCallback: ((ByteArray) -> Unit)? = null

    override fun setOnAudioDataCallback(callback: ((ByteArray) -> Unit)?) {
        onAudioDataCallback = callback
    }

    override fun isCurrentlyPlaying(): Boolean = isPlaying

    override suspend fun synthesizeAndPlay(text: String, context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        synthMutex.withLock {
            synthesizeAndPlayLocked(text, context)
        }
    }

    private suspend fun synthesizeAndPlayLocked(text: String, context: Context): Result<Unit> {
        val sanitizedText = sanitizeTextForTts(text)
        if (sanitizedText.isBlank()) {
            return Result.success(Unit)
        }

        val tts = ensureOfflineTts().getOrElse { error ->
            return Result.failure(error)
        }

        cancelPlayback()
        stopRequested = false

        val sampleRate = runCatching { tts.sampleRate() }
            .getOrDefault(24000)
            .coerceAtLeast(8000)
        val sid = resolveSpeakerId(tts)
        val tunedSpeed = tunedSpeed()
        val segments = splitTextForSynthesis(sanitizedText)
        if (segments.isEmpty()) {
            return Result.success(Unit)
        }

        val audioTrack = createAudioTrack(sampleRate)
        synchronized(trackLock) {
            currentAudioTrack = audioTrack
            totalFramesWritten = 0L
        }
        isPlaying = true
        isSynthesizing = true

        return try {
            requestAudioFocus(context)

            var totalFrames = 0L
            var trackStarted = false

            for ((index, segment) in segments.withIndex()) {
                if (stopRequested) break

                Log.d(TAG, "Generate segment ${index + 1}/${segments.size}, length=${segment.length}")
                val generated = tts.generate(
                    text = segment,
                    sid = sid,
                    speed = tunedSpeed
                )
                Log.d(TAG, "Generated samples=${generated.samples.size} for segment ${index + 1}")

                if (generated.samples.isNotEmpty() && !stopRequested) {
                    val pcm = floatSamplesToPcm16(generated.samples, volume)
                    val chunkBytes = ((sampleRate * OUTPUT_CHUNK_MS) / 1000).coerceAtLeast(1) * 2
                    var offset = 0
                    while (!stopRequested && offset < pcm.size) {
                        if (!trackStarted) {
                            audioTrack.play()
                            trackStarted = true
                        }

                        val length = min(chunkBytes, pcm.size - offset)
                        val wrote = runCatching {
                            audioTrack.write(pcm, offset, length, AudioTrack.WRITE_BLOCKING)
                        }.getOrDefault(-1)

                        if (wrote <= 0) {
                            Log.e(TAG, "Failed to write audio data: wrote=$wrote")
                            break
                        }

                        totalFrames += wrote / 2L // PCM16 mono: 2 bytes per frame
                        totalFramesWritten = totalFrames
                        onAudioDataCallback?.invoke(pcm.copyOfRange(offset, offset + wrote))
                        offset += wrote
                    }
                }

                if (index < segments.lastIndex && !stopRequested) {
                    val pauseFrames = (sampleRate * (SEGMENT_PAUSE_MS / 1000f)).toInt().coerceAtLeast(1)
                    val pausePcm = ByteArray(pauseFrames * 2)
                    if (!trackStarted) {
                        audioTrack.play()
                        trackStarted = true
                    }
                    val wrotePause = audioTrack.write(pausePcm, 0, pausePcm.size, AudioTrack.WRITE_BLOCKING)
                    if (wrotePause > 0) {
                        totalFrames += wrotePause / 2L
                        totalFramesWritten = totalFrames
                        onAudioDataCallback?.invoke(pausePcm.copyOf(wrotePause))
                    }
                }
            }

            if (trackStarted) {
                waitPlaybackCompleted(audioTrack, totalFrames)
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.e(TAG, "Offline TTS playback failed", t)
            Result.failure(t)
        } finally {
            isSynthesizing = false
            releaseTrack()
        }
    }

    override suspend fun isAudioTrackStillPlaying(): Boolean = withContext(Dispatchers.IO) {
        synchronized(trackLock) {
            val track = currentAudioTrack ?: return@withContext false
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                return@withContext false
            }
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                return@withContext false
            }
            val written = totalFramesWritten
            if (written <= 0L) {
                return@withContext false
            }
            val played = runCatching {
                track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            }.getOrDefault(0L)
            written - played > 0L
        }
    }

    override fun cancelPlayback() {
        stopRequested = true
        if (!isSynthesizing) {
            releaseTrack()
        }
    }

    override fun release() {
        stopRequested = true
        if (!isSynthesizing) {
            releaseTrack()
        }

        runBlocking(Dispatchers.IO) {
            synthMutex.withLock {
                isSynthesizing = false
                releaseTrack()
                runCatching { offlineTts?.release() }
                offlineTts = null
                initFailure = null
            }
        }
    }

    private fun ensureOfflineTts(): Result<OfflineTts> {
        offlineTts?.let { return Result.success(it) }

        initFailure?.let { return Result.failure(it) }

        return runCatching {
            val model = prepareModelForRuntime(resolveModel())
            Log.d(
                TAG,
                "Resolved sherpa-onnx model: dir=${model.modelDir}, model=${model.modelName}, voices=${model.voices}"
            )

            val config = getOfflineTtsConfig(
                modelDir = model.modelDir,
                modelName = model.modelName,
                acousticModelName = model.acousticModelName,
                vocoder = model.vocoder,
                voices = model.voices,
                lexicon = model.lexicon,
                dataDir = model.dataDir,
                dictDir = "",
                ruleFsts = "",
                ruleFars = "",
                numThreads = 1
            )

            OfflineTts(
                assetManager = null,
                config = config
            ).also { offlineTts = it }
        }.onFailure { error ->
            initFailure = error
            Log.e(TAG, "Failed to initialize sherpa-onnx offline TTS", error)
        }
    }

    private fun prepareModelForRuntime(assetModel: ResolvedModel): ResolvedModel {
        preparedModel?.let { return it }

        val runtimeRoot = File(appContext.noBackupFilesDir, RUNTIME_MODEL_ROOT)
        val targetModelDir = File(runtimeRoot, assetModel.modelDir)
        val marker = File(targetModelDir, ".ready")

        if (!marker.exists()) {
            if (targetModelDir.exists()) {
                targetModelDir.deleteRecursively()
            }
            targetModelDir.mkdirs()
            copyAssetTree(assetModel.modelDir, targetModelDir)
            marker.writeText("ok")
            Log.d(TAG, "Prepared offline TTS model files: ${targetModelDir.absolutePath}")
        }

        val mappedDataDir = if (assetModel.dataDir.isBlank()) {
            ""
        } else {
            File(
                targetModelDir,
                relativePathUnderModel(assetModel.dataDir, assetModel.modelDir)
            ).absolutePath
        }

        val mappedVocoder = if (assetModel.vocoder.isBlank()) {
            ""
        } else if (assetModel.vocoder.startsWith("/")) {
            assetModel.vocoder
        } else {
            File(
                targetModelDir,
                relativePathUnderModel(assetModel.vocoder, assetModel.modelDir)
            ).absolutePath
        }

        return assetModel.copy(
            modelDir = targetModelDir.absolutePath,
            dataDir = mappedDataDir,
            vocoder = mappedVocoder
        ).also {
            preparedModel = it
        }
    }

    private fun relativePathUnderModel(path: String, modelDir: String): String {
        val prefix = "$modelDir/"
        return if (path.startsWith(prefix)) {
            path.removePrefix(prefix)
        } else {
            path
        }
    }

    private fun copyAssetTree(assetPath: String, target: File) {
        val children = runCatching {
            appContext.assets.list(assetPath) ?: emptyArray()
        }.getOrDefault(emptyArray())

        if (children.isEmpty()) {
            copyAssetFile(assetPath, target)
            return
        }

        if (!target.exists()) {
            target.mkdirs()
        }

        for (child in children) {
            val childAssetPath = "$assetPath/$child"
            val childTarget = File(target, child)
            val grandChildren = runCatching {
                appContext.assets.list(childAssetPath) ?: emptyArray()
            }.getOrDefault(emptyArray())
            if (grandChildren.isEmpty()) {
                copyAssetFile(childAssetPath, childTarget)
            } else {
                copyAssetTree(childAssetPath, childTarget)
            }
        }
    }

    private fun copyAssetFile(assetPath: String, targetFile: File) {
        targetFile.parentFile?.mkdirs()
        appContext.assets.open(assetPath).use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun resolveModel(): ResolvedModel {
        val assetManager = appContext.assets
        val rootDirs = runCatching { assetManager.list("")?.toList() ?: emptyList() }
            .getOrDefault(emptyList())

        val modelDir = when {
            preferredModelDir.isNotBlank() && rootDirs.contains(preferredModelDir) -> preferredModelDir
            else -> rootDirs.firstOrNull { it.startsWith("sherpa-onnx-tts") || it.startsWith("sherpa_onnx_tts") }
        } ?: throw IllegalStateException(
            "No offline TTS model directory found in assets. Expected `$preferredModelDir` or `sherpa-onnx-tts*`."
        )

        val files = runCatching { assetManager.list(modelDir)?.toList() ?: emptyList() }
            .getOrDefault(emptyList())

        if (!files.contains("tokens.txt")) {
            throw IllegalStateException("Model directory `$modelDir` missing tokens.txt")
        }

        val onnxFiles = files.filter { it.endsWith(".onnx", ignoreCase = true) }
        if (onnxFiles.isEmpty()) {
            throw IllegalStateException("Model directory `$modelDir` missing .onnx model file")
        }

        val vocoderFile = onnxFiles.firstOrNull { it.contains("vocoder", ignoreCase = true) }
        val (modelName, acousticModelName, vocoderPath) = if (vocoderFile != null && onnxFiles.size >= 2) {
            val acoustic = onnxFiles.firstOrNull { it != vocoderFile }
                ?: throw IllegalStateException("Model directory `$modelDir` has vocoder but missing acoustic model")
            Triple("", acoustic, "$modelDir/$vocoderFile")
        } else {
            Triple(onnxFiles.first(), "", "")
        }

        val voices = files.firstOrNull {
            it.endsWith(".bin", ignoreCase = true) && it.contains("voice", ignoreCase = true)
        } ?: files.firstOrNull { it.endsWith(".bin", ignoreCase = true) } ?: ""

        val lexicon = files.firstOrNull {
            it.endsWith(".txt", ignoreCase = true) && it.contains("lexicon", ignoreCase = true)
        } ?: ""

        val dataDir = runCatching {
            val dictFiles = assetManager.list("$modelDir/dict") ?: emptyArray()
            val dictFileSet = dictFiles.toSet()
            val hasPiperPhonemeDict = setOf("phontab", "phonindex", "phondata", "intonations")
                .all { dictFileSet.contains(it) }

            when {
                hasPiperPhonemeDict -> "$modelDir/dict"
                (assetManager.list("$modelDir/espeak-ng-data") ?: emptyArray()).isNotEmpty() -> {
                    "$modelDir/espeak-ng-data"
                }
                else -> {
                    if (dictFiles.isNotEmpty()) {
                        Log.w(
                            TAG,
                            "Ignore `$modelDir/dict` as vits data_dir: missing piper files " +
                                "(phontab/phonindex/phondata/intonations)"
                        )
                    }
                    ""
                }
            }
        }.getOrDefault("")

        return ResolvedModel(
            modelDir = modelDir,
            modelName = modelName,
            acousticModelName = acousticModelName,
            vocoder = vocoderPath,
            voices = voices,
            lexicon = lexicon,
            dataDir = dataDir
        )
    }

    private fun resolveSpeakerId(tts: OfflineTts): Int {
        val count = runCatching { tts.numSpeakers() }.getOrDefault(1)
        if (count <= 0) return 0
        return DEFAULT_SPEAKER_ID.coerceIn(0, count - 1)
    }

    private fun tunedSpeed(): Float {
        val configured = speed.coerceIn(0.5f, 2.0f)
        return if (configured in 0.95f..1.05f) NATURAL_DEFAULT_SPEED else configured
    }

    private fun splitTextForSynthesis(rawText: String): List<String> {
        val normalized = rawText
            .replace("\r", "\n")
            .replace(Regex("[\\t ]+"), " ")
            .replace(Regex("\\n+"), "\n")
            .trim()
        if (normalized.isEmpty()) {
            return emptyList()
        }

        val sentenceLike = normalized
            .replace("\n", "。")
            .split(Regex("(?<=[。！？!?；;…])"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (sentenceLike.isEmpty()) {
            return listOf(normalized)
        }

        val result = mutableListOf<String>()
        for (sentence in sentenceLike) {
            if (sentence.length <= SEGMENT_MAX_CHARS) {
                result += sentence
                continue
            }

            var start = 0
            while (start < sentence.length) {
                var end = (start + SEGMENT_MAX_CHARS).coerceAtMost(sentence.length)
                if (end < sentence.length) {
                    val breakAt = sentence.lastIndexOfAny(
                        charArrayOf('，', ',', '、', ' '),
                        startIndex = end - 1
                    )
                    if (breakAt >= start + (SEGMENT_MAX_CHARS / 2)) {
                        end = breakAt + 1
                    }
                }
                val segment = sentence.substring(start, end).trim()
                if (segment.isNotEmpty()) {
                    result += segment
                }
                start = end
            }
        }
        return if (result.isNotEmpty()) result else listOf(normalized)
    }

    private fun sanitizeTextForTts(rawText: String): String {
        if (rawText.isBlank()) return ""

        val sb = StringBuilder(rawText.length)
        var index = 0
        while (index < rawText.length) {
            val cp = rawText.codePointAt(index)
            index += Character.charCount(cp)

            when {
                Character.isLetterOrDigit(cp) -> sb.appendCodePoint(cp)
                cp == '\n'.code || cp == '\r'.code || cp == '\t'.code || cp == ' '.code -> sb.append(' ')
                ALLOWED_PUNCTUATION.indexOf(cp.toChar()) >= 0 -> sb.appendCodePoint(cp)
                else -> sb.append(' ')
            }
        }

        return sb.toString()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun createAudioTrack(sampleRate: Int): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(1)

        val targetBuffer = max(minBuffer, sampleRate * 2)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(targetBuffer)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private suspend fun waitPlaybackCompleted(track: AudioTrack, totalFrames: Long) {
        if (totalFrames <= 0 || stopRequested) {
            return
        }

        val maxWaitMs = 12_000L
        val startMs = System.currentTimeMillis()
        while (!stopRequested && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
            val playbackHead = runCatching { track.playbackHeadPosition.toLong() }
                .getOrDefault(totalFrames)
            if (playbackHead >= totalFrames) {
                break
            }
            if (System.currentTimeMillis() - startMs > maxWaitMs) {
                Log.w(TAG, "Timeout waiting playback completion")
                break
            }
            delay(20)
        }
    }

    private fun requestAudioFocus(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching {
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
    }

    private fun releaseTrack() {
        synchronized(trackLock) {
            val track = currentAudioTrack ?: return
            currentAudioTrack = null
            isPlaying = false
            totalFramesWritten = 0L
            runCatching {
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.stop()
                    }
                }
            }
            runCatching { track.release() }
        }
    }

    private fun floatSamplesToPcm16(samples: FloatArray, volume: Int): ByteArray {
        val gain = (volume.coerceIn(0, 100) / 50f).coerceIn(0f, 2f)
        var peak = 0f
        for (sample in samples) {
            val v = abs(sample * gain)
            if (v > peak) peak = v
        }
        val limiter = if (peak > 0.98f) (0.98f / peak) else 1f

        val pcm = ByteArray(samples.size * 2)
        var index = 0
        for (sample in samples) {
            val value = (sample * gain * limiter).coerceIn(-1f, 1f)
            val shortValue = (value * Short.MAX_VALUE).toInt().toShort()
            pcm[index++] = (shortValue.toInt() and 0xFF).toByte()
            pcm[index++] = ((shortValue.toInt() shr 8) and 0xFF).toByte()
        }
        return pcm
    }
}
