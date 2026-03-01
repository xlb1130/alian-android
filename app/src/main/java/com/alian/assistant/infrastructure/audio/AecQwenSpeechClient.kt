package com.alian.assistant.infrastructure.audio

import android.content.Context
import android.util.Log
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import com.alibaba.idst.nui.AsrResult
import com.alibaba.idst.nui.Constants
import com.alibaba.idst.nui.INativeNuiCallback
import com.alibaba.idst.nui.KwsResult
import com.alibaba.idst.nui.NativeNui
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * 支持 AEC（回声消除）的 Qwen 语音识别客户端
 * 
 * 与标准 QwenSpeechClient 的区别：
 * 1. 接受经过 AEC 处理的音频数据
 * 2. 支持语音打断功能检测
 * 3. 更精确的用户语音检测
 */
class AecQwenSpeechClient(
    private val apiKey: String,
    private val url: String = "wss://dashscope.aliyuncs.com/api-ws/v1/inference",
    private val model: String = "fun-asr-realtime-2025-09-15",
    private val sampleRate: Int = 16000,
    private val audioFormat: String = "pcm",
    private val vadEnabled: Boolean = true
) : INativeNuiCallback {
    companion object {
        private const val TAG = "AecQwenSpeechClient"
    }

    private val WAVE_FRAM_SIZE = 20 * 2 * 1 * sampleRate / 1000

    private val nuiInstance: NativeNui = NativeNui()
    private var mInit = false
    private var mStopping = false
    private var mDebugPath = ""
    private var curTaskId = ""
    
    // 用于存储识别结果的变量
    private var recognitionResult: String = ""
    private var recognitionError: Exception? = null

    // 音频数据队列（来自 AEC 处理器）
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private var isProcessingAudio = false

    // 回调
    private var onPartialResultCallback: ((String) -> Unit)? = null
    private var onFinalResultCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((Exception) -> Unit)? = null

    // 统一的协程作用域，用于管理所有协程
    private val clientJob = SupervisorJob()
    private val clientScope = CoroutineScope(clientJob + Dispatchers.IO)

    /**
     * 初始化 SDK
     */
    suspend fun initialize(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            mDebugPath = context.externalCacheDir?.absolutePath + "/debug"

            // 检查是否已经初始化
            if (mInit) {
                Log.d(TAG, "SDK 已经初始化,跳过初始化")
                return@withContext Result.success(Unit)
            }

            val initResult = nuiInstance.initialize(
                this@AecQwenSpeechClient,
                genInitParams(mDebugPath),
                Constants.LogLevel.LOG_LEVEL_DEBUG,
                true
            )

            // 240012 表示已经初始化,这也是成功的情况
            if (initResult != Constants.NuiResultCode.SUCCESS && initResult != 240012) {
                return@withContext Result.failure(Exception("初始化失败: $initResult"))
            }

            mInit = true

            // 设置识别参数
            val setParamsString = genParams()
            nuiInstance.setParams(setParamsString)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 开始识别
     */
    suspend fun startRecognition(): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            if (!mInit) {
                return@withContext Result.failure(Exception("SDK 未初始化"))
            }
            
            if (isProcessingAudio) {
                Log.w(TAG, "识别已在进行中")
                return@withContext Result.success(Unit)
            }
            
            // 重置停止标志，确保可以正常接收音频数据
            mStopping = false
            recognitionResult = ""
            
            val startResult = nuiInstance.startDialog(
                Constants.VadMode.TYPE_P2T,
                genDialogParams()
            )
            
            if (startResult != Constants.NuiResultCode.SUCCESS) {
                return@withContext Result.failure(Exception("启动对话失败: $startResult"))
            }
            
            isProcessingAudio = true
            
            // 启动音频处理协程
            clientScope.launch {
                processAudioQueue()
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 停止识别
     */
    fun stopRecognition() {
        Log.d(TAG, "停止识别")
        
        mStopping = true
        recognitionResult = ""
        isProcessingAudio = false
        
        if (mInit) {
            nuiInstance.stopDialog()
        }
        
        // 清空音频队列
        audioQueue.clear()
        
        Log.d(TAG, "识别已停止")
    }

    /**
     * 释放资源
     */
    fun release() {
        Log.d(TAG, "释放资源")

        // 取消所有协程
        clientJob.cancel()

        mStopping = true
        isProcessingAudio = false

        if (mInit) {
            nuiInstance.stopDialog()
            nuiInstance.release()
            mInit = false
        }

        audioQueue.clear()
    }

    /**
     * 添加音频数据（来自 AEC 处理器）
     */
    fun addAudioData(audioData: ByteArray) {
        if (isProcessingAudio && !mStopping) {
            val offered = audioQueue.offer(audioData)
            if (offered) {
                // Log.d(TAG, "addAudioData: 成功添加音频数据，大小=${audioData.size}，队列大小=${audioQueue.size}")
            } else {
                Log.w(TAG, "音频队列已满，丢弃音频数据，队列大小: ${audioQueue.size}")
            }
        } else {
            Log.w(TAG, "addAudioData 被调用但未处理: isProcessingAudio=$isProcessingAudio, mStopping=$mStopping, 音频大小=${audioData.size}")
        }
    }

    /**
     * 处理音频队列
     */
    private suspend fun processAudioQueue() {
        Log.d(TAG, "开始处理音频队列")
        
        while (isProcessingAudio && !mStopping) {
            try {
                // 检查队列中是否有音频数据
                if (audioQueue.isEmpty()) {
                    // 队列为空，等待一段时间
                    delay(10)
                    continue
                }
                
                // 音频数据会通过 onNuiNeedAudioData 回调被 SDK 消费
                // 这里不需要主动消费队列，只需等待 SDK 请求音频数据
                delay(10)
            } catch (e: Exception) {
                Log.e(TAG, "处理音频队列失败", e)
            }
        }
        
        Log.d(TAG, "音频队列处理已停止")
    }

    /**
     * 设置回调
     */
    fun setCallbacks(
        onPartialResult: ((String) -> Unit)? = null,
        onFinalResult: ((String) -> Unit)? = null,
        onError: ((Exception) -> Unit)? = null
    ) {
        onPartialResultCallback = onPartialResult
        onFinalResultCallback = onFinalResult
        onErrorCallback = onError
    }

    // INativeNuiCallback 实现

    override fun onNuiEventCallback(
        event: Constants.NuiEvent?,
        resultCode: Int,
        arg2: Int,
        kwsResult: KwsResult?,
        asrResult: AsrResult?
    ) {
        when (event) {
            Constants.NuiEvent.EVENT_TRANSCRIBER_STARTED -> {
                Log.d(TAG, "识别器已启动")
            }
            Constants.NuiEvent.EVENT_TRANSCRIBER_COMPLETE -> {
                Log.d(TAG, "语音识别完成")
                onFinalResultCallback?.invoke(recognitionResult)
            }
            Constants.NuiEvent.EVENT_ASR_PARTIAL_RESULT -> {
                asrResult?.let { result ->
                    val text = extractTextFromResult(result.allResponse)
                    if (!text.isNullOrEmpty()) {
                        Log.d(TAG, "部分识别结果: $text")
                        onPartialResultCallback?.invoke(text)
                        recognitionResult = text
                    }
                }
            }
            Constants.NuiEvent.EVENT_SENTENCE_END -> {
                asrResult?.let { result ->
                    val text = extractTextFromResult(result.allResponse)
                    if (!text.isNullOrEmpty()) {
                        val thread = Thread.currentThread()
                        Log.d(TAG, "句子结束识别结果: $text, thread=${thread.name}, isMain=${thread.name == "main"}")
                        recognitionResult = text
                        onFinalResultCallback?.invoke(text)
                    }
                }
            }
            Constants.NuiEvent.EVENT_ASR_ERROR -> {
                Log.e(TAG, "语音识别错误: $resultCode")
                val error = Exception("语音识别错误: $resultCode")
                recognitionError = error
                onErrorCallback?.invoke(error)
            }
            Constants.NuiEvent.EVENT_MIC_ERROR -> {
                Log.e(TAG, "麦克风错误: $resultCode")
                val error = Exception("麦克风错误: $resultCode")
                recognitionError = error
                onErrorCallback?.invoke(error)
            }
            else -> {
                Log.d(TAG, "其他事件: $event")
            }
        }
    }

    override fun onNuiNeedAudioData(buffer: ByteArray?, len: Int): Int {
        if (buffer == null || len <= 0) {
            Log.w(TAG, "onNuiNeedAudioData: buffer=null 或 len<=0, len=$len")
            return -1
        }

        if (!isProcessingAudio || mStopping) {
            Log.w(TAG, "onNuiNeedAudioData: 未在处理状态，isProcessingAudio=$isProcessingAudio, mStopping=$mStopping, 队列大小=${audioQueue.size}")
            return -1
        }

        try {
            // 从队列获取音频数据,阻塞等待最多 100ms
            // 这样可以避免 SDK 超时,同时不会持续填充静音导致 ASR 提前结束
            val audioData = audioQueue.poll(100, TimeUnit.MILLISECONDS)

            if (audioData != null) {
                val copyLen = min(len, audioData.size)
                System.arraycopy(audioData, 0, buffer, 0, copyLen)
                // Log.d(TAG, "onNuiNeedAudioData: 成功获取音频数据, copyLen=$copyLen, audioQueue.size=${audioQueue.size}")
                return copyLen
            }

            // 如果超时仍未获取到数据,返回 0
            // SDK 会再次调用,这是正常的
            Log.w(TAG, "onNuiNeedAudioData: 队列为空,等待超时,返回 0 (当前队列大小=${audioQueue.size})")
            return 0
        } catch (e: InterruptedException) {
            Log.w(TAG, "onNuiNeedAudioData: 被中断")
            Thread.currentThread().interrupt()
            return -1
        }
    }

    override fun onNuiAudioStateChanged(state: Constants.AudioState?) {
        Log.d(TAG, "音频状态改变: $state, isProcessingAudio=$isProcessingAudio, audioQueue.size=${audioQueue.size}")
        when (state) {
            Constants.AudioState.STATE_OPEN -> {
                Log.d(TAG, "音频录制开始（SDK 期望开始提供音频数据）")
            }
            Constants.AudioState.STATE_CLOSE -> {
                Log.d(TAG, "音频录制关闭")
            }
            Constants.AudioState.STATE_PAUSE -> {
                Log.d(TAG, "音频录制暂停")
            }
            else -> {
                Log.d(TAG, "其他音频状态: $state")
            }
        }
    }

    override fun onNuiAudioRMSChanged(`val`: Float) {
        // 音频RMS值变化，可用于显示音量
    }

    override fun onNuiVprEventCallback(event: Constants.NuiVprEvent?) {
        Log.d(TAG, "VPR事件: $event")
    }

    override fun onNuiLogTrackCallback(level: Constants.LogLevel?, log: String?) {
        // SDK内部日志回调
    }

    // 辅助方法

    private fun genInitParams(debugPath: String): String {
        val obj = JSONObject()
        obj["device_id"] = "alian_aec_${System.currentTimeMillis()}"
        obj["url"] = url
        obj["save_wav"] = "true"
        obj["debug_path"] = debugPath
        obj["max_log_file_size"] = 50 * 1024 * 1024
        obj["log_track_level"] = Constants.LogLevel.toInt(Constants.LogLevel.LOG_LEVEL_NONE).toString()
        obj["service_mode"] = Constants.ModeFullCloud
        return obj.toJSONString()
    }

    private fun genParams(): String {
        val nls_config = JSONObject()
        nls_config["sr_format"] = audioFormat
        nls_config["model"] = model
        nls_config["sample_rate"] = sampleRate
        
        if (vadEnabled) {
            nls_config["semantic_punctuation_enabled"] = false
        } else {
            nls_config["semantic_punctuation_enabled"] = true
        }
        
        nls_config["punctuation_prediction_enabled"] = true
        
        // 设置句子结束静音时间，减少识别延迟
        // 默认值通常是 2000ms，设置为 800ms 可以更快识别句子结束
        nls_config["max_sentence_silence"] = 800
        
        val tmp = JSONObject()
        tmp["nls_config"] = nls_config
        tmp["service_type"] = Constants.kServiceTypeSpeechTranscriber
        
        return tmp.toJSONString()
    }

    private fun genDialogParams(): String {
        val dialog_param = JSONObject()
        dialog_param["apikey"] = apiKey
        return dialog_param.toJSONString()
    }

    private fun extractTextFromResult(jsonResponse: String?): String? {
        if (jsonResponse.isNullOrEmpty()) {
            return null
        }
        
        return try {
            val jsonObject = JSON.parseObject(jsonResponse)
            val payload = jsonObject.getJSONObject("payload")
            val output = payload?.getJSONObject("output")
            val sentence = output?.getJSONObject("sentence")
            sentence?.getString("text")
        } catch (e: Exception) {
            Log.e(TAG, "提取文本失败", e)
            null
        }
    }
}