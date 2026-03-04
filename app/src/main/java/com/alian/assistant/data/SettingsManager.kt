package com.alian.assistant.data

import android.content.Context
import com.alian.assistant.common.utils.AvatarCacheManager
import java.io.File
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.alian.assistant.data.model.SpeechModels
import com.alian.assistant.data.model.SpeechProvider
import com.alian.assistant.data.model.SpeechProviderCredentials
import com.alian.assistant.presentation.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * API 提供商配置
 */
data class ApiProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val defaultModel: String,
    val isGUIAgent: Boolean = false  // 是否为 GUI Agent 专用协议（非 OpenAI 兼容）
) {
    companion object {
        val GUI_OWL = ApiProvider(
            id = "gui_owl",
            name = "GUI-Owl (阿里云)",
            baseUrl = "https://dashscope.aliyuncs.com/api/v2/apps/gui-owl/gui_agent_server",
            defaultModel = "pre-gui_owl_7b",
            isGUIAgent = true
        )
        val MAI_UI = ApiProvider(
            id = "mai_ui",
            name = "MAI-UI (本地部署)",
            baseUrl = "http://localhost:8000/v1",  // vLLM 默认地址
            defaultModel = "MAI-UI-2B"  // 支持 MAI-UI-2B 或 MAI-UI-8B
        )
        val ALIYUN = ApiProvider(
            id = "aliyun",
            name = "阿里云 (Qwen-VL)",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            defaultModel = "qwen3-vl-plus"
        )
        val OPENAI = ApiProvider(
            id = "openai",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-4o"
        )
        val OPENROUTER = ApiProvider(
            id = "openrouter",
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            defaultModel = "anthropic/claude-3.5-sonnet"
        )
        val CUSTOM = ApiProvider(
            id = "custom",
            name = "自定义",
            baseUrl = "",
            defaultModel = ""
        )

        val ALL = listOf(GUI_OWL, MAI_UI, ALIYUN, OPENAI, OPENROUTER, CUSTOM)
    }
}

/**
 * 服务商配置（每个服务商独立保存）
 */
data class ProviderConfig(
    val apiKey: String = "",
    val model: String = "",  // VLM模型（视觉模型）
    val textModel: String = "",  // 文本模型
    val speechModel: String = "",  // 语音识别模型
    val cachedModels: List<String> = emptyList(),
    val customBaseUrl: String = ""  // 仅 custom 服务商使用
)

/**
 * 默认推荐模型
 */
const val DEFAULT_MODEL = "qwen3-vl-plus"

/**
 * 应用设置
 */
