package com.alian.assistant.core.agent.improve

import com.alian.assistant.core.agent.memory.Action

/**
 * 任务终态判定器：
 * 结合动作语义、Reflector 结果和执行上下文判断是否应结束任务。
 */
class TaskCompletionJudge {

    data class Decision(
        val shouldTerminate: Boolean,
        val success: Boolean,
        val reason: String
    )

    fun judge(
        action: Action,
        verification: ReflectorImprove.VerificationResult,
        actionDesc: String,
        lastSummary: String,
        instruction: String,
        contextHints: List<String> = emptyList()
    ): Decision {
        if (action.type == "terminate") {
            val isSuccess = action.status?.lowercase() in SUCCESS_STATUSES
            return Decision(
                shouldTerminate = true,
                success = isSuccess,
                reason = "explicit_terminate_${if (isSuccess) "success" else "fail"}"
            )
        }

        if (verification.outcome != "A") {
            return Decision(false, false, "reflect_not_success")
        }

        val profile = inferProfile(instruction)
        val text = listOfNotNull(
            action.type,
            action.targetText,
            action.targetDesc,
            action.targetResourceId,
            action.expectedOutcome,
            action.tellUser,
            action.message,
            actionDesc,
            lastSummary
        ).joinToString(" ").lowercase()
        val evidenceText = (listOf(text) + contextHints).joinToString(" ").lowercase()

        val hasNegativeAnchor = profile.negativeAnchors.any { anchor ->
            evidenceText.contains(anchor)
        }
        if (hasNegativeAnchor) {
            return Decision(false, false, "negative_anchor_detected_${profile.name.lowercase()}")
        }

        val hasPositiveAnchor = profile.positiveAnchors.any { anchor ->
            evidenceText.contains(anchor)
        }
        val isTerminalAction = TERMINAL_KEYWORDS.any { keyword ->
            text.contains(keyword)
        }
        if (!isTerminalAction) {
            return Decision(false, false, "not_terminal_semantic")
        }
        if (!hasPositiveAnchor && action.expectedOutcome.isNullOrBlank()) {
            return Decision(false, false, "terminal_without_positive_anchor_${profile.name.lowercase()}")
        }

        return Decision(
            shouldTerminate = true,
            success = true,
            reason = "terminal_anchor_verified_${profile.name.lowercase()}"
        )
    }

    companion object {
        private data class CompletionProfile(
            val name: String,
            val positiveAnchors: List<String>,
            val negativeAnchors: List<String>
        )

        private val SUCCESS_STATUSES = setOf("success", "succeeded", "completed", "done")
        private val TERMINAL_KEYWORDS = listOf(
            "发送", "提交", "确认", "完成", "保存", "发布", "下单", "支付", "付款", "删除", "移除", "上传",
            "send", "submit", "confirm", "finish", "complete", "save", "publish", "order", "pay", "delete", "upload"
        )

        private val CHAT_PROFILE = CompletionProfile(
            name = "chat",
            positiveAnchors = listOf("已发送", "发送成功", "消息已发送", "message sent", "sent"),
            negativeAnchors = listOf("发送失败", "重试", "失败", "fail", "error")
        )
        private val FORM_PROFILE = CompletionProfile(
            name = "form",
            positiveAnchors = listOf("提交成功", "保存成功", "已完成", "success", "saved", "submitted"),
            negativeAnchors = listOf("必填", "不能为空", "格式错误", "error", "invalid")
        )
        private val SEARCH_PROFILE = CompletionProfile(
            name = "search",
            positiveAnchors = listOf("搜索结果", "结果", "已找到", "results", "found"),
            negativeAnchors = listOf("无结果", "为空", "not found", "no result")
        )
        private val DEFAULT_PROFILE = CompletionProfile(
            name = "default",
            positiveAnchors = listOf("成功", "完成", "ok", "done", "success"),
            negativeAnchors = listOf("失败", "重试", "error", "fail")
        )

        private fun inferProfile(instruction: String): CompletionProfile {
            val text = instruction.lowercase()
            return when {
                listOf("消息", "发送", "chat", "dingtalk", "微信", "钉钉").any { text.contains(it) } -> CHAT_PROFILE
                listOf("提交", "保存", "表单", "申请", "confirm", "submit").any { text.contains(it) } -> FORM_PROFILE
                listOf("搜索", "查找", "search", "find").any { text.contains(it) } -> SEARCH_PROFILE
                else -> DEFAULT_PROFILE
            }
        }
    }
}

