package com.amurcanov.tgwsproxy.ui.dpi

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.dpi.DpiSettingsStore
import com.amurcanov.tgwsproxy.ui.BrandOutlineButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private const val MAX_BACKUP_BYTES = 2 * 1024 * 1024

@Composable
internal fun DpiBackupActions() {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val store = remember(context) { DpiSettingsStore(context) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Int?>(null) }
    var confirmExport by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    fun runOperation(uri: Uri?, success: Int, operation: (Uri) -> Unit) {
        if (uri == null || busy) return
        busy = true
        result = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { operation(uri) }
                result = success
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                result = R.string.dpi_backup_error
            } finally {
                busy = false
            }
        }
    }

    val importDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        runOperation(uri, R.string.dpi_backup_imported) { source ->
            val json = requireNotNull(resolver.openInputStream(source)).use { input ->
                val data = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(data.size() + count <= MAX_BACKUP_BYTES)
                    data.write(buffer, 0, count)
                }
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data.toByteArray())).toString()
            }
            store.importJson(json)
        }
    }
    val exportDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        runOperation(uri, R.string.dpi_backup_exported) { destination ->
            val json = store.exportPortableBackup().toByteArray(Charsets.UTF_8)
            requireNotNull(resolver.openOutputStream(destination, "wt")).use { it.write(json) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BrandOutlineButton(stringResource(R.string.dpi_backup_import), { importDocument.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !busy)
        BrandOutlineButton(stringResource(R.string.dpi_backup_export), { confirmExport = true }, enabled = !busy)
        BrandOutlineButton(stringResource(R.string.dpi_backup_reset), { confirmReset = true }, enabled = !busy)
        result?.let { Text(stringResource(it)) }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.dpi_backup_reset)) },
            text = { Text(stringResource(R.string.settings_v3_reset_confirm)) },
            confirmButton = { TextButton(onClick = {
                confirmReset = false
                result = if (runCatching { store.resetSettings() }.isSuccess) R.string.dpi_backup_reset_done else R.string.dpi_backup_error
            }) { Text(stringResource(R.string.dpi_backup_reset)) } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    if (confirmExport) {
        AlertDialog(
            onDismissRequest = { confirmExport = false },
            title = { Text(stringResource(R.string.dpi_backup_export)) },
            text = { Text(stringResource(R.string.dpi_backup_contents)) },
            confirmButton = { TextButton(onClick = { confirmExport = false; exportDocument.launch("byebyedpi-backup.json") }) { Text(stringResource(R.string.dpi_backup_export)) } },
            dismissButton = { TextButton(onClick = { confirmExport = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}
