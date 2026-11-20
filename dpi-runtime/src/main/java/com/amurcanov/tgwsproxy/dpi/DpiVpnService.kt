package com.amurcanov.tgwsproxy.dpi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.ResultReceiver
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.romanvht.byedpi.core.ByeDpiProxy
import io.github.romanvht.byedpi.core.TProxyService
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * System VPN for ByeByeDPI: establishes the TUN slot and drives the pinned
 * SOCKS5 engine + HEV tunnel in a dedicated `:vpn` process.
 *
 * Routing is conservative by construction:
 *  * the host package (Telegram engine + DPI engine share one applicationId)
 *    is ALWAYS excluded from the tunnel, so neither engine's outgoing
 *    connections loop back through the VPN and the engines never capture
 *    themselves;
 *  * all-apps is the default; [DpiVpnRouting] adds blacklist/whitelist modes;
 *  * [onRevoke] stops without restarting, so a system revocation or lost
 *    consent can never enter a restart loop.
 *
 * Commands are private (service not exported) and use a namespace distinct
 * from the Telegram proxy and the SOCKS5 [DpiProxyService]. The service never
 * trusts a start intent: it re-samples the slot and re-runs [VpnStartPolicy.decide]
 * immediately before [establish], so a raw (in-app) start intent cannot bypass
 * an authorization that is no longer valid. A stop/revoke kills the `:vpn`
 * process only, so the pinned engine's sticky native globals are reset without
 * ever touching the Telegram process.
 */
class DpiVpnService : VpnService() {
    companion object {
        const val ACTION_START = "io.github.derigava.tgwsproxy.dpi.vpn.START"
        const val ACTION_STOP = "io.github.derigava.tgwsproxy.dpi.vpn.STOP"
        const val EXTRA_ARGS = "EXTRA_ARGS"
        const val EXTRA_HOST = "EXTRA_HOST"
        const val EXTRA_PORT = "EXTRA_PORT"
        const val EXTRA_DNS = "EXTRA_DNS"
        const val EXTRA_IPV6 = "EXTRA_IPV6"
        const val EXTRA_APP_LIST_MODE = "EXTRA_APP_LIST_MODE"
        const val EXTRA_APPS = "EXTRA_APPS"
        const val EXTRA_REPLACEMENT_SLOT = "EXTRA_REPLACEMENT_SLOT"
        const val EXTRA_RESULT_RECEIVER = "EXTRA_RESULT_RECEIVER"
        const val EXTRA_BINDER = "EXTRA_BINDER"
        const val EXTRA_ERROR = "EXTRA_ERROR"
        const val RESULT_SESSION = 3
        const val RESULT_STARTED = 1
        const val RESULT_FAILED = 2
        const val START_TIMEOUT_MS = 10_000L

        private const val PROBE_TIMEOUT_MS = 250
        private const val POLL_MS = 50L
        private const val TUNNEL_READY_GRACE_MS = 200L
        private const val CHANNEL = "ByeDPI_Vpn_Service"
        private const val NOTIFICATION_ID = 103
        private const val TAG = "DpiVpnService"
    }

    private val proxy by lazy { ByeDpiProxy() }
    private val sessionBinder = Binder()
    private var engineThread: Thread? = null
    private var tunnelThread: Thread? = null
    private var tunFd: ParcelFileDescriptor? = null

    @Volatile
    private var engineError: String? = null

    @Volatile
    private var ready = false

