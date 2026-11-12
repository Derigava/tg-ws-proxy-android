package com.amurcanov.tgwsproxy

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Start, stop or toggle the proxy without opening the UI: launcher shortcuts
 * and automation apps (MacroDroid, Tasker) call this activity by action.
 *
 * It stays alive until the service is asked to start, because Android 12+
 * only lets an app start a foreground service while one of its activities is visible.
 */
class ProxyShortcutActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withAppLanguage())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action
        lifecycleScope.launch {
            val running = ProxyService.isRunning.value || ProxyService.isStarting.value
            when (action) {
                ACTION_START -> if (!running) start()
                ACTION_STOP -> if (running) ProxyController.stop(this@ProxyShortcutActivity)
                ACTION_TOGGLE -> if (running) ProxyController.stop(this@ProxyShortcutActivity) else start()
            }
            finishQuietly()
        }
    }

    private suspend fun start() {
        ProxyController.startFromSavedSettings(this, showInvalidPortToast = true)
    }

    private fun finishQuietly() {
        finish()
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        const val ACTION_START = "io.github.derigava.tgwsproxy.action.START"
        const val ACTION_STOP = "io.github.derigava.tgwsproxy.action.STOP"
        const val ACTION_TOGGLE = "io.github.derigava.tgwsproxy.action.TOGGLE"

        fun intent(context: Context, action: String): Intent =
            Intent(context, ProxyShortcutActivity::class.java).setAction(action)
    }
}
