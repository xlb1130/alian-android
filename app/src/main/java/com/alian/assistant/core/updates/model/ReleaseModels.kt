package com.alian.assistant.core.updates.model

/**
 * 应用更新频道。
 *
 * 设计约束：
 * 1. `STABLE` 默认过滤预发布版本。
 * 2. `BETA` 允许接收预发布版本。
 */
enum class ReleaseChannel {
    STABLE,
    BETA;

    companion object {
        /**
         * 从字符串解析更新频道。
         *
         * 失败行为：
         * - 非法值统一降级为 `STABLE`。
         */
        fun fromRaw(raw: String?): ReleaseChannel {
            return when (raw?.trim()?.lowercase()) {
                "beta" -> BETA
                else -> STABLE
            }
        }
    }
}

/**
 * 发布资产信息。
 *
 * @property name 文件名。
 * @property downloadUrl 下载地址。
 * @property sizeBytes 文件大小（字节）。
 */
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)

/**
 * 发布信息。
 *
 * 设计约束：
 * 1. `version` 用于语义版本比较，建议不含前缀 `v`。
 * 2. `tag` 保留原始发布标签用于展示与追溯。
 *
 * @property version 规范化版本号。
 * @property tag 发布标签。
 * @property title 发布标题。
 * @property body 发布说明。
 * @property htmlUrl 发布页面。
 * @property publishedAtMs 发布时间（毫秒时间戳）。
 * @property preRelease 是否预发布。
 * @property assets 可下载资产列表。
 */
data class ReleaseInfo(
    val version: String,
    val tag: String,
    val title: String,
    val body: String,
    val htmlUrl: String,
    val publishedAtMs: Long,
    val preRelease: Boolean,
    val assets: List<ReleaseAsset>,
) {
    /**
     * 选择最优 APK 下载地址。
     *
     * 失败行为：
     * - 未找到 APK 时返回空字符串。
     */
    fun preferredApkUrl(): String {
        val apkAsset = assets.firstOrNull { it.name.lowercase().endsWith(".apk") }
        return apkAsset?.downloadUrl.orEmpty()
    }
}

/**
 * 更新决策枚举。
 */
enum class UpdateDecision {
    UPDATE_AVAILABLE,
    UP_TO_DATE,
    THROTTLED,
    IGNORED,
}

/**
 * 更新规划结果。
 *
 * @property decision 决策结果。
 * @property release 目标版本；无可更新时为空。
 * @property message 面向 UI 的解释信息。
 * @property nextCheckAfterMs 下一次建议检查时间戳（毫秒）。
 */
data class UpdatePlan(
    val decision: UpdateDecision,
    val release: ReleaseInfo?,
    val message: String,
    val nextCheckAfterMs: Long,
)

/**
 * 下载请求。
 *
 * @property url 下载地址。
 * @property fileName 目标文件名。
 * @property title 系统下载通知标题。
 * @property description 系统下载通知描述。
 */
data class DownloadRequest(
    val url: String,
    val fileName: String,
    val title: String,
    val description: String,
)

/**
 * 下载任务票据。
 *
 * @property id 平台下载任务 ID。
 */
data class DownloadTicket(
    val id: Long,
)

/**
 * 下载状态。
 *
 * @property state 状态枚举。
 * @property bytesDownloaded 已下载字节数。
 * @property bytesTotal 总字节数。
 * @property reason 失败或暂停原因。
 */
data class DownloadStatus(
    val state: DownloadState,
    val bytesDownloaded: Long,
    val bytesTotal: Long,
    val reason: String,
)

/**
 * 下载状态枚举。
 */
enum class DownloadState {
    PENDING,
    RUNNING,
    PAUSED,
    SUCCESSFUL,
    FAILED,
    UNKNOWN,
}
