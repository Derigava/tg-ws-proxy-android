package com.amurcanov.tgwsproxy.dpi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import io.github.romanvht.byedpi.core.ByeDpiProxy
import kotlin.concurrent.thread

/**
 * Foreground service that hosts the pinned ByeByeDPI SOCKS5 engine in its own
 * `:dpi` process. Commands are private (the service is not exported) and use a
 * namespace distinct from the Telegram proxy's.
 *
 * The engine cannot be restarted in-process: the pinned native code keeps
 * sticky `proxy_started`/`params` statics, so every stop ends the process to
 * give the next start fresh globals (mirrors the upstream `:native` design).
 */
class DpiProxyService : Service() {
    companion object {
        const val ACTION_START = "io.github.derigava.tgwsproxy.dpi.START"
        const val ACTION_STOP = "io.github.derigava.tgwsproxy.dpi.STOP"
        const val EXTRA_ARGS = "EXTRA_ARGS"
        private const val CHANNEL = "ByeDPI_Proxy_Service"
        private const val NOTIFICATION_ID = 102
    }

    private val proxy by lazy { ByeDpiProxy() }
    private var engineThread: Thread? = null

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> stop()
            else -> stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (engineThread != null) return
        val args = intent.getStringArrayListExtra(EXTRA_ARGS)?.toTypedArray()
        if (args.isNullOrEmpty()) {
            stopSelf()
            return
        }
        stopping = false
        startForeground(notification(getString(R.string.dpi_notification_running)))
        engineThread = thread(name = "ByeDPI") {
            proxy.startProxy(args)
            // If we reach here without an explicit stop, the engine exited on
            // its own (bind failure or fatal error): don't leave an orphaned
            // foreground service behind.
            if (!stopping) terminate()
        }
    }

    private fun stop() {
        stopping = true
        runCatching { proxy.stopProxy() }
        engineThread?.join(2_000)
        engineThread = null
        terminate()
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
