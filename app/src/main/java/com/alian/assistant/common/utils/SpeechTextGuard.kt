package com.alian.assistant.common.utils

/**
 * 语音识别文本守卫：
 * - 统一清洗 ASR 文本
 * - 过滤常见噪声词/无意义短词
 * - 为不同场景提供不同强度的有效性门槛
 */
object SpeechTextGuard {
    private val trailingPunctuationRegex =
        Regex("[\\s\\p{Punct}，。！？、；：,.!?…\"'“”‘’()（）\\[\\]【】《》]+$")
    private val compactNoiseRegex =
        Regex("[\\s\\p{Punct}，。！？、；：,.!?…\"'“”‘’()（）\\[\\]【】《》]+")

    private val noiseTokens = setOf(
        "嗯", "啊", "呃", "额", "诶", "欸", "唉", "哎", "喂", "哦", "噢",
        "嗯嗯", "啊啊", "呃呃", "哈哈", "嘿", "那个", "这个"
    )

    /**
     * 通用 final 文本清洗。
     *
     * @param raw 原始文本
     * @param minMeaningfulChars 最小有效字符数（默认 2，更抗噪）
     * @return 过滤后的文本；若无效返回空串
     */
    fun sanitizeFinalText(raw: String, minMeaningfulChars: Int = 2): String {
        val normalized = raw.replace("\\s+".toRegex(), " ").trim()
        if (normalized.isBlank()) return ""

        val stripped = normalized.replace(trailingPunctuationRegex, "").trim()
        if (stripped.isBlank()) return ""

        val compact = stripped.replace(compactNoiseRegex, "")
        if (compact.isBlank()) return ""
        if (compact in noiseTokens) return ""
        if (isShortRepeatedNoise(compact)) return ""

        val meaningfulCount = compact.count { it.isLetterOrDigit() || isHan(it) }
        if (meaningfulCount < minMeaningfulChars) return ""

        return stripped
    }

    /**
     * 打断场景文本清洗。
     * 命中关键词时允许更短文本通过；未命中关键词时使用更严格门槛。
     */
    fun sanitizeInterruptText(
        raw: String,
        keywords: Collection<String>,
        nonKeywordMinMeaningfulChars: Int = 4
    ): String {
        val relaxed = sanitizeFinalText(raw, minMeaningfulChars = 1)
        if (relaxed.isBlank()) return ""

        if (containsKeyword(relaxed, keywords)) {
            return relaxed
        }

        val strict = sanitizeFinalText(relaxed, minMeaningfulChars = nonKeywordMinMeaningfulChars)
        return strict
    }

    fun containsKeyword(text: String, keywords: Collection<String>): Boolean {
        if (text.isBlank()) return false
        return keywords.any { keyword -> keyword.isNotBlank() && text.contains(keyword) }
    }

    private fun isShortRepeatedNoise(text: String): Boolean {
        if (text.length !in 2..4) return false
        val first = text.first()
        return text.all { it == first }
    }

    private fun isHan(ch: Char): Boolean {
        return Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN
    }
}

