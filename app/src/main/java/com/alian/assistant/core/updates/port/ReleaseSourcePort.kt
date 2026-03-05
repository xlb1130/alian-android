package com.alian.assistant.core.updates.port

import com.alian.assistant.core.updates.model.ReleaseChannel
import com.alian.assistant.core.updates.model.ReleaseInfo

/**
 * 发布源端口。
 *
 * 设计约束：
 * 1. 领域层仅依赖该端口，不感知具体更新源实现。
 * 2. 返回 `Result` 承载网络错误，避免抛异常污染调用链。
 */
interface ReleaseSourcePort {

    /**
     * 获取指定频道的最新发布。
     *
     * @param channel 更新频道。
     * @return 最新发布；无发布时 `Result.success(null)`。
     */
    suspend fun fetchLatest(channel: ReleaseChannel): Result<ReleaseInfo?>

    /**
     * 获取发布历史。
     *
     * @param channel 更新频道。
     * @param page 页码（从 1 开始）。
     * @param size 每页数量。
     * @return 发布历史列表。
     */
    suspend fun fetchHistory(channel: ReleaseChannel, page: Int, size: Int): Result<List<ReleaseInfo>>
}
