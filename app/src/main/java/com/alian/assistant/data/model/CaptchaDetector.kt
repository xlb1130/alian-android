package com.alian.assistant.data.model

import android.graphics.Bitmap
import android.util.Log
import com.alian.assistant.infrastructure.ai.llm.VLMClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 验证码和敏感页面检测器
 *
 * 功能:
 * 1. 通过 VLM 检测验证码/图形验证/滑块验证
 * 2. 检测支付确认页面
 * 3. 检测安全检查页面
 * 4. OCR 文本检测 (备用方案)
 */
class CaptchaDetector(
    private val vlmClient: VLMClient
) {
    companion object {
        private const val TAG = "CaptchaDetector"

        // 验证码相关关键词
        private val CAPTCHA_KEYWORDS = listOf(
            "验证码", "图形验证", "滑块验证", "人机验证", "拼图验证",
            "captcha", "verify", "security check", "robot", "i'm not a robot"
        )

        // 支付确认相关关键词
        private val PAYMENT_KEYWORDS = listOf(
            "确认", "支付", "付款", "结算", "购买", "下单", "立即支付", "去支付",
            "confirm", "pay", "checkout", "purchase", "buy", "payment"
        )

        // 安全检查相关关键词
        private val SECURITY_KEYWORDS = listOf(
            "安全检查", "身份验证", "二次确认", "风险提示",
            "security check", "identity verification", "risk warning"
        )
    }

    /**
     * 检测敏感页面
     *
     * @param screenshot 屏幕截图
     * @return 敏感页面检测结果
     */
    suspend fun detectSensitivePage(screenshot: Bitmap): SensitivePageResult = withContext(Dispatchers.IO) {
        try {
            val prompt = buildString {
                append("Analyze this screenshot and detect if it contains any sensitive elements.\n\n")
                append("Check for:\n")
                append("1. CAPTCHA/Verification (验证码/图形验证/滑块验证/拼图验证)\n")
                append("2. Payment Confirmation (支付确认/付款/结算/购买/下单)\n")
                append("3. Security Check (安全检查/人机验证/身份验证)\n\n")
                append("Return JSON:\n")
                append("{\n")
                append("  \"is_captcha\": true/false,\n")
                append("  \"is_payment\": true/false,\n")
                append("  \"is_security\": true/false,\n")
                append("  \"is_sensitive\": true/false,\n")
                append("  \"sensitive_type\": \"captcha|payment|security|none\",\n")
                append("  \"confidence\": 0.0-1.0,\n")
                append("  \"description\": \"brief description of what was detected\"\n")
                append("}\n")
            }

            Log.d(TAG, "开始敏感页面检测...")

            val response = vlmClient.predict(prompt, listOf(screenshot))

            if (response.isFailure) {
                Log.e(TAG, "VLM 检测失败: ${response.exceptionOrNull()?.message}")
                return@withContext SensitivePageResult(
                    isSensitive = false,
                    sensitiveType = SensitiveType.NONE,
                    confidence = 0.0f,
                    description = "VLM 检测失败"
                )
            }

            val jsonResponse = JSONObject(response.getOrThrow())
            val isCaptcha = jsonResponse.optBoolean("is_captcha", false)
            val isPayment = jsonResponse.optBoolean("is_payment", false)
            val isSecurity = jsonResponse.optBoolean("is_security", false)
            val isSensitive = jsonResponse.optBoolean("is_sensitive", isCaptcha || isPayment || isSecurity)
            val typeStr = jsonResponse.optString("sensitive_type", "none").lowercase()
            val confidence = jsonResponse.optDouble("confidence", 0.5).toFloat()
            val description = jsonResponse.optString("description", "未检测到敏感信息")

            val sensitiveType = when {
                isCaptcha || typeStr.contains("captcha") -> SensitiveType.CAPTCHA
                isPayment || typeStr.contains("payment") -> SensitiveType.PAYMENT_CONFIRM
                isSecurity || typeStr.contains("security") -> SensitiveType.SECURITY_CHECK
                else -> SensitiveType.NONE
            }

            val result = SensitivePageResult(
                isSensitive = isSensitive,
                sensitiveType = sensitiveType,
                confidence = confidence,
                description = description
            )

            Log.d(TAG, "检测结果: $result")

            return@withContext result

        } catch (e: Exception) {
            Log.e(TAG, "敏感页面检测异常: ${e.message}", e)
            return@withContext SensitivePageResult(
                isSensitive = false,
                sensitiveType = SensitiveType.NONE,
                confidence = 0.0f,
                description = "检测异常: ${e.message}"
            )
        }
    }

    /**
     * 通过 OCR 检测敏感页面 (备用方案)
     *
     * 注意: 需要添加 ML Kit Text Recognition 依赖
     * implementation("com.google.mlkit:text-recognition-chinese:16.0.0")
     */
    suspend fun detectViaOCR(screenshot: Bitmap): SensitivePageResult = withContext(Dispatchers.IO) {
        try {
            // TODO: 集成 ML Kit Text Recognition
            // val image = InputImage.fromBitmap(screenshot, 0)
            // val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            // val result = recognizer.process(image)
            // val text = result.text

            // 临时实现: 模拟 OCR 检测
            Log.d(TAG, "OCR 检测功能暂未实现")

            return@withContext SensitivePageResult(
                isSensitive = false,
                sensitiveType = SensitiveType.NONE,
                confidence = 0.0f,
                description = "OCR 检测功能暂未实现"
            )

        } catch (e: Exception) {
            Log.e(TAG, "OCR 检测异常: ${e.message}", e)
            return@withContext SensitivePageResult(
                isSensitive = false,
                sensitiveType = SensitiveType.NONE,
                confidence = 0.0f,
                description = "OCR 检测异常: ${e.message}"
            )
        }
    }

    /**
     * 检查文本是否包含敏感关键词
     */
    fun containsSensitiveKeywords(text: String): Boolean {
        val lowerText = text.lowercase()
        return CAPTCHA_KEYWORDS.any { lowerText.contains(it.lowercase()) } ||
               PAYMENT_KEYWORDS.any { lowerText.contains(it.lowercase()) } ||
               SECURITY_KEYWORDS.any { lowerText.contains(it.lowercase()) }
    }

    /**
     * 获取敏感类型
     */
    fun getSensitiveType(text: String): SensitiveType {
        val lowerText = text.lowercase()

        return when {
            CAPTCHA_KEYWORDS.any { lowerText.contains(it.lowercase()) } -> SensitiveType.CAPTCHA
            PAYMENT_KEYWORDS.any { lowerText.contains(it.lowercase()) } -> SensitiveType.PAYMENT_CONFIRM
            SECURITY_KEYWORDS.any { lowerText.contains(it.lowercase()) } -> SensitiveType.SECURITY_CHECK
            else -> SensitiveType.NONE
        }
    }
}

/**
 * 敏感页面检测结果
 */
data class SensitivePageResult(
    val isSensitive: Boolean,           // 是否是敏感页面
    val sensitiveType: SensitiveType,   // 敏感类型
    val confidence: Float,              // 检测置信度
    val description: String             // 描述
) {
    override fun toString(): String {
        return "SensitivePageResult(isSensitive=$isSensitive, type=$sensitiveType, confidence=$confidence, desc='$description')"
    }
}

/**
 * 敏感类型枚举
 */
enum class SensitiveType {
    NONE,               // 无敏感内容
    CAPTCHA,            // 验证码
    PAYMENT_CONFIRM,    // 支付确认
    SECURITY_CHECK,     // 安全检查
    MANUAL_APPROVAL     // 人工审批
}
