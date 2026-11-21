package com.amurcanov.tgwsproxy

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProxyStartStopRaceTest {
    @Test fun stopInvalidatesStartStillReadingSettings() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val start = async(start = CoroutineStart.UNDISPATCHED) { ProxyController.startFromSavedSettings(context) }
        assertFalse("start must be suspended before stop", start.isCompleted)
        ProxyController.stop(context)
        assertFalse(start.await())
        val shadow = shadowOf(context as Application)
        val actions = mutableListOf<String?>()
        while (true) actions.add((shadow.nextStartedService ?: break).action)
        assertEquals(listOf(ProxyService.ACTION_STOP), actions)
    }
}
