package com.alian.assistant.core.agent.improve

import android.util.Log
import com.alian.assistant.data.model.ChatResult
import com.alian.assistant.data.model.ChatRole
import com.alian.assistant.data.model.ChatSession
import com.alian.assistant.data.model.InterruptType
import org.json.JSONObject

/**
 * AdjustChatImprove - 聊天理解器
 * 
 * 负责面向用户对话，输出结构化结果（回答、NEW_INTENT、控制指令）
 * 
 * 核心功能：
 * 1. 解析用户意图（问答、参数调整、任务改道、临时接管、安全确认、停止）
 * 2. 生成结构化响应（JSON 格式）
 * 3. 支持上下文理解（原始任务、当前步骤、历史记录）
 */
class AdjustChatImprove {

    companion object {
        private const val TAG = "AdjustChatImprove"
        
        // 打断关键词
        private val STOP_KEYWORDS = listOf("停", "停止", "取消", "结束", "退出")
        private val PAUSE_KEYWORDS = listOf("暂停", "等等", "等一下", "暂停一下")
        private val CONFIRM_KEYWORDS = listOf("确认", "继续", "是的", "好的", "行")
        private val TAKEOVER_KEYWORDS = listOf("我来", "让我来", "我来操作", "我自己来")
    }

    /**
     * 生成聊天 Prompt
     * 
     * @param infoPool InfoPoolImprove 实例
     * @param chatSession 聊天会话
     * @param userText 用户输入文本
     * @return Prompt 字符串
     */
    fun getPrompt(
        infoPool: InfoPoolImprove,
        chatSession: ChatSession,
        userText: String
    ): String = buildString {
        append("### Context ###\n")
        append("You are helping user complete: ${infoPool.instruction}\n")
        append("Progress: Step ${infoPool.totalSteps}, Done: ${infoPool.completedPlan}\n")
        append("A SCREENSHOT of current screen is attached.\n\n")

        append("### Recent Actions ###\n")
        infoPool.summaryHistory.takeLast(3).forEachIndexed { i, s ->
            append("${i+1}. $s\n")
        }
        append("\n")

        append("### Conversation ###\n")
        chatSession.getRecentMessages(5).forEach { msg ->
            val role = if (msg.role == ChatRole.USER) "User" else "Assistant"
            append("$role: ${msg.content}\n")
        }
        append("\n")

        append("### Current Input ###\n")
        append("$userText\n\n")

        append("### Interrupt Types ###\n")
        append("QA: Answer question (use screenshot) | NEW_INTENT: Change task\n")
        append("TAKE_OVER: User handles it | CONFIRM: User says continue\n")
        append("STOP: User wants to stop | NONE: Just chatting\n\n")

        append("### Output (JSON) ###\n")
        append("""{"answer": "...", "interruptType": "...", "newIntent": "...", "shouldResume": true/false}""")
        append("\n\n")

        append("### Key Rules ###\n")
        append("1. shouldResume=false by default (keep listening)\n")
        append("2. shouldResume=true ONLY when: user confirms continue OR provides new intent\n")
        append("3. When shouldResume=true, newIntent is REQUIRED\n")
        append("4. Use screenshot to answer visual questions accurately\n")
    }

    /**
     * 解析响应
     * 
     * @param response LLM 响应文本
     * @return ChatResult 对象
     */
    fun parseResponse(response: String): ChatResult {
        // 尝试提取 JSON
        val jsonStr = extractJson(response)
        
        return if (jsonStr != null) {
            try {
                val json = JSONObject(jsonStr)
                val interruptTypeStr = json.optString("interruptType", "NONE")
                val interruptType = when (interruptTypeStr.uppercase()) {
                    "QA" -> InterruptType.QA
                    "NEW_INTENT" -> InterruptType.NEW_INTENT
                    "TAKE_OVER" -> InterruptType.TAKE_OVER
                    "CONFIRM" -> InterruptType.CONFIRM
                    "STOP" -> InterruptType.STOP
                    else -> InterruptType.NONE
                }

                val shouldResume = json.optBoolean("shouldResume", true)
                var newIntent = json.optString("newIntent", null)

                // 验证：shouldResume=true 时必须要有 newIntent
                if (shouldResume && (newIntent.isNullOrEmpty())) {
                    Log.w(TAG, "shouldResume=true but newIntent is empty, extracting from answer")
                    // 如果 newIntent 为空，尝试从 answer 中提取用户的诉求
                    newIntent = json.optString("answer", "")
                    if (newIntent.isNotEmpty()) {
                        newIntent = newIntent.take(100) // 限制长度
                    }
                }

                ChatResult(
                    answer = json.optString("answer", ""),
                    interruptType = interruptType,
                    newIntent = if (newIntent.isNullOrEmpty()) null else newIntent,
                    shouldResume = shouldResume
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse JSON response", e)
                // 解析失败，使用默认值
                createFallbackResult(response)
            }
        } else {
            // 未找到 JSON，尝试从文本中推断
            inferFromText(response)
        }
    }

    /**
     * 从响应中提取 JSON
     */
    private fun extractJson(response: String): String? {
        // 尝试找到 ```json ... ``` 块
        val jsonBlockRegex = Regex("""```json\s*([\s\S]*?)\s*```""")
        val match = jsonBlockRegex.find(response)
        if (match != null) {
            return match.groupValues[1].trim()
        }

        // 尝试找到 { ... } 块
        val jsonRegex = Regex("""\{[\s\S]*\}""")
        val jsonMatch = jsonRegex.find(response)
        if (jsonMatch != null) {
            return jsonMatch.value
        }

        return null
    }

    /**
     * 从文本推断结果（当 JSON 解析失败时）
     */
    private fun inferFromText(text: String): ChatResult {
        val lowerText = text.lowercase()

        // 检测停止
        if (STOP_KEYWORDS.any { it in lowerText }) {
            return ChatResult(
                answer = text,
                interruptType = InterruptType.STOP,
                shouldResume = false
            )
        }

        // 检测暂停
        if (PAUSE_KEYWORDS.any { it in lowerText }) {
            return ChatResult(
                answer = text,
                interruptType = InterruptType.QA,
                shouldResume = false
            )
        }

        // 检测接管
        if (TAKEOVER_KEYWORDS.any { it in lowerText }) {
            return ChatResult(
                answer = text,
                interruptType = InterruptType.TAKE_OVER,
                shouldResume = false
            )
        }

        // 检测确认
        if (CONFIRM_KEYWORDS.any { it in lowerText }) {
            return ChatResult(
                answer = text,
                interruptType = InterruptType.CONFIRM,
                shouldResume = true
            )
        }

        // 默认：问答类型，保持监听状态
        return ChatResult(
            answer = text,
            interruptType = InterruptType.QA,
            shouldResume = false
        )
    }

    /**
     * 创建备用结果（当解析完全失败时）
     */
    private fun createFallbackResult(response: String): ChatResult {
        return ChatResult(
            answer = response.take(200),
            interruptType = InterruptType.QA,
            shouldResume = false
        )
    }
}

/**
 * 聊天理解结果（用于内部使用）
 */
data class AdjustChatResult(
    val chatResult: ChatResult,
    val rawResponse: String,
    val parseSuccess: Boolean
)