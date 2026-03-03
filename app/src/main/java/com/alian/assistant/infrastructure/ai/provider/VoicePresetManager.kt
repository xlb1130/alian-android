package com.alian.assistant.infrastructure.ai.provider

import com.alian.assistant.data.VoiceCategory
import com.alian.assistant.data.VoicePreset
import com.alian.assistant.data.model.SpeechProvider

/**
 * 音色提供者接口
 */
interface VoicePresetProvider {
    /** 服务商标识 */
    val provider: SpeechProvider

    /** 获取所有音色 */
    fun getVoicePresets(): List<VoicePreset>

    /** 根据 ID 获取音色 */
    fun getVoicePresetById(id: String): VoicePreset?

    /** 根据分类获取音色 */
    fun getVoicePresetsByCategory(category: VoiceCategory): List<VoicePreset>

    /** 获取默认音色 */
    fun getDefaultVoice(): VoicePreset
}

/**
 * 音色管理器
 * 
 * 根据当前语音服务商提供对应的音色列表
 */
class VoicePresetManager {

    private val providers: Map<SpeechProvider, VoicePresetProvider> = mapOf(
        SpeechProvider.VOLCANO to VolcanoVoicePresetProvider(),
        SpeechProvider.BAILIAN to BailianVoicePresetProvider()
    )

    /**
     * 获取指定服务商的音色列表
     */
    fun getVoicePresets(provider: SpeechProvider): List<VoicePreset> {
        return providers[provider]?.getVoicePresets() ?: emptyList()
    }

    /**
     * 根据分类获取音色
     */
    fun getVoicePresetsByCategory(
        provider: SpeechProvider,
        category: VoiceCategory
    ): List<VoicePreset> {
        return providers[provider]?.getVoicePresetsByCategory(category) ?: emptyList()
    }

    /**
     * 获取音色详情
     */
    fun getVoicePreset(provider: SpeechProvider, voiceId: String): VoicePreset? {
        return providers[provider]?.getVoicePresetById(voiceId)
    }

    /**
     * 获取默认音色
     */
    fun getDefaultVoice(provider: SpeechProvider): VoicePreset {
        return providers[provider]?.getDefaultVoice()
            ?: providers[SpeechProvider.BAILIAN]!!.getDefaultVoice()
    }

    /**
     * 获取所有分类
     */
    fun getCategories(provider: SpeechProvider): List<VoiceCategory> {
        return getVoicePresets(provider)
            .map { it.category }
            .distinct()
    }

    /**
     * 根据 voiceParam 查找音色（兼容旧接口）
     */
    fun findByVoiceParam(provider: SpeechProvider, voiceParam: String): VoicePreset? {
        return getVoicePresets(provider).find { it.voiceParam == voiceParam }
    }

    companion object {
        @Volatile
        private var instance: VoicePresetManager? = null

        fun getInstance(): VoicePresetManager {
            return instance ?: synchronized(this) {
                instance ?: VoicePresetManager().also { instance = it }
            }
        }
    }
}

/**
 * 百炼音色提供者
 */
class BailianVoicePresetProvider : VoicePresetProvider {
    override val provider: SpeechProvider = SpeechProvider.BAILIAN

    override fun getVoicePresets(): List<VoicePreset> {
        // 直接使用 VoicePreset.Companion.ALL，但需要确保 provider 字段正确
        return VoicePreset.ALL.map { it.copy(provider = SpeechProvider.BAILIAN) }
    }

    override fun getVoicePresetById(id: String): VoicePreset? {
        return getVoicePresets().find { it.id == id }
    }

    override fun getVoicePresetsByCategory(category: VoiceCategory): List<VoicePreset> {
        return getVoicePresets().filter { it.category == category }
    }

    override fun getDefaultVoice(): VoicePreset {
        return getVoicePresets().find { it.voiceParam == "longyingmu_v3" }
            ?: getVoicePresets().first()
    }
}

/**
 * 火山引擎音色提供者
 */
class VolcanoVoicePresetProvider : VoicePresetProvider {
    override val provider: SpeechProvider = SpeechProvider.VOLCANO

