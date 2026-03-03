package com.alian.assistant.infrastructure.ai.provider

import android.content.Context
import com.alian.assistant.data.SettingsManager
import com.alian.assistant.data.model.SpeechModels
import com.alian.assistant.data.model.SpeechProvider
import com.alian.assistant.data.model.SpeechProviderConfig
import com.alian.assistant.infrastructure.ai.asr.AsrConfig
import com.alian.assistant.infrastructure.ai.asr.AsrEngine
import com.alian.assistant.infrastructure.ai.asr.AsrEngineFactory
import com.alian.assistant.infrastructure.ai.asr.AsrListener
import com.alian.assistant.infrastructure.ai.asr.bailian.BailianAsrEngineFactory
import com.alian.assistant.infrastructure.ai.asr.volcano.VolcanoAsrEngineFactory
import com.alian.assistant.infrastructure.ai.tts.TtsConfig
import com.alian.assistant.infrastructure.ai.tts.TtsEngine
import com.alian.assistant.infrastructure.ai.tts.TtsEngineFactory
import com.alian.assistant.infrastructure.ai.tts.bailian.BailianTtsEngineFactory
import com.alian.assistant.infrastructure.ai.tts.volcano.VolcanoTtsEngineFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * ASR/TTS 服务管理器
 * 
 * 负责管理 ASR 和 TTS 引擎的生命周期，根据用户选择的语音服务商提供对应的引擎实例
 */
class SpeechProviderManager(
    private val context: Context,
    private val settingsManager: SettingsManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ASR 引擎工厂
    private val asrFactories: Map<SpeechProvider, AsrEngineFactory> = mapOf(
        SpeechProvider.VOLCANO to VolcanoAsrEngineFactory(context, scope),
        SpeechProvider.BAILIAN to BailianAsrEngineFactory(context, scope)
    )

    // TTS 引擎工厂
    private val ttsFactories: Map<SpeechProvider, TtsEngineFactory> = mapOf(
        SpeechProvider.VOLCANO to VolcanoTtsEngineFactory(),
        SpeechProvider.BAILIAN to BailianTtsEngineFactory()
    )

    // 缓存服务实例
    @Volatile
    private var cachedAsrEngine: AsrEngine? = null
    @Volatile
    private var cachedTtsEngine: TtsEngine? = null
    @Volatile
    private var cachedProvider: SpeechProvider? = null

    /**
     * 获取当前服务商
     */
    val currentProvider: SpeechProvider
        get() = settingsManager.settings.value.speechProvider

    /**
     * 获取当前服务商配置
     */
    val currentProviderConfig: SpeechProviderConfig
        get() = SpeechProviderConfig.get(currentProvider)

    /**
     * 获取 ASR 引擎
     */
    fun getAsrEngine(listener: AsrListener): AsrEngine {
        val provider = currentProvider

        // 服务商变化时清除缓存
        if (cachedProvider != provider) {
            clearCache()
            cachedProvider = provider
        }

        return cachedAsrEngine ?: asrFactories[provider]!!
            .create(buildAsrConfig(provider), listener)
            .also { cachedAsrEngine = it }
    }

    /**
     * 获取 TTS 引擎
     */
    fun getTtsEngine(): TtsEngine {
        val provider = currentProvider

        if (cachedProvider != provider) {
            clearCache()
            cachedProvider = provider
        }

        return cachedTtsEngine ?: ttsFactories[provider]!!
            .create(buildTtsConfig(provider))
            .also { cachedTtsEngine = it }
    }

    /**
     * 服务商切换时清理
     */
    fun onProviderChanged() {
        clearCache()
    }

    /**
     * 清除缓存
     */
    private fun clearCache() {
        cachedAsrEngine?.release()
        cachedTtsEngine?.release()
        cachedAsrEngine = null
        cachedTtsEngine = null
        cachedProvider = null
    }

    /**
     * 构建 ASR 配置
     */
    private fun buildAsrConfig(provider: SpeechProvider): AsrConfig {
        val credentials = settingsManager.getSpeechCredentials(provider)
        val models = settingsManager.settings.value.speechModels[provider] ?: SpeechModels()
        val providerConfig = SpeechProviderConfig.get(provider)

        return AsrConfig(
            apiKey = credentials.apiKey,
            model = models.asrModel.ifEmpty { providerConfig.asrDefaultModel },
            language = "zh",
            appId = credentials.appId,
            cluster = credentials.cluster
        )
    }

    /**
     * 构建 TTS 配置
     */
    private fun buildTtsConfig(provider: SpeechProvider): TtsConfig {
        val credentials = settingsManager.getSpeechCredentials(provider)
        val settings = settingsManager.settings.value
        val models = settings.speechModels[provider] ?: SpeechModels()
        val providerConfig = SpeechProviderConfig.get(provider)

        return TtsConfig(
            apiKey = credentials.apiKey,
            model = models.ttsModel.ifEmpty { providerConfig.ttsDefaultModel },
            voice = settings.ttsVoice,
            speed = settings.ttsSpeed,
            volume = settings.volume,
            appId = credentials.appId,
            cluster = credentials.cluster
        )
    }

    /**
     * 获取所有支持的 ASR 模型列表
     */
    fun getAsrModels(provider: SpeechProvider): List<String> {
        return SpeechProviderConfig.get(provider).asrModels
    }

    /**
     * 获取所有支持的 TTS 模型列表
     */
    fun getTtsModels(provider: SpeechProvider): List<String> {
        return SpeechProviderConfig.get(provider).ttsModels
    }

    /**
     * 检查服务商是否需要 AppId
     */
    fun requiresAppId(provider: SpeechProvider): Boolean {
        return SpeechProviderConfig.get(provider).requiresAppId
    }

    /**
     * 检查服务商是否需要 Cluster
     */
    fun requiresCluster(provider: SpeechProvider): Boolean {
        return SpeechProviderConfig.get(provider).requiresCluster
    }

    companion object {
        @Volatile
        private var instance: SpeechProviderManager? = null

        fun getInstance(context: Context, settingsManager: SettingsManager): SpeechProviderManager {
            return instance ?: synchronized(this) {
                instance ?: SpeechProviderManager(context, settingsManager).also { instance = it }
            }
        }
    }
}
