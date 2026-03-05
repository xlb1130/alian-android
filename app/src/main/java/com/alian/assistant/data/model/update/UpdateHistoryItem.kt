package com.alian.assistant.data.model.update

/**
 * 更新历史条目。
 *
 * @property timestampMs 时间戳（毫秒）。
 * @property type 事件类型。
 * @property message 事件描述。
 * @property version 关联版本。
 * @property url 关联链接。
 */
data class UpdateHistoryItem(
    val timestampMs: Long,
    val type: String,
    val message: String,
    val version: String,
    val url: String,
)

/**
 * 更新偏好配置。
 *
 * @property channel 更新频道，`stable` / `beta`。
 * @property ignoredVersion 忽略版本。
 * @property lastCheckAtMs 上次检查时间戳。
 */
data class UpdatePreferences(
    val channel: String = "stable",
    val ignoredVersion: String = "",
    val lastCheckAtMs: Long = 0L,
)
