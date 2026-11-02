package com.amurcanov.tgwsproxy

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ProxyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val operations = Mutex()
    @Volatile private var engine: ProxyEngine? = null
    private var configuration: ProxyConfiguration? = null
    private var statistics: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var generation = 0
    private var starting = false
        set(value) {
            field = value
            preparing.value = value
            ProxyTileService.requestSync(this)
        }
    private var stopping = false
    private var lastText = ""

    companion object {
        const val ACTION_START = "io.github.derigava.tgwsproxy.START"
        const val ACTION_STOP = "io.github.derigava.tgwsproxy.STOP"
        const val ACTION_RESTART = "io.github.derigava.tgwsproxy.RESTART"
        const val EXTRA_BIND_IP = "EXTRA_BIND_IP"
        const val EXTRA_PORT = "EXTRA_PORT"
        const val EXTRA_IPS = "EXTRA_IPS"
        const val EXTRA_POOL_SIZE = "EXTRA_POOL_SIZE"
        const val EXTRA_CFPROXY_ENABLED = "EXTRA_CFPROXY_ENABLED"
        const val EXTRA_CFPROXY_PRIORITY = "EXTRA_CFPROXY_PRIORITY"
        const val EXTRA_CFPROXY_DOMAIN = "EXTRA_CFPROXY_DOMAIN"
        const val EXTRA_SECRET_KEY = "EXTRA_SECRET_KEY"
        private const val CHANNEL = "TG_WS_Proxy_Service_v5"
        private const val NOTIFICATION = 101
        private val preparing = MutableStateFlow(false)
        val isStarting: StateFlow<Boolean> = preparing
        private val running = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = running
        val isVerifiedRunning: StateFlow<Boolean> = running
        private val connection = MutableStateFlow<ActiveProxyConnection?>(null)
        val activeConnection: StateFlow<ActiveProxyConnection?> = connection
    }
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL,
                getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null); enableVibration(false); setShowBadge(false)
            })
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(ProxyConfiguration(
                intent.getStringExtra(EXTRA_BIND_IP) ?: "127.0.0.1",
                intent.getIntExtra(EXTRA_PORT, 1443), intent.getStringExtra(EXTRA_IPS) ?: "",
                intent.getStringExtra(EXTRA_SECRET_KEY) ?: "", intent.getIntExtra(EXTRA_POOL_SIZE, 4),
                intent.getBooleanExtra(EXTRA_CFPROXY_ENABLED, true), intent.getStringExtra(EXTRA_CFPROXY_DOMAIN) ?: ""))
            ACTION_RESTART -> configuration?.let { restart(it) } ?: stopSelf()
            ACTION_STOP -> stop()
            else -> stopSelf()
        }
        return START_REDELIVER_INTENT
    }
    private fun foreground(text: String) {
        lastText = text
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION, notification(text))
    }
    private fun start(config: ProxyConfiguration) {
        if (starting || stopping || running.value) return
        configuration = config
        starting = true
        val current = ++generation
        foreground(getString(R.string.notification_starting))
        acquireWakeLock()
        scope.launch {
            operations.withLock {
                try {
                    val result = withContext(Dispatchers.IO) {
                        val runtime = engine ?: PythonProxyEngine(this@ProxyService).also { engine = it }
                        // Engine initialization can outlive a stop/destroy
                        // request. Do not open a listener after that request.
                        if (generation != current) runtime.stop() else runtime.start(config)
                    }
                    if (generation != current) return@withLock
                    starting = false
                    if (result.connection == null) {
                        Log.e("TgWsProxy", "Proxy start failed: ${result.error}")
                        update(getString(R.string.notification_error, result.error ?: result.state))
                        android.widget.Toast.makeText(this@ProxyService, getString(R.string.notification_error, result.error ?: result.state), android.widget.Toast.LENGTH_LONG).show()
                        stop()
                    } else {
                        connection.value = result.connection
                        running.value = true
                        ProxyTileService.requestSync(this@ProxyService)
                        update(getString(R.string.notification_running))
                        statistics = scope.launch { pollStatistics() }
                    }
                } catch (e: Exception) {
                    if (generation == current) {
                        Log.e("TgWsProxy", "Proxy startup failed", e)
                        starting = false
                        stop()
                    }
                }
            }
        }
    }
    private suspend fun pollStatistics() {
        var lastWake = System.currentTimeMillis()
        while (currentCoroutineContext().isActive) {
            delay(3000)
            val result = try { withContext(Dispatchers.IO) { engine?.snapshot() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.e("TgWsProxy", "Proxy status check failed", e)
                stop()
                break
            } ?: break
            if (result.connection == null) { stop(); break }
            val bytes = result.bytesUp + result.bytesDown
            val human = if (bytes < 1048576) "${bytes / 1024} KB" else "${bytes / 1048576} MB"
            update(getString(R.string.notification_traffic, human, result.activeConnections))
            if (System.currentTimeMillis() - lastWake > 25 * 60 * 1000) {
                acquireWakeLock(); lastWake = System.currentTimeMillis()
            }
        }
    }
    private fun restart(config: ProxyConfiguration) {
        if (starting || stopping) return
        stop(restart = config)
    }
    private fun stop(restart: ProxyConfiguration? = null) {
        if (stopping) return
        val interruptStartup = starting
        stopping = true
        ++generation
        starting = false
        statistics?.cancel(); statistics = null
        connection.value = null; running.value = false
        ProxyTileService.requestSync(this)
        update(getString(R.string.notification_stopping))
        scope.launch {
            // Signal Python before waiting for the start operation's mutex.
            // The second stop below also covers initialization completing
            // after this first nullable engine read.
            if (interruptStartup) {
                try { withContext(Dispatchers.IO) { engine?.stop() } }
                catch (e: Exception) { Log.w("TgWsProxy", "Startup cancellation failed", e) }
            }
            operations.withLock {
                try { withContext(Dispatchers.IO) { engine?.stop() } }
                catch (e: Exception) { Log.e("TgWsProxy", "Proxy cleanup failed", e) }
            }
            releaseWakeLock()
            stopping = false
            if (restart != null) start(restart)
            else { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
    }
    private fun update(text: String) {
        if (lastText == text) return
        lastText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(text))
    }
    private fun notification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), flags)
        fun action(name: String, code: Int) = PendingIntent.getService(this, code,
            Intent(this, ProxyService::class.java).setAction(name), flags)
        return NotificationCompat.Builder(this, CHANNEL).setContentTitle("Telegram WS Proxy")
            .setContentText(text).setSmallIcon(R.drawable.ic_notification).setContentIntent(open)
            .addAction(android.R.drawable.ic_popup_sync, getString(R.string.notification_restart), action(ACTION_RESTART, 2))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notification_disconnect), action(ACTION_STOP, 3))
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).setPriority(NotificationCompat.PRIORITY_LOW).build()
    }
    private fun acquireWakeLock() {
        releaseWakeLock()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TGWS:Proxy").apply { acquire(30 * 60 * 1000L) }
    }
    private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }
    override fun onTaskRemoved(rootIntent: Intent?) { super.onTaskRemoved(rootIntent) }
    override fun onDestroy() {
        ++generation
        scope.cancel()
        starting = false
        connection.value = null; running.value = false
        releaseWakeLock()
        // Service teardown must also clean up when Android did not send ACTION_STOP.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { engine?.stop() }
            // Read engine again after initialization/start relinquishes the
            // mutex; capturing null before initialization would leak it.
            operations.withLock { runCatching { engine?.stop() } }
        }
        ProxyTileService.requestSync(this)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
