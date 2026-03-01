package com.alian.assistant.core.alian.backend

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Backend API 数据模型
 */

/**
 * 登录请求
 */
@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

/**
 * 刷新Token请求
 */
@Serializable
data class RefreshTokenRequest(
    val refresh_token: String
)

/**
 * 登录响应
 */
@Serializable
data class LoginResponse(
    val code: Int,
    val msg: String,
    val data: AuthData?
)

@Serializable
data class AuthData(
    val user: UserData? = null,
    val access_token: String,
    val refresh_token: String,
    val token_type: String
)

/**
 * 刷新Token响应
 */
@Serializable
data class RefreshTokenResponse(
    val code: Int,
    val msg: String,
    val data: RefreshTokenData?
)

@Serializable
data class RefreshTokenData(
    val access_token: String,
    val token_type: String
)

/**
 * 认证状态响应
 */
@Serializable
data class AuthStatusResponse(
    val code: Int,
    val msg: String,
    val data: AuthStatusData?
)

@Serializable
data class AuthStatusData(
    val auth_provider: String? = null
)

@Serializable
data class UserData(
    val id: String? = null,
    val email: String? = null,
    val name: String? = null
)

/**
 * 通用API响应
 */
@Serializable
data class ApiResponse<T>(
    val code: Int,
    val msg: String,
    val data: T?
)

/**
 * 会话数据
 */
@Serializable
data class SessionData(
    val session_id: String,
    val title: String? = null,
    val status: String? = null,
    val unread_message_count: Int = 0,
    val latest_message: String? = null,
    val latest_message_at: Long? = null,
    val is_shared: Boolean = false
)

/**
 * 会话消息数据
 */
@Serializable
data class SessionMessage(
    val message_id: String,
    val role: String,  // "user" | "assistant"
    val content: String,
    val timestamp: Long,
    val attachments: List<Attachment>? = null
)

/**
 * 会话消息列表响应
 */
@Serializable
data class SessionMessagesResponse(
    val code: Int,
    val msg: String,
    val data: SessionMessagesData?
)

@Serializable
data class SessionMessagesData(
    val messages: List<SessionMessage>
)

/**
 * 会话详细信息响应
 */
@Serializable
data class SessionDetailResponse(
    val code: Int,
    val msg: String,
    val data: SessionDetailData?
)

@Serializable
data class SessionDetailData(
    val session_id: String,
    val title: String? = null,
    val status: String? = null,
    val events: List<SessionEvent> = emptyList(),  // 旧接口字段，设为可选
    val event_count: Int = 0,  // 新接口字段
    val last_event_seq: Int = 0,  // 新接口字段
    val is_shared: Boolean = false  // 新接口字段
)

/**
 * 会话事件
 */
@Serializable
data class SessionEvent(
    val event: String,  // "message", "deep_thinking_chunk", "message_chunk", etc.
    val data: JsonElement  // JSON对象或字符串，需要根据event类型解析
)

/**
 * 会话列表响应
 */
@Serializable
data class SessionsResponse(
    val code: Int,
    val msg: String,
    val data: SessionsData?
)

@Serializable
data class SessionsData(
    val sessions: List<SessionData>
)

/**
 * 创建会话响应
 */
@Serializable
data class CreateSessionResponse(
    val code: Int,
    val msg: String,
    val data: SessionIdData?
)

@Serializable
data class SessionIdData(
    val session_id: String
)

/**
 * 聊天请求
 */
@Serializable
data class ChatRequest(
    val message: String,
    val timestamp: Long = System.currentTimeMillis() / 1000,
    val event_id: String? = null,
    val attachments: List<String> = emptyList()
)

/**
 * SSE事件类型
 */
enum class SSEEventType {
    MESSAGE,              // 助手的文本消息（旧接口）
    MESSAGE_CHUNK,        // 助手的流式文本消息块（旧接口）
    TITLE,                // 会话标题更新（旧接口）
    PLAN,                 // 执行计划（旧接口）
    STEP,                 // 步骤状态更新（旧接口）
    TOOL,                 // 工具调用信息（旧接口）
    DEEP_THINKING_CHUNK,  // 深度思考流式消息块（旧接口）
    ERROR,                // 错误信息（旧接口）
    DONE,                 // 对话完成（旧接口）
    WAIT,                 // 等待事件（旧接口）
    COMMON,               // 通用事件（兜底）

