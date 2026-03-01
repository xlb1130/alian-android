package com.alian.assistant.infrastructure.ai.asr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 基于流式ASR引擎的语音识别管理器
 * 使用 DashscopeStreamAsrEngine 实现流式语音识别功能
 */
class StreamingVoiceRecognitionManager(
    private val context: Context,
    private val apiKey: String,
    private val model: String = "fun-asr-realtime-2025-09-15"
) {
    private val tag = "StreamingVoiceRecognitionManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var asrEngine: DashscopeStreamAsrEngine? = null
    private var isListening = false
    
    // 识别结果回调
    private var onResultCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private var onPartialResultCallback: ((String) -> Unit)? = null
    
    /**
     * 设置识别结果回调
     */
    fun setResultCallback(
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onPartialResult: ((String) -> Unit)? = null
    ) {
        onResultCallback = onResult
        onErrorCallback = onError
        onPartialResultCallback = onPartialResult
    }
    
    /**
     * 检查是否正在监听
     */
    fun isCurrentlyListening(): Boolean {
        return isListening && asrEngine?.isRunning == true
    }
    
    /**
     * 开始语音识别
     */
    fun startListening() {
        if (isListening) {
            Log.d(tag, "语音识别已在进行中")
            return
        }
        
        Log.d(tag, "开始流式语音识别")
        
        try {
            // 创建ASR引擎监听器
            val listener = object : StreamingAsrEngine.Listener {
                override fun onFinal(text: String) {
                    Log.d(tag, "收到最终识别结果: $text")
                    isListening = false
                    onResultCallback?.invoke(text)
                }

                override fun onError(message: String) {
                    Log.e(tag, "语音识别错误: $message")
                    isListening = false
                    onErrorCallback?.invoke(message)
                }

                override fun onPartial(text: String) {
                    super.onPartial(text)
                    Log.d(tag, "收到部分识别结果: $text")
                    onPartialResultCallback?.invoke(text)
                }

                override fun onStopped() {
                    super.onStopped()
                    Log.d(tag, "录音阶段结束")
                }

                override fun onAmplitude(amplitude: Float) {
                    super.onAmplitude(amplitude)
                    // 可以在这里处理音频振幅，用于UI波形显示
                }
            }
            
            // 创建并启动ASR引擎
            asrEngine = DashscopeStreamAsrEngine(
                apiKey = apiKey,
                model = model,
                context = context,
                scope = scope,
                listener = listener
            )
            
            asrEngine?.start()
            isListening = true
        } catch (e: Exception) {
            Log.e(tag, "启动语音识别失败", e)
            isListening = false
            onErrorCallback?.invoke("启动语音识别失败: ${e.message}")
        }
    }
    
    /**
     * 停止语音识别
     */
    fun stopListening() {
        Log.d(tag, "停止语音识别，当前状态: $isListening")
        if (isListening) {
            try {
                asrEngine?.stop()
                isListening = false
            } catch (e: Exception) {
                Log.e(tag, "停止语音识别失败", e)
                isListening = false
                onErrorCallback?.invoke("停止语音识别失败: ${e.message}")
            }
        } else {
            Log.d(tag, "语音识别未在运行，无需停止")
        }
    }
    
    /**
     * 取消语音识别
     */
    fun cancelListening() {
        Log.d(tag, "取消语音识别")
        try {
            asrEngine?.stop()  // 对于流式ASR，使用stop即可
            isListening = false
        } catch (e: Exception) {
            Log.e(tag, "取消语音识别失败", e)
            onErrorCallback?.invoke("取消语音识别失败: ${e.message}")
        }
    }
    
    /**
     * 释放资源
     */
    fun destroy() {
        Log.d(tag, "销毁语音识别资源")
        try {
            asrEngine?.stop()
            scope.cancel()
            isListening = false
        } catch (e: Exception) {
            Log.e(tag, "销毁语音识别资源时出错", e)
        }
    }
}