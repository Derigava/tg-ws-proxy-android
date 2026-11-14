package com.amurcanov.tgwsproxy.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.amurcanov.tgwsproxy.AppReleaseInfo
import com.amurcanov.tgwsproxy.ApkInstaller
import com.amurcanov.tgwsproxy.BuildConfig
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.UPDATE_DIALOG_ACTION_INSTALLER_OPENED
import com.amurcanov.tgwsproxy.UPDATE_DIALOG_ACTION_RELEASE_OPENED
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun AppUpdateDialog(
    release: AppReleaseInfo,
    onPostpone: () -> Unit,
    onActionCompleted: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val canDownload = BuildConfig.APP_UPDATES_ENABLED && release.apk != null
    var busy by remember(release) { mutableStateOf(false) }
    var pendingApk by remember(release) { mutableStateOf<File?>(null) }

    fun openRelease() {
        if (openUrlInBrowser(context, release.releaseUrl)) {
            onActionCompleted(UPDATE_DIALOG_ACTION_RELEASE_OPENED)
        } else {
            Toast.makeText(context, R.string.update_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    fun openInstaller(apk: File) {
        try {
            context.startActivity(ApkInstaller.installerIntent(context, apk))
            onActionCompleted(UPDATE_DIALOG_ACTION_INSTALLER_OPENED)
        } catch (_: Exception) {
            Toast.makeText(context, R.string.update_install_failed, Toast.LENGTH_LONG).show()
        }
    }

    val installPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val apk = pendingApk
        pendingApk = null
        if (apk != null && ApkInstaller.hasInstallPermission(context)) openInstaller(apk)
    }

    val title = stringResource(R.string.update_available_title)
    BrandDialog(
        onDismiss = {},
        dismissible = false,
        modifier = Modifier.fillMaxWidth(0.92f)
    ) {
        MonoLabel("> ${title.uppercase()}", color = Brand.accent)
        Spacer(Modifier.height(8.dp))
        Text(release.versionTag, style = MaterialTheme.typography.headlineSmall, color = Brand.ink)
        Spacer(Modifier.height(12.dp))
        val description = if (canDownload) R.string.update_install_description else R.string.update_available_description
        Text(stringResource(description, release.versionTag),
            style = MaterialTheme.typography.bodyMedium, color = Brand.muted)
        Spacer(Modifier.height(20.dp))
        BrandButton(
            text = stringResource(when {
                busy -> R.string.update_downloading
                canDownload -> R.string.update_install
                else -> R.string.update_action
            }),
            enabled = !busy,
            onClick = {
                if (!canDownload) {
                    openRelease()
                } else {
                    busy = true
                    scope.launch {
                        try {
                            val apk = ApkInstaller.prepare(context, release)
                            if (ApkInstaller.hasInstallPermission(context)) {
                                openInstaller(apk)
                            } else {
                                pendingApk = apk
                                Toast.makeText(context, R.string.update_allow_install, Toast.LENGTH_LONG).show()
                                installPermission.launch(ApkInstaller.installPermissionIntent(context))
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            pendingApk = null
                            val detail = if (error is IllegalArgumentException || error is IllegalStateException) error.message else null
                            Toast.makeText(context, detail ?: context.getString(R.string.update_install_failed), Toast.LENGTH_LONG).show()
                        } finally {
                            busy = false
                        }
                    }
                }
            },
            trailing = "→"
        )
        if (canDownload) {
            Spacer(Modifier.height(8.dp))
            BrandOutlineButton(text = stringResource(R.string.update_action), onClick = ::openRelease, enabled = !busy)
        }
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
