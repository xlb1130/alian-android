package com.alian.assistant.data.model

import com.alian.assistant.core.agent.memory.Action

/**
 * RiskyActionPolicy - 敏感操作检测策略
 * 
 * 检测支付、删除、授权等敏感操作，强制要求用户确认
 * 
 * 核心功能：
 * 1. 动作类型检测
 * 2. 屏幕语义检测（通过 VLM 识别）
 * 3. 文本内容检测（按钮文本、输入框内容等）
 * 4. 风险等级评估
 */
class RiskyActionPolicy {
    
    companion object {
        private const val TAG = "RiskyActionPolicy"
        
        // 支付相关关键词
        private val PAYMENT_KEYWORDS = listOf(
            "支付", "付款", "结算", "购买", "下单", "确认支付", "立即支付", "去支付",
            "pay", "payment", "checkout", "buy", "purchase", "order"
        )
        
        // 删除相关关键词
        private val DELETE_KEYWORDS = listOf(
            "删除", "卸载", "移除", "清除", "删除记录", "删除账户",
            "delete", "remove", "uninstall", "clear", "erase"
        )
        
        // 授权相关关键词
        private val AUTH_KEYWORDS = listOf(
            "授权", "允许", "同意", "授予权限", "开启权限", "授权登录",
            "authorize", "allow", "grant", "permit", "enable"
        )
        
        // 敏感输入类型
        private val SENSITIVE_INPUT_TYPES = listOf(
            "password", "passwd", "pwd", "pin", "验证码", "验证", "验证身份",
            "verification", "verify", "otp", "code"
        )
        
        // 验证码相关
        private val CAPTCHA_KEYWORDS = listOf(
            "验证码", "图形验证", "滑块验证", "人机验证", "验证", "captcha",
            "verification code", "verify", "captcha", "i'm not a robot"
        )
    }

    /**
     * 风险等级
     */
    enum class RiskLevel {
        NONE,       // 无风险
        LOW,        // 低风险
        MEDIUM,     // 中风险
        HIGH,       // 高风险
        CRITICAL    // 极高风险
    }

    /**
     * 风险评估结果
     */
    data class RiskAssessment(
        val isRisky: Boolean,
        val riskLevel: RiskLevel,
        val riskType: RiskType,
        val reason: String,
        val needConfirm: Boolean,
        val confirmMessage: String
    )

    /**
     * 风险类型
     */
    enum class RiskType {
        PAYMENT,        // 支付
        DELETE,         // 删除
        AUTHORIZATION,  // 授权
        SENSITIVE_INPUT,// 敏感输入（密码、验证码等）
        CAPTCHA,        // 验证码
        UNKNOWN         // 未知风险
    }

    /**
     * 评估动作风险
     * 
     * @param action 要评估的动作
     * @param buttonText 按钮文本（如果有）
     * @param screenDescription 屏幕描述（如果有）
     * @return 风险评估结果
     */
    fun assessActionRisk(
        action: Action,
        buttonText: String? = null,
        screenDescription: String? = null
    ): RiskAssessment {
        // 1. 检查动作类型
        val actionTypeRisk = assessActionType(action)
        if (actionTypeRisk.isRisky) {
            return actionTypeRisk
        }

        // 2. 检查按钮文本
        if (!buttonText.isNullOrBlank()) {
            val textRisk = assessTextContent(buttonText)
            if (textRisk.isRisky) {
                return textRisk
            }
        }

        // 3. 检查屏幕描述
        if (!screenDescription.isNullOrBlank()) {
            val screenRisk = assessScreenDescription(screenDescription)
            if (screenRisk.isRisky) {
                return screenRisk
            }
        }

        // 4. 检查动作的 tellUser 字段
        if (!action.tellUser.isNullOrBlank()) {
            val tellUserRisk = assessTextContent(action.tellUser)
            if (tellUserRisk.isRisky) {
                return tellUserRisk
            }
        }

        // 5. 检查动作的 text 字段（输入内容）
        if (!action.text.isNullOrBlank()) {
            val inputRisk = assessInputContent(action.text)
            if (inputRisk.isRisky) {
                return inputRisk
            }
        }

        // 无风险
        return RiskAssessment(
            isRisky = false,
            riskLevel = RiskLevel.NONE,
            riskType = RiskType.UNKNOWN,
            reason = "No risk detected",
            needConfirm = false,
            confirmMessage = ""
        )
    }

