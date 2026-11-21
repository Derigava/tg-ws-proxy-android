package com.amurcanov.tgwsproxy

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BootReceiverIsolationTest {
    @Test fun telegramFailureDoesNotPreventCheckingDpiOptIn() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsStore(app)
        settings.saveAutoStartOnBoot(true)
        settings.saveDpiAutoStartOnBoot(false)
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(service: Intent): android.content.ComponentName {
                throw IllegalStateException("foreground start denied")
            }
        }
        ShadowLog.clear()
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline && ShadowLog.getLogsForTag("BootReceiver").none { it.msg.contains("DPI autostart disabled") }) {
            kotlinx.coroutines.delay(20)
        }
        assertTrue(ShadowLog.getLogsForTag("BootReceiver").any { it.throwable?.message == "foreground start denied" })
        assertTrue(ShadowLog.getLogsForTag("BootReceiver").any { it.msg.contains("DPI autostart disabled") })
    }
}
