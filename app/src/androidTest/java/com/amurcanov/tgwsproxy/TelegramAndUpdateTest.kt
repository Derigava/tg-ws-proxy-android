package com.amurcanov.tgwsproxy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TelegramAndUpdateTest {
    @Test fun linkUsesActualConfigurationAndCompleteSecret() {
        val connection = ActiveProxyConnection("127.0.0.1", 23456, "ee0123456789abcdef0123456789abcdef74656c656772616d2e6f7267")
        val uri = TelegramConnector.proxyUri(connection)
        assertEquals("tg", uri.scheme)
        assertEquals("proxy", uri.authority)
        assertEquals("127.0.0.1", uri.getQueryParameter("server"))
        assertEquals("23456", uri.getQueryParameter("port"))
        assertEquals(connection.secret, uri.getQueryParameter("secret"))
        assertFalse(uri.toString().startsWith("https:"))
    }

    @Test fun wildcardBindingUsesDeviceLoopback() {
        assertEquals("127.0.0.1", TelegramConnector.proxyUri(ActiveProxyConnection("0.0.0.0", 1443, "dd00")).getQueryParameter("server"))
        assertEquals("::1", TelegramConnector.proxyUri(ActiveProxyConnection("::", 1443, "dd00")).getQueryParameter("server"))
        assertEquals("2001:db8::1", TelegramConnector.proxyUri(ActiveProxyConnection("2001:db8::1", 1443, "dd00")).getQueryParameter("server"))
    }

    @Test fun checksumManifestMustMatchBothFilenameAndDigest() {
        val digest = "a".repeat(64)
        assertTrue(ApkInstaller.checksumMatches("$digest  app-arm64.apk\n", "app-arm64.apk", digest))
        assertTrue(ApkInstaller.checksumMatches("$digest *app-arm64.apk", "app-arm64.apk", digest))
        assertFalse(ApkInstaller.checksumMatches("$digest  other.apk", "app-arm64.apk", digest))
        assertFalse(ApkInstaller.checksumMatches("${"b".repeat(64)}  app-arm64.apk", "app-arm64.apk", digest))
        assertFalse(ApkInstaller.checksumMatches("", "app-arm64.apk", digest))
    }

    @Test fun corruptedAndNonApkDownloadsAreRejected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("update-validation-", ".apk", context.cacheDir)
        try {
            val contents = "This is not a signed Android package".toByteArray()
            file.writeBytes(contents)
            val actualDigest = MessageDigest.getInstance("SHA-256").digest(contents).joinToString("") { "%02x".format(it) }
            val release = AppReleaseInfo("v99.0", "https://github.com/Derigava/tg-ws-proxy-android/releases/tag/v99.0",
                versionCode = 999999, apkName = "app.apk", apkUrl = "https://example.invalid/app.apk",
                apkSize = contents.size.toLong(), sha256 = actualDigest, checksumsUrl = "https://example.invalid/SHA256SUMS")
            val unsignedError = runCatching { ApkInstaller.verifyArchive(context, file, release) }.exceptionOrNull()
            assertNotNull("Unsigned non-APK file must not reach the installer", unsignedError)
            assertTrue(unsignedError?.message.orEmpty().contains("APK"))
            val checksumError = runCatching { ApkInstaller.verifyArchive(context, file, release.copy(sha256 = "0".repeat(64))) }.exceptionOrNull()
            assertTrue(checksumError?.message.orEmpty().contains("сумма"))
            val sizeError = runCatching { ApkInstaller.verifyArchive(context, file, release.copy(apkSize = contents.size + 1L)) }.exceptionOrNull()
            assertTrue(sizeError?.message.orEmpty().contains("размер"))
        } finally { file.delete() }
    }

    @Test fun releaseVersionComparisonHandlesMissingSegments() {
        assertTrue(isNewerVersion("v1.2.9", "1.3.0"))
        assertFalse(isNewerVersion("1.3", "v1.3.0"))
        assertFalse(isNewerVersion("2.0.0", "1.9.9"))
        assertFalse(isNewerVersion("1.0", "unknown"))
    }
}
