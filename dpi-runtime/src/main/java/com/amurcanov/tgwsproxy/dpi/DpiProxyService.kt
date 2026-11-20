package com.amurcanov.tgwsproxy.dpi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.ResultReceiver
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.romanvht.byedpi.core.ByeDpiProxy
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Foreground service that hosts the pinned ByeByeDPI SOCKS5 engine in its own
 * `:dpi` process. Commands are private (the service is not exported) and use a
 * namespace distinct from the Telegram proxy's.
 *
 * Start is acknowledged positively only when this service's own engine thread
 * has bound the listener (a foreign SOCKS5 responder can never satisfy it), and
 * the engine thread reports failures so the controller never sees a false
 * Running. Every stop ends the process to give the next start fresh native
 * globals (the pinned engine keeps sticky `proxy_started`/`params` statics).
 */
class DpiProxyService : Service() {
    companion object {
        const val ACTION_START = "io.github.derigava.tgwsproxy.dpi.START"
        const val ACTION_STOP = "io.github.derigava.tgwsproxy.dpi.STOP"
        const val ACTION_BIND = "io.github.derigava.tgwsproxy.dpi.BIND"
        const val EXTRA_ARGS = "EXTRA_ARGS"
        const val EXTRA_HOST = "EXTRA_HOST"
        const val EXTRA_PORT = "EXTRA_PORT"
        const val EXTRA_RESULT_RECEIVER = "EXTRA_RESULT_RECEIVER"
        const val EXTRA_ERROR = "EXTRA_ERROR"
        const val RESULT_STARTED = 1
        const val RESULT_FAILED = 2
        const val START_TIMEOUT_MS = 10_000L
        private const val PROBE_TIMEOUT_MS = 250
        private const val POLL_MS = 50L
        private const val CHANNEL = "ByeDPI_Proxy_Service"
        private const val NOTIFICATION_ID = 102
        private const val TAG = "DpiProxyService"
    }

    private val binder = Binder()
    private val proxy by lazy { ByeDpiProxy() }
    private var engineThread: Thread? = null

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
                    getString(R.string.dpi_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> stop()
            else -> stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun start(intent: Intent) {
        if (engineThread != null) return
        val args = intent.getStringArrayListExtra(EXTRA_ARGS)?.toTypedArray()
        val host = intent.getStringExtra(EXTRA_HOST)
        val port = intent.getIntExtra(EXTRA_PORT, -1)
        val receiver = intent.getParcelableExtra<ResultReceiver>(EXTRA_RESULT_RECEIVER)
        if (args.isNullOrEmpty() || host.isNullOrBlank() || port !in 1..65535) {
            report(receiver, "invalid engine configuration")
            terminate()
            return
        }

        stopping = false
        ready = false
        engineError = null
        startForeground(notification(getString(R.string.dpi_notification_running)))

        engineThread = thread(name = "ByeDPI") {
            try {
                val code = proxy.startProxy(args)
                if (code != 0) engineError = "engine exited with code $code"
            } catch (e: Throwable) {
                Log.e(TAG, "ByeDPI engine failed", e)
                engineError = "engine exception: ${e.message ?: e.javaClass.simpleName}"
            }
            // The engine stopped on its own after the controller was told it was
            // ready: end the process so the main process's binder-death callback
            // fires and the controller leaves Running. A deliberate stop already
            // set `stopping` and handles cleanup itself.
            if (!stopping && ready) terminate()
        }

        thread(name = "ByeDPIMonitor") {
            val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val error = engineError
                if (error != null) {
                    report(receiver, error)
                    terminate()
                    return@thread
                }
                if (engineThread?.isAlive == true && socks5Ready(host, port)) {
                    ready = true
                    receiver?.send(RESULT_STARTED, Bundle())
                    return@thread
                }
                Thread.sleep(POLL_MS)
            }
            report(receiver, "SOCKS5 proxy not ready on $host:$port")
            runCatching { proxy.stopProxy() }
            terminate()
        }
    }

    private fun stop() {
        stopping = true
        runCatching { proxy.stopProxy() }
        engineThread?.join(2_000)
        engineThread = null
        terminate()
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

    private fun terminate() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
        Process.killProcess(Process.myPid())
    }

    private fun notification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, flags)
        }
        val stopAction = PendingIntent.getService(
            this, 1,
            Intent(this, DpiProxyService::class.java).setAction(ACTION_STOP),
            flags
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.dpi_notification_channel_name))
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

    private fun startForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { proxy.stopProxy() }
    }
}
