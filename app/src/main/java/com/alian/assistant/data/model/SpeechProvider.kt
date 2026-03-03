package com.alian.assistant.data.model

/**
 * ASR/TTS 服务商（仅用于语音服务）
 * LLM/VLM 使用独立的 ApiProvider 枚举
 */
enum class SpeechProvider {
    VOLCANO,    // 火山引擎
    BAILIAN;    // 百炼（阿里云 DashScope）

    companion object {
        val DEFAULT = BAILIAN
    }
}

/**
 * ASR/TTS 服务商凭证
 */
data class SpeechProviderCredentials(
    val apiKey: String = "",
    // 火山引擎特有
    val appId: String = "",
    val cluster: String = ""
)

/**
 * 用户选择的 ASR/TTS 模型
 */
data class SpeechModels(
    val asrModel: String = "",
    val ttsModel: String = ""
)

/**
 * 服务商静态配置
 */
data class SpeechProviderConfig(
    val provider: SpeechProvider,
    val displayName: String,
    val asrDefaultModel: String,
    val asrModels: List<String>,
    val ttsDefaultModel: String,
    val ttsModels: List<String>,
    val requiresAppId: Boolean = false,  // 火山引擎需要
    val requiresCluster: Boolean = false // 火山引擎 TTS 需要
) {
    companion object {
        val VOLCANO = SpeechProviderConfig(
            provider = SpeechProvider.VOLCANO,
            displayName = "火山引擎",
            asrDefaultModel = "bigmodel",
            asrModels = listOf("bigmodel", "smallmodel"),
            ttsDefaultModel = "volcano_tts",
            ttsModels = listOf("volcano_tts"),
            requiresAppId = true,
            requiresCluster = true
        )

        val BAILIAN = SpeechProviderConfig(
            provider = SpeechProvider.BAILIAN,
            displayName = "百炼",
            asrDefaultModel = "fun-asr-realtime-2025-09-15",
            asrModels = listOf(
                "fun-asr-realtime-2025-09-15",
                "qwen3-asr-flash-realtime"
            ),
            ttsDefaultModel = "cosyvoice-v3-flash",
            ttsModels = listOf("cosyvoice-v3-flash", "cosyvoice-v2")
        )

        val ALL = listOf(VOLCANO, BAILIAN)

        fun get(provider: SpeechProvider): SpeechProviderConfig {
            return ALL.find { it.provider == provider } ?: BAILIAN
        }
    }
}
