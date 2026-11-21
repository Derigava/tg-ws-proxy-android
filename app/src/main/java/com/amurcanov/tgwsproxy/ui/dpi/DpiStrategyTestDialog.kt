package com.amurcanov.tgwsproxy.ui.dpi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amurcanov.tgwsproxy.DpiSession
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.dpi.DpiProbeTarget
import com.amurcanov.tgwsproxy.dpi.DpiSettingsStore
import com.amurcanov.tgwsproxy.dpi.DpiState
import com.amurcanov.tgwsproxy.dpi.DpiStrategyTestService
import com.amurcanov.tgwsproxy.dpi.rankDpiStrategies
import com.amurcanov.tgwsproxy.ui.Brand
import com.amurcanov.tgwsproxy.ui.BrandButton
import com.amurcanov.tgwsproxy.ui.BrandDialog
import com.amurcanov.tgwsproxy.ui.BrandLink
import com.amurcanov.tgwsproxy.ui.BrandOutlineButton
import com.amurcanov.tgwsproxy.ui.MonoLabel

@Composable
internal fun DpiStrategyTestDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { DpiSettingsStore(context) }
    val test by DpiStrategyTestService.state.collectAsStateWithLifecycle()
    val dpiState by DpiSession.state.collectAsStateWithLifecycle()
    val available = dpiState == DpiState.Off
    var applyFailed by remember { mutableStateOf(false) }
    val saved = remember(test.results, store) { store.successfulStrategies() }

    fun apply(command: String) {
        try {
            store.applyStrategy(command)
            onDismiss()
        } catch (_: Exception) {
            applyFailed = true
        }
    }

    BrandDialog(
        onDismiss = onDismiss,
        modifier = Modifier.fillMaxWidth(0.92f),
    ) {
        Text(stringResource(R.string.dpi_strategy_test), style = MaterialTheme.typography.titleLarge, color = Brand.ink)
        Text(stringResource(R.string.dpi_strategy_test_description), color = Brand.muted)
        if (!available && test.message != R.string.dpi_strategy_test_busy) {
            MonoLabel(stringResource(R.string.dpi_strategy_test_busy))
        }
        if (test.total > 0) {
            MonoLabel(stringResource(R.string.dpi_strategy_test_progress, test.results.size, test.total))
        }
        val failedStarts = test.results.count { it.startFailed }
        if (failedStarts > 0) MonoLabel(stringResource(R.string.dpi_strategy_test_start_failures, failedStarts))
        if (test.message != 0) MonoLabel(stringResource(test.message))
        if (applyFailed) MonoLabel(stringResource(R.string.dpi_strategy_test_failed))

        if (test.running) {
            BrandOutlineButton(
                text = stringResource(R.string.dpi_strategy_test_stop),
                onClick = { DpiStrategyTestService.stop(context) },
            )
        } else {
            BrandButton(text = stringResource(R.string.dpi_strategy_test_start), enabled = available, onClick = {
                applyFailed = false
                DpiStrategyTestService.start(context)
            })
        }

        val ranked = if (test.running) emptyList() else rankDpiStrategies(test.results)
            .filter { it.candidate.command !in saved }.take(5)
        if (saved.isNotEmpty() || ranked.isNotEmpty()) {
            Column(
                modifier = Modifier.heightIn(max = 250.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (saved.isNotEmpty()) {
                    MonoLabel(stringResource(R.string.dpi_strategy_test_saved))
                    saved.forEach { command ->
                        Text(command, style = MaterialTheme.typography.bodySmall, color = Brand.ink)
                        if (!test.running) {
                            BrandLink(text = stringResource(R.string.dpi_strategy_apply), onClick = { apply(command) })
                        }
                    }
                }
                ranked.forEach { result ->
                    MonoLabel(stringResource(R.string.dpi_strategy_test_score, result.reachable, DpiProbeTarget.entries.size))
                    val missing = DpiProbeTarget.entries.filter { it !in result.reached }
                    if (missing.isNotEmpty()) {
                        val hosts = missing.joinToString(", ") { it.host }
                        MonoLabel(stringResource(R.string.dpi_strategy_test_unreachable, hosts))
                    }
                    val command = result.candidate.command
                    Text(command, style = MaterialTheme.typography.bodySmall, color = Brand.ink)
                    BrandLink(text = stringResource(R.string.dpi_strategy_apply), onClick = { apply(command) })
                }
            }
        }
        BrandLink(text = stringResource(R.string.dpi_strategy_test_close), onClick = onDismiss)
    }
}
