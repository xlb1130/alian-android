package com.alian.assistant.infrastructure.updates

import com.alian.assistant.core.updates.model.ReleaseChannel
import com.alian.assistant.core.updates.model.ReleaseInfo
import com.alian.assistant.core.updates.port.ReleaseSourcePort
import com.alian.assistant.data.model.update.ReleaseAssetDto
import com.alian.assistant.data.model.update.ReleaseDto
import com.alian.assistant.data.model.update.toDomain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * GitHub Release 发布源适配器。
 *
 * 设计约束：
 * 1. 仅解析发布信息，不做更新决策。
 * 2. 网络请求失败统一通过 `Result.failure` 返回。
 */
class GithubReleaseSourceAdapter(
    private val owner: String,
    private val repo: String,
    private val httpClient: OkHttpClient = OkHttpClient(),
) : ReleaseSourcePort {

    override suspend fun fetchLatest(channel: ReleaseChannel): Result<ReleaseInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val releases = fetchReleases(limit = 10)
            val filtered = releases.filter { release ->
                if (channel == ReleaseChannel.STABLE) !release.preRelease else true
            }
            filtered.firstOrNull()?.toDomain()
        }
    }

    override suspend fun fetchHistory(channel: ReleaseChannel, page: Int, size: Int): Result<List<ReleaseInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val rawPage = page.coerceAtLeast(1)
            val rawSize = size.coerceIn(1, 50)
            val releases = fetchReleases(limit = rawPage * rawSize)
                .filter { release ->
                    if (channel == ReleaseChannel.STABLE) !release.preRelease else true
                }
            val from = (rawPage - 1) * rawSize
            if (from >= releases.size) return@runCatching emptyList()

            val to = minOf(from + rawSize, releases.size)
            releases.subList(from, to).map { it.toDomain() }
        }
    }

    private fun fetchReleases(limit: Int): List<ReleaseDto> {
        val url = "https://api.github.com/repos/$owner/$repo/releases?per_page=${limit.coerceIn(1, 100)}"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            error("GitHub release request failed: ${response.code}")
        }

        val body = response.body?.string().orEmpty()
        response.close()

        val array = JSONArray(body)
        val releases = mutableListOf<ReleaseDto>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            releases.add(item.toReleaseDto())
        }
        return releases
    }

    private fun JSONObject.toReleaseDto(): ReleaseDto {
        val tag = optString("tag_name").orEmpty()
        val normalizedVersion = tag.removePrefix("v").ifBlank { "0.0.0" }

        val assetsJson = optJSONArray("assets") ?: JSONArray()
        val assets = mutableListOf<ReleaseAssetDto>()
        for (index in 0 until assetsJson.length()) {
            val asset = assetsJson.optJSONObject(index) ?: continue
            assets.add(
                ReleaseAssetDto(
                    name = asset.optString("name").orEmpty(),
                    downloadUrl = asset.optString("browser_download_url").orEmpty(),
                    sizeBytes = asset.optLong("size"),
                ),
            )
        }

        return ReleaseDto(
            version = normalizedVersion,
            tag = tag,
            title = optString("name").ifBlank { tag },
            body = optString("body").orEmpty(),
            htmlUrl = optString("html_url").orEmpty(),
            publishedAtMs = parseEpochMillis(optString("published_at")),
            preRelease = optBoolean("prerelease", false),
            assets = assets,
        )
    }

    private fun parseEpochMillis(raw: String): Long {
        if (raw.isBlank()) return 0L
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrDefault(0L)
    }
}
