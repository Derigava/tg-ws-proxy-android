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
 * Real [DpiEngine]: starts [DpiProxyService] in the `:dpi` process and waits for
 * a positive, session-scoped acknowledgment. The service sends its Binder token
 * first (so death monitoring is established before Ready) and acknowledges
 * STARTED only on the native-owned listener signal — a foreign SOCKS5 responder
 * can never be mistaken for us. A generation counter scopes every acknowledgment
 * and death event to the current session, so a stale exit from a previous
 * session can never change a new one.
 */
class NativeDpiEngine(
    private val context: Context,
    private val owner: IBinder? = null,
) : DpiEngine {
    override var onExit: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var generation = 0

    override fun start(config: DpiConfig): DpiEngineResult {
        if (!config.isLoopback) {
            return DpiEngineResult.Failed("SOCKS5 bind host must be loopback, got ${config.host}")
        }
        val myGeneration = ++generation
        val argv = config.toArgv()
        val session = StartSession(myGeneration)
        val receiver = object : ResultReceiver(mainHandler) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (myGeneration != generation) return // stale session
                when (resultCode) {
                    DpiProxyService.RESULT_SESSION ->
                        session.onSession(resultData?.getBinder(DpiProxyService.EXTRA_BINDER))
                    DpiProxyService.RESULT_STARTED -> session.onReady()
                    DpiProxyService.RESULT_FAILED ->
                        session.onFailure(resultData?.getString(DpiProxyService.EXTRA_ERROR) ?: "engine failed to start")
                }
            }
        }
        ContextCompat.startForegroundService(
            context,
            Intent(context, DpiProxyService::class.java)
                .setAction(DpiProxyService.ACTION_START)
                .putStringArrayListExtra(DpiProxyService.EXTRA_ARGS, ArrayList(argv.toList()))
                .putExtra(DpiProxyService.EXTRA_HOST, config.normalizedHost)
                .putExtra(DpiProxyService.EXTRA_PORT, config.port)
                .putExtra(DpiProxyService.EXTRA_RESULT_RECEIVER, receiver)
                .putExtras(Bundle().apply { putBinder(DpiProxyService.EXTRA_OWNER, owner) })
        )
        val outcome = runBlocking {
            withTimeoutOrNull(DpiProxyService.START_TIMEOUT_MS) { session.acknowledged.await() }
        } ?: DpiEngineResult.Failed("SOCKS5 proxy not ready on ${config.normalizedHost}:${config.port}")
        return outcome
    }

    override fun stop() {
        // Invalidate the current session before ending it, so its death cannot
        // fire onExit and a stale exit cannot leak into the next session.
        generation++
        check(runBlocking { DpiRemoteSession.stopExisting(context, DpiMode.SOCKS5) }) {
            "DPI stop could not be confirmed"
        }
    }

    /**
     * One start attempt. All methods run on the main thread (acknowledgments via
     * [ResultReceiver] and death via a posted [IBinder.DeathRecipient]), so its
     * state needs no extra synchronization.
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
                // The service died between sending its token and this link.
                deathRecorded = true
            }
        }

        fun onReady() {
            ready = true
            if (deathRecorded) {
                acknowledged.complete(DpiEngineResult.Failed("engine exited during startup"))
            } else {
                acknowledged.complete(DpiEngineResult.Running)
            }
        }

        fun onFailure(reason: String) {
            acknowledged.complete(DpiEngineResult.Failed(reason))
        }

        fun onDeath() {
            // Main thread. If Ready was already published, the controller is
            // told to leave Running; otherwise the death is reconciled before
            // onReady can publish Running.
            if (ready) onExit?.invoke() else deathRecorded = true
        }
    }
}
