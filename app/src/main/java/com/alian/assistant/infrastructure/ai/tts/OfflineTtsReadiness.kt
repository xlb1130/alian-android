package com.alian.assistant.infrastructure.ai.tts

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts

/**
 * 离线 TTS 可用性检查
 */
object OfflineTtsReadiness {

    data class Status(
        val ready: Boolean,
        val message: String
    )

    private const val DEFAULT_ASSET_DIR = "sherpa-onnx-tts"

    fun check(context: Context, preferredModelDir: String = DEFAULT_ASSET_DIR): Status {
        if (!OfflineTts.ensureNativeLoaded()) {
            val nativeError = OfflineTts.nativeLoadErrorMessage()
            return Status(
                ready = false,
                message = if (!nativeError.isNullOrBlank()) {
                    "未检测到 sherpa-onnx native 库：$nativeError"
                } else {
                    "未检测到 sherpa-onnx native 库（缺少 libsherpa-onnx-jni.so）"
                }
            )
        }

        val modelDirResult = resolveModelDir(context, preferredModelDir)
        if (modelDirResult.isFailure) {
            return Status(
                ready = false,
                message = modelDirResult.exceptionOrNull()?.message ?: "未检测到离线TTS模型目录"
            )
        }

        val modelDir = modelDirResult.getOrThrow()
        val files = runCatching {
            context.assets.list(modelDir)?.toSet() ?: emptySet()
        }.getOrDefault(emptySet())

        if ("tokens.txt" !in files) {
            return Status(
                ready = false,
                message = "模型目录 `$modelDir` 缺少 tokens.txt"
            )
        }

        val hasOnnx = files.any { it.endsWith(".onnx", ignoreCase = true) }
        if (!hasOnnx) {
            return Status(
                ready = false,
                message = "模型目录 `$modelDir` 缺少 .onnx 文件"
            )
        }

        return Status(
            ready = true,
            message = "离线 TTS 资源可用（$modelDir）"
        )
    }

    private fun resolveModelDir(context: Context, preferredModelDir: String): Result<String> {
        return runCatching {
            val roots = context.assets.list("")?.toList() ?: emptyList()
            when {
                preferredModelDir.isNotBlank() && roots.contains(preferredModelDir) -> preferredModelDir
                else -> roots.firstOrNull {
                    it.startsWith("sherpa-onnx-tts") || it.startsWith("sherpa_onnx_tts")
                }
            } ?: throw IllegalStateException("未检测到离线TTS模型目录（期望 `$preferredModelDir`）")
        }
    }
}
