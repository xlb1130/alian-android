package com.alian.assistant.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 聊天消息数据（用于序列化存储）
 */
data class ChatMessageData(
    val id: String,
    val role: String,  // USER, ASSISTANT, SYSTEM
    val content: String,
    val timestamp: Long,
    val interruptType: String? = null,  // 打断类型
    val newIntent: String? = null
)

/**
 * 执行指标快照（用于灰度对比与回归分析）。
 */
data class ExecutionMetricsData(
    val runtimeSelected: String = "unknown",
    val runtimeHealthAnomalyCount: Int = 0,
    val runtimeFallbackCount: Int = 0,
    val snapshotTotalRequests: Int = 0,
    val snapshotForceRefreshRequests: Int = 0,
    val snapshotFreshCaptureCount: Int = 0,
    val snapshotCacheHitCount: Int = 0,
    val snapshotThrottleReuseCount: Int = 0,
    val snapshotHitRate: String = "0.0%",
    val snapshotRepoFallbackCount: Int = 0,
    val snapshotRuntimeFallbackCount: Int = 0,
    val snapshotRuntimeRecoveredCount: Int = 0,
    val snapshotRecoverRate: String = "0.0%",
    val durationMs: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("runtimeSelected", runtimeSelected)
        put("runtimeHealthAnomalyCount", runtimeHealthAnomalyCount)
        put("runtimeFallbackCount", runtimeFallbackCount)
        put("snapshotTotalRequests", snapshotTotalRequests)
        put("snapshotForceRefreshRequests", snapshotForceRefreshRequests)
        put("snapshotFreshCaptureCount", snapshotFreshCaptureCount)
        put("snapshotCacheHitCount", snapshotCacheHitCount)
        put("snapshotThrottleReuseCount", snapshotThrottleReuseCount)
        put("snapshotHitRate", snapshotHitRate)
        put("snapshotRepoFallbackCount", snapshotRepoFallbackCount)
        put("snapshotRuntimeFallbackCount", snapshotRuntimeFallbackCount)
        put("snapshotRuntimeRecoveredCount", snapshotRuntimeRecoveredCount)
        put("snapshotRecoverRate", snapshotRecoverRate)
        put("durationMs", durationMs)
    }

    companion object {
        fun fromJson(json: JSONObject): ExecutionMetricsData {
            return ExecutionMetricsData(
                runtimeSelected = json.optString("runtimeSelected", "unknown"),
                runtimeHealthAnomalyCount = json.optInt("runtimeHealthAnomalyCount", 0),
                runtimeFallbackCount = json.optInt("runtimeFallbackCount", 0),
                snapshotTotalRequests = json.optInt("snapshotTotalRequests", 0),
                snapshotForceRefreshRequests = json.optInt("snapshotForceRefreshRequests", 0),
                snapshotFreshCaptureCount = json.optInt("snapshotFreshCaptureCount", 0),
                snapshotCacheHitCount = json.optInt("snapshotCacheHitCount", 0),
                snapshotThrottleReuseCount = json.optInt("snapshotThrottleReuseCount", 0),
                snapshotHitRate = json.optString("snapshotHitRate", "0.0%"),
                snapshotRepoFallbackCount = json.optInt("snapshotRepoFallbackCount", 0),
                snapshotRuntimeFallbackCount = json.optInt("snapshotRuntimeFallbackCount", 0),
                snapshotRuntimeRecoveredCount = json.optInt("snapshotRuntimeRecoveredCount", 0),
                snapshotRecoverRate = json.optString("snapshotRecoverRate", "0.0%"),
                durationMs = json.optLong("durationMs", 0L)
            )
        }
    }
}

/**
 * 执行步骤记录
 */
