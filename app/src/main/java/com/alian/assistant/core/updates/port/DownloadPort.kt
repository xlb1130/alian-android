package com.alian.assistant.core.updates.port

import com.alian.assistant.core.updates.model.DownloadRequest
import com.alian.assistant.core.updates.model.DownloadStatus
import com.alian.assistant.core.updates.model.DownloadTicket

/**
 * 下载端口。
 *
 * 设计约束：
 * 1. 对上层统一下载任务模型。
 * 2. 平台差异由基础设施层适配。
 */
interface DownloadPort {

    /**
     * 入队下载任务。
     *
     * @param request 下载请求。
     * @return 下载票据。
     */
    suspend fun enqueue(request: DownloadRequest): Result<DownloadTicket>

    /**
     * 查询下载任务状态。
     *
     * @param ticket 下载票据。
     * @return 下载状态。
     */
    suspend fun query(ticket: DownloadTicket): Result<DownloadStatus>
}