    // 新接口事件类型
    TEXT_MESSAGE_START,   // 文本消息开始（新接口）
    TEXT_MESSAGE_CHUNK,   // 文本消息块（新接口）
    TEXT_MESSAGE_END,     // 文本消息结束（新接口）
    USER_MESSAGE,         // 用户消息（新接口）
    TOOL_CALL_START,      // 工具调用开始（新接口）
    TOOL_CALL_CHUNK,      // 工具调用块（新接口）- 暂时忽略
    TOOL_CALL_RESULT,     // 工具调用结果（新接口）
    TOOL_CALL_END,        // 工具调用结束（新接口）
    PLAN_STARTED,         // 计划开始（新接口）
    PLAN_FINISHED,        // 计划完成（新接口）
    PHASE_STARTED,        // 阶段开始（新接口）
    PHASE_FINISHED        // 阶段完成（新接口）
}

/**
 * SSE事件
 */
data class SSEEvent(
    val type: SSEEventType,
    val data: String
)

/**
 * Shell输出请求
 */
@Serializable
data class ShellOutputRequest(
    val session_id: String
)

/**
 * 文件内容请求
 */
@Serializable
data class FileContentRequest(
    val file: String
)

/**
 * 文件列表响应
 */
@Serializable
data class FileListResponse(
    val code: Int,
    val msg: String,
    val data: FileListData?
)

@Serializable
data class FileListData(
    val files: List<String>
)

/**
 * SSE事件数据模型
 */

/**
 * 附件信息
 */
@Serializable
data class Attachment(
    val file_id: String? = null,
    val filename: String? = null,
    val name: String? = null,
    val path: String? = null,
    val size: Long? = null,
    val url: String? = null,
    val file_url: String? = null,
    val content_type: String? = null,
    val upload_date: String? = null,
    val metadata: Map<String, @Serializable JsonElement>? = null
)

/**
 * 步骤信息
 */
@Serializable
data class Step(
    val event_id: String,
    val timestamp: Long,
    val status: String,  // "pending" | "in_progress" | "completed" | "failed"
    val id: Int,
    val description: String
)

/**
 * 工具调用信息
 */
@Serializable
data class ToolCall(
    val event_id: String,
    val timestamp: Long,
    val tool_call_id: String,
    val name: String,
    val status: String,  // "pending" | "in_progress" | "completed" | "failed"
    val function: String,
    val args: Map<String, String> = emptyMap(),
    val content: JsonObject? = null  // 改为JsonObject类型，以匹配后端返回的对象类型
)

/**
 * 消息数据
 */
@Serializable
data class MessageData(
    val event_id: String,
    val timestamp: Long,
    val role: String,  // "user" | "assistant"
    val content: String,
    val message_id: String? = null,
    val attachments: List<Attachment>? = null
)

/**
 * 消息块数据（流式输出）
 */
@Serializable
data class MessageChunkData(
    val event_id: String,
    val timestamp: Long,
    val message_id: String,
    val chunk: String,
    val chunk_index: Int,
    val chunk_total: Int? = null,
    val done: Boolean,
    val attachments: List<Attachment>? = null
)

/**
 * 标题数据
 */
@Serializable
data class TitleData(
    val event_id: String,
    val timestamp: Long,
    val title: String
)

/**
 * 计划数据
 */
@Serializable
data class PlanData(
    val event_id: String,
    val timestamp: Long,
    val steps: List<Step>
)

/**
 * 步骤数据
 */
@Serializable
data class StepData(
    val event_id: String,
    val timestamp: Long,
    val status: String,  // "pending" | "in_progress" | "completed" | "failed"
    val id: Int,
    val description: String
)

/**
 * 工具数据
 */
@Serializable
data class ToolData(
    val event_id: String,
    val timestamp: Long,
    val tool_call_id: String,
    val name: String,
    val status: String,  // "pending" | "in_progress" | "completed" | "failed"
    val function: String,
    val args: Map<String, JsonElement> = emptyMap(),
    val content: JsonObject? = null  // 改为JsonObject类型，以匹配后端返回的对象类型
)

