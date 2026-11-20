package com.amurcanov.tgwsproxy.ui.dpi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.dpi.DpiSettingsStore
import com.amurcanov.tgwsproxy.ui.KeyValueTable
import com.amurcanov.tgwsproxy.ui.MonoAction
import com.amurcanov.tgwsproxy.ui.SectionLabel

/**
 * ByeByeDPI diagnostics report (parity G-09): the effective endpoint, mode and
 * engine arguments, with a copy action. Never includes the Telegram secret.
 */
@Composable
internal fun DpiDiagnostics() {
    val context = LocalContext.current
    val store = remember(context) { DpiSettingsStore(context) }
    val options = remember(store) { runCatching { store.connectionOptions() }.getOrNull() }
    val copied = stringResource(R.string.copied)

    if (options == null) return
    val commandMode = (store.currentSettings()["byedpi_enable_cmd_settings"] as? Boolean) ?: true
    val lines = listOf(
        stringResource(R.string.dpi_diag_endpoint) to "${options.host}:${options.port}",
        stringResource(R.string.dpi_diag_mode) to (if (commandMode) stringResource(R.string.dpi_cmd_use_command_line) else stringResource(R.string.dpi_strategy_editor)),
        stringResource(R.string.dpi_diag_args) to options.args.joinToString(" "),
        stringResource(R.string.dpi_diag_ipv6) to options.routing.ipv6.toString(),
        stringResource(R.string.dpi_diag_apps) to options.routing.appListMode.name,
    )
    val report = lines.joinToString("\n") { "${it.first}: ${it.second}" }

    SectionLabel(stringResource(R.string.dpi_diag_title))
    KeyValueTable(lines)
    MonoAction(
        text = stringResource(R.string.copy),
        onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("ByeByeDPI diagnostics", report))
            Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
        },
    )
}
