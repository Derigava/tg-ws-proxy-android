package com.amurcanov.tgwsproxy.dpi

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Real [DpiEngine]: starts the [DpiProxyService] in the `:dpi` process and
 * confirms readiness with a SOCKS5 handshake against the loopback endpoint.
 * A returned [DpiEngineResult.Running] therefore means the engine bound its
 * port and answers the handshake — not merely that the start intent was sent.
 */
class NativeDpiEngine(private val context: Context) : DpiEngine {
    companion object {
        private const val START_TIMEOUT_MS = 10_000L
        private const val PROBE_TIMEOUT_MS = 250
        private const val POLL_MS = 50L
    }

    override fun start(config: DpiConfig): DpiEngineResult {
        val argv = config.toArgv()
        ContextCompat.startForegroundService(
            context,
            Intent(context, DpiProxyService::class.java)
                .setAction(DpiProxyService.ACTION_START)
                .putStringArrayListExtra(DpiProxyService.EXTRA_ARGS, ArrayList(argv.toList()))
        )
        val address = probeAddress(config.host)
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (socks5Ready(address, config.port)) return DpiEngineResult.Running
            Thread.sleep(POLL_MS)
        }
        return DpiEngineResult.Failed("SOCKS5 proxy not ready on ${config.host}:${config.port}")
    }

    override fun stop() {
        context.startService(
            Intent(context, DpiProxyService::class.java).setAction(DpiProxyService.ACTION_STOP)
        )
    }

    private fun probeAddress(host: String): String = when (host) {
        "0.0.0.0" -> "127.0.0.1"
        "::", "[::]" -> "::1"
        else -> host.removeSurrounding("[", "]")
    }

    private fun socks5Ready(host: String, port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
            socket.soTimeout = PROBE_TIMEOUT_MS
            socket.getOutputStream().write(byteArrayOf(5, 1, 0))
            val input = socket.getInputStream()
            input.read() == 5 && input.read() == 0
        }
    } catch (e: IOException) {
        false
    }
}
