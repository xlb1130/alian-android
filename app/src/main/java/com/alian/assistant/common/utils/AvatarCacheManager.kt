package com.alian.assistant.common.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * 头像缓存管理器
 * 用于将用户选择的图片URI复制到应用私有存储，确保持久化
 */
class AvatarCacheManager(private val context: Context) {

    companion object {
        private const val TAG = "AvatarCacheManager"
        private const val AVATAR_DIR = "avatars"
        private const val USER_AVATAR_FILE = "user_avatar.jpg"
        private const val ASSISTANT_AVATAR_FILE = "assistant_avatar.jpg"
    }

    private val avatarDir: File by lazy {
        File(context.filesDir, AVATAR_DIR).apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    private val userAvatarFile: File by lazy {
        File(avatarDir, USER_AVATAR_FILE)
    }

    private val assistantAvatarFile: File by lazy {
        File(avatarDir, ASSISTANT_AVATAR_FILE)
    }

    /**
     * 保存用户头像
     * @param uri 图片URI（可以是content://或file://）
     * @return 保存后的文件路径，失败返回null
     */
    suspend fun saveUserAvatar(uri: Uri): String? = withContext(Dispatchers.IO) {
        saveAvatar(uri, userAvatarFile)
    }

    /**
     * 保存艾莲头像
     * @param uri 图片URI（可以是content://或file://）
     * @return 保存后的文件路径，失败返回null
     */
    suspend fun saveAssistantAvatar(uri: Uri): String? = withContext(Dispatchers.IO) {
        saveAvatar(uri, assistantAvatarFile)
    }

    /**
     * 获取用户头像路径
     * @return 头像文件路径，不存在返回null
     */
    fun getUserAvatarPath(): String? {
        return if (userAvatarFile.exists()) {
            userAvatarFile.absolutePath
        } else {
            null
        }
    }

    /**
     * 获取艾莲头像路径
     * @return 头像文件路径，不存在返回null
     */
    fun getAssistantAvatarPath(): String? {
        return if (assistantAvatarFile.exists()) {
            assistantAvatarFile.absolutePath
        } else {
            null
        }
    }

    /**
     * 清除用户头像
     */
    fun clearUserAvatar() {
        if (userAvatarFile.exists()) {
            userAvatarFile.delete()
            Log.d(TAG, "User avatar cleared")
        }
    }

    /**
     * 清除艾莲头像
     */
    fun clearAssistantAvatar() {
        if (assistantAvatarFile.exists()) {
            assistantAvatarFile.delete()
            Log.d(TAG, "Assistant avatar cleared")
        }
    }

    /**
     * 保存头像到指定文件
     * @param uri 图片URI
     * @param targetFile 目标文件
     * @return 保存后的文件路径，失败返回null
     */
    private suspend fun saveAvatar(uri: Uri, targetFile: File): String? = withContext(Dispatchers.IO) {
        try {
            // 打开输入流
            val inputStream: InputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext null

            // 读取图片并压缩（避免图片过大）
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream.close()

            // 重新打开输入流（因为上面已经关闭了）
            val newInputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext null

            // 计算采样率（最大边长限制为1024）
            val maxSize = 1024
            val sampleSize = calculateInSampleSize(options, maxSize, maxSize)

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }

            val bitmap = BitmapFactory.decodeStream(newInputStream, null, decodeOptions)
            newInputStream.close()

            if (bitmap == null) {
                Log.e(TAG, "Failed to decode bitmap from uri: $uri")
                return@withContext null
            }

            // 删除旧文件（如果存在）
            if (targetFile.exists()) {
                targetFile.delete()
            }

            // 保存图片
            FileOutputStream(targetFile).use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            }

            // 回收bitmap
            bitmap.recycle()

            Log.d(TAG, "Avatar saved to: ${targetFile.absolutePath}")
            targetFile.absolutePath

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save avatar", e)
            null
        }
    }

    /**
     * 计算采样率
     */
    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    /**
     * 清除所有头像缓存
     */
    fun clearAllAvatars() {
        clearUserAvatar()
        clearAssistantAvatar()
    }
}