    /**
     * 评估动作类型
     */
    private fun assessActionType(action: Action): RiskAssessment {
        // take_over 动作本身就是高风险
        if (action.type == "take_over") {
            return RiskAssessment(
                isRisky = true,
                riskLevel = RiskLevel.HIGH,
                riskType = RiskType.UNKNOWN,
                reason = "Action type is take_over",
                needConfirm = true,
                confirmMessage = action.message ?: "需要用户介入操作"
            )
        }

        return RiskAssessment(
            isRisky = false,
            riskLevel = RiskLevel.NONE,
            riskType = RiskType.UNKNOWN,
            reason = "Action type is safe",
            needConfirm = false,
            confirmMessage = ""
        )
    }

    /**
     * 评估文本内容
     */
    private fun assessTextContent(text: String): RiskAssessment {
        val lowerText = text.lowercase()

        // 检测支付
        if (PAYMENT_KEYWORDS.any { it in lowerText }) {
            return RiskAssessment(
                isRisky = true,
                riskLevel = RiskLevel.HIGH,
                riskType = RiskType.PAYMENT,
                reason = "Payment-related text detected: $text",
                needConfirm = true,
                confirmMessage = "即将进行支付操作，是否继续？"
            )
        }

        // 检测删除
        if (DELETE_KEYWORDS.any { it in lowerText }) {
            return RiskAssessment(
                isRisky = true,
                riskLevel = RiskLevel.MEDIUM,
                riskType = RiskType.DELETE,
                reason = "Delete-related text detected: $text",
                needConfirm = true,
                confirmMessage = "即将删除内容，是否继续？"
            )
        }

        // 检测授权
        if (AUTH_KEYWORDS.any { it in lowerText }) {
            return RiskAssessment(
                isRisky = true,
                riskLevel = RiskLevel.MEDIUM,
                riskType = RiskType.AUTHORIZATION,
                reason = "Authorization-related text detected: $text",
                needConfirm = true,
                confirmMessage = "即将授权操作，是否继续？"
            )
        }

        // 检测验证码
        if (CAPTCHA_KEYWORDS.any { it in lowerText }) {
            return RiskAssessment(
                isRisky = true,
                riskLevel = RiskLevel.CRITICAL,
                riskType = RiskType.CAPTCHA,
                reason = "Captcha detected: $text",
                needConfirm = true,
                confirmMessage = "检测到验证码，需要用户手动操作"
            )
        }

        return RiskAssessment(
            isRisky = false,
            riskLevel = RiskLevel.NONE,
            riskType = RiskType.UNKNOWN,
            reason = "Text content is safe",
            needConfirm = false,
            confirmMessage = ""
        )
    }

    /**
     * 评估屏幕描述
     */
    private fun assessScreenDescription(description: String): RiskAssessment {
        val lowerDesc = description.lowercase()

        // 检测支付页面
        if (PAYMENT_KEYWORDS.any { it in lowerDesc }) {
            return RiskAssessment(
                isRisky = true,
                riskLevel = RiskLevel.HIGH,
                riskType = RiskType.PAYMENT,
                reason = "Payment screen detected",
                needConfirm = true,
                confirmMessage = "当前页面涉及支付，是否继续？"
            )
        }

        // 检测验证码页面
        if (CAPTCHA_KEYWORDS.any { it in lowerDesc }) {
            return RiskAssessment(
                isRisky = true,
                riskLevel = RiskLevel.CRITICAL,
                riskType = RiskType.CAPTCHA,
                reason = "Captcha screen detected",
                needConfirm = true,
                confirmMessage = "检测到验证码，需要用户手动操作"
            )
        }

        return RiskAssessment(
            isRisky = false,
            riskLevel = RiskLevel.NONE,
            riskType = RiskType.UNKNOWN,
            reason = "Screen description is safe",
            needConfirm = false,
            confirmMessage = ""
        )
    }