/**
 * 深度思考块数据（流式输出）
 */
@Serializable
data class DeepThinkingChunkData(
    val event_id: String,
    val timestamp: Long,
    val chunk_type: String,  // "title" | "paragraph"
    val section_index: Int,
    val chunk_index: Int,
    val chunk: String,
    val done: Boolean
)

/**
 * 错误数据
 */
@Serializable
data class ErrorData(
    val event_id: String,
    val timestamp: Long,
    val error: String
)

/**
 * 完成数据
 */
@Serializable
data class DoneData(
    val event_id: String,
    val timestamp: Long
)

/**
 * 等待数据
 */
@Serializable
data class WaitData(
    val event_id: String,
    val timestamp: Long
)

/**
 * 通用数据
 */
@Serializable
data class CommonData(
    val event_id: String,
    val timestamp: Long
)

/**
 * SSE事件（完整格式）
 */
@Serializable
data class SSEEventFull(
    val event: String,
    val data: String  // JSON字符串，需要根据event类型解析
)

/**
 * 步骤状态枚举
 */
enum class StepStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}

/**
 * 工具状态枚举
 */
enum class ToolStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}

/**
 * UI事件数据模型 - 用于在UI层展示各种事件
 */

/**
 * UI事件基类
 */
sealed class UIEvent {
    abstract val eventId: String
    abstract val timestamp: Long
}

/**
 * UI消息事件
 */
data class UIMessageEvent(
    override val eventId: String,
    override val timestamp: Long,
    val role: String,
    val content: String,
    val attachments: List<Attachment>? = null
) : UIEvent()

/**
 * UI消息块事件（流式输出）
 */
data class UIMessageChunkEvent(
    override val eventId: String,
    override val timestamp: Long,
    val messageId: String,
    val chunk: String,
    val chunkIndex: Int,
    val chunkTotal: Int? = null,
    val done: Boolean,
    val attachments: List<Attachment>? = null
) : UIEvent()

/**
 * UI标题事件
 */
data class UITitleEvent(
    override val eventId: String,
    override val timestamp: Long,
    val title: String
) : UIEvent()

/**
 * UI计划事件
 */
data class UIPlanEvent(
    override val eventId: String,
    override val timestamp: Long,
    val steps: List<UIStep>
) : UIEvent()

/**
 * UI步骤事件
 */
data class UIStepEvent(
    override val eventId: String,
    override val timestamp: Long,
    val step: UIStep
) : UIEvent()

/**
 * UI工具调用事件
 */
data class UIToolEvent(
    override val eventId: String,
    override val timestamp: Long,
    val toolCall: UIToolCall
) : UIEvent()

/**
 * UI深度思考块事件（流式输出）
 */
data class UIDeepThinkingChunkEvent(
    override val eventId: String,
    override val timestamp: Long,
    val chunkType: String,  // "title" | "paragraph"
    val sectionIndex: Int,
    val chunkIndex: Int,
    val chunk: String,
    val done: Boolean
) : UIEvent()

/**
 * UI错误事件
 */
data class UIErrorEvent(
    override val eventId: String,
    override val timestamp: Long,
    val error: String
) : UIEvent()

/**
 * UI完成事件
 */
data class UIDoneEvent(
    override val eventId: String,
    override val timestamp: Long
) : UIEvent()

/**
 * UI等待事件
 */
data class UIWaitEvent(
    override val eventId: String,
    override val timestamp: Long
) : UIEvent()

/**
 * UI步骤数据
 */
data class UIStep(
    val id: Int,
    val description: String,
    val status: String,  // "pending" | "in_progress" | "completed" | "failed"
    val toolCallIds: List<String> = emptyList(),  // 步骤执行的工具调用ID列表
    val tools: List<ToolInfo> = emptyList()  // 步骤执行的工具列表（用于显示）
)

/**
 * 工具信息
 */
data class ToolInfo(
    val name: String,
    val status: String,  // "calling" | "called" | "failed" 等
    val function: String = "",  // 工具调用的函数名
    val args: Map<String, JsonElement> = emptyMap()  // 工具参数，用于显示描述
)

