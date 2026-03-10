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

    private fun preset(
        name: String,
        voiceParam: String,
        trait: String,
        age: String,
        language: String,
        category: VoiceCategory,
        avatarUrl: String,
        auditionUrl: String
    ): VoicePreset {
        return VoicePreset(
            name = name,
            voiceParam = voiceParam,
            trait = trait,
            age = age,
            language = language,
            supportsSSML = false,
            supportsInstruct = false,
            supportsTimestamp = false,
            category = category,
            avatarUrl = avatarUrl,
            auditionUrl = auditionUrl,
            provider = SpeechProvider.VOLCANO
        )
    }

    // 火山引擎音色列表（seed-tts-2.0）
    private val presetList: List<VoicePreset> by lazy {
        listOf(
            preset(
                name = "小何 2.0",
                voiceParam = "zh_female_xiaohe_uranus_bigtts",
                trait = "声线甜美有活力的妹妹，活泼开朗，笑容明媚。",
                age = "青年",
                language = "中文",
                category = VoiceCategory.VOICE_ASSISTANT,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/小何.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/zh_female_xiaohe_uranus_bigtts.mp3"
            ),
            preset(
                name = "Vivi 2.0",
                voiceParam = "zh_female_vv_uranus_bigtts",
                trait = "语调平稳、咬字柔和、自带治愈安抚力的女声音色",
                age = "青年",
                language = "中/日/印尼/西语",
                category = VoiceCategory.VOICE_ASSISTANT,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/vivi_v2.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/zh_female_vv_uranus_bigtts.wav"
            ),
            preset(
                name = "撒娇学妹 2.0",
                voiceParam = "zh_female_sajiaoxuemei_uranus_bigtts",
                trait = "嗲甜软萌的可爱妹妹，灵动娇气，活泼讨喜",
                age = "青年",
                language = "中文",
                category = VoiceCategory.SOCIAL_COMPANION,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/撒娇学妹.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/zh_female_sajiaoxuemei_uranus_bigtts.mp3"
            ),
            preset(
                name = "邻家女孩 2.0",
                voiceParam = "zh_female_linjianvhai_uranus_bigtts",
                trait = "软糯温柔的邻家女孩，低调内敛，耐心亲和",
                age = "青年",
                language = "中文",
                category = VoiceCategory.VOICE_ASSISTANT,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/邻家女孩.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/zh_female_linjianvhai_uranus_bigtts.mp3"
            ),
            preset(
                name = "暖阳女声 2.0",
                voiceParam = "zh_female_kefunvsheng_uranus_bigtts",
                trait = "开朗温柔的客服，阳光热情，服务贴心细致",
                age = "青年",
                language = "中文",
                category = VoiceCategory.CUSTOMER_SERVICE,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/暖阳女声.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/zh_female_kefunvsheng_uranus_bigtts.mp3"
            ),
            preset(
                name = "少年梓辛/Brayan 2.0",
                voiceParam = "zh_male_shaonianzixin_uranus_bigtts",
                trait = "少年感十足的清爽男生，温柔亲切，阳光开朗",
                age = "青年",
                language = "中文",
                category = VoiceCategory.VOICE_ASSISTANT,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/少年梓辛.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/zh_male_shaonianzixin_uranus_bigtts.mp3"
            ),
            preset(
                name = "佩奇猪 2.0",
                voiceParam = "zh_female_peiqi_uranus_bigtts",
                trait = "活泼童趣，天真烂漫，可爱治愈",
                age = "儿童",
                language = "中文",
                category = VoiceCategory.SHORT_VIDEO,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/佩奇猪.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/zh_female_peiqi_uranus_bigtts.mp3"
            ),
            preset(
                name = "刘飞 2.0",
                voiceParam = "zh_male_liufei_uranus_bigtts",
                trait = "逻辑清晰、理性稳重的男性",
                age = "青年",
                language = "中文",
                category = VoiceCategory.VOICE_ASSISTANT,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/刘飞.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/zh_male_liufei_uranus_bigtts.mp3"
            ),
            preset(
                name = "知性灿灿 2.0",
                voiceParam = "zh_female_cancan_uranus_bigtts",
                trait = "语气温柔舒缓，软糯但有善解人意的治愈系少女音",
                age = "青年",
                language = "中文",
                category = VoiceCategory.SOCIAL_COMPANION,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/灿灿.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/zh_female_cancan_uranus_bigtts.mp3"
            ),
            preset(
                name = "儒雅逸辰 2.0",
                voiceParam = "zh_male_ruyayichen_uranus_bigtts",
                trait = "低沉优雅的稳重青年，温柔成熟，儒雅得体",
                age = "青年",
                language = "中文",
                category = VoiceCategory.SHORT_VIDEO,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/儒雅逸辰.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/zh_male_ruyayichen_uranus_bigtts.mp3"
            ),
            preset(
                name = "流畅女声 2.0",
                voiceParam = "zh_female_liuchangnv_uranus_bigtts",
                trait = "温暖爽朗的小妹，阳光热情，性格直爽好相处",
                age = "青年",
                language = "中文",
                category = VoiceCategory.SHORT_VIDEO,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/流畅女声.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/zh_female_liuchangnv_uranus_bigtts.mp3"
            ),
            preset(
                name = "魅力女友 2.0",
                voiceParam = "zh_female_meilinvyou_uranus_bigtts",
                trait = "性感妩媚的御姐，成熟有魅力，风情十足",
                age = "青年",
                language = "中文",
                category = VoiceCategory.VOICE_ASSISTANT,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/魅力女友.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/zh_female_meilinvyou_uranus_bigtts.mp3"
            ),
            preset(
                name = "鸡汤女 2.0",
                voiceParam = "zh_female_jitangnv_uranus_bigtts",
                trait = "声音治愈的知心姐姐，温柔体贴，擅长倾听与理解",
                age = "青年",
                language = "中文",
                category = VoiceCategory.SHORT_VIDEO,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/鸡汤女.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/zh_female_jitangnv_uranus_bigtts.mp3"
            ),
            preset(
                name = "黑猫侦探社咪仔 2.0",
                voiceParam = "zh_female_mizai_uranus_bigtts",
                trait = "声线稳重优雅的知心姐姐，温暖亲和，善于陪伴",
                age = "青年",
                language = "中文",
                category = VoiceCategory.SHORT_VIDEO,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/黑猫侦探社咪仔.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/zh_female_mizai_uranus_bigtts.mp3"
            ),
            preset(
                name = "大壹 2.0",
                voiceParam = "zh_male_dayi_uranus_bigtts",
                trait = "历经世事的沉稳大叔，果敢可靠，让人安心信赖",
                age = "青年",
                language = "中文",
                category = VoiceCategory.SHORT_VIDEO,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/大壹.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/zh_male_dayi_uranus_bigtts.mp3"
            ),
            preset(
                name = "Tina老师 2.0",
                voiceParam = "zh_female_yingyujiaoxue_uranus_bigtts",
                trait = "磁性知性的青年讲师，温柔耐心，专业靠谱",
                age = "青年",
                language = "中文",
                category = VoiceCategory.VOICE_ASSISTANT,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/Tina老师.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/zh_female_yingyujiaoxue_uranus_bigtts.mp3"
            ),
            preset(
                name = "猴哥 2.0",
                voiceParam = "zh_male_sunwukong_uranus_bigtts",
                trait = "傲娇不羁的猴哥，声线经典灵动，一秒梦回西游",
                age = "青年",
                language = "中文",
                category = VoiceCategory.SHORT_VIDEO,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/猴哥.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/zh_male_sunwukong_uranus_bigtts.mp3"
            ),
            preset(
                name = "爽快思思 2.0",
                voiceParam = "zh_female_shuangkuaisisi_uranus_bigtts",
                trait = "温暖直爽的邻家小妹，阳光热情，相处轻松自在",
                age = "青年",
                language = "中文",
                category = VoiceCategory.VOICE_ASSISTANT,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/爽快思思.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/zh_female_shuangkuaisisi_uranus_bigtts.mp3"
            ),
            preset(
                name = "甜美桃子 2.0",
                voiceParam = "zh_female_tianmeitaozi_uranus_bigtts",
                trait = "俏皮元气女生，开朗外向，自带活跃气氛的感染力",
                age = "青年",
                language = "中文",
                category = VoiceCategory.VOICE_ASSISTANT,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/甜美桃子.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/zh_female_tianmeitaozi_uranus_bigtts.mp3"
            ),
            preset(
                name = "清新女声 2.0",
                voiceParam = "zh_female_qingxinnvsheng_uranus_bigtts",
                trait = "气质出众的旗袍美人兼职场精英，明媚大方，魅力十足",
                age = "青年",
                language = "中文",
                category = VoiceCategory.VOICE_ASSISTANT,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/清新女声.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/zh_female_qingxinnvsheng_uranus_bigtts.mp3"
            ),
            preset(
                name = "魅力苏菲 2.0",
                voiceParam = "zh_male_sophie_uranus_bigtts",
                trait = "高冷御姐，外表疏离难亲近，内心却细腻柔软",
                age = "青年",
                language = "中文",
                category = VoiceCategory.VOICE_ASSISTANT,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/魅力苏菲.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/zh_male_sophie_uranus_bigtts.mp3"
            ),
            preset(
                name = "小天 2.0",
                voiceParam = "zh_male_taocheng_uranus_bigtts",
                trait = "眉目清朗男大，清澈温润有朝气，开朗真诚。",
                age = "青年",
                language = "中文",
                category = VoiceCategory.VOICE_ASSISTANT,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/小天.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/zh_male_taocheng_uranus_bigtts.mp3"
            ),
            preset(
                name = "云舟 2.0",
                voiceParam = "zh_male_m191_uranus_bigtts",
                trait = "声音磁性的男生，成熟理性，做事有条理，让人信赖。",
                age = "青年",
                language = "中文",
                category = VoiceCategory.VOICE_ASSISTANT,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/云舟.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/zh_male_m191_uranus_bigtts.mp3"
            ),
            preset(
                name = "儿童绘本 2.0",
                voiceParam = "zh_female_xiaoxue_uranus_bigtts",
                trait = "清甜讲述者，充满童趣与耐心，为孩子编织美好梦境",
                age = "青年",
                language = "中文",
                category = VoiceCategory.AUDIOBOOK_ADULT,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/儿童绘本.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/zh_female_xiaoxue_uranus_bigtts.mp3"
            ),
            preset(
                name = "甜美小源 2.0",
                voiceParam = "zh_female_tianmeixiaoyuan_uranus_bigtts",
                trait = "声线明亮甜美的专业客服，亲切耐心，服务细致周到",
                age = "青年",
                language = "中文",
                category = VoiceCategory.VOICE_ASSISTANT,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/甜美小源.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/zh_female_tianmeixiaoyuan_uranus_bigtts.mp3"
            ),
            preset(
                name = "轻盈朵朵 2.0",
                voiceParam = "saturn_zh_female_qingyingduoduo_cs_tob",
                trait = "知性活力的女老师，谦虚包容，平易近人",
                age = "青年",
                language = "中文",
                category = VoiceCategory.CUSTOMER_SERVICE,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/轻盈朵朵.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/ICL_zh_female_qingyingduoduo_cs_tob.mp3"
            ),
            preset(
                name = "Stokie",
                voiceParam = "en_female_stokie_uranus_bigtts",
                trait = "音色甜美温柔的邻家姐姐，性格亲切细腻，相处时让人倍感安心。",
                age = "青年",
                language = "英语（美式）",
                category = VoiceCategory.INTERNATIONAL,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/Stokie.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/en_female_stokie_uranus_bigtts.mp3"
            ),
            preset(
                name = "Dacey",
                voiceParam = "en_female_dacey_uranus_bigtts",
                trait = "温暖声的直爽小妹，性格阳光热情，相处爽快。",
                age = "青年",
                language = "英语（美式）",
                category = VoiceCategory.INTERNATIONAL,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/Dacey.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/en_female_dacey_uranus_bigtts.mp3"
            ),
            preset(
                name = "Tim",
                voiceParam = "en_male_tim_uranus_bigtts",
                trait = "声线温润干净的儒雅青年，待人温柔又暖心，自带治愈人心的力量。",
                age = "青年",
                language = "英语（美式）",
                category = VoiceCategory.INTERNATIONAL,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/TIM2.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/en_male_tim_uranus_bigtts.mp3"
            ),
            preset(
                name = "流畅女声",
                voiceParam = "zh_female_santongyongns_saturn_bigtts",
                trait = "语调顺滑自然、咬字利落不拖沓、适配多种场景的百搭女声音色",
                age = "青年",
                language = "中文",
                category = VoiceCategory.SHORT_VIDEO,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/流畅女声.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/流畅女声.wav"
            ),
            preset(
                name = "魅力女友",
                voiceParam = "zh_female_meilinvyou_saturn_bigtts",
                trait = "嗲软轻飘的轻熟美人，妩媚有耐心，温柔勾人。",
                age = "青年",
                language = "中文",
                category = VoiceCategory.SHORT_VIDEO,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/魅力女友_v2.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/zh_female_meilinvyou_saturn_bigtts.wav"
            ),
            preset(
                name = "黑猫侦探社咪仔",
                voiceParam = "zh_female_mizai_saturn_bigtts",
                trait = "语调沉稳、咬字扎实、自带踏实和包容感的女声音色",
                age = "青年",
                language = "中文",
                category = VoiceCategory.SHORT_VIDEO,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/黑猫侦探社咪仔.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/zh_female_mizai_saturn_bigtts.wav"
            ),
            preset(
                name = "天才同桌",
                voiceParam = "saturn_zh_male_tiancaitongzhuo_tob",
                trait = "语气明快利落、咬字清晰带活力、聪明机敏又毒舌的少年音色",
                age = "青年",
                language = "中文",
                category = VoiceCategory.SOCIAL_COMPANION,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/天才同桌.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/saturn_zh_male_tiancaitongzhuo_tob.wav"
            ),
            preset(
                name = "知性灿灿",
                voiceParam = "saturn_zh_female_cancan_tob",
                trait = "语气温柔平缓、带有些许软绵感却又善解人意的少女音色",
                age = "青年",
                language = "中文",
                category = VoiceCategory.SOCIAL_COMPANION,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/灿灿.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/saturn_zh_female_cancan_tob.wav"
            ),
            preset(
                name = "鸡汤女",
                voiceParam = "zh_female_jitangnv_saturn_bigtts",
                trait = "语气轻软、尾音轻落、带有自然呼吸感且十分亲切的女声音色",
                age = "青年",
                language = "中文",
                category = VoiceCategory.SHORT_VIDEO,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/鸡汤女.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/zh_female_jitangnv_saturn_bigtts.wav"
            ),
            preset(
                name = "大壹",
                voiceParam = "zh_male_dayi_saturn_bigtts",
                trait = "语气沉稳有力、咬字清晰厚重、自带可靠安全感的男声音色",
                age = "青年",
                language = "中文",
                category = VoiceCategory.SHORT_VIDEO,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/大壹.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/zh_male_dayi_saturn_bigtts.wav"
            ),
            preset(
                name = "儿童绘本",
                voiceParam = "zh_female_xueayi_saturn_bigtts",
                trait = "温柔又有力量的音色，陪孩子在故事里勇敢探索！",
                age = "青年",
                language = "中文",
                category = VoiceCategory.VOICE_ASSISTANT,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/儿童绘本.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/zh_female_xueayi_saturn_bigtts.mp3"
            ),
            preset(
                name = "温婉珊珊 2.0",
                voiceParam = "saturn_zh_female_wenwanshanshan_cs_tob",
                trait = "温婉治愈的体贴女生，自带亲和力",
                age = "青年",
                language = "中文",
                category = VoiceCategory.CUSTOMER_SERVICE,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/温婉珊珊.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/ICL_zh_female_wenwanshanshan_cs_tob.mp3"
            ),
            preset(
                name = "热情艾娜 2.0",
                voiceParam = "saturn_zh_female_reqingaina_cs_tob",
                trait = "热情阳光的数学教师，气质优雅飒爽",
                age = "青年",
                language = "中文",
                category = VoiceCategory.CUSTOMER_SERVICE,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/热情艾娜.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/ICL_zh_female_reqingaina_cs_tob.mp3"
            ),
            preset(
                name = "可爱女生",
                voiceParam = "saturn_zh_female_keainvsheng_tob",
                trait = "可爱的妹妹，性格活泼，举动讨喜。",
                age = "青年",
                language = "中文",
                category = VoiceCategory.SOCIAL_COMPANION,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/可爱女生.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/saturn_zh_female_keainvsheng_tob.wav"
            ),
            preset(
                name = "爽朗少年",
                voiceParam = "saturn_zh_male_shuanglangshaonian_tob",
                trait = "语气洪亮开阔、咬字干脆爽朗、阳光率真不扭捏的少年音色",
                age = "青年",
                language = "中文",
                category = VoiceCategory.SOCIAL_COMPANION,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/爽朗少年.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/saturn_zh_male_shuanglangshaonian_tob.wav"
            ),
            preset(
                name = "调皮公主",
                voiceParam = "saturn_zh_female_tiaopigongzhu_tob",
                trait = "语调灵动跳脱、尾音带娇俏、古灵精怪爱撒娇的少女音色",
                age = "青年",
                language = "中文",
                category = VoiceCategory.SOCIAL_COMPANION,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/调皮公主.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/saturn_zh_female_tiaopigongzhu_tob.wav"
            ),
            preset(
                name = "儒雅逸辰",
                voiceParam = "zh_male_ruyayichen_saturn_bigtts",
                trait = "语气温润有礼、咬字文雅舒缓、自带书卷气的儒雅男声音色",
                age = "青年",
                language = "中文",
                category = VoiceCategory.SHORT_VIDEO,
                avatarUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/avatar/儒雅逸辰.png",
                auditionUrl = "https://lf3-static.bytednsdoc.com/obj/eden-cn/lm_hz_ihsph/ljhwZthlaukjlkulzlp/portal/bigtts/zh_male_ruyayichen_saturn_bigtts.wav"
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
        return presetList.find { it.voiceParam == "zh_female_shuangkuaisisi_uranus_bigtts" }
            ?: presetList.first()
    }
}