data class ExecutionStep(
    val id: String = UUID.randomUUID().toString(),  // 唯一标识符，用于 LazyColumn key
    val stepNumber: Int,
    val timestamp: Long,
    val action: String,
    val description: String,
    val thought: String,
    val outcome: String,  // A=成功, B=部分成功, C=失败
    val screenshotPath: String? = null,
    val icon: String? = null,          // 动作图标
    val duration: Long = 0,            // 执行耗时（毫秒）
    val isExpanded: Boolean = true,    // 是否展开详情
    val actionText: String? = null,    // 动作的 text 字段（用于 type, answer 等动作）
    val actionButton: String? = null,  // 动作的 button 字段（用于 system_button 等动作）
    val actionDuration: Int? = null,   // 动作的 duration 字段（用于 wait 动作，单位：秒）
    val actionMessage: String? = null  // 动作的 message 字段（用于 take_over, ask_user 等动作）
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("stepNumber", stepNumber)
        put("timestamp", timestamp)
        put("action", action)
        put("description", description)
        put("thought", thought)
        put("outcome", outcome)
        put("screenshotPath", screenshotPath ?: "")
        put("icon", icon ?: "")
        put("duration", duration)
        put("isExpanded", isExpanded)
        put("actionText", actionText ?: "")
        put("actionButton", actionButton ?: "")
        put("actionDuration", actionDuration ?: 0)
        put("actionMessage", actionMessage ?: "")
    }

    companion object {
        fun fromJson(json: JSONObject): ExecutionStep = ExecutionStep(
            id = json.optString("id", UUID.randomUUID().toString()),
            stepNumber = json.optInt("stepNumber", 0),
            timestamp = json.optLong("timestamp", 0),
            action = json.optString("action", ""),
            description = json.optString("description", ""),
            thought = json.optString("thought", ""),
            outcome = json.optString("outcome", ""),
            screenshotPath = json.optString("screenshotPath", "").ifEmpty { null },
            icon = json.optString("icon", "").ifEmpty { null },
            duration = json.optLong("duration", 0),
            isExpanded = json.optBoolean("isExpanded", true),
            actionText = json.optString("actionText", "").ifEmpty { null },
            actionButton = json.optString("actionButton", "").ifEmpty { null },
            actionDuration = json.optInt("actionDuration", 0).takeIf { it > 0 },
            actionMessage = json.optString("actionMessage", "").ifEmpty { null }
        )
    }
}

/**
 * 执行记录
 */
data class ExecutionRecord(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val instruction: String,
    val startTime: Long,
    val endTime: Long = 0,
    val status: ExecutionStatus = ExecutionStatus.RUNNING,
    val steps: List<ExecutionStep> = emptyList(),
    val logs: List<String> = emptyList(),
    val resultMessage: String = "",
    val chatHistory: List<ChatMessageData> = emptyList(),  // 对话历史
    val executionMetrics: ExecutionMetricsData? = null
) {
    val duration: Long get() = if (endTime > 0) endTime - startTime else System.currentTimeMillis() - startTime

    val formattedStartTime: String get() {
        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        return sdf.format(Date(startTime))
    }

    val formattedDuration: String get() {
        val seconds = duration / 1000
        return if (seconds < 60) "${seconds}秒" else "${seconds / 60}分${seconds % 60}秒"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("instruction", instruction)
        put("startTime", startTime)
        put("endTime", endTime)
        put("status", status.name)
        put("resultMessage", resultMessage)
        put("steps", JSONArray().apply {
            steps.forEach { put(it.toJson()) }
        })
        put("logs", JSONArray().apply {
            logs.forEach { put(it) }
        })
        put("chatHistory", JSONArray().apply {
            chatHistory.forEach { 
                put(JSONObject().apply {
                    put("id", it.id)
                    put("role", it.role)
                    put("content", it.content)
                    put("timestamp", it.timestamp)
                    put("interruptType", it.interruptType)
                    put("newIntent", it.newIntent)
                })
            }
        })
        executionMetrics?.let { put("executionMetrics", it.toJson()) }
    }

    companion object {
        fun fromJson(json: JSONObject): ExecutionRecord {
            val stepsArray = json.optJSONArray("steps") ?: JSONArray()
            val steps = mutableListOf<ExecutionStep>()
            for (i in 0 until stepsArray.length()) {
                steps.add(ExecutionStep.fromJson(stepsArray.getJSONObject(i)))
            }
            val logsArray = json.optJSONArray("logs") ?: JSONArray()
            val logs = mutableListOf<String>()
            for (i in 0 until logsArray.length()) {
                logs.add(logsArray.optString(i, ""))
            }
            val chatHistoryArray = json.optJSONArray("chatHistory") ?: JSONArray()
            val chatHistory = mutableListOf<ChatMessageData>()
            for (i in 0 until chatHistoryArray.length()) {
                val msgJson = chatHistoryArray.getJSONObject(i)
                val interruptTypeStr = msgJson.optString("interruptType", null)
                val newIntentStr = msgJson.optString("newIntent", null)
                chatHistory.add(ChatMessageData(
                    id = msgJson.optString("id", UUID.randomUUID().toString()),
                    role = msgJson.optString("role", "USER"),
                    content = msgJson.optString("content", ""),
                    timestamp = msgJson.optLong("timestamp", 0),
                    interruptType = if (interruptTypeStr.isNullOrEmpty()) null else interruptTypeStr,
                    newIntent = if (newIntentStr.isNullOrEmpty()) null else newIntentStr
                ))
            }
            return ExecutionRecord(
                id = json.optString("id", UUID.randomUUID().toString()),
                title = json.optString("title", ""),
                instruction = json.optString("instruction", ""),
                startTime = json.optLong("startTime", 0),
                endTime = json.optLong("endTime", 0),
                status = try {
                    ExecutionStatus.valueOf(json.optString("status", "COMPLETED"))
                } catch (e: Exception) {
                    ExecutionStatus.COMPLETED
                },
                steps = steps,
                logs = logs,
                resultMessage = json.optString("resultMessage", ""),
                chatHistory = chatHistory,
                executionMetrics = json.optJSONObject("executionMetrics")?.let {
                    ExecutionMetricsData.fromJson(it)
                }
            )
        }
    }
}

