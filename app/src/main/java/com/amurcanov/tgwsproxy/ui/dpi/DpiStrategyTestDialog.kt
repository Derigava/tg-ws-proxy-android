package com.amurcanov.tgwsproxy.ui.dpi

import android.os.Binder
import androidx.lifecycle.compose.LifecycleStartEffect
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.amurcanov.tgwsproxy.dpi.DpiStrategyResult
import com.amurcanov.tgwsproxy.dpi.DpiStrategyTester
import com.amurcanov.tgwsproxy.dpi.NativeDpiEngine
import com.amurcanov.tgwsproxy.dpi.rankDpiStrategies
import com.amurcanov.tgwsproxy.ui.Brand
import com.amurcanov.tgwsproxy.ui.BrandButton
import com.amurcanov.tgwsproxy.ui.BrandDialog
import com.amurcanov.tgwsproxy.ui.BrandLink
import com.amurcanov.tgwsproxy.ui.BrandOutlineButton
import com.amurcanov.tgwsproxy.ui.MonoLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket

@Composable
internal fun DpiStrategyTestDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val store = remember(app) { DpiSettingsStore(app) }
    val scope = rememberCoroutineScope()
    var task by remember { mutableStateOf<Job?>(null) }
    var running by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<DpiStrategyResult>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var message by remember { mutableIntStateOf(0) }
    val dpiState by DpiSession.state.collectAsStateWithLifecycle()
    val available = dpiState == DpiState.Off
    LifecycleStartEffect(Unit) {
        onStopOrDispose { task?.cancel() }
    }

    fun runTest() {
        running = true
        results = emptyList()
        total = 0
        message = 0
        task = scope.launch {
            try {
                val started = DpiSession.runStrategyTest(app) {
                    val (candidates, port) = withContext(Dispatchers.IO) {
                        val candidates = app.assets.open("proxytest_strategies.list").bufferedReader().use {
                            store.strategyCandidates(it.lineSequence())
                        }
                        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
                        candidates to port
                    }
                    total = candidates.size
                    val tester = DpiStrategyTester(NativeDpiEngine(app, owner = Binder()))
                    tester.test(candidates, port) { result ->
                        withContext(Dispatchers.Main) { results = results + result }
                    }
                }
                if (!started) message = R.string.dpi_strategy_test_busy
                else if (rankDpiStrategies(results).isEmpty()) message = R.string.dpi_strategy_test_empty
            } catch (e: CancellationException) {
                message = R.string.dpi_strategy_test_cancelled
                throw e
            } catch (_: Exception) {
                message = R.string.dpi_strategy_test_failed
            } finally {
                running = false
            }
        }
    }

    BrandDialog(
        onDismiss = onDismiss,
        dismissible = !running,
        modifier = Modifier.fillMaxWidth(0.92f),
    ) {
        Text(stringResource(R.string.dpi_strategy_test), style = MaterialTheme.typography.titleLarge, color = Brand.ink)
        Text(stringResource(R.string.dpi_strategy_test_description), color = Brand.muted)
        if (!available && message != R.string.dpi_strategy_test_busy) {
            MonoLabel(stringResource(R.string.dpi_strategy_test_busy))
        }
        if (total > 0) {
            MonoLabel(stringResource(R.string.dpi_strategy_test_progress, results.size, total))
        }
        val failedStarts = results.count { it.startFailed }
        if (failedStarts > 0) MonoLabel(stringResource(R.string.dpi_strategy_test_start_failures, failedStarts))
        if (message != 0) MonoLabel(stringResource(message))

        if (running) {
            BrandOutlineButton(text = stringResource(R.string.dpi_strategy_test_stop), onClick = {
                task?.cancel()
            })
        } else {
            BrandButton(text = stringResource(R.string.dpi_strategy_test_start), enabled = available, onClick = ::runTest)
            val ranked = rankDpiStrategies(results).take(5)
            if (ranked.isNotEmpty()) {
                Column(
                    modifier = Modifier.heightIn(max = 250.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ranked.forEach { result ->
                        MonoLabel(stringResource(R.string.dpi_strategy_test_score, result.reachable, DpiProbeTarget.entries.size))
                        val missing = DpiProbeTarget.entries.filter { it !in result.reached }
                        if (missing.isNotEmpty()) {
                            val hosts = missing.joinToString(", ") { it.host }
                            MonoLabel(stringResource(R.string.dpi_strategy_test_unreachable, hosts))
                        }
                        Text(result.candidate.command, style = MaterialTheme.typography.bodySmall, color = Brand.ink)
                        BrandLink(text = stringResource(R.string.dpi_strategy_apply), onClick = {
                            try {
                                store.applyStrategy(result.candidate.command)
                                onDismiss()
                            } catch (_: Exception) {
                                message = R.string.dpi_strategy_test_failed
                            }
                        })
                    }
                }
            }
            BrandLink(text = stringResource(R.string.vpn_offer_cancel), onClick = onDismiss)
        }
    }
}