    /**
     * 评估输入内容
     */
    private fun assessInputContent(input: String): RiskAssessment {
        // 检测敏感输入类型（通过输入框提示或上下文）
        // 这里我们假设输入内容本身可能包含敏感信息
        
        // 检测是否是密码（简单启发式）
        if (isLikelyPassword(input)) {
            return RiskAssessment(
                isRisky = true,
                riskLevel = RiskLevel.CRITICAL,
                riskType = RiskType.SENSITIVE_INPUT,
                reason = "Sensitive input detected (password)",
                needConfirm = true,
                confirmMessage = "正在输入敏感信息，请确认"
            )
        }

        return RiskAssessment(
            isRisky = false,
            riskLevel = RiskLevel.NONE,
            riskType = RiskType.UNKNOWN,
            reason = "Input content is safe",
            needConfirm = false,
            confirmMessage = ""
        )
    }

    /**
     * 判断是否可能是密码（简单启发式）
     */
    private fun isLikelyPassword(input: String): Boolean {
        // 密码通常包含字母和数字，且不包含空格
        val hasLetter = input.any { it.isLetter() }
        val hasDigit = input.any { it.isDigit() }
        val noSpace = !input.contains(" ")
        
        return hasLetter && hasDigit && noSpace && input.length >= 6
    }

    /**
     * 从 VLM 响应中检测敏感操作
     * 
     * @param vlmResponse VLM 响应文本
     * @return 风险评估结果
     */
    fun assessVLMResponse(vlmResponse: String): RiskAssessment {
        val lowerResponse = vlmResponse.lowercase()

        // 检测敏感关键词
        if (SENSITIVE_INPUT_TYPES.any { it in lowerResponse }) {
            return RiskAssessment(
                isRisky = true,
                riskLevel = RiskLevel.CRITICAL,
                riskType = RiskType.SENSITIVE_INPUT,
                reason = "Sensitive input detected in VLM response",
                needConfirm = true,
                confirmMessage = "检测到敏感操作，需要用户确认"
            )
        }

        if (CAPTCHA_KEYWORDS.any { it in lowerResponse }) {
            return RiskAssessment(
                isRisky = true,
                riskLevel = RiskLevel.CRITICAL,
                riskType = RiskType.CAPTCHA,
                reason = "Captcha detected in VLM response",
                needConfirm = true,
                confirmMessage = "检测到验证码，需要用户手动操作"
            )
        }

        return RiskAssessment(
            isRisky = false,
            riskLevel = RiskLevel.NONE,
            riskType = RiskType.UNKNOWN,
            reason = "VLM response is safe",
            needConfirm = false,
            confirmMessage = ""
        )
    }

    /**
     * 生成确认消息（根据风险类型）
     */
    fun generateConfirmMessage(riskType: RiskType, context: String = ""): String {
        return when (riskType) {
            RiskType.PAYMENT -> "即将进行支付操作，金额可能涉及金钱，是否继续？"
            RiskType.DELETE -> "即将删除内容，此操作不可恢复，是否继续？"
            RiskType.AUTHORIZATION -> "即将授权访问，是否继续？"
            RiskType.SENSITIVE_INPUT -> "正在输入敏感信息（如密码），请确认"
            RiskType.CAPTCHA -> "检测到验证码，需要用户手动操作"
            RiskType.UNKNOWN -> "检测到敏感操作，是否继续？"
        }
    }
}