package com.alian.assistant.common.utils

import java.text.SimpleDateFormat
import java.util.*

/**
 * 格式化时间为友好的显示格式
 * @param timestamp 时间戳（毫秒）
 * @return 格式化后的时间字符串
 */
fun formatMessageTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "刚刚"
        diff < 3600_000 -> "${diff / 60_000}分钟前"
        diff < 86400_000 -> "${diff / 3600_000}小时前"
        diff < 172800_000 -> "昨天"
        diff < 604800_000 -> "${diff / 86400_000}天前"
        else -> {
            val date = Date(timestamp)
            val format = SimpleDateFormat("MM月dd日", Locale.getDefault())
            format.format(date)
        }
    }
}