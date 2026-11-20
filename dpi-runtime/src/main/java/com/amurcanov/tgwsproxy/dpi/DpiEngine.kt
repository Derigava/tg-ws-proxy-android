package com.amurcanov.tgwsproxy.dpi

/** Outcome of a single engine command. */
sealed interface DpiEngineResult {
    data object Running : DpiEngineResult
    data class Failed(val reason: String) : DpiEngineResult
}

/**
 * Abstraction over the pinned native ByeByeDPI engine. The controller only
 * depends on this interface, so its state machine is testable with an in-JVM
 * engine while the real [NativeDpiEngine] drives the native service.
 *
 * [start] must not return until the engine is actually ready (a successful
 * SOCKS5 handshake) or has failed — a returned [DpiEngineResult.Running] is
 * proof of a working listener, not proof that a start intent was delivered.
 */
interface DpiEngine {
    fun start(config: DpiConfig): DpiEngineResult

    fun stop()
}