    // 火山引擎支持的音色列表
    // 参考: https://www.volcengine.com/docs/6561/97465
    private val presetList: List<VoicePreset> by lazy {
        listOf(
            // 通用音色
            VoicePreset(
                name = "天美",
                voiceParam = "zh_female_tianmei_moon_bigtts",
                trait = "温柔甜美女声",
                age = "20~30岁",
                language = "中文",
                supportsSSML = false,
                supportsInstruct = false,
                supportsTimestamp = false,
                category = VoiceCategory.VOICE_ASSISTANT,
                provider = SpeechProvider.VOLCANO
            ),
            VoicePreset(
                name = "梓梓",
                voiceParam = "zh_female_zhimai_emo_moon_bigtts",
                trait = "知性温柔女声",
                age = "25~35岁",
                language = "中文",
                supportsSSML = false,
                supportsInstruct = false,
                supportsTimestamp = false,
                category = VoiceCategory.VOICE_ASSISTANT,
                provider = SpeechProvider.VOLCANO
            ),
            VoicePreset(
                name = "泽泽",
                voiceParam = "zh_male_zhiboshuangkuai_moon_bigtts",
                trait = "阳光开朗男声",
                age = "20~30岁",
                language = "中文",
                supportsSSML = false,
                supportsInstruct = false,
                supportsTimestamp = false,
                category = VoiceCategory.VOICE_ASSISTANT,
                provider = SpeechProvider.VOLCANO
            ),
            VoicePreset(
                name = "燃燃",
                voiceParam = "zh_female_chunhou_moon_bigtts",
                trait = "清纯甜美女声",
                age = "18~25岁",
                language = "中文",
                supportsSSML = false,
                supportsInstruct = false,
                supportsTimestamp = false,
                category = VoiceCategory.SOCIAL_COMPANION,
                provider = SpeechProvider.VOLCANO
            ),
            VoicePreset(
                name = "炀炀",
                voiceParam = "zh_male_chunhou_moon_bigtts",
                trait = "温暖磁性男声",
                age = "25~35岁",
                language = "中文",
                supportsSSML = false,
                supportsInstruct = false,
                supportsTimestamp = false,
                category = VoiceCategory.SOCIAL_COMPANION,
                provider = SpeechProvider.VOLCANO
            ),
            // 客服音色
            VoicePreset(
                name = " Latina",
                voiceParam = "zh_female_qingxinwenrou_moon_bigtts",
                trait = "清新温柔女声",
                age = "20~30岁",
                language = "中文",
                supportsSSML = false,
                supportsInstruct = false,
                supportsTimestamp = false,
                category = VoiceCategory.CUSTOMER_SERVICE,
                provider = SpeechProvider.VOLCANO
            ),
            VoicePreset(
                name = "哲哲",
                voiceParam = "zh_male_chenwen_moon_bigtts",
                trait = "沉稳专业男声",
                age = "30~40岁",
                language = "中文",
                supportsSSML = false,
                supportsInstruct = false,
                supportsTimestamp = false,
                category = VoiceCategory.CUSTOMER_SERVICE,
                provider = SpeechProvider.VOLCANO
            ),
            // 有声书音色
            VoicePreset(
                name = "童童",
                voiceParam = "zh_female_tongxin_moon_bigtts",
                trait = "童声女声",
                age = "6~12岁",
                language = "中文",
                supportsSSML = false,
                supportsInstruct = false,
                supportsTimestamp = false,
                category = VoiceCategory.CHILDREN,
                provider = SpeechProvider.VOLCANO
            ),
            VoicePreset(
                name = "讲故事女声",
                voiceParam = "zh_female_storytelling_moon_bigtts",
                trait = "故事讲述女声",
                age = "25~35岁",
                language = "中文",
                supportsSSML = false,
                supportsInstruct = false,
                supportsTimestamp = false,
                category = VoiceCategory.AUDIOBOOK_ADULT,
                provider = SpeechProvider.VOLCANO
            ),
            // 英文音色
            VoicePreset(
                name = "Emma",
                voiceParam = "en_female_amy_moon_bigtts",
                trait = "美式英文女声",
                age = "20~30岁",
                language = "英文",
                supportsSSML = false,
                supportsInstruct = false,
                supportsTimestamp = false,
                category = VoiceCategory.INTERNATIONAL,
                provider = SpeechProvider.VOLCANO
            ),
            VoicePreset(
                name = "James",
                voiceParam = "en_male_narration_moon_bigtts",
                trait = "美式英文男声",
                age = "30~40岁",
                language = "英文",
                supportsSSML = false,
                supportsInstruct = false,
                supportsTimestamp = false,
                category = VoiceCategory.INTERNATIONAL,
                provider = SpeechProvider.VOLCANO
            )
        )
    }

    override fun getVoicePresets(): List<VoicePreset> = presetList

    override fun getVoicePresetById(id: String): VoicePreset? {
        return presetList.find { it.id == id }
    }

    override fun getVoicePresetsByCategory(category: VoiceCategory): List<VoicePreset> {
        return presetList.filter { it.category == category }
    }

    override fun getDefaultVoice(): VoicePreset {
        return presetList.first()
    }
}
