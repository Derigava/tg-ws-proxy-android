package com.amurcanov.tgwsproxy.dpi

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [DpiEngine] for VPN mode. It drives [DpiVpnService] in the `:vpn` process and
 * — crucially — never trusts an intent to have authorized the start: it runs
 * [VpnAuthorizer] itself (decision + immediate recheck) and only starts the
 * service when the result is [VpnStartDecision.Start]. Readiness is confirmed by
 * a session-scoped acknowledgment from the service (the same protocol the SOCKS5
 * [NativeDpiEngine] uses), so a `Running(VPN)` controller state means a real,
 * confirmed VPN, not a delivered intent.
 *
 * [replaceAuthorized] is the per-attempt explicit replacement authorization the
 * caller sets after the user confirms replacing a third-party VPN; it is kept
 * separate from the OS prepare grant (which the authorizer samples fresh).
 */
class DpiVpnEngine(
    private val context: Context,
    private val authorizer: VpnAuthorizer,
    private val routing: DpiVpnRouting = DpiVpnRouting(),
) : DpiEngine {
    override var onExit: (() -> Unit)? = null

    /**
     * Per-attempt explicit authorization to replace a third-party VPN. Set by
     * the caller right before a start, after the user confirms; it is consumed
     * (reset to false) by the next [start], so a stale confirmation cannot
     * authorize a later, different attempt.
     */
    var replaceAuthorized: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var generation = 0

    override fun start(config: DpiConfig): DpiEngineResult {
        // Authorization + immediate recheck before touching the VPN slot. The
        // per-attempt replacement confirmation is consumed here, so a stale
        // confirmation can never authorize a later, different attempt.
        val decision = authorizer.authorize(replaceAuthorized)
        replaceAuthorized = false
        if (decision !is VpnStartDecision.Start) {
            return DpiEngineResult.Failed(failureMessage(decision))
        }

        val myGeneration = ++generation
        val argv = config.toArgv()
        val session = StartSession(myGeneration)
        val receiver = object : ResultReceiver(mainHandler) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (myGeneration != generation) return // stale session
                when (resultCode) {
                    DpiVpnService.RESULT_SESSION ->
                        session.onSession(resultData?.getBinder(DpiVpnService.EXTRA_BINDER))
                    DpiVpnService.RESULT_STARTED -> session.onReady()
                    DpiVpnService.RESULT_FAILED ->
                        session.onFailure(resultData?.getString(DpiVpnService.EXTRA_ERROR) ?: "VPN failed to start")
                }
            }
        }
        ContextCompat.startForegroundService(
            context,
            Intent(context, DpiVpnService::class.java)
                .setAction(DpiVpnService.ACTION_START)
                .putStringArrayListExtra(DpiVpnService.EXTRA_ARGS, ArrayList(argv.toList()))
                .putExtra(DpiVpnService.EXTRA_HOST, config.normalizedHost)
                .putExtra(DpiVpnService.EXTRA_PORT, config.port)
                .putExtra(DpiVpnService.EXTRA_REPLACE_AUTHORIZED, replaceAuthorized)
                .putExtra(DpiVpnService.EXTRA_DNS, routing.dns)
                .putExtra(DpiVpnService.EXTRA_IPV6, routing.ipv6)
                .putExtra(DpiVpnService.EXTRA_APP_LIST_MODE, routing.appListMode.name)
                .putStringArrayListExtra(DpiVpnService.EXTRA_APPS, ArrayList(routing.apps))
                .putExtra(DpiVpnService.EXTRA_RESULT_RECEIVER, receiver)
        )
        return runBlocking {
            withTimeoutOrNull(DpiVpnService.START_TIMEOUT_MS) { session.acknowledged.await() }
        } ?: DpiEngineResult.Failed("VPN not ready on ${config.normalizedHost}:${config.port}")
    }

    override fun stop() {
        // Invalidate the current session before ending it, so its death cannot
        // fire onExit and a stale exit cannot leak into the next session.
        generation++
        runCatching {
            context.startService(
                Intent(context, DpiVpnService::class.java).setAction(DpiVpnService.ACTION_STOP)
            )
        }
    }

    private fun failureMessage(decision: VpnStartDecision): String = when (decision) {
        is VpnStartDecision.Offer ->
            if (decision.ownerUncertain) {
                "VPN start needs user confirmation: an active VPN's owner could not be verified"
            } else {
                "VPN start needs user choice: another VPN is active"
            }
        is VpnStartDecision.Refuse -> when (decision.reason) {
            VpnRefuseReason.CONSENT_NOT_GRANTED -> "VPN permission not granted; obtain consent first"
            VpnRefuseReason.CONSENT_LOST -> "VPN consent was lost"
            VpnRefuseReason.LOCKDOWN -> "always-on VPN or lockdown is active"
            VpnRefuseReason.SLOT_CHANGED -> "VPN slot changed during startup"
        }
        VpnStartDecision.Start -> "unreachable"
    }

    /**
     * One start attempt. All methods run on the main thread (acknowledgments via
     * [ResultReceiver] and death via a posted [IBinder.DeathRecipient]).
     */
    private inner class StartSession(private val myGeneration: Int) {
        val acknowledged = CompletableDeferred<DpiEngineResult>()
        private var deathRecorded = false
        private var ready = false

        private val deathRecipient = IBinder.DeathRecipient {
            mainHandler.post {
                if (myGeneration == generation) onDeath()
            }
        }

        fun onSession(binder: IBinder?) {
            if (binder == null) {
                deathRecorded = true
                return
            }
            try {
                binder.linkToDeath(deathRecipient, 0)
            } catch (e: Exception) {
                deathRecorded = true
            }
        }

        fun onReady() {
            ready = true
            if (deathRecorded) {
                acknowledged.complete(DpiEngineResult.Failed("VPN exited during startup"))
            } else {
                acknowledged.complete(DpiEngineResult.Running)
            }
        }

        fun onFailure(reason: String) {
            acknowledged.complete(DpiEngineResult.Failed(reason))
        }

        fun onDeath() {
            if (ready) onExit?.invoke() else deathRecorded = true
        }
    }
}
