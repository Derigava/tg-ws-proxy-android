package com.amurcanov.tgwsproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.amurcanov.tgwsproxy.dpi.DpiConfig
import com.amurcanov.tgwsproxy.dpi.DpiMode
import com.amurcanov.tgwsproxy.dpi.DpiProxyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Receives boot broadcasts and starts each module only when its own saved
 * opt-in is enabled. The Telegram proxy and the ByeByeDPI module have separate
 * opt-ins: enabling one never enables the other, and an app update never
 * creates an opt-in (both default to false, with no migration that flips them).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settingsStore = SettingsStore(appContext)
                if (settingsStore.autoStartOnBoot.first()) {
                    val started = ProxyController.startFromSavedSettings(
                        context = appContext,
                        showInvalidPortToast = false
                    )
                    Log.i(TAG, "Boot completed, proxy autostart requested: $started")
                } else {
                    Log.i(TAG, "Boot completed, proxy autostart disabled")
                }

                // The DPI module has its own opt-in; start it only when the user
                // explicitly enabled it. The default (and post-update) value is
                // false, so a VPN/SOCKS5 engine is never auto-started implicitly.
                if (settingsStore.dpiAutoStartOnBoot.first()) {
                    startDpiSocks5(appContext, settingsStore)
                } else {
                    Log.i(TAG, "Boot completed, DPI autostart disabled")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to autostart after boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Starts the DPI module in SOCKS5 mode (never the system VPN) using the
     * persisted host/port. SOCKS5 does not touch the VPN slot, so this is safe
     * to run at boot; VPN autostart is deliberately not offered here.
     */
    private suspend fun startDpiSocks5(appContext: Context, settingsStore: SettingsStore) {
        val host = settingsStore.dpiSocks5Host.first()
        val port = settingsStore.dpiSocks5Port.first()
        val argv = DpiConfig(mode = DpiMode.SOCKS5, host = host, port = port).toArgv()
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, DpiProxyService::class.java)
                .setAction(DpiProxyService.ACTION_START)
                .putStringArrayListExtra(DpiProxyService.EXTRA_ARGS, ArrayList(argv.toList()))
                .putExtra(DpiProxyService.EXTRA_HOST, host)
                .putExtra(DpiProxyService.EXTRA_PORT, port)
        )
        Log.i(TAG, "Boot completed, DPI SOCKS5 autostart requested on $host:$port")
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
