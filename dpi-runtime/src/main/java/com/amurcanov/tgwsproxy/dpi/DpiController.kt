package com.amurcanov.tgwsproxy.dpi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * State machine over the DPI engine. The UI sends commands and observes [state];
 * the engine itself is the only source of the Running/Failed verdict, so a
 * `Running` state always means a confirmed listener, never a delivered intent.
 *
 * Start and stop are fire-and-forget (safe to call from the UI thread); the
 * engine call runs on [Dispatchers.IO] and is serialized by a mutex so a stop
 * never races an in-flight start. An engine that dies is observed through
 * [DpiEngine.onExit]: a death after Running leaves Running, and a death during
 * Starting is recorded and reconciled so Running is never published.
 */
class DpiController(private val engine: DpiEngine) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _state = MutableStateFlow<DpiState>(DpiState.Off)

    /** Current controller state; collectors see Off/Starting/Running/Stopping/Failed. */
    val state: StateFlow<DpiState> = _state.asStateFlow()

    @Volatile
    private var deathDuringStart = false

    init {
        engine.onExit = { onEngineExit() }
    }

    fun start(config: DpiConfig) {
        val current = _state.value
        if (current is DpiState.Starting || current is DpiState.Running) return
        _state.value = DpiState.Starting
        deathDuringStart = false
        scope.launch {
            mutex.withLock {
                val result = runCatching { engine.start(config) }.getOrElse {
                    DpiEngineResult.Failed(it.message ?: "engine start failed")
                }
                _state.value = when (result) {
                    is DpiEngineResult.Running ->
                        if (deathDuringStart) DpiState.Failed("engine exited during startup")
                        else DpiState.Running(config.mode)
                    is DpiEngineResult.Failed -> DpiState.Failed(result.reason)
                }
            }
        }
    }

    /** Idempotent: a second stop while already Off is a no-op and never re-signals the engine. */
    fun stop() {
        val current = _state.value
        if (current is DpiState.Off || current is DpiState.Stopping) return
        _state.value = DpiState.Stopping
        scope.launch {
            mutex.withLock {
                runCatching { engine.stop() }
                _state.value = DpiState.Off
            }
        }
    }

    private fun onEngineExit() {
        // A stop already in flight (Stopping), an already-stopped engine (Off),
        // or an already-failed start (Failed) must not be overridden; a stale
        // exit is a no-op. A death during Starting is recorded and reconciled in
        // start(); a death after Running leaves Running.
        when (val current = _state.value) {
            is DpiState.Starting -> deathDuringStart = true
            is DpiState.Running -> _state.value = DpiState.Failed("engine process exited")
            else -> Unit
        }
    }
}