/**
 * UI工具调用数据
 */
data class UIToolCall(
    val toolCallId: String,
    val name: String,
    val status: String,  // "pending" | "in_progress" | "completed" | "failed"
    val function: String,
    val args: Map<String, JsonElement> = emptyMap(),
    val content: JsonObject? = null
)

// ========================================
// 新接口数据模型 (New Backend API Models)
// ========================================

/**
 * 文本消息开始事件数据（新接口）
 */
@Serializable
data class TextMessageStartData(
    val type: String,
    val id: String,
    val timestamp: Long,
    val message_type: String,  // "info" | "result" | "error" 等
    val message_id: String
)

/**
 * 文本消息块事件数据（新接口）
 */
@Serializable
data class TextMessageChunkData(
    val type: String,
    val id: String,
    val timestamp: Long,
    val message_type: String,  // "info" | "result" | "error" 等
    val message_id: String,
    val delta: String
)

/**
 * 文本消息结束事件数据（新接口）
 */
@Serializable
data class TextMessageEndData(
    val type: String,
    val id: String,
    val timestamp: Long,
    val message_id: String
)

/**
 * 工具调用开始事件数据（新接口）
 */
@Serializable
data class ToolCallStartData(
    val type: String,
    val id: String,
    val timestamp: Long,
    val tool_call_id: String,
    val tool_call_name: String
)

/**
 * 工具调用块事件数据（新接口）- 暂时忽略
 */
@Serializable
data class ToolCallChunkData(
    val type: String,
    val id: String,
    val timestamp: Long,
    val tool_call_id: String,
    val tool_call_name: String,
    val delta: String? = null  // 可能为 null 或字符串，或 JSON 对象
)

/**
 * 工具调用结果事件数据（新接口）
 */
@Serializable
data class ToolCallResultData(
    val type: String,
    val id: String,
    val timestamp: Long,
    val tool_call_id: String,
    val tool_call_name: String,
    val content: JsonObject? = null  // 工具执行结果，可能为 null
)

/**
 * 工具调用结束事件数据（新接口）
 */
@Serializable
data class ToolCallEndData(
    val type: String,
    val id: String,
    val timestamp: Long,
    val tool_call_id: String,
    val tool_call_name: String
)

/**
 * 用户消息事件数据（新接口）
 */
@Serializable
data class UserMessageData(
    val type: String,
    val id: String,
    val timestamp: Long,
    val message_id: String,
    val message: String,
    val attachments: List<Attachment>? = null
)

/**
 * 计划开始事件数据（新接口）
 */
@Serializable
data class PlanStartedData(
    val type: String,
    val id: String,
    val timestamp: Long,
    val plan: PlanInfo
)

/**
 * 计划信息（新接口）
 */
@Serializable
data class PlanInfo(
    val goal: String,
    val current_phase_id: Int,
    val phases: List<PhaseInfo>,
    val status: String  // "running" | "completed" | "failed"
)

/**
 * 阶段信息（新接口）
 */
@Serializable
data class PhaseInfo(
    val id: Int,
    val title: String,
    val status: String,  // "running" | "pending" | "completed" | "failed"
    val capabilities: PhaseCapabilities? = null
)

/**
 * 阶段能力（新接口）
 */
@Serializable
data class PhaseCapabilities(
    val creative_writing: Boolean,
    val deep_research: Boolean,
    val technical_writing: Boolean,
    val media_generation: Boolean
)

/**
 * 阶段开始事件数据（新接口）
 */
@Serializable
data class PhaseStartedData(
    val type: String,
    val id: String,
    val timestamp: Long,
    val phase_id: Int,
    val title: String
)

/**
 * 阶段结束事件数据（新接口）
 */
@Serializable
data class PhaseFinishedData(
    val type: String,
    val id: String,
    val timestamp: Long,
    val phase_id: Int,
    val title: String
)

/**
 * 计划完成事件数据（新接口）
 */
@Serializable
data class PlanFinishedData(
    val type: String,
    val id: String,
    val timestamp: Long,
    val plan: PlanInfo,
    val attachments: List<Attachment>? = null
)