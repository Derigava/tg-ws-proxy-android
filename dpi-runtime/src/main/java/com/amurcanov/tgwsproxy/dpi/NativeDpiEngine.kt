package com.amurcanov.tgwsproxy.dpi

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
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
 * a positive, session-scoped acknowledgment. The service only acknowledges
 * STARTED after its own engine thread has bound the listener (so a foreign
 * SOCKS5 responder on the same port can never be mistaken for us). After a
 * successful start the engine binds to the service to observe process death and
 * forwards it through [onExit], so a dead session never leaves Running.
 */
class NativeDpiEngine(private val context: Context) : DpiEngine {
    override var onExit: (() -> Unit)? = null

    private val deathRecipient = IBinder.DeathRecipient { onExit?.invoke() }
    private var connection: ServiceConnection? = null
    private var bound = false

    override fun start(config: DpiConfig): DpiEngineResult {
        if (!config.isLoopback) {
            return DpiEngineResult.Failed("SOCKS5 bind host must be loopback, got ${config.host}")
        }
        val argv = config.toArgv()
        val acknowledged = CompletableDeferred<DpiEngineResult>()
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                when (resultCode) {
                    DpiProxyService.RESULT_STARTED -> acknowledged.complete(DpiEngineResult.Running)
                    DpiProxyService.RESULT_FAILED -> acknowledged.complete(
                        DpiEngineResult.Failed(
                            resultData?.getString(DpiProxyService.EXTRA_ERROR) ?: "engine failed to start"
                        )
                    )
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
        )
        val outcome = runBlocking {
            withTimeoutOrNull(DpiProxyService.START_TIMEOUT_MS) { acknowledged.await() }
        } ?: DpiEngineResult.Failed("SOCKS5 proxy not ready on ${config.normalizedHost}:${config.port}")

        if (outcome is DpiEngineResult.Running) bindForDeathDetection()
        return outcome
    }

    override fun stop() {
        unbind()
        runCatching {
            context.startService(
                Intent(context, DpiProxyService::class.java).setAction(DpiProxyService.ACTION_STOP)
            )
        }
    }

    private fun bindForDeathDetection() {
        if (bound) return
        bound = true
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) {
                    onExit?.invoke()
                    return
                }
                try {
                    service.linkToDeath(deathRecipient, 0)
                } catch (e: Exception) {
                    onExit?.invoke()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) { onExit?.invoke() }

            override fun onBindingDied(name: ComponentName?) { onExit?.invoke() }

            override fun onNullBinding(name: ComponentName?) { onExit?.invoke() }
        }
        connection = conn
        runCatching {
            context.bindService(
                Intent(context, DpiProxyService::class.java).setAction(DpiProxyService.ACTION_BIND),
                conn,
                Context.BIND_AUTO_CREATE,
            )
        }.onFailure {
            bound = false
            connection = null
            onExit?.invoke()
        }
    }

    private fun unbind() {
        if (!bound) return
        bound = false
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }
}