enum class ExecutionStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    STOPPED
}

/**
 * 执行记录仓库
 */
class ExecutionRepository(private val context: Context) {

    private val historyFile: File
        get() = File(context.filesDir, "execution_history.json")

    /**
     * 获取所有记录
     */
    suspend fun getAllRecords(): List<ExecutionRecord> = withContext(Dispatchers.IO) {
        try {
            if (!historyFile.exists()) return@withContext emptyList()
            val json = historyFile.readText()
            val array = JSONArray(json)
            val records = mutableListOf<ExecutionRecord>()
            for (i in 0 until array.length()) {
                val record = ExecutionRecord.fromJson(array.getJSONObject(i))
                records.add(record)
                android.util.Log.d("ExecutionRepository", "加载记录 $i - ID: ${record.id}, steps 数量: ${record.steps.size}")
            }
            records.sortedByDescending { it.startTime }
        } catch (e: Exception) {
            android.util.Log.e("ExecutionRepository", "加载记录失败", e)
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * 获取单条记录
     */
    suspend fun getRecord(id: String): ExecutionRecord? = withContext(Dispatchers.IO) {
        getAllRecords().find { it.id == id }
    }

    /**
     * 保存记录
     */
    suspend fun saveRecord(record: ExecutionRecord) = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("ExecutionRepository", "保存记录 - ID: ${record.id}, steps 数量: ${record.steps.size}")
            val records = getAllRecords().toMutableList()
            val existingIndex = records.indexOfFirst { it.id == record.id }
            if (existingIndex >= 0) {
                records[existingIndex] = record
            } else {
                records.add(0, record)
            }
            // 只保留最近100条记录
            val trimmedRecords = records.take(100)
            val array = JSONArray().apply {
                trimmedRecords.forEach { put(it.toJson()) }
            }
            val jsonString = array.toString()
            historyFile.writeText(jsonString)
            android.util.Log.d("ExecutionRepository", "JSON 文件大小: ${jsonString.length} 字符")
        } catch (e: Exception) {
            android.util.Log.e("ExecutionRepository", "保存记录失败", e)
            e.printStackTrace()
        }
    }

    /**
     * 删除记录
     */
    suspend fun deleteRecord(id: String) = withContext(Dispatchers.IO) {
        try {
            val records = getAllRecords().filter { it.id != id }
            val array = JSONArray().apply {
                records.forEach { put(it.toJson()) }
            }
            historyFile.writeText(array.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 清空所有记录
     */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        try {
            historyFile.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
