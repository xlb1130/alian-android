package com.alian.assistant.data.model.update

import com.alian.assistant.core.updates.model.ReleaseAsset
import com.alian.assistant.core.updates.model.ReleaseInfo

/**
 * 发布 DTO。
 */
data class ReleaseDto(
    val version: String,
    val tag: String,
    val title: String,
    val body: String,
    val htmlUrl: String,
    val publishedAtMs: Long,
    val preRelease: Boolean,
    val assets: List<ReleaseAssetDto>,
)

/**
 * 发布资产 DTO。
 */
data class ReleaseAssetDto(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)

/**
 * 映射为领域模型。
 */
fun ReleaseDto.toDomain(): ReleaseInfo {
    return ReleaseInfo(
        version = version,
        tag = tag,
        title = title,
        body = body,
        htmlUrl = htmlUrl,
        publishedAtMs = publishedAtMs,
        preRelease = preRelease,
        assets = assets.map {
            ReleaseAsset(
                name = it.name,
                downloadUrl = it.downloadUrl,
                sizeBytes = it.sizeBytes,
            )
        },
    )
}
