package com.amurcanov.tgwsproxy

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.amurcanov.tgwsproxy.dpi.DpiMode
import com.amurcanov.tgwsproxy.dpi.DpiState

/**
 * External automation for the ByeByeDPI module requires visible confirmation,
 * mirroring [ProxyShortcutActivity]. The VPN mode never captures the slot
 * silently: [DpiSession.start] re-samples and re-authorizes before establish, so
 * an unprepared or third-party slot is refused rather than taken over.
 */
open class DpiShortcutActivity : ComponentActivity() {
    protected open val requireConfirmation = true

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withAppLanguage())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action
        val title = when (action) {
            ACTION_START -> R.string.dpi_shortcut_start
            ACTION_STOP -> R.string.dpi_shortcut_stop
            ACTION_TOGGLE -> R.string.dpi_shortcut_toggle
            else -> {
                finishQuietly()
                return
            }
        }
        if (requireConfirmation) {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(R.string.shortcut_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ -> applyAction(action) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> finishQuietly() }
                .setOnCancelListener { finishQuietly() }
                .show()
                .getButton(AlertDialog.BUTTON_POSITIVE).filterTouchesWhenObscured = true
        } else {
            applyAction(action)
        }
    }

    private fun applyAction(action: String) {
        val state = DpiSession.state.value
        val active = state is DpiState.Running || state is DpiState.Starting
        when (action) {
            ACTION_START -> if (!active) DpiSession.start(this)
            ACTION_STOP -> if (active) DpiSession.stop()
            ACTION_TOGGLE -> if (active) DpiSession.stop() else DpiSession.start(this)
        }
        finishQuietly()
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
        const val ACTION_START = "io.github.derigava.tgwsproxy.action.dpi.START"
        const val ACTION_STOP = "io.github.derigava.tgwsproxy.action.dpi.STOP"
        const val ACTION_TOGGLE = "io.github.derigava.tgwsproxy.action.dpi.TOGGLE"
    }
}

class DpiLauncherShortcutActivity : DpiShortcutActivity() {
    override val requireConfirmation = false
}
