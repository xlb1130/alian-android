package com.alian.assistant.presentation.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.alian.assistant.BuildConfig
import com.alian.assistant.R
import com.alian.assistant.data.SettingsManager
import com.alian.assistant.data.repository.DefaultUpdateRepository
import com.alian.assistant.infrastructure.updates.GithubReleaseSourceAdapter
import com.alian.assistant.infrastructure.updates.SystemDownloadAdapter
import com.alian.assistant.infrastructure.updates.UpdateHistoryStore
import com.alian.assistant.presentation.viewmodel.UpdateViewModel

/**
 * 更新设置二级页面。
 */
@Composable
fun UpdateSettingsSection() {
    val context = LocalContext.current
    val appContext = context.applicationContext

    val updateViewModel = remember {
        UpdateViewModel(
            currentVersion = BuildConfig.VERSION_NAME,
            updateRepository = DefaultUpdateRepository(
                settingsManager = SettingsManager(appContext),
                releaseSourcePort = GithubReleaseSourceAdapter(
                    owner = "xielingbo",
                    repo = "beanbun",
                ),
                downloadPort = SystemDownloadAdapter(appContext),
                historyStore = UpdateHistoryStore(appContext),
            ),
        )
    }

    val state = updateViewModel.uiState

    LaunchedEffect(Unit) {
        updateViewModel.initialize()
    }

    Column {
        SettingsItem(
            icon = Icons.Default.Info,
            title = stringResource(R.string.update_current_version),
            subtitle = state.currentVersion,
            onClick = { },
        )

        SettingsItem(
            icon = Icons.Default.CloudSync,
            title = stringResource(R.string.settings_item_update),
            subtitle = if (state.checking) stringResource(R.string.update_checking) else state.checkingMessage.ifBlank { stringResource(R.string.update_check_idle) },
            onClick = { updateViewModel.checkForUpdate(force = true) },
        )

        SettingsItem(
            icon = Icons.Default.CloudSync,
            title = stringResource(R.string.update_channel),
            subtitle = state.preferences.channel,
            onClick = { updateViewModel.toggleChannel() },
        )

        val ignoredVersion = state.preferences.ignoredVersion
        SettingsItem(
            icon = Icons.Default.Info,
            title = stringResource(R.string.update_ignored_version),
            subtitle = if (ignoredVersion.isBlank()) stringResource(R.string.update_none) else ignoredVersion,
            onClick = {
                if (ignoredVersion.isBlank()) {
                    updateViewModel.ignoreLatestVersion()
                } else {
                    updateViewModel.clearIgnoredVersion()
                }
            },
        )

        val release = state.latestRelease
        if (release != null) {
            SettingsItem(
                icon = Icons.Default.CloudDownload,
                title = stringResource(R.string.update_download_latest, release.version),
                subtitle = if (state.downloadStateText.isBlank()) stringResource(R.string.update_download_hint) else state.downloadStateText,
                onClick = { updateViewModel.startDownloadLatest() },
            )

            SettingsItem(
                icon = Icons.Default.Info,
                title = stringResource(R.string.update_release_notes),
                subtitle = release.title,
                onClick = {
                    if (release.htmlUrl.isNotBlank()) {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl)),
                        )
                    }
                },
            )
        }

        if (state.historyItems.isNotEmpty()) {
            SettingsItem(
                icon = Icons.Default.History,
                title = stringResource(R.string.update_history_title),
                subtitle = state.historyItems.first().message,
                onClick = { },
            )

            state.historyItems.take(3).forEachIndexed { index, item ->
                SettingsItem(
                    icon = Icons.Default.History,
                    title = "#${index + 1} ${item.type}",
                    subtitle = item.message,
                    onClick = {
                        if (item.url.isNotBlank()) {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(item.url)),
                            )
                        }
                    },
                )
            }
        } else {
            Text(
                text = stringResource(R.string.update_history_empty),
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}
