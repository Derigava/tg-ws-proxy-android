package com.amurcanov.tgwsproxy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class UpdateScheduleInstrumentedTest {
    @Test fun publicScheduleUsesTwelveHoursAndNetworkConstraint() {
        val request = AppUpdateScheduler.periodicRequest(true, DEFAULT_UPDATE_CHECK_INTERVAL_HOURS)
        assertNotNull(request)
        val spec = request!!.workSpec
        assertEquals(TimeUnit.HOURS.toMillis(12), spec.intervalDuration)
        assertEquals(TimeUnit.HOURS.toMillis(12), spec.initialDelay)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(AppUpdateWorker::class.java.name, spec.workerClassName)
    }

    @Test fun privateChannelAndNeverCannotCreatePeriodicWork() {
        assertNull(AppUpdateScheduler.periodicRequest(false, 12))
        assertNull(AppUpdateScheduler.periodicRequest(false, 7))
        assertNull(AppUpdateScheduler.periodicRequest(true, UPDATE_CHECK_NEVER))
    }

    @Test fun chosenIntervalIsPreserved() {
        val request = AppUpdateScheduler.periodicRequest(true, 24)!!
        assertEquals(TimeUnit.HOURS.toMillis(24), request.workSpec.intervalDuration)
        assertEquals(TimeUnit.HOURS.toMillis(24), request.workSpec.initialDelay)
    }

    @Test fun releaseMetadataSurvivesProcessCacheSerialization() {
        val release = AppReleaseInfo("v1.0.42", "https://github.com/Derigava/tg-ws-proxy-android/releases/tag/v1.0.42",
            versionCode = 1042, apkName = "app-arm64.apk", apkUrl = "https://example.invalid/app-arm64.apk",
            apkSize = 123456, sha256 = "a".repeat(64), checksumsUrl = "https://example.invalid/SHA256SUMS")
        assertEquals(release, cachedReleaseInfo(release.cacheJson()))
        assertNull(cachedReleaseInfo(""))
        assertNull(cachedReleaseInfo("{invalid"))
    }
}
