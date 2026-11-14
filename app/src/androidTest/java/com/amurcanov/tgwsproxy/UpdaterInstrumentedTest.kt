package com.amurcanov.tgwsproxy

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.ParcelFileDescriptor
import androidx.core.content.pm.PackageInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class UpdaterInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Test
    @Suppress("DEPRECATION")
    fun verifiedDownloadOpensOnlyTheAndroidInstaller() = withFixture("valid") { file, release ->
        if (!BuildConfig.APP_UPDATES_ENABLED) {
            val error = runCatching { ApkInstaller.prepare(context, release) { error("Unexpected download") } }
            assertTrue(error.isFailure)
            return@withFixture
        }
        val prepared = ApkInstaller.prepare(context, release) { read -> file.inputStream().use(read) }
        try {
            assertArrayEquals(file.readBytes(), prepared.readBytes())
            val installer = ApkInstaller.installerIntent(context, prepared)
            assertEquals(Intent.ACTION_INSTALL_PACKAGE, installer.action)
            assertEquals("content", installer.data?.scheme)
            assertEquals("${context.packageName}.updates", installer.data?.authority)
            assertTrue(installer.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val systemApp = context.packageManager.getApplicationInfo(installer.component!!.packageName, 0)
            assertTrue(systemApp.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
        } finally {
            prepared.delete()
        }
    }

    @Test
    fun rejectsWrongChecksumAndSize() = withFixture("valid") { file, release ->
        val asset = requireNotNull(release.apk)
        assertRejected(file, release.copy(apk = asset.copy(sha256 = "0".repeat(64))), R.string.update_error_checksum)
        assertRejected(file, release.copy(apk = asset.copy(size = asset.size + 1)), R.string.update_error_size)
    }

    @Test
    fun rejectsWrongSigningKey() = withFixture("wrong-key") { file, release ->
        assertRejected(file, release, R.string.update_error_signature)
    }

    @Test
    fun rejectsWrongPackage() = withFixture("wrong-package") { file, release ->
        assertRejected(file, release, R.string.update_error_package)
    }

    @Test
    fun rejectsNonIncreasingOrUnexpectedVersion() = withFixture("same-version") { file, release ->
        assertRejected(file, release, R.string.update_error_version)
        assertRejected(file, release.copy(versionCode = release.versionCode + 1), R.string.update_error_version)
    }

    private fun assertRejected(file: File, release: AppReleaseInfo, reason: Int) {
        val error = runCatching { ApkInstaller.verifyArchive(context, file, release) }.exceptionOrNull()
        assertEquals(context.getString(reason), error?.message)
    }

    @Suppress("DEPRECATION")
    private fun withFixture(name: String, test: suspend (File, AppReleaseInfo) -> Unit): Unit = runBlocking {
        val file = File.createTempFile("update-test-", ".apk", context.cacheDir)
        try {
            val input = instrumentation.uiAutomation.executeShellCommand("cat /data/local/tmp/tgws-updater-fixtures/$name.apk")
            ParcelFileDescriptor.AutoCloseInputStream(input).use { source ->
                file.outputStream().use { source.copyTo(it) }
            }
            check(file.length() > 0) { "Missing signed fixture: $name" }
            val installed = PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
            val code = if (name == "same-version") installed else installed + 1
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            test(file, AppReleaseInfo("v99.0.0", code, UpdateApk(file.length(), digest)))
        } finally {
            file.delete()
        }
    }
}
