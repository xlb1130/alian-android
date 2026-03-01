package com.alian.assistant.data.model

import java.util.UUID

/**
 * 聊天消息数据类
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val screenshotPath: String? = null,  // 关联的截图路径
    val relatedAgentStep: Int? = null,   // 关联的 Agent 步骤
    val interruptType: InterruptType? = null,  // 打断类型（如果是打断消息）
    val newIntent: String? = null,       // 新意图（如果打断类型是 NEW_INTENT）
    val needConfirm: Boolean = false,    // 是否需要确认
    val confirmText: String? = null,     // 确认提示文本
    val ttsEnabled: Boolean = false      // 是否启用 TTS 播放
)

/**
 * 聊天角色
 */
enum class ChatRole {
    USER,       // 用户
    ASSISTANT,  // 助手（AI）
    SYSTEM      // 系统（状态更新等）
}

/**
 * 打断类型
 */
enum class InterruptType {
    QA,              // 澄清问答
    NEW_INTENT,      // 任务改道
    TAKE_OVER,       // 临时接管
    CONFIRM,         // 安全确认
    STOP,            // 停止/退出
    NONE             // 无打断（继续执行）
}

/**
 * 聊天会话数据类
 */
data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val originalInstruction: String,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var messages: MutableList<ChatMessage> = mutableListOf(),
    var isCompleted: Boolean = false,
    var isPaused: Boolean = false,
    var currentStep: Int = 0,
    var totalSteps: Int = 0
) {
    /**
     * 添加消息
     */
    fun addMessage(message: ChatMessage) {
        messages.add(message)
        updatedAt = System.currentTimeMillis()
    }

    /**
     * 获取最近的 N 条消息
     */
    fun getRecentMessages(count: Int = 10): List<ChatMessage> {
        return messages.takeLast(count)
    }

    /**
     * 获取用户和助手的对话历史（排除系统消息）
     */
    fun getConversationHistory(): List<ChatMessage> {
        return messages.filter { it.role != ChatRole.SYSTEM }
    }

    /**
     * 获取最后一条用户消息
     */
    fun getLastUserMessage(): ChatMessage? {
        return messages.lastOrNull { it.role == ChatRole.USER }
    }

    /**
     * 获取最后一条助手消息
     */
    fun getLastAssistantMessage(): ChatMessage? {
        return messages.lastOrNull { it.role == ChatRole.ASSISTANT }
    }
}

/**
 * 聊天结果
 */
data class ChatResult(
    val answer: String,
    val interruptType: InterruptType,
    val newIntent: String? = null,
    val shouldResume: Boolean = true  // 是否应该恢复执行（true: 问答结束后恢复，false: 继续问答/暂停）
)