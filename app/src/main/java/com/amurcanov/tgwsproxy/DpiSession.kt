package com.amurcanov.tgwsproxy

import android.content.Context
import com.amurcanov.tgwsproxy.dpi.AndroidVpnSlotSampler
import com.amurcanov.tgwsproxy.dpi.DpiConfig
import com.amurcanov.tgwsproxy.dpi.DpiController
import com.amurcanov.tgwsproxy.dpi.DpiEngine
import com.amurcanov.tgwsproxy.dpi.DpiMode
import com.amurcanov.tgwsproxy.dpi.DpiState
import com.amurcanov.tgwsproxy.dpi.DpiSettingsStore
import com.amurcanov.tgwsproxy.dpi.DpiVpnEngine
import com.amurcanov.tgwsproxy.dpi.NativeDpiEngine
import com.amurcanov.tgwsproxy.dpi.VpnAuthorizer
import com.amurcanov.tgwsproxy.dpi.VpnReplacement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Process-wide holder for the ByeByeDPI module: one [DpiController] for the
 * currently selected [DpiMode], the controller being the only source of the
 * Running/Failed verdict. The UI sends commands and observes [state]; it never
 * infers "running" from a delivered command.
 *
 * VPN is the default. A mode switch is only accepted while [DpiState.Off], so a
 * Running/Starting/Stopping session is never torn down by a UI navigation
 * change, and a start command is never duplicated during a transition (the
 * controller itself rejects a second start while Starting/Running/Stopping).
 *
 * The VPN consent/replacement flow is deliberately owned by the UI: this holder
 * only exposes [vpnAuthorizer] (the same fail-closed sampler seam the
 * [DpiVpnEngine] uses) and forwards a captured [VpnReplacement] proof to the
 * engine for the single start attempt. A proof is minted by the UI only after a
 * visible offer is decided, and reused only if the fresh re-check still matches.
 */
object DpiSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _mode = MutableStateFlow(DpiMode.VPN)
    val mode: StateFlow<DpiMode> = _mode.asStateFlow()

    private val _state = MutableStateFlow<DpiState>(DpiState.Off)
    val state: StateFlow<DpiState> = _state.asStateFlow()

    @Volatile
    private var controller: DpiController? = null

    @Volatile
    private var engine: DpiEngine? = null

    private var stateJob: Job? = null
    private val journal = MutableStateFlow<List<LogEntry>>(emptyList())
    internal val logs = journal.asStateFlow()
    private var nextLogId = 0L

    internal fun clearLogs() { journal.value = emptyList() }

    private fun journalState(context: Context, state: DpiState) {
        val label = when (state) {
            DpiState.Off -> R.string.status_disconnected
            DpiState.Starting -> R.string.status_connecting
            DpiState.Stopping -> R.string.dpi_status_stopping
            is DpiState.Running -> if (state.mode == DpiMode.VPN) R.string.dpi_status_vpn_running else R.string.dpi_status_socks5_running
            is DpiState.Failed -> R.string.dpi_log_failed
        }
        val mode = context.getString(if (_mode.value == DpiMode.VPN) R.string.dpi_mode_vpn else R.string.dpi_mode_socks5)
        val error = state is DpiState.Failed
        val entry = LogEntry("dpi-${nextLogId++}", "ByeByeDPI $mode: ${context.getString(label)}", 1, error, if (error) 6 else 4)
        journal.update { (it + entry).takeLast(50) }
    }

    /** Endpoint for the current attempt, not the next edited/imported document. */
    private val _endpoint = MutableStateFlow<String?>(null)
    val endpoint = _endpoint.asStateFlow()

    /**
     * Fail-closed VPN authorization seam for the UI. It re-samples the slot on
     * every call, so a consent/replacement decision is never a cached grant.
     */
    fun vpnAuthorizer(context: Context): VpnAuthorizer =
        VpnAuthorizer(AndroidVpnSlotSampler(context.applicationContext))

    /** Switches mode only while Off; rejects (and reports) otherwise. */
    fun switchMode(context: Context, newMode: DpiMode): Boolean {
        val current = controller?.state?.value ?: DpiState.Off
        if (current !is DpiState.Off && current !is DpiState.Failed) return false
        if (newMode == _mode.value) return true
        _mode.value = newMode
        rebuild(context.applicationContext)
        return true
    }

    /**
     * Starts the current controller. For VPN the [replacement] proof (if any)
     * is attached to the engine for this single attempt and consumed by the
     * engine's own authorize-then-establish re-check.
     */
    fun start(context: Context, replacement: VpnReplacement? = null) {
        ensure(context.applicationContext)
        val activeController = controller ?: return
        val current = activeController.state.value
        if (current !is DpiState.Off && current !is DpiState.Failed) return
        val options = try {
            DpiSettingsStore(context).connectionOptions()
        } catch (_: Exception) {
            journal.update {
                (it + LogEntry(
                    "dpi-${nextLogId++}", context.getString(R.string.dpi_backup_error), 1,
                    isError = true, priority = 6,
                )).takeLast(50)
            }
            return
        }
        val hostLabel = if (':' in options.host) "[${options.host}]" else options.host
        _endpoint.value = "$hostLabel:${options.port}"
        (engine as? DpiVpnEngine)?.replacement = replacement
        activeController.start(DpiConfig(_mode.value, options.host, options.port, vpnRouting = options.routing))
    }

    fun stop() {
        controller?.stop()
    }

    private fun ensure(context: Context) {
        if (controller == null) rebuild(context)
    }

    private fun rebuild(context: Context) {
        stateJob?.cancel()
        val newEngine: DpiEngine = when (_mode.value) {
            DpiMode.VPN -> DpiVpnEngine(context, VpnAuthorizer(AndroidVpnSlotSampler(context)))
            DpiMode.SOCKS5 -> NativeDpiEngine(context)
        }
        val newController = DpiController(newEngine)
        engine = newEngine
        controller = newController
        _state.value = DpiState.Off
        stateJob = scope.launch {
            newController.state.collect { state ->
                if (state != _state.value) journalState(context, state)
                _state.value = state
            }
        }
    }
}
