package com.amurcanov.tgwsproxy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.amurcanov.tgwsproxy.AppReleaseInfo
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.RemoteVersionSource

@Composable
fun AppUpdateDialog(
    release: AppReleaseInfo,
    onPostpone: () -> Unit,
    onUpdate: () -> Unit,
    busy: Boolean = false
) {
    val isTagOnly = release.source == RemoteVersionSource.Tag
    val title = stringResource(if (isTagOnly) R.string.update_new_tag_title else R.string.update_available_title)
    val description = if (isTagOnly) {
        stringResource(R.string.update_new_tag_description, release.versionTag)
    } else {
        stringResource(R.string.update_available_description, release.versionTag)
    }

    BrandDialog(
        onDismiss = {},
        dismissible = false,
        modifier = Modifier.fillMaxWidth(0.92f)
    ) {
        MonoLabel("> ${title.uppercase()}", color = Brand.accent)
        Spacer(Modifier.height(8.dp))
        Text(release.versionTag, style = MaterialTheme.typography.headlineSmall, color = Brand.ink)
        Spacer(Modifier.height(12.dp))
        Text(description, style = MaterialTheme.typography.bodyMedium, color = Brand.muted)
        Spacer(Modifier.height(20.dp))
        BrandButton(
            text = stringResource(if (busy) R.string.update_downloading else R.string.update_action),
            onClick = onUpdate,
            enabled = !busy,
            trailing = "→"
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MonoAction(text = stringResource(R.string.later), onClick = onPostpone, enabled = !busy)
        }
    }
}
