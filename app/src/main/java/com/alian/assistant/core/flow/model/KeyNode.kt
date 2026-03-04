package com.alian.assistant.core.flow.model

import kotlinx.serialization.*

/**
 * 关键节点（值对象）
 * 用于屏幕状态验证
 */
@Serializable
data class KeyNode(
    @SerialName("id")
    val id: String,                          // 节点唯一ID
    
    @SerialName("name")
    val name: String,                        // 节点名称，如 "首页"、"点餐页"
    
    @SerialName("stepIndex")
    val stepIndex: Int,                      // 对应的步骤索引
    
    @SerialName("screenSignature")
    val screenSignature: ScreenSignature,    // 屏幕签名
    
    @SerialName("verificationMethod")
    val verificationMethod: VerificationMethod,
    
    @SerialName("isRequired")
    val isRequired: Boolean = true           // 是否必须验证通过
)

/**
 * 屏幕签名（值对象）
 * 用于快速匹配屏幕状态
 */
@Serializable
data class ScreenSignature(
    // 文本特征
    @SerialName("textPatterns")
    val textPatterns: List<String>,          // 必须出现的文本
    
    // UI 元素特征（从无障碍服务提取）
    @SerialName("elementSignatures")
    val elementSignatures: List<ElementSignature>,
    
    // 包名
    @SerialName("packageName")
    val packageName: String? = null,
    
    // 布局哈希（可选，用于快速比对）
    @SerialName("layoutHash")
    val layoutHash: String? = null
) {
    /**
     * 匹配当前屏幕
     * @param currentTexts 当前屏幕文本列表
     * @param currentElements 当前屏幕元素列表
     * @param currentPackage 当前包名
     * @return 匹配结果
     */
    fun match(
        currentTexts: List<String>,
        currentElements: List<ElementSignature>,
        currentPackage: String? = null
    ): MatchResult {
        var totalScore = 0f
        var totalWeight = 0f
        
        // 包名匹配（权重最高）
        if (packageName != null && currentPackage != null) {
            totalWeight += 0.3f
            if (packageName == currentPackage) {
                totalScore += 0.3f
            } else {
                // 包名不匹配，直接返回失败
                return MatchResult(matched = false, confidence = 0f)
            }
        }
        
        // 文本匹配
        if (textPatterns.isNotEmpty()) {
            val textWeight = 0.4f
            totalWeight += textWeight
            val textMatchScore = textPatterns.count { pattern ->
                currentTexts.any { it.contains(pattern, ignoreCase = true) }
            }.toFloat() / textPatterns.size
            totalScore += textMatchScore * textWeight
        }
        
        // 元素匹配
        if (elementSignatures.isNotEmpty()) {
            val elementWeight = 0.3f
            totalWeight += elementWeight
            val elementMatchScore = elementSignatures.count { expected ->
                currentElements.any { it.matches(expected) }
            }.toFloat() / elementSignatures.size
            totalScore += elementMatchScore * elementWeight
        }
        
        // 如果没有设置任何匹配条件，默认匹配
        if (totalWeight == 0f) {
            return MatchResult(matched = true, confidence = 0.5f)
        }
        
        val confidence = totalScore / totalWeight
        
        return MatchResult(
            matched = confidence > 0.7f,
            confidence = confidence
        )
    }
    
    /**
     * 快速匹配（仅检查包名和关键文本）
     */
    fun quickMatch(currentPackage: String?, currentTexts: List<String>): MatchResult {
        // 包名检查
        if (packageName != null && currentPackage != null && packageName != currentPackage) {
            return MatchResult(matched = false, confidence = 0f)
        }
        
        // 关键文本检查
        if (textPatterns.isNotEmpty()) {
            val matchCount = textPatterns.count { pattern ->
                currentTexts.any { it.contains(pattern, ignoreCase = true) }
            }
            if (matchCount == 0) {
                return MatchResult(matched = false, confidence = 0.3f)
            }
        }
        
        return MatchResult(matched = true, confidence = 0.6f)
    }
}
