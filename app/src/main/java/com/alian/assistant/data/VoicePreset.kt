package com.alian.assistant.data

/**
 * 音色分类
 */
enum class VoiceCategory(val displayName: String) {
    SOCIAL_COMPANION("社交陪伴"),
    CHILDREN("童声"),
    TOY("智能玩具/儿童故事机"),
    AUDIOBOOK("消费电子-儿童有声书"),
    DIALECT("方言"),
    INTERNATIONAL("出海营销"),
    POETRY("诗词朗诵"),
    TELEMARKETING("电话销售"),
    CUSTOMER_SERVICE("客服"),
    VOICE_ASSISTANT("语音助手"),
    AUDIOBOOK_ADULT("有声书"),
    SHORT_VIDEO("短视频配音"),
    LIVE_STREAMING("直播带货"),
    NEWS("新闻播报")
}

/**
 * 音色预设
 */
data class VoicePreset(
    val name: String,
    val voiceParam: String,
    val trait: String,
    val age: String,
    val language: String,
    val supportsSSML: Boolean,
    val supportsInstruct: Boolean,
    val supportsTimestamp: Boolean,
    val category: VoiceCategory,
    val avatarUrl: String = "",
    val auditionUrl: String = ""
) {
    companion object {
        // 社交陪伴（标杆音色）
        val LONGANYANG = VoicePreset(
            name = "龙安洋",
            voiceParam = "longanyang",
            trait = "阳光大男孩",
            age = "20~30岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = true,
            supportsTimestamp = true,
            category = VoiceCategory.SOCIAL_COMPANION,
            avatarUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/avatar/longanyang.png",
            auditionUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/audition/longanyang.mp3"
        )

        val LONGANHUAN = VoicePreset(
            name = "龙安欢",
            voiceParam = "longanhuan",
            trait = "欢脱元气女",
            age = "20~30岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = true,
            supportsTimestamp = true,
            category = VoiceCategory.SOCIAL_COMPANION,
            avatarUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/avatar/longanhuan.png",
            auditionUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/audition/longanhuan.mp3"
        )

        // 童声（标杆音色）
        val LONGHUHU = VoicePreset(
            name = "龙呼呼",
            voiceParam = "longhuhu_v3",
            trait = "天真烂漫女童",
            age = "6~10岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = true,
            supportsTimestamp = true,
            category = VoiceCategory.CHILDREN,
            avatarUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/avatar/longhuhu.png",
            auditionUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/audition/longhuhu.mp3"
        )

        // 智能玩具/儿童故事机
        val LONGPAOPAO = VoicePreset(
            name = "龙泡泡",
            voiceParam = "longpaopao_v3",
            trait = "飞天泡泡音",
            age = "6~15岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.TOY
        )

        val LONGJIELIDOU = VoicePreset(
            name = "龙杰力豆",
            voiceParam = "longjielidou_v3",
            trait = "阳光顽皮男",
            age = "10岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.TOY
        )

        val LONGXIAN = VoicePreset(
            name = "龙仙",
            voiceParam = "longxian_v3",
            trait = "豪放可爱女",
            age = "12岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.TOY
        )

        val LONGLING = VoicePreset(
            name = "龙铃",
            voiceParam = "longling_v3",
            trait = "稚气呆板女",
            age = "10岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.TOY
        )

        // 消费电子-儿童有声书
        val LONGSHANSHAN = VoicePreset(
            name = "龙闪闪",
            voiceParam = "longshanshan_v3",
            trait = "戏剧化童声",
            age = "6~15岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.AUDIOBOOK
        )

        val LONGNIUNIU = VoicePreset(
            name = "龙牛牛",
            voiceParam = "longniuniu_v3",
            trait = "阳光男童声",
            age = "6~15岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.AUDIOBOOK
        )

        // 方言
        val LONGJIAXIN = VoicePreset(
            name = "龙嘉欣",
            voiceParam = "longjiaxin_v3",
            trait = "优雅粤语女",
            age = "30~35岁",
            language = "中文（粤语）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.DIALECT
        )

        val LONGJIAYI = VoicePreset(
            name = "龙嘉怡",
            voiceParam = "longjiayi_v3",
            trait = "知性粤语女",
            age = "25~30岁",
            language = "中文（粤语）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.DIALECT
        )

        val LONGANYUE = VoicePreset(
            name = "龙安粤",
            voiceParam = "longanyue_v3",
            trait = "欢脱粤语男",
            age = "25~35岁",
            language = "中文（粤语）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.DIALECT,
            avatarUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/avatar/longanyue.png",
            auditionUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/audition/longanyue.mp3"
        )

        val LONGLAOTIE = VoicePreset(
            name = "龙老铁",
            voiceParam = "longlaotie_v3",
            trait = "东北直率男",
            age = "25~30岁",
            language = "中文（东北话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.DIALECT
        )

        val LONGSHANGE = VoicePreset(
            name = "龙陕哥",
            voiceParam = "longshange_v3",
            trait = "原味陕北男",
            age = "25~35岁",
            language = "中文（陕西话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.DIALECT,
            avatarUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/avatar/longshange.png",
            auditionUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/audition/longshange.mp3"
        )

        val LONGANMIN = VoicePreset(
            name = "龙安闽",
            voiceParam = "longanmin_v3",
            trait = "清纯萝莉女",
            age = "18~25岁",
            language = "中文（闽南话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.DIALECT,
            avatarUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/avatar/longanmin.png",
            auditionUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/audition/longanmin.mp3"
        )

        // 出海营销
        val LOONGKYONG = VoicePreset(
            name = "loongkyong",
            voiceParam = "loongkyong_v3",
            trait = "韩语女",
            age = "25~30岁",
            language = "韩语",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.INTERNATIONAL,
            avatarUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/avatar/loongkyong_v3.png",
            auditionUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/timbreList/loongkyong_v3.mp3"
        )

        val LOONGRIKO = VoicePreset(
            name = "Riko",
            voiceParam = "loongriko_v3",
            trait = "二次元霓虹女",
            age = "18~25岁",
            language = "日语",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.INTERNATIONAL
        )

        val LOONGTOMOKA = VoicePreset(
            name = "loongtomoka",
            voiceParam = "loongtomoka_v3",
            trait = "日语女",
            age = "30~35岁",
            language = "日语",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.INTERNATIONAL,
            avatarUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/avatar/loongtomoka_v3.png",
            auditionUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/timbreList/loongtomoka_v3.mp3"
        )

        // 诗词朗诵
        val LONGFEI = VoicePreset(
            name = "龙飞",
            voiceParam = "longfei_v3",
            trait = "热血磁性男",
            age = "30~35岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.POETRY
        )

        // 电话销售
        val LONGYINGXIAO = VoicePreset(
            name = "龙应笑",
            voiceParam = "longyingxiao_v3",
            trait = "清甜推销女",
            age = "20~25岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.TELEMARKETING,
            avatarUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/avatar/longyingxiao.png",
            auditionUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/audition/longyingxiao.mp3"
        )

        // 客服
        val LONGYINGXUN = VoicePreset(
            name = "龙应询",
            voiceParam = "longyingxun_v3",
            trait = "年轻青涩男",
            age = "20~25岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.CUSTOMER_SERVICE,
            avatarUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/avatar/longyingxun.png",
            auditionUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/audition/longyingxun.mp3"
        )

        val LONGYINGJING = VoicePreset(
            name = "龙应静",
            voiceParam = "longyingjing_v3",
            trait = "低调冷静女",
            age = "25~35岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.CUSTOMER_SERVICE
        )

        val LONGYINGLING = VoicePreset(
            name = "龙应聆",
            voiceParam = "longyingling_v3",
            trait = "温和共情女",
            age = "25~30岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.CUSTOMER_SERVICE
        )

        val LONGYINGTAO = VoicePreset(
            name = "龙应桃",
            voiceParam = "longyingtao_v3",
            trait = "温柔淡定女",
            age = "25~30岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.CUSTOMER_SERVICE,
            avatarUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/avatar/longyingtao.png",
            auditionUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/audition/longyingtao.mp3"
        )

        // 语音助手
        val LONGXIAOCHUN = VoicePreset(
            name = "龙小淳",
            voiceParam = "longxiaochun_v3",
            trait = "知性积极女",
            age = "25~30岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.VOICE_ASSISTANT
        )

        val LONGXIAOXIA = VoicePreset(
            name = "龙小夏",
            voiceParam = "longxiaoxia_v3",
            trait = "沉稳权威女",
            age = "25~30岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.VOICE_ASSISTANT
        )

        val LONGYUMI = VoicePreset(
            name = "YUMI",
            voiceParam = "longyumi_v3",
            trait = "正经青年女",
            age = "20~25岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.VOICE_ASSISTANT
        )

        val LONGANYUN = VoicePreset(
            name = "龙安昀",
            voiceParam = "longanyun_v3",
            trait = "居家暖男",
            age = "30~35岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.VOICE_ASSISTANT,
            avatarUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/avatar/longanyun.png",
            auditionUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/audition/longanyun.mp3"
        )

        val LONGANWEN = VoicePreset(
            name = "龙安温",
            voiceParam = "longanwen_v3",
            trait = "优雅知性女",
            age = "25~35岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.VOICE_ASSISTANT,
            avatarUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/avatar/longanwen.png",
            auditionUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/audition/longanwen.mp3"
        )

        val LONGANLI = VoicePreset(
            name = "龙安莉",
            voiceParam = "longanli_v3",
            trait = "利落从容女",
            age = "25~35岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.VOICE_ASSISTANT,
            avatarUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/avatar/longanli.png",
            auditionUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/audition/longanli.mp3"
        )

        val LONGANLANG = VoicePreset(
            name = "龙安朗",
            voiceParam = "longanlang_v3",
            trait = "清爽利落男",
            age = "20~25岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.VOICE_ASSISTANT,
            avatarUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/avatar/longanlang.png",
            auditionUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/audition/longanlang.mp3"
        )

        val LONGYINGMU = VoicePreset(
            name = "龙应沐",
            voiceParam = "longyingmu_v3",
            trait = "优雅知性女",
            age = "25~30岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.VOICE_ASSISTANT,
            avatarUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/avatar/longyingmu.png",
            auditionUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/audition/longyingmu.mp3"
        )

        // 社交陪伴
        val LONGANTAI = VoicePreset(
            name = "龙安台",
            voiceParam = "longantai_v3",
            trait = "嗲甜台湾女",
            age = "20~25岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.SOCIAL_COMPANION
        )

        val LONGHUA = VoicePreset(
            name = "龙华",
            voiceParam = "longhua_v3",
            trait = "元气甜美女",
            age = "20~25岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.SOCIAL_COMPANION
        )

        val LONGCHENG = VoicePreset(
            name = "龙橙",
            voiceParam = "longcheng_v3",
            trait = "智慧青年男",
            age = "20~25岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.SOCIAL_COMPANION
        )

        val LONGZE = VoicePreset(
            name = "龙泽",
            voiceParam = "longze_v3",
            trait = "温暖元气男",
            age = "25~30岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.SOCIAL_COMPANION
        )

        val LONGZHE = VoicePreset(
            name = "龙哲",
            voiceParam = "longzhe_v3",
            trait = "呆板大暖男",
            age = "25~30岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.SOCIAL_COMPANION
        )

        val LONGYAN = VoicePreset(
            name = "龙颜",
            voiceParam = "longyan_v3",
            trait = "温暖春风女",
            age = "30~35岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.SOCIAL_COMPANION
        )

        val LONGXING = VoicePreset(
            name = "龙星",
            voiceParam = "longxing_v3",
            trait = "温婉邻家女",
            age = "20~25岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.SOCIAL_COMPANION
        )

        val LONGTIAN = VoicePreset(
            name = "龙天",
            voiceParam = "longtian_v3",
            trait = "磁性理智男",
            age = "30~35岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.SOCIAL_COMPANION
        )

        val LONGWAN = VoicePreset(
            name = "龙婉",
            voiceParam = "longwan_v3",
            trait = "细腻柔声女",
            age = "20~30岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.SOCIAL_COMPANION
        )

        val LONGQIANG = VoicePreset(
            name = "龙嫱",
            voiceParam = "longqiang_v3",
            trait = "浪漫风情女",
            age = "30~35岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.SOCIAL_COMPANION
        )

        val LONGFEIFEI = VoicePreset(
            name = "龙菲菲",
            voiceParam = "longfeifei_v3",
            trait = "甜美娇气女",
            age = "20~25岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.SOCIAL_COMPANION
        )

        val LONGHAO = VoicePreset(
            name = "龙浩",
            voiceParam = "longhao_v3",
            trait = "多情忧郁男",
            age = "30~35岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.SOCIAL_COMPANION
        )

        val LONGANROU = VoicePreset(
            name = "龙安柔",
            voiceParam = "longanrou_v3",
            trait = "温柔闺蜜女",
            age = "20~35岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.SOCIAL_COMPANION
        )

        val LONGHAN = VoicePreset(
            name = "龙寒",
            voiceParam = "longhan_v3",
            trait = "温暖痴情男",
            age = "30~35岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.SOCIAL_COMPANION
        )

        val LONGANZHI = VoicePreset(
            name = "龙安智",
            voiceParam = "longanzhi_v3",
            trait = "睿智轻熟男",
            age = "25~35岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.SOCIAL_COMPANION,
            avatarUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/avatar/longanzhi.png",
            auditionUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/audition/longanzhi.mp3"
        )

        val LONGANLING = VoicePreset(
            name = "龙安灵",
            voiceParam = "longanling_v3",
            trait = "思维灵动女",
            age = "20~30岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.SOCIAL_COMPANION,
            avatarUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/avatar/longanling.png",
            auditionUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/audition/longanling.mp3"
        )

        val LONGANYA = VoicePreset(
            name = "龙安雅",
            voiceParam = "longanya_v3",
            trait = "高雅气质女",
            age = "25~35岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.SOCIAL_COMPANION,
            avatarUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/avatar/longanya.png",
            auditionUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/audition/longanya.mp3"
        )

        val LONGANQIN = VoicePreset(
            name = "龙安亲",
            voiceParam = "longanqin_v3",
            trait = "亲和活泼女",
            age = "20~25岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.SOCIAL_COMPANION,
            avatarUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/avatar/longanqin.png",
            auditionUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/audition/longanqin.mp3"
        )

        // 有声书
        val LONGMIAO = VoicePreset(
            name = "龙妙",
            voiceParam = "longmiao_v3",
            trait = "抑扬顿挫女",
            age = "25~30岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.AUDIOBOOK_ADULT
        )

        val LONGSANSHU = VoicePreset(
            name = "龙三叔",
            voiceParam = "longsanshu_v3",
            trait = "沉稳质感男",
            age = "25~45岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.AUDIOBOOK_ADULT
        )

        val LONGYUAN = VoicePreset(
            name = "龙媛",
            voiceParam = "longyuan_v3",
            trait = "温暖治愈女",
            age = "35~40岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.AUDIOBOOK_ADULT
        )

        val LONGYUE = VoicePreset(
            name = "龙悦",
            voiceParam = "longyue_v3",
            trait = "温暖磁性女",
            age = "30~35岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.AUDIOBOOK_ADULT
        )

        val LONGXIU = VoicePreset(
            name = "龙修",
            voiceParam = "longxiu_v3",
            trait = "博才说书男",
            age = "25~35岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.AUDIOBOOK_ADULT
        )

        val LONGNAN = VoicePreset(
            name = "龙楠",
            voiceParam = "longnan_v3",
            trait = "睿智青年男",
            age = "25~30岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.AUDIOBOOK_ADULT
        )

        val LONGWANJUN = VoicePreset(
            name = "龙婉君",
            voiceParam = "longwanjun_v3",
            trait = "细腻柔声女",
            age = "20~30岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.AUDIOBOOK_ADULT,
            avatarUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/avatar/longwanjun.png",
            auditionUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/audition/longwanjun.mp3"
        )

        val LONGYICHEN = VoicePreset(
            name = "龙逸尘",
            voiceParam = "longyichen_v3",
            trait = "洒脱活力男",
            age = "20~30岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.AUDIOBOOK_ADULT,
            avatarUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/avatar/longyichen.png",
            auditionUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/audition/longyichen.mp3"
        )

        val LONGLAOBO = VoicePreset(
            name = "龙老伯",
            voiceParam = "longlaobo_v3",
            trait = "沧桑岁月爷",
            age = "60岁以上",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.AUDIOBOOK_ADULT,
            avatarUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/avatar/longlaobo.png",
            auditionUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/audition/longlaobo.mp3"
        )

        val LONGLAOYI = VoicePreset(
            name = "龙老姨",
            voiceParam = "longlaoyi_v3",
            trait = "烟火从容阿姨",
            age = "60岁以上",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.AUDIOBOOK_ADULT,
            avatarUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/avatar/longlaoyi.png",
            auditionUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/audition/longlaoyi.mp3"
        )

        // 短视频配音
        val LONGJIQI = VoicePreset(
            name = "龙机器",
            voiceParam = "longjiqi_v3",
            trait = "呆萌机器人",
            age = "20~30岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.SHORT_VIDEO
        )

        val LONGHOU = VoicePreset(
            name = "龙猴哥",
            voiceParam = "longhouge_v3",
            trait = "经典猴哥",
            age = "20~25岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.SHORT_VIDEO,
            avatarUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/avatar/longhouge.png",
            auditionUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/audition/longhouge.mp3"
        )

        val LONGDAIYU = VoicePreset(
            name = "龙黛玉",
            voiceParam = "longdaiyu_v3",
            trait = "娇率才女音",
            age = "15~25岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.SHORT_VIDEO,
            avatarUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/avatar/longdaiyu.png",
            auditionUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/audition/longdaiyu.mp3"
        )

        // 直播带货
        val LONGANRAN = VoicePreset(
            name = "龙安燃",
            voiceParam = "longanran_v3",
            trait = "活泼质感女",
            age = "30~40岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.LIVE_STREAMING
        )

        val LONGANXUAN = VoicePreset(
            name = "龙安宣",
            voiceParam = "longanxuan_v3",
            trait = "经典直播女",
            age = "30~40岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.LIVE_STREAMING,
            avatarUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/avatar/longanxuan.png",
            auditionUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/audition/longanxuan.mp3"
        )

        // 新闻播报
        val LONGSHUO = VoicePreset(
            name = "龙硕",
            voiceParam = "longshuo_v3",
            trait = "博才干练男",
            age = "25~30岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.NEWS
        )

        val LONGSHU = VoicePreset(
            name = "龙书",
            voiceParam = "longshu_v3",
            trait = "沉稳青年男",
            age = "20~25岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.NEWS
        )

        val LOONGBELLA = VoicePreset(
            name = "Bella3.0",
            voiceParam = "loongbella_v3",
            trait = "精准干练女",
            age = "25~30岁",
            language = "中文（普通话）、英文",
            supportsSSML = true,
            supportsInstruct = false,
            supportsTimestamp = true,
            category = VoiceCategory.NEWS,
            avatarUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/avatar/loongbella_v2.png",
            auditionUrl = "https://bailian-bmp-pre.oss-cn-hangzhou.aliyuncs.com/public/audio/cosyvoice-v2/audition/loongbella_v2.mp3"
        )

        // 所有音色
        val ALL = listOf(
            LONGANYANG, LONGANHUAN, LONGHUHU,
            LONGPAOPAO, LONGJIELIDOU, LONGXIAN, LONGLING,
            LONGSHANSHAN, LONGNIUNIU,
            LONGJIAXIN, LONGJIAYI, LONGANYUE, LONGLAOTIE, LONGSHANGE, LONGANMIN,
            LOONGKYONG, LOONGRIKO, LOONGTOMOKA,
            LONGFEI,
            LONGYINGXIAO,
            LONGYINGXUN, LONGYINGJING, LONGYINGLING, LONGYINGTAO,
            LONGXIAOCHUN, LONGXIAOXIA, LONGYUMI, LONGANYUN, LONGANWEN, LONGANLI, LONGANLANG, LONGYINGMU,
            LONGANTAI, LONGHUA, LONGCHENG, LONGZE, LONGZHE, LONGYAN, LONGXING, LONGTIAN, LONGWAN, LONGQIANG, LONGFEIFEI, LONGHAO, LONGANROU, LONGHAN, LONGANZHI, LONGANLING, LONGANYA, LONGANQIN,
            LONGMIAO, LONGSANSHU, LONGYUAN, LONGYUE, LONGXIU, LONGNAN, LONGWANJUN, LONGYICHEN, LONGLAOBO, LONGLAOYI,
            LONGJIQI, LONGHOU, LONGDAIYU,
            LONGANRAN, LONGANXUAN,
            LONGSHUO, LONGSHU, LOONGBELLA
        )

        // 按分类分组
        fun getByCategory(category: VoiceCategory): List<VoicePreset> {
            return ALL.filter { it.category == category }
        }

        // 根据参数查找
        fun findByVoiceParam(voiceParam: String): VoicePreset? {
            return ALL.find { it.voiceParam == voiceParam }
        }
    }
}