data class AppSettings(
    val currentProviderId: String = ApiProvider.ALIYUN.id,  // 当前选中的服务商
    val providerConfigs: Map<String, ProviderConfig> = emptyMap(),  // 每个服务商的配置
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val hasSeenOnboarding: Boolean = false,
    val maxSteps: Int = 25,
    val cloudCrashReportEnabled: Boolean = true,
    val rootModeEnabled: Boolean = false,
    val suCommandEnabled: Boolean = false,
    val useBackend: Boolean = true,  // 是否使用Backend API（Alian聊天）
    val backendBaseUrl: String = "http://39.98.113.244:5173/api/v1",  // Backend服务器地址
    val ttsEnabled: Boolean = false,  // 是否启用TTS语音播放
    val ttsRealtime: Boolean = false,  // 是否实时播放（机器人输出时立即播放）
    val ttsVoice: String = "longyingmu_v3",  // TTS音色
    val ttsSpeed: Float = 1.0f,  // TTS语速，取值范围 [0.5, 2.0]，1.0为标准语速
    val ttsInterruptEnabled: Boolean = false,  // 是否启用实时语音打断（TTS播放时检测用户说话并停止）
    val enableAEC: Boolean = false,  // 是否启用 AEC（回声消除）用于语音打断
    val enableStreaming: Boolean = false,  // 是否启用流式 LLM + 流式 TTS（边生成边播放）
    val volume: Int = 50,  // 音量，取值范围 [0, 100]，50 为标准音量
    val assistantAvatar: String = "",  // 艾莲头像路径
    val userAvatar: String = "",  // 用户头像路径
    val voiceCallSystemPrompt: String = "你叫艾莲，女性，一位温柔体贴的语音陪伴助手。你的声音柔和自然，语调亲切稳重，善于倾听和回应。你善于营造轻松愉快的对话氛围，既能提供有价值的帮助。请保持回应简洁明了，每次回复控制在1到5句话之间，避免冗长。",  // 语音通话系统提示词
    val videoCallSystemPrompt: String = "你叫艾莲，女性，一位全能的视频助手。你的声音柔和自然，语调亲切稳重。通过视频画面，你能够观察用户所在的环境，从而更好地理解用户的需求,并通过已有工具解答用户问题。请保持回应简洁明了，每次回复控制在1到5句话之间，避免冗长。",  // 视频通话系统提示词
    val enableBatchExecution: Boolean = false,  // 是否启用批量执行模式（多步执行）
    val enableImproveMode: Boolean = true,  // 是否启用 Improve 模式（优化版 Agent，减少 VLM 调用次数）
    val reactOnly: Boolean = true,  // ReactOnly 模式：Manager 只规划一次，后续全靠 Executor 执行
    val enableChatAgent: Boolean = true,  // 是否启用 ChatAgent 模式（语音打断和实时交互）
    val enableFlowMode: Boolean = true,  // 是否启用 Flow 模式（流程模板自动沉淀和学习）
    
    // ========== 设备控制器配置 ==========
    val executionStrategy: String = "accessibility_only",  // 执行策略: auto, shizuku_only, accessibility_only, hybrid
    val fallbackStrategy: String = "auto",  // 降级策略: auto, shizuku_first, accessibility_first
    val screenshotCacheEnabled: Boolean = true,  // 是否启用截图缓存
    val gestureDelayMs: Int = 100,  // 手势延迟（毫秒）
    val inputDelayMs: Int = 50,  // 输入延迟（毫秒）
    val swipeVelocity: Float = 0.5f,  // 滑动力度/速度，范围 0.0-1.0，默认 0.5（中等）

    // ========== 手机通话配置 ==========
    val phoneCallEnabled: Boolean = true,  // 是否启用手机通话
    val phoneCallAutoOperate: Boolean = true,  // 是否自动执行操作
    val phoneCallConfirmBeforeAction: Boolean = false,  // 操作前是否确认
    val phoneCallShowOperationDetails: Boolean = true,  // 是否显示操作详情
    val phoneCallMaxOperationSteps: Int = 10,  // 最大操作步数
    val phoneCallOperationTimeout: Int = 5000,  // 操作超时时间（毫秒）
    val phoneCallFloatingModeEnabled: Boolean = true,  // 是否启用浮动窗口模式
    val phoneCallFloatingWindowOpacity: Float = 0.95f,  // 浮动窗口透明度 (0.5-1.0)
    val phoneCallFloatingWindowAutoHide: Boolean = true,  // 用户操作时自动隐藏
    val phoneCallFloatingWindowRememberPosition: Boolean = true,  // 记住窗口位置
    val phoneCallFloatingWindowEdgeSnap: Boolean = true,  // 边缘吸附
    val phoneCallFloatingWindowMinimizeOnTap: Boolean = false,  // 点击空白处最小化

    // ========== ASR/TTS 服务商配置 ==========
    val speechProvider: SpeechProvider = SpeechProvider.BAILIAN,  // 当前 ASR/TTS 服务商
    val speechCredentials: Map<SpeechProvider, SpeechProviderCredentials> = emptyMap(),  // 各服务商凭证
    val speechModels: Map<SpeechProvider, SpeechModels> = emptyMap()  // 各服务商选择的模型
) {
    // 便捷属性：获取当前服务商的配置
    val currentConfig: ProviderConfig
        get() = providerConfigs[currentProviderId] ?: ProviderConfig()

    val currentProvider: ApiProvider
        get() = ApiProvider.ALL.find { it.id == currentProviderId } ?: ApiProvider.ALIYUN

    val apiKey: String get() = currentConfig.apiKey
    val model: String get() = currentConfig.model.ifEmpty { currentProvider.defaultModel }
    val textModel: String get() = currentConfig.textModel.ifEmpty { "qwen-max" }  // 默认文本模型
    val speechModel: String get() = currentConfig.speechModel.ifEmpty { "fun-asr-realtime-2025-09-15" }  // 默认语音识别模型
    val cachedModels: List<String> get() = currentConfig.cachedModels

    val baseUrl: String
        get() = when {
            currentProviderId == "custom" -> currentConfig.customBaseUrl
            // MAI-UI 支持自定义 URL（用于远程部署）
            currentProviderId == "mai_ui" && currentConfig.customBaseUrl.isNotEmpty() -> currentConfig.customBaseUrl
            else -> currentProvider.baseUrl
        }
}

/**
 * 设置管理器
 */
class SettingsManager(context: Context) {

    // 头像缓存管理器
    private val avatarCacheManager = AvatarCacheManager(context)

    // 普通设置存储
    private val prefs: SharedPreferences =
        context.getSharedPreferences("baozi_settings", Context.MODE_PRIVATE)

