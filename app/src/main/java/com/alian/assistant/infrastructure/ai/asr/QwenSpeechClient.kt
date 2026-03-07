package com.alian.assistant.infrastructure.ai.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import com.alibaba.idst.nui.AsrResult
import com.alibaba.idst.nui.Constants
import com.alibaba.idst.nui.INativeNuiCallback
import com.alibaba.idst.nui.KwsResult
import com.alibaba.idst.nui.NativeNui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.LinkedBlockingQueue

/**
 * 通义千问语音识别客户端
 * 使用阿里云NativeNui官方SDK进行语音转文字
 */
class QwenSpeechClient(
    private val apiKey: String,
    private val url: String = "wss://dashscope.aliyuncs.com/api-ws/v1/inference",
    private val model: String = "fun-asr-realtime-2025-09-15",
    private val sampleRate: Int = 16000,
    private val audioFormat: String = "pcm",
    private val vadEnabled: Boolean = true, // 语音活动检测断句
    private val maxSentenceSilenceMs: Int = 1200
) : INativeNuiCallback {
    companion object {
        private const val TAG = "QwenSpeechClient"
        private const val SAMPLE_RATE = 16000
        private const val WAVE_FRAM_SIZE = 20 * 2 * 1 * SAMPLE_RATE / 1000 // 20ms audio for 16k/16bit/mono
    }

    private val nuiInstance: NativeNui = NativeNui()
    private var mAudioRecorder: AudioRecord? = null
    private var mInit = false
    private var mStopping = false
    private var mDebugPath = ""
    private var curTaskId = ""
    private val tmpAudioQueue = LinkedBlockingQueue<ByteArray>()

    // 音频录制配置常量
    private val audioSource = MediaRecorder.AudioSource.MIC
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioEncodingFormat = AudioFormat.ENCODING_PCM_16BIT

    // 用于存储识别结果的变量
    private var recognitionResult: String = ""
    private var recognitionError: Exception? = null

    /**
     * 检查录音权限
     * @param context 上下文
     * @return Boolean 是否拥有录音权限
     */
    fun hasRecordPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * 初始化音频录制器
     * @param context 上下文
     * @return Boolean 初始化是否成功
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun initAudioRecorder(context: Context): Boolean {
        if (mAudioRecorder != null) {
            return true
        }

        try {
            // 获取推荐的缓冲区大小
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                channelConfig,
                audioEncodingFormat
            )

            // 如果推荐的缓冲区大小无效，则使用默认值
            val actualBufferSize = if (bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                SAMPLE_RATE * 2 * 10 // 默认缓冲区大小
            } else {
                bufferSize.coerceAtLeast(SAMPLE_RATE * 2 * 10) // 至少保证10ms的数据
            }

            // 创建音频录制器
            mAudioRecorder = AudioRecord(
                audioSource,
                SAMPLE_RATE,
                channelConfig,
                audioEncodingFormat,
                actualBufferSize
            )

            // 检查录制器是否初始化成功
            if (mAudioRecorder!!.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "音频录制器初始化失败")
                mAudioRecorder = null
                return false
            }

            Log.d(TAG, "音频录制器初始化成功，缓冲区大小: $actualBufferSize")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "初始化音频录制器失败", e)
            mAudioRecorder = null
            return false
        }
    }

    /**
     * 实时录音识别
     * @param context 上下文
     * @return Result<String> 包含识别文本的结果
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun recognizeSpeechRealTime(context: Context): Result<String> =
        withContext(Dispatchers.Main) {
            try {
                // 清空识别结果，避免累积之前的内容
                recognitionResult = ""

                // 检查录音权限
                if (!hasRecordPermission(context)) {
                    return@withContext Result.failure(Exception("缺少录音权限"))
                }

                // 初始化音频录制器
                if (!initAudioRecorder(context)) {
                    return@withContext Result.failure(Exception("音频录制器初始化失败"))
                }

                // 设置调试路径
                mDebugPath = context.externalCacheDir?.absolutePath + "/debug"

                // 初始化SDK
                val initResult = nuiInstance.initialize(
                    this@QwenSpeechClient, genInitParams(mDebugPath),
                    Constants.LogLevel.LOG_LEVEL_DEBUG, true
                )
                Log.i(TAG, "初始化结果: $initResult")

                if (initResult != Constants.NuiResultCode.SUCCESS) {
                    return@withContext Result.failure(Exception("初始化失败: $initResult"))
                }
                mInit = true

                // 设置识别参数
                val setParamsString = genParams()
                Log.i(TAG, "设置参数: $setParamsString")
                nuiInstance.setParams(setParamsString)

                // 启动对话
                val startResult =
                    nuiInstance.startDialog(Constants.VadMode.TYPE_P2T, genDialogParams())
                if (startResult != Constants.NuiResultCode.SUCCESS) {
                    return@withContext Result.failure(Exception("启动对话失败: $startResult"))
                }

                // 不再等待识别完成，而是立即返回成功状态
                // 识别将在后台进行，结果通过回调返回
                Result.success("")

            } catch (e: Exception) {
                Log.e(TAG, "实时语音识别失败", e)
                Result.failure(e)
            }
        }

    /**
     * 停止语音识别
     */
    fun stopRecognition() {
        try {
            mStopping = true

            // 清空识别结果，避免下次录音时累积之前的内容
            recognitionResult = ""

            // 先停止 AudioRecord 录音
            mAudioRecorder?.let {
                if (it.state == AudioRecord.STATE_INITIALIZED && it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    try {
                        it.stop()
                        Log.d(TAG, "AudioRecord 已停止")
                    } catch (e: Exception) {
                        Log.e(TAG, "停止 AudioRecord 失败", e)
                    }
                }
            }

            // 停止并释放 SDK
            if (mInit) {
                try {
                    nuiInstance.stopDialog()
                    nuiInstance.release()
                    mInit = false
                    Log.d(TAG, "SDK 已释放")
                } catch (e: Exception) {
                    Log.e(TAG, "释放 SDK 失败", e)
                }
            }

            // 释放 AudioRecord
            mAudioRecorder?.let {
                try {
                    it.release()
                    Log.d(TAG, "AudioRecord 已释放")
                } catch (e: Exception) {
                    Log.e(TAG, "释放 AudioRecord 失败", e)
                }
            }
            mAudioRecorder = null

            Log.d(TAG, "语音识别已停止，资源已释放")
        } catch (e: Exception) {
            Log.e(TAG, "停止语音识别失败", e)
        }
    }

    /**
     * 生成初始化参数
     */
    private fun genInitParams(debugPath: String): String {
        val obj = JSONObject()

        // 设置必要参数
        obj["device_id"] = "alian_autopilot_${'$'}{System.currentTimeMillis()}" // 设备ID
        obj["url"] = url
        obj["save_wav"] = "true" // 保存音频调试文件
        obj["debug_path"] = debugPath
        obj["max_log_file_size"] = 50 * 1024 * 1024 // 日志文件最大大小
        obj["log_track_level"] = Constants.LogLevel.toInt(Constants.LogLevel.LOG_LEVEL_NONE).toString()
        obj["service_mode"] = Constants.ModeFullCloud // 使用云端服务

        Log.i(TAG, "初始化参数: $obj")
        return obj.toJSONString()
    }

    /**
     * 生成识别参数
     */
    private fun genParams(): String {
        val nls_config = JSONObject()

        // 设置待识别音频格式
        nls_config["sr_format"] = audioFormat
        // 模型选择
        nls_config["model"] = model
        // 设置待识别音频采样率（单位Hz）
        nls_config["sample_rate"] = sampleRate

        if (vadEnabled) {
            // 设置开启VAD（Voice Activity Detection，语音活动检测）断句
            nls_config["semantic_punctuation_enabled"] = false
            // 句末静音阈值适当放宽，降低噪声环境下“半句被截断”概率
            nls_config["max_sentence_silence"] = maxSentenceSilenceMs
            // nls_config["multi_threshold_mode_enabled"] = true
        } else {
            // 设置开启语义断句
            nls_config["semantic_punctuation_enabled"] = true
        }

        // 设置是否在识别结果中自动添加标点
        nls_config["punctuation_prediction_enabled"] = true

        val tmp = JSONObject()
        tmp["nls_config"] = nls_config
        tmp["service_type"] = Constants.kServiceTypeSpeechTranscriber // 必填

        Log.i(TAG, "识别参数: $tmp")
        return tmp.toJSONString()
    }

    /**
     * 生成对话参数
     */
    private fun genDialogParams(): String {
        val dialog_param = JSONObject()
        dialog_param["apikey"] = apiKey

        Log.i(TAG, "对话参数: $dialog_param")
        return dialog_param.toJSONString()
    }

    // 用于回调实时识别结果的函数
    private var onPartialResultCallback: ((String) -> Unit)? = null
    private var onFinalResultCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((Exception) -> Unit)? = null

    // 设置回调函数
    fun setCallbacks(
        onPartialResult: ((String) -> Unit)? = null,
        onFinalResult: ((String) -> Unit)? = null,
        onError: ((Exception) -> Unit)? = null
    ) {
        onPartialResultCallback = onPartialResult
        onFinalResultCallback = onFinalResult
        onErrorCallback = onError
    }

    /**
     * 从JSON响应中提取识别文本
     * @param jsonResponse JSON格式的响应字符串
     * @return 提取的文本，如果解析失败则返回null
     */
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

    // 实现 INativeNuiCallback 接口的方法

    override fun onNuiEventCallback(event: Constants.NuiEvent?, resultCode: Int, arg2: Int, kwsResult: KwsResult?, asrResult: AsrResult?) {
        Log.i(TAG, "事件: $event, 错误码: $resultCode")

        when (event) {
            Constants.NuiEvent.EVENT_TRANSCRIBER_STARTED -> {
                Log.d(TAG, "识别器已启动")
                if (asrResult != null) {
                    try {
                        val jsonObject = JSON.parseObject(asrResult.allResponse)
                        val header = jsonObject.getJSONObject("header")
                        curTaskId = header.getString("task_id")
                    } catch (e: Exception) {
                        Log.e(TAG, "解析task_id失败", e)
                    }
                }
            }
            Constants.NuiEvent.EVENT_TRANSCRIBER_COMPLETE -> {
                Log.d(TAG, "语音识别完成")
                onFinalResultCallback?.invoke(recognitionResult)
                mStopping = false
            }
            // {"header":{"task_id":"3f076028fd834181996b892748cc399c","event":"result-generated","attributes":{}},"payload":{"output":{"sentence":{"sentence_id":1,"begin_time":740,"end_time":null,"text":"你好啊","channel_id":0,"speaker_id":null,"sentence_end":false,"words":[{"begin_time":740,"end_time":900,"text":"你","punctuation":"","fixed":false,"speaker_id":null},{"begin_time":900,"end_time":1060,"text":"好","punctuation":"","fixed":false,"speaker_id":null},{"begin_time":1060,"end_time":1180,"text":"啊","punctuation":"","fixed":false,"speaker_id":null}]}}}}
            Constants.NuiEvent.EVENT_ASR_PARTIAL_RESULT -> {
                // 中间结果，可以实时显示
                asrResult?.let { result ->
                    val text = extractTextFromResult(result.allResponse)
                    if (!text.isNullOrEmpty()) {
                        Log.d(TAG, "部分识别结果: ${'$'}{text}")
                        onPartialResultCallback?.invoke(text)
                        // 更新识别结果以备最终返回
                        recognitionResult = text
                    }
                }
            }
            // {"header":{"task_id":"c8b1e4c10565417e81f8b8509c50c11d","event":"result-generated","attributes":{}},"payload":{"output":{"sentence":{"sentence_id":1,"begin_time":640,"end_time":1640,"text":"你好啊。","channel_id":0,"speaker_id":null,"sentence_end":true,"words":[{"begin_time":640,"end_time":1160,"text":"你好","punctuation":"","fixed":false,"speaker_id":null},{"begin_time":1160,"end_time":1640,"text":"啊","punctuation":"。","fixed":false,"speaker_id":null}],"stash":{"sentence_id":2,"text":"","begin_time":1640,"current_time":1640,"words":[]}}},"usage":{"duration":2}}};response:{"header":{"task_id":"c8b1e4c10565417e81f8b8509c50c11d","event":"result-generated","attributes":{}},"payload":{"output":{"sentence":{"sentence_id":1,"begin_time":640,"end_time":1640,"text":"你好啊。","channel_id":0,"speaker_id":null,"sentence_end":true,"words":[{"begin_time":640,"end_time":1160,"text":"你好","punctuation":"","fixed":false,"speaker_id":null},{"begin_time":1160,"end_time":1640,"text":"啊","punctuation":"。","fixed":false,"speaker_id":null}],"stash":{"sentence_id":2,"text":"","begin_time":1640,"current_time":1640,"words":[]}}},"usage":{"duration":2}}}
            Constants.NuiEvent.EVENT_SENTENCE_END -> {
                // 句子结束，获取完整结果
                asrResult?.let { result ->
                    val text = extractTextFromResult(result.allResponse)
                    if (!text.isNullOrEmpty()) {
                        Log.d(TAG, "句子结束识别结果: ${'$'}{text}")
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
                mStopping = false
            }
            Constants.NuiEvent.EVENT_MIC_ERROR -> {
                Log.e(TAG, "麦克风错误: $resultCode")
                val error = Exception("麦克风错误: $resultCode")
                recognitionError = error
                onErrorCallback?.invoke(error)
                mStopping = false
            }
            else -> {
                Log.d(TAG, "其他事件: $event")
            }
        }
    }

    override fun onNuiNeedAudioData(buffer: ByteArray?, len: Int): Int {
        if (buffer == null || len <= 0) {
            return -1
        }

        if (mAudioRecorder == null) {
            Log.e(TAG, "音频录制器为空")
            return -1
        }

        if (mAudioRecorder!!.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "音频录制器未初始化")
            return -1
        }

        // 从音频录制器读取数据
        val audioSize = mAudioRecorder!!.read(buffer, 0, len)

        // 存储音频到本地（可选）
        // 这里可以实现音频存储逻辑，如需要的话

        return audioSize
    }

    override fun onNuiAudioStateChanged(state: Constants.AudioState?) {
        Log.i(TAG, "音频状态改变: $state")
        when (state) {
            Constants.AudioState.STATE_OPEN -> {
                Log.i(TAG, "音频录制开始")
                // 确保音频录制器已初始化
                if (mAudioRecorder == null) {
                    Log.e(TAG, "音频录制器未初始化")
                    return
                }
                mAudioRecorder?.let { recorder ->
                    if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                        recorder.startRecording()
                    }
                }
            }
            Constants.AudioState.STATE_CLOSE -> {
                Log.i(TAG, "音频录制关闭")
                mAudioRecorder?.let { recorder ->
                    if (recorder.state == AudioRecord.STATE_INITIALIZED &&
                        recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop()
                    }
                    recorder.release()
                }
                mAudioRecorder = null
            }
            Constants.AudioState.STATE_PAUSE -> {
                Log.i(TAG, "音频录制暂停")
                mAudioRecorder?.let { recorder ->
                    if (recorder.state == AudioRecord.STATE_INITIALIZED &&
                        recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop()
                    }
                }
            }

            else -> {
                Log.i(TAG, "其他音频状态: $state")
            }
        }
    }

    override fun onNuiAudioRMSChanged(`val`: Float) {
        // 音频RMS值变化，可用于显示音量
    }

    override fun onNuiVprEventCallback(event: Constants.NuiVprEvent?) {
        Log.i(TAG, "VPR事件: $event")
    }

    override fun onNuiLogTrackCallback(level: Constants.LogLevel?, log: String?) {
        // SDK内部日志回调
    }
}