    @Volatile
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    getString(R.string.dpi_vpn_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> stop()
            else -> stopSelfResult(startId)
        }
        // No automatic restart: a VPN that the user (or system) stopped must
        // stay stopped until an explicit, policy-gated start.
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun start(intent: Intent) {
        if (engineThread != null || tunFd != null) return
        val args = intent.getStringArrayListExtra(EXTRA_ARGS)?.toTypedArray()
        val host = intent.getStringExtra(EXTRA_HOST) ?: "127.0.0.1"
        val port = intent.getIntExtra(EXTRA_PORT, 1080)
        val routing = DpiVpnRouting(
            dns = intent.getStringExtra(EXTRA_DNS).orEmpty(),
            ipv6 = intent.getBooleanExtra(EXTRA_IPV6, true),
            appListMode = runCatching {
                VpnAppListMode.valueOf(intent.getStringExtra(EXTRA_APP_LIST_MODE) ?: "")
            }.getOrDefault(VpnAppListMode.ALL_APPS),
            apps = intent.getStringArrayListExtra(EXTRA_APPS)?.toList().orEmpty(),
        )
        val receiver = intent.getParcelableExtra<ResultReceiver>(EXTRA_RESULT_RECEIVER)
        val replacement = intent.getStringExtra(EXTRA_REPLACEMENT_SLOT)?.let { slotName ->
            runCatching { VpnReplacement(confirmedSlot = VpnSlotState.valueOf(slotName)) }.getOrNull()
        }
        if (args.isNullOrEmpty() || host.isBlank() || port !in 1..65535) {
            report(receiver, "invalid VPN engine configuration")
            terminate()
            return
        }

        stopping = false
        ready = false
        engineError = null
        // Publish the session identity immediately so the caller can establish
        // death monitoring before the engine is ready.
        receiver?.send(RESULT_SESSION, Bundle().apply { putBinder(EXTRA_BINDER, sessionBinder) })
        startForeground(notification(getString(R.string.dpi_vpn_notification_running)))

        engineThread = thread(name = "ByeDPI") {
            try {
                val code = proxy.startProxy(args)
                if (code != 0) engineError = "engine exited with code $code"
            } catch (e: Throwable) {
                Log.e(TAG, "ByeDPI engine failed", e)
                engineError = "engine exception: ${e.message ?: e.javaClass.simpleName}"
            }
            if (!stopping && ready) terminate()
        }

        thread(name = "ByeDPIVpnMonitor") {
            val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val error = engineError
                if (error != null) {
                    report(receiver, error)
                    terminate()
                    return@thread
                }
                if (DpiNative.isListenerReady() != 0 && socks5Ready(host, port)) {
                    ready = true
                    startTunnel(receiver, routing, host, port, replacement)
                    return@thread
                }
                Thread.sleep(POLL_MS)
            }
            report(receiver, "VPN engine not ready on $host:$port")
            runCatching { proxy.stopProxy() }
            terminate()
        }
    }

    /** Re-checks authorization immediately before establishing, then starts the HEV tunnel. */
    private fun startTunnel(
        receiver: ResultReceiver?,
        routing: DpiVpnRouting,
        host: String,
        port: Int,
        replacement: VpnReplacement?,
    ) {
        try {
            // Immediately before establish: independently re-sample the slot and
            // re-decide with the attempt's replacement proof, so a raw (in-app)
            // start intent cannot bypass an authorization that is no longer
            // valid, and the proof is validated against the freshly observed
            // slot (attempt identity is preserved here).
            val fresh = VpnStartPolicy.decide(AndroidVpnSlotSampler(this).sample(), replacement)
            if (fresh !is VpnStartDecision.Start) {
                report(receiver, "VPN authorization no longer valid: $fresh")
                runCatching { proxy.stopProxy() }
                terminate()
                return
            }

            val fd = establish(routing)
            tunFd = fd
            val tunnelConfig = buildString {
                appendLine("tunnel:")
                appendLine("  mtu: 8500")
                appendLine("misc:")
                appendLine("  task-stack-size: 81920")
                appendLine("socks5:")
                appendLine("  address: $host")
                appendLine("  port: $port")
                appendLine("  udp: udp")
            }
            tunnelThread = thread(name = "HEV") {
                // startTunnel blocks in the native event loop until stopTunnel;
                // a return without an explicit stop is a native failure/early
                // exit, which must end the session so Running is never stale.
                val code = TProxyService.startTunnel(tunnelConfig, fd.fd)
                if (!stopping) {
                    Log.w(TAG, "HEV tunnel exited without stop (code $code)")
                    report(receiver, "HEV tunnel exited (code $code)")
                    terminate()
                }
            }
            // Give the tunnel a short grace to fail fast before claiming
            // readiness: a surviving thread means the native event loop is
            // running (genuine native readiness, as far as the HEV API exposes).
            Thread.sleep(TUNNEL_READY_GRACE_MS)
            if (tunnelThread?.isAlive == true) {
                receiver?.send(RESULT_STARTED, Bundle())
            } else {
                report(receiver, "HEV tunnel failed to start")
                terminate()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "VPN tunnel failed", e)
            report(receiver, "VPN tunnel failed: ${e.message ?: e.javaClass.simpleName}")
            terminate()
        }
    }

    @Suppress("DEPRECATION")
    private fun establish(routing: DpiVpnRouting): ParcelFileDescriptor {
        val builder = Builder()
        builder.setSession("ByeDPI")
            .addAddress("10.10.10.10", 32)
            .addRoute("0.0.0.0", 0)

        if (routing.ipv6) {
            builder.addAddress("fd00::1", 128)
                .addRoute("::", 0)
        }
        if (routing.dns.isNotBlank()) {
            builder.addDnsServer(routing.dns)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        // Self-exclusion: both engines share our applicationId, so excluding
        // our own package keeps the Telegram engine and the DPI engine's
        // upstream sockets off the tunnel (and out of a forwarding loop).
        builder.addDisallowedApplication(applicationContext.packageName)

        when (routing.appListMode) {
            VpnAppListMode.ALL_APPS -> Unit
            VpnAppListMode.BLACKLIST -> routing.apps.forEach { pkg ->
                runCatching { builder.addDisallowedApplication(pkg) }
            }
            VpnAppListMode.WHITELIST -> routing.apps.forEach { pkg ->
                runCatching { builder.addAllowedApplication(pkg) }
            }
        }

        return builder.establish() ?: throw IllegalStateException("VPN connection failed")
    }

    private fun stop() {
        stopping = true
        // Native stop ordering mirrors upstream NativeSession: stop the tunnel
        // (stop reading TUN) first, then the SOCKS5 engine, then join both
        // threads, then close the TUN fd, then terminate the :vpn process.
        runCatching { TProxyService.stopTunnel() }
        runCatching { proxy.stopProxy() }
        tunnelThread?.join(2_000)
        engineThread?.join(2_000)
        tunnelThread = null
        engineThread = null
        runCatching { tunFd?.close() }
        tunFd = null
        terminate()
    }

    /** The system revoked the VPN (or consent was withdrawn): stop, do not restart. */
    override fun onRevoke() {
        Log.w(TAG, "VPN revoked; stopping without restart")
        runCatching { TProxyService.stopTunnel() }
        runCatching { proxy.stopProxy() }
        runCatching { tunFd?.close() }
        tunFd = null
        terminate()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { TProxyService.stopTunnel() }
        runCatching { proxy.stopProxy() }
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

    private fun report(receiver: ResultReceiver?, reason: String) {
        receiver?.send(RESULT_FAILED, Bundle().apply { putString(EXTRA_ERROR, reason) })
    }

    /**
     * Ends the session and kills the `:vpn` process. Killing only this process
     * (never the Telegram process) is what resets the pinned engine's sticky
     * native globals (`proxy_started`/`params`) so the next VPN start gets a
     * fresh engine.
     */
    private fun terminate() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
        Process.killProcess(Process.myPid())
    }

    private fun startForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, flags)
        }
        val stopAction = PendingIntent.getService(
            this, 1,
            Intent(this, DpiVpnService::class.java).setAction(ACTION_STOP),
            flags
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.dpi_vpn_notification_channel_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_dpi_notification)
            .apply { if (open != null) setContentIntent(open) }
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.dpi_notification_disconnect),
                stopAction
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)
}