    // 加密存储（用于敏感数据如 API Key）
    private val securePrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                "baozi_secure_settings",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // 加密失败时回退到普通存储（不应该发生）
            android.util.Log.e("SettingsManager", "Failed to create encrypted prefs", e)
            prefs
        }
    }

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings

    init {
        // 迁移旧的明文 API Key 到加密存储
        migrateApiKeyToSecureStorage()
    }

    /**
     * 加载头像路径
     * 优先使用缓存路径，如果缓存路径不存在，则使用SharedPreferences中的路径
     */
    private fun loadAvatarPath(prefKey: String, cachedPath: String?): String {
        val prefPath = prefs.getString(prefKey, "") ?: ""
        // 如果缓存路径存在且文件存在，使用缓存路径
        if (!cachedPath.isNullOrEmpty() && File(cachedPath).exists()) {
            // 如果SharedPreferences中的路径与缓存路径不同，更新SharedPreferences
            if (prefPath != cachedPath) {
                prefs.edit().putString(prefKey, cachedPath).apply()
            }
            return cachedPath
        }
        // 如果SharedPreferences中的路径对应的文件存在，使用SharedPreferences中的路径
        if (prefPath.isNotEmpty() && File(prefPath).exists()) {
            return prefPath
        }
        // 都不存在，返回空字符串
        return ""
    }
    
    /**
     * 迁移旧的明文 API Key 到加密存储
         */
        private fun migrateApiKeyToSecureStorage() {        val oldApiKey = prefs.getString("api_key", null)
        if (!oldApiKey.isNullOrEmpty()) {
            // 保存到加密存储
            securePrefs.edit().putString("api_key", oldApiKey).apply()
            // 删除旧的明文存储
            prefs.edit().remove("api_key").apply()
            android.util.Log.d("SettingsManager", "API Key migrated to secure storage")
        }
    }

    private fun loadSettings(): AppSettings {
        val themeModeStr = prefs.getString("theme_mode", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
        val themeMode = try {
            ThemeMode.valueOf(themeModeStr)
        } catch (e: Exception) {
            ThemeMode.SYSTEM
        }

        // 加载当前选中的服务商
        val currentProviderId = prefs.getString("current_provider_id", ApiProvider.ALIYUN.id) ?: ApiProvider.ALIYUN.id

        // 加载每个服务商的配置
        val providerConfigs = mutableMapOf<String, ProviderConfig>()
        for (provider in ApiProvider.ALL) {
            val config = loadProviderConfig(provider.id)
            providerConfigs[provider.id] = config
        }

        // 迁移旧数据（如果有）
        val oldApiKey = securePrefs.getString("api_key", null)
        val oldModel = prefs.getString("model", null)
        val oldBaseUrl = prefs.getString("base_url", null)
        val oldCachedModels = prefs.getStringSet("cached_models", null)

        if (oldApiKey != null || oldModel != null) {
            // 找到旧数据对应的服务商
            val oldProviderId = when (oldBaseUrl) {
                ApiProvider.ALIYUN.baseUrl -> ApiProvider.ALIYUN.id
                ApiProvider.OPENAI.baseUrl -> ApiProvider.OPENAI.id
                ApiProvider.OPENROUTER.baseUrl -> ApiProvider.OPENROUTER.id
                else -> "custom"
            }

            // 迁移到新格式
            val migratedConfig = ProviderConfig(
                apiKey = oldApiKey ?: "",
                model = oldModel ?: "",
                speechModel = "",  // 新增语音识别模型字段
                cachedModels = oldCachedModels?.toList() ?: emptyList(),
                customBaseUrl = if (oldProviderId == "custom") oldBaseUrl ?: "" else ""
            )
            providerConfigs[oldProviderId] = migratedConfig
            saveProviderConfig(oldProviderId, migratedConfig)

            // 清除旧数据
            securePrefs.edit().remove("api_key").apply()
            prefs.edit()
                .remove("model")
                .remove("base_url")
                .remove("cached_models")
                .putString("current_provider_id", oldProviderId)
                .apply()

            android.util.Log.d("SettingsManager", "Migrated old settings to provider: $oldProviderId")
        }

        return AppSettings(
            currentProviderId = currentProviderId,
            providerConfigs = providerConfigs,
            themeMode = themeMode,
            hasSeenOnboarding = prefs.getBoolean("has_seen_onboarding", false),
            maxSteps = prefs.getInt("max_steps", 25),
            cloudCrashReportEnabled = prefs.getBoolean("cloud_crash_report_enabled", true),
            rootModeEnabled = prefs.getBoolean("root_mode_enabled", false),
            suCommandEnabled = prefs.getBoolean("su_command_enabled", false),
            useBackend = prefs.getBoolean("use_backend", true),
            backendBaseUrl = prefs.getString("backend_base_url", "http://39.98.113.244:5173/api/v1") ?: "http://39.98.113.244:5173/api/v1",
            ttsEnabled = prefs.getBoolean("tts_enabled", false),
            ttsRealtime = prefs.getBoolean("tts_realtime", false),
            ttsVoice = prefs.getString("tts_voice", "longyingmu_v3") ?: "longyingmu_v3",
            ttsSpeed = prefs.getFloat("tts_speed", 1.0f),
            ttsInterruptEnabled = prefs.getBoolean("tts_interrupt_enabled", false),
            enableAEC = prefs.getBoolean("enable_aec", false),
            enableStreaming = prefs.getBoolean("enable_streaming", false),
            volume = prefs.getInt("volume", 50),
            assistantAvatar = loadAvatarPath("assistant_avatar", avatarCacheManager.getAssistantAvatarPath()),
            userAvatar = loadAvatarPath("user_avatar", avatarCacheManager.getUserAvatarPath()),
            voiceCallSystemPrompt = prefs.getString("voice_call_system_prompt", "你叫艾莲，女性，一位温柔体贴的语音陪伴助手。你的声音柔和自然，语调亲切稳重，善于倾听和回应。你善于营造轻松愉快的对话氛围，既能提供有价值的帮助。请保持回应简洁明了，每次回复控制在1到5句话之间，避免冗长。") ?: "你叫艾莲，女性，一位温柔体贴的语音陪伴助手。你的声音柔和自然，语调亲切稳重，善于倾听和回应。你善于营造轻松愉快的对话氛围，既能提供有价值的帮助。请保持回应简洁明了，每次回复控制在1到5句话之间，避免冗长。",
            videoCallSystemPrompt = prefs.getString("video_call_system_prompt", "你叫艾莲，女性，一位全能的视频助手。你的声音柔和自然，语调亲切稳重。通过视频画面，你能够观察用户所在的环境，从而更好地理解用户的需求,并通过已有工具解答用户问题。请保持回应简洁明了，每次回复控制在1到5句话之间，避免冗长。") ?: "你叫艾莲，女性，一位全能的视频助手。你的声音柔和自然，语调亲切稳重。通过视频画面，你能够观察用户所在的环境，从而更好地理解用户的需求,并通过已有工具解答用户问题。请保持回应简洁明了，每次回复控制在1到5句话之间，避免冗长。",
            enableBatchExecution = prefs.getBoolean("enable_batch_execution", true),
            enableImproveMode = prefs.getBoolean("enable_improve_mode", true),
            reactOnly = prefs.getBoolean("react_only", true),
            enableChatAgent = prefs.getBoolean("enable_chat_agent", true),
            enableFlowMode = prefs.getBoolean("enable_flow_mode", true),
            executionStrategy = prefs.getString("execution_strategy", "accessibility_only") ?: "accessibility_only",
            fallbackStrategy = prefs.getString("fallback_strategy", "auto") ?: "auto",
            screenshotCacheEnabled = prefs.getBoolean("screenshot_cache_enabled", true),
            gestureDelayMs = prefs.getInt("gesture_delay_ms", 100),
            inputDelayMs = prefs.getInt("input_delay_ms", 50),
            swipeVelocity = prefs.getFloat("swipe_velocity", 0.5f),
            phoneCallEnabled = prefs.getBoolean("phone_call_enabled", true),
            phoneCallAutoOperate = prefs.getBoolean("phone_call_auto_operate", true),
            phoneCallConfirmBeforeAction = prefs.getBoolean("phone_call_confirm_before_action", false),
            phoneCallShowOperationDetails = prefs.getBoolean("phone_call_show_operation_details", true),
            phoneCallMaxOperationSteps = prefs.getInt("phone_call_max_operation_steps", 10),
            phoneCallOperationTimeout = prefs.getInt("phone_call_operation_timeout", 5000),
            phoneCallFloatingModeEnabled = prefs.getBoolean("phone_call_floating_mode_enabled", true),
            phoneCallFloatingWindowOpacity = prefs.getFloat("phone_call_floating_window_opacity", 0.95f),
            phoneCallFloatingWindowAutoHide = prefs.getBoolean("phone_call_floating_window_auto_hide", true),
            phoneCallFloatingWindowRememberPosition = prefs.getBoolean("phone_call_floating_window_remember_position", true),
            phoneCallFloatingWindowEdgeSnap = prefs.getBoolean("phone_call_floating_window_edge_snap", true),
            phoneCallFloatingWindowMinimizeOnTap = prefs.getBoolean("phone_call_floating_window_minimize_on_tap", false),
            
            // 加载 ASR/TTS 服务商配置
            speechProvider = loadSpeechProvider(),
            speechCredentials = loadAllSpeechCredentials(),
            speechModels = loadAllSpeechModels()
        ).also {
            android.util.Log.d("SettingsManager", "loadSettings - executionStrategy: ${it.executionStrategy}")
        }
    }

    /**
     * 加载 ASR/TTS 服务商
     */
    private fun loadSpeechProvider(): SpeechProvider {
        val providerName = prefs.getString("speech_provider", SpeechProvider.BAILIAN.name) ?: SpeechProvider.BAILIAN.name
        return try {
            SpeechProvider.valueOf(providerName)
        } catch (e: Exception) {
            SpeechProvider.BAILIAN
        }
    }

    /**
     * 加载所有服务商凭证
     */
    private fun loadAllSpeechCredentials(): Map<SpeechProvider, SpeechProviderCredentials> {
        val credentials = mutableMapOf<SpeechProvider, SpeechProviderCredentials>()
        for (provider in SpeechProvider.entries) {
            val cred = loadSpeechCredentials(provider)
            if (cred.apiKey.isNotEmpty() || cred.appId.isNotEmpty()) {
                credentials[provider] = cred
            }
        }
        return credentials
    }

    /**
     * 加载所有服务商模型选择
     */
    private fun loadAllSpeechModels(): Map<SpeechProvider, SpeechModels> {
        val models = mutableMapOf<SpeechProvider, SpeechModels>()
        for (provider in SpeechProvider.entries) {
            val model = loadSpeechModels(provider)
            if (model.asrModel.isNotEmpty() || model.ttsModel.isNotEmpty()) {
                models[provider] = model
            }
        }
        return models
    }

    /**
     * 加载指定服务商的配置
     */
    private fun loadProviderConfig(providerId: String): ProviderConfig {
        val prefix = "provider_${providerId}_"
        return ProviderConfig(
            apiKey = (securePrefs.getString("${prefix}api_key", "") ?: "").trim(),
            model = prefs.getString("${prefix}model", "") ?: "",
            textModel = prefs.getString("${prefix}text_model", "") ?: "",
            speechModel = prefs.getString("${prefix}speech_model", "") ?: "",
            cachedModels = prefs.getStringSet("${prefix}cached_models", emptySet())?.toList() ?: emptyList(),
            customBaseUrl = prefs.getString("${prefix}custom_base_url", "") ?: ""
        )
    }

    /**
     * 保存指定服务商的配置
     */
    private fun saveProviderConfig(providerId: String, config: ProviderConfig) {
        val prefix = "provider_${providerId}_"
        securePrefs.edit().putString("${prefix}api_key", config.apiKey).apply()
        prefs.edit()
            .putString("${prefix}model", config.model)
            .putString("${prefix}text_model", config.textModel)
            .putString("${prefix}speech_model", config.speechModel)
            .putStringSet("${prefix}cached_models", config.cachedModels.toSet())
            .putString("${prefix}custom_base_url", config.customBaseUrl)
            .apply()
    }

    /**
     * 更新当前服务商的配置
     */
    private fun updateCurrentConfig(update: (ProviderConfig) -> ProviderConfig) {
        val currentId = _settings.value.currentProviderId
        val currentConfig = _settings.value.currentConfig
        val newConfig = update(currentConfig)

        saveProviderConfig(currentId, newConfig)

        val newConfigs = _settings.value.providerConfigs.toMutableMap()
        newConfigs[currentId] = newConfig
        _settings.value = _settings.value.copy(providerConfigs = newConfigs)
    }

    fun updateApiKey(apiKey: String) {
        updateCurrentConfig { it.copy(apiKey = apiKey.trim()) }
    }

    fun updateBaseUrl(baseUrl: String) {
        // 自定义服务商和 MAI-UI 可以修改 URL
        val providerId = _settings.value.currentProviderId
        if (providerId == "custom" || providerId == "mai_ui") {
            updateCurrentConfig { it.copy(customBaseUrl = baseUrl) }
        }
    }

    fun updateModel(model: String) {
        updateCurrentConfig { it.copy(model = model) }
    }

    fun updateTextModel(textModel: String) {
        updateCurrentConfig { it.copy(textModel = textModel) }
    }

    fun updateSpeechModel(speechModel: String) {
        updateCurrentConfig { it.copy(speechModel = speechModel) }
    }

    /**
     * 更新缓存的模型列表（从 API 获取后调用）
     */
    fun updateCachedModels(models: List<String>) {
        val distinctModels = models.distinct()
        updateCurrentConfig { it.copy(cachedModels = distinctModels) }
    }

    /**
     * 清空缓存的模型列表
     */
    fun clearCachedModels() {
        updateCurrentConfig { it.copy(cachedModels = emptyList()) }
    }

    /**
     * 选择服务商（切换时自动加载该服务商的配置）
     */
    fun selectProvider(provider: ApiProvider) {
        prefs.edit().putString("current_provider_id", provider.id).apply()
        _settings.value = _settings.value.copy(currentProviderId = provider.id)
    }

    /**
     * 获取当前服务商
     */
    fun getCurrentProvider(): ApiProvider {
        return _settings.value.currentProvider
    }

    /**
     * 判断是否使用自定义 URL
     */
    fun isCustomUrl(): Boolean {
        return _settings.value.currentProviderId == "custom"
    }

    fun updateThemeMode(themeMode: ThemeMode) {
        prefs.edit().putString("theme_mode", themeMode.name).apply()
        _settings.value = _settings.value.copy(themeMode = themeMode)
    }

    fun setOnboardingSeen() {
        prefs.edit().putBoolean("has_seen_onboarding", true).apply()
        _settings.value = _settings.value.copy(hasSeenOnboarding = true)
    }

    fun updateMaxSteps(maxSteps: Int) {
        val validSteps = maxSteps.coerceIn(5, 100) // 限制范围 5-100
        prefs.edit().putInt("max_steps", validSteps).apply()
        _settings.value = _settings.value.copy(maxSteps = validSteps)
    }

    fun updateCloudCrashReportEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("cloud_crash_report_enabled", enabled).apply()
        _settings.value = _settings.value.copy(cloudCrashReportEnabled = enabled)
    }

    fun updateRootModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("root_mode_enabled", enabled).apply()
        _settings.value = _settings.value.copy(rootModeEnabled = enabled)
        // 关闭 Root 模式时，同时关闭 su -c
        if (!enabled) {
            updateSuCommandEnabled(false)
        }
    }

    fun updateSuCommandEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("su_command_enabled", enabled).apply()
        _settings.value = _settings.value.copy(suCommandEnabled = enabled)
    }

    fun updateUseBackend(enabled: Boolean) {
        prefs.edit().putBoolean("use_backend", enabled).apply()
        _settings.value = _settings.value.copy(useBackend = enabled)
    }

    fun updateBackendBaseUrl(url: String) {
        prefs.edit().putString("backend_base_url", url).apply()
        _settings.value = _settings.value.copy(backendBaseUrl = url)
    }

    fun updateTTSEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("tts_enabled", enabled).apply()
        _settings.value = _settings.value.copy(ttsEnabled = enabled)
    }

    fun updateTTSRealtime(enabled: Boolean) {
        prefs.edit().putBoolean("tts_realtime", enabled).apply()
        _settings.value = _settings.value.copy(ttsRealtime = enabled)
    }

    fun updateTTSVoice(voice: String) {
        prefs.edit().putString("tts_voice", voice).apply()
        _settings.value = _settings.value.copy(ttsVoice = voice)
    }

    fun updateTTSSpeed(speed: Float) {
        // 限制语速范围在 [0.5, 2.0]
        val validSpeed = speed.coerceIn(0.5f, 2.0f)
        prefs.edit().putFloat("tts_speed", validSpeed).apply()
        _settings.value = _settings.value.copy(ttsSpeed = validSpeed)
    }

    fun updateTTSInterruptEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("tts_interrupt_enabled", enabled).apply()
        _settings.value = _settings.value.copy(ttsInterruptEnabled = enabled)
    }

    fun updateEnableAEC(enabled: Boolean) {
        prefs.edit().putBoolean("enable_aec", enabled).apply()
        _settings.value = _settings.value.copy(enableAEC = enabled)
    }

    fun updateEnableStreaming(enabled: Boolean) {
        prefs.edit().putBoolean("enable_streaming", enabled).apply()
        _settings.value = _settings.value.copy(enableStreaming = enabled)
    }

    fun updateVoiceCallSystemPrompt(prompt: String) {
        prefs.edit().putString("voice_call_system_prompt", prompt).apply()
        _settings.value = _settings.value.copy(voiceCallSystemPrompt = prompt)
    }

    fun updateVideoCallSystemPrompt(prompt: String) {
        prefs.edit().putString("video_call_system_prompt", prompt).apply()
        _settings.value = _settings.value.copy(videoCallSystemPrompt = prompt)
    }

    fun updateVolume(volume: Int) {
        // 限制音量范围在 [0, 100]
        val validVolume = volume.coerceIn(0, 100)
        prefs.edit().putInt("volume", validVolume).apply()
        _settings.value = _settings.value.copy(volume = validVolume)
    }

    fun updateAssistantAvatar(avatarPath: String) {
        prefs.edit().putString("assistant_avatar", avatarPath).apply()
        _settings.value = _settings.value.copy(assistantAvatar = avatarPath)
    }

    fun updateUserAvatar(avatarPath: String) {
        prefs.edit().putString("user_avatar", avatarPath).apply()
        _settings.value = _settings.value.copy(userAvatar = avatarPath)
    }

    fun updateEnableBatchExecution(enabled: Boolean) {
        prefs.edit().putBoolean("enable_batch_execution", enabled).apply()
        _settings.value = _settings.value.copy(enableBatchExecution = enabled)
    }

    fun updateEnableImproveMode(enabled: Boolean) {
        prefs.edit().putBoolean("enable_improve_mode", enabled).apply()
        _settings.value = _settings.value.copy(enableImproveMode = enabled)
    }

    fun updateReactOnly(enabled: Boolean) {
        prefs.edit().putBoolean("react_only", enabled).apply()
        _settings.value = _settings.value.copy(reactOnly = enabled)
    }

    fun updateEnableChatAgent(enabled: Boolean) {
        prefs.edit().putBoolean("enable_chat_agent", enabled).apply()
        _settings.value = _settings.value.copy(enableChatAgent = enabled)
    }

    fun updateEnableFlowMode(enabled: Boolean) {
        prefs.edit().putBoolean("enable_flow_mode", enabled).apply()
        _settings.value = _settings.value.copy(enableFlowMode = enabled)
    }
    
    // ========== 设备控制器配置更新方法 ==========
    
    fun updateExecutionStrategy(strategy: String) {
        android.util.Log.d("SettingsManager", "updateExecutionStrategy - old: ${_settings.value.executionStrategy}, new: $strategy")
        prefs.edit().putString("execution_strategy", strategy).apply()
        _settings.value = _settings.value.copy(executionStrategy = strategy)
        android.util.Log.d("SettingsManager", "updateExecutionStrategy - updated: ${_settings.value.executionStrategy}")
    }
    
    fun updateFallbackStrategy(strategy: String) {
        prefs.edit().putString("fallback_strategy", strategy).apply()
        _settings.value = _settings.value.copy(fallbackStrategy = strategy)
    }
    
    fun updateScreenshotCacheEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("screenshot_cache_enabled", enabled).apply()
        _settings.value = _settings.value.copy(screenshotCacheEnabled = enabled)
    }
    
    fun updateGestureDelayMs(delayMs: Int) {
        val validDelay = delayMs.coerceIn(0, 1000)
        prefs.edit().putInt("gesture_delay_ms", validDelay).apply()
        _settings.value = _settings.value.copy(gestureDelayMs = validDelay)
    }
    
    fun updateInputDelayMs(delayMs: Int) {
        val validDelay = delayMs.coerceIn(0, 500)
        prefs.edit().putInt("input_delay_ms", validDelay).apply()
        _settings.value = _settings.value.copy(inputDelayMs = validDelay)
    }

    fun updateSwipeVelocity(velocity: Float) {
        val validVelocity = velocity.coerceIn(0f, 1f)
        prefs.edit().putFloat("swipe_velocity", validVelocity).apply()
        _settings.value = _settings.value.copy(swipeVelocity = validVelocity)
    }
    
    // ========== 手机通话配置更新方法 ==========

    fun updatePhoneCallEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("phone_call_enabled", enabled).apply()
        _settings.value = _settings.value.copy(phoneCallEnabled = enabled)
    }

    fun updatePhoneCallAutoOperate(autoOperate: Boolean) {
        prefs.edit().putBoolean("phone_call_auto_operate", autoOperate).apply()
        _settings.value = _settings.value.copy(phoneCallAutoOperate = autoOperate)
    }

    fun updatePhoneCallConfirmBeforeAction(confirm: Boolean) {
        prefs.edit().putBoolean("phone_call_confirm_before_action", confirm).apply()
        _settings.value = _settings.value.copy(phoneCallConfirmBeforeAction = confirm)
    }

    fun updatePhoneCallShowOperationDetails(show: Boolean) {
        prefs.edit().putBoolean("phone_call_show_operation_details", show).apply()
        _settings.value = _settings.value.copy(phoneCallShowOperationDetails = show)
    }

    fun updatePhoneCallMaxOperationSteps(steps: Int) {
        val validSteps = steps.coerceIn(1, 50)
        prefs.edit().putInt("phone_call_max_operation_steps", validSteps).apply()
        _settings.value = _settings.value.copy(phoneCallMaxOperationSteps = validSteps)
    }

    fun updatePhoneCallOperationTimeout(timeoutMs: Int) {
        val validTimeout = timeoutMs.coerceIn(1000, 30000)
        prefs.edit().putInt("phone_call_operation_timeout", validTimeout).apply()
        _settings.value = _settings.value.copy(phoneCallOperationTimeout = validTimeout)
    }

    fun updatePhoneCallFloatingModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("phone_call_floating_mode_enabled", enabled).apply()
        _settings.value = _settings.value.copy(phoneCallFloatingModeEnabled = enabled)
    }

    fun updatePhoneCallFloatingWindowOpacity(opacity: Float) {
        val validOpacity = opacity.coerceIn(0.5f, 1.0f)
        prefs.edit().putFloat("phone_call_floating_window_opacity", validOpacity).apply()
        _settings.value = _settings.value.copy(phoneCallFloatingWindowOpacity = validOpacity)
    }

    fun updatePhoneCallFloatingWindowAutoHide(autoHide: Boolean) {
        prefs.edit().putBoolean("phone_call_floating_window_auto_hide", autoHide).apply()
        _settings.value = _settings.value.copy(phoneCallFloatingWindowAutoHide = autoHide)
    }

    fun updatePhoneCallFloatingWindowRememberPosition(remember: Boolean) {
        prefs.edit().putBoolean("phone_call_floating_window_remember_position", remember).apply()
        _settings.value = _settings.value.copy(phoneCallFloatingWindowRememberPosition = remember)
    }

    fun updatePhoneCallFloatingWindowEdgeSnap(edgeSnap: Boolean) {
        prefs.edit().putBoolean("phone_call_floating_window_edge_snap", edgeSnap).apply()
        _settings.value = _settings.value.copy(phoneCallFloatingWindowEdgeSnap = edgeSnap)
    }

    fun updatePhoneCallFloatingWindowMinimizeOnTap(minimizeOnTap: Boolean) {
        prefs.edit().putBoolean("phone_call_floating_window_minimize_on_tap", minimizeOnTap).apply()
        _settings.value = _settings.value.copy(phoneCallFloatingWindowMinimizeOnTap = minimizeOnTap)
    }

    // ========== ASR/TTS 服务商配置方法 ==========

    /**
     * 切换 ASR/TTS 服务商
     */
    fun selectSpeechProvider(provider: SpeechProvider) {
        prefs.edit().putString("speech_provider", provider.name).apply()
        _settings.value = _settings.value.copy(speechProvider = provider)
    }

    /**
     * 更新服务商凭证
     */
    fun updateSpeechCredentials(
        provider: SpeechProvider,
        credentials: SpeechProviderCredentials
    ) {
        val newCredentials = _settings.value.speechCredentials.toMutableMap()
        newCredentials[provider] = credentials

        // 加密存储 API Key
        securePrefs.edit()
            .putString("speech_${provider.name}_api_key", credentials.apiKey)
            .putString("speech_${provider.name}_app_id", credentials.appId)
            .putString("speech_${provider.name}_cluster", credentials.cluster)
            .apply()

        _settings.value = _settings.value.copy(speechCredentials = newCredentials)
    }

    /**
     * 获取当前服务商凭证
     */
    fun getSpeechCredentials(provider: SpeechProvider): SpeechProviderCredentials {
        return _settings.value.speechCredentials[provider] ?: loadSpeechCredentials(provider)
    }

    /**
     * 从存储加载服务商凭证
     */
    private fun loadSpeechCredentials(provider: SpeechProvider): SpeechProviderCredentials {
        return SpeechProviderCredentials(
            apiKey = (securePrefs.getString("speech_${provider.name}_api_key", "") ?: "").trim(),
            appId = prefs.getString("speech_${provider.name}_app_id", "") ?: "",
            cluster = prefs.getString("speech_${provider.name}_cluster", "") ?: ""
        )
    }

    /**
     * 更新服务商模型选择
     */
    fun updateSpeechModels(provider: SpeechProvider, models: SpeechModels) {
        val newModels = _settings.value.speechModels.toMutableMap()
        newModels[provider] = models
        prefs.edit()
            .putString("speech_${provider.name}_asr_model", models.asrModel)
            .putString("speech_${provider.name}_tts_model", models.ttsModel)
            .apply()
        _settings.value = _settings.value.copy(speechModels = newModels)
    }

    /**
     * 获取服务商模型选择
     */
    fun getSpeechModels(provider: SpeechProvider): SpeechModels {
        return _settings.value.speechModels[provider] ?: loadSpeechModels(provider)
    }

    /**
     * 从存储加载服务商模型选择
     */
    private fun loadSpeechModels(provider: SpeechProvider): SpeechModels {
        return SpeechModels(
            asrModel = prefs.getString("speech_${provider.name}_asr_model", "") ?: "",
            ttsModel = prefs.getString("speech_${provider.name}_tts_model", "") ?: ""
        )
    }
}
