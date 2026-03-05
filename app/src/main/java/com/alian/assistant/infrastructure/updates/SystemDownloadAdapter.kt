package com.alian.assistant.infrastructure.updates

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import com.alian.assistant.core.updates.model.DownloadRequest
import com.alian.assistant.core.updates.model.DownloadState
import com.alian.assistant.core.updates.model.DownloadStatus
import com.alian.assistant.core.updates.model.DownloadTicket
import com.alian.assistant.core.updates.port.DownloadPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 基于系统 DownloadManager 的下载适配器。
 */
class SystemDownloadAdapter(
    context: Context,
) : DownloadPort {

    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    override suspend fun enqueue(request: DownloadRequest): Result<DownloadTicket> = withContext(Dispatchers.IO) {
        runCatching {
            val task = DownloadManager.Request(Uri.parse(request.url))
                .setTitle(request.title)
                .setDescription(request.description)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, request.fileName)

            val id = downloadManager.enqueue(task)
            DownloadTicket(id = id)
        }
    }

    override suspend fun query(ticket: DownloadTicket): Result<DownloadStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val query = DownloadManager.Query().setFilterById(ticket.id)
            downloadManager.query(query).useCursor { cursor ->
                if (!cursor.moveToFirst()) {
                    return@useCursor DownloadStatus(
                        state = DownloadState.UNKNOWN,
                        bytesDownloaded = 0L,
                        bytesTotal = 0L,
                        reason = "下载任务不存在",
                    )
                }

                val status = cursor.getIntByColumn(DownloadManager.COLUMN_STATUS)
                val reasonCode = cursor.getIntByColumn(DownloadManager.COLUMN_REASON)
                val bytesDownloaded = cursor.getLongByColumn(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val bytesTotal = cursor.getLongByColumn(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                DownloadStatus(
                    state = when (status) {
                        DownloadManager.STATUS_PENDING -> DownloadState.PENDING
                        DownloadManager.STATUS_RUNNING -> DownloadState.RUNNING
                        DownloadManager.STATUS_PAUSED -> DownloadState.PAUSED
                        DownloadManager.STATUS_SUCCESSFUL -> DownloadState.SUCCESSFUL
                        DownloadManager.STATUS_FAILED -> DownloadState.FAILED
                        else -> DownloadState.UNKNOWN
                    },
                    bytesDownloaded = bytesDownloaded,
                    bytesTotal = bytesTotal,
                    reason = if (reasonCode == 0) "" else "reason=$reasonCode",
                )
            }
        }
    }

    private inline fun <T> Cursor.useCursor(block: (Cursor) -> T): T {
        return try {
            block(this)
        } finally {
            close()
        }
    }

    private fun Cursor.getIntByColumn(columnName: String): Int {
        val index = getColumnIndex(columnName)
        if (index < 0) return 0
        return getInt(index)
    }

    private fun Cursor.getLongByColumn(columnName: String): Long {
        val index = getColumnIndex(columnName)
        if (index < 0) return 0L
        return getLong(index)
    }
}
