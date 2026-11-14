package com.amurcanov.tgwsproxy

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class UpdateFileProvider : FileProvider()

internal object ApkInstaller {
    private val downloadLock = Mutex()

    suspend fun prepare(
        context: Context,
        release: AppReleaseInfo,
        readApk: ((InputStream) -> Unit) -> Unit = { read -> withReleaseAsset(release.apkUrl, read) }
    ): File = withContext(Dispatchers.IO) {
        check(BuildConfig.APP_UPDATES_ENABLED) { context.getString(R.string.update_disabled_message) }
        val asset = requireNotNull(release.apk)
        val downloadContext = coroutineContext
        downloadLock.withLock {
            val directory = File(context.cacheDir, "updates")
            check(directory.isDirectory || directory.mkdirs()) { context.getString(R.string.update_install_failed) }
            val target = File(directory, "update-${release.versionCode}.apk")
            if (target.isFile && runCatching { verifyArchive(context, target, release) }.isSuccess) {
                return@withLock target
            }
            if (target.exists()) check(target.delete()) { context.getString(R.string.update_install_failed) }

            // Older Android versions require an .apk suffix to read package metadata.
            val partial = File.createTempFile("download-", ".apk", directory)
            try {
                var received = 0L
                readApk { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            downloadContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            require(received <= asset.size) { context.getString(R.string.update_error_size) }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                verifyArchive(context, partial, release)
                check(partial.renameTo(target)) { context.getString(R.string.update_install_failed) }
                target
            } finally {
                partial.delete()
            }
        }
    }

    @Suppress("DEPRECATION")
    internal fun verifyArchive(context: Context, apk: File, release: AppReleaseInfo) {
        val asset = requireNotNull(release.apk)
        require(apk.length() == asset.size) { context.getString(R.string.update_error_size) }
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val expected = asset.sha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        require(MessageDigest.isEqual(digest.digest(), expected)) { context.getString(R.string.update_error_checksum) }

        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: error(context.getString(R.string.update_error_unrecognized))
        val installed = pm.getPackageInfo(context.packageName, flags)
        require(archive.packageName == context.packageName) { context.getString(R.string.update_error_package) }
        require(PackageInfoCompat.getLongVersionCode(archive) == release.versionCode &&
            release.versionCode > PackageInfoCompat.getLongVersionCode(installed)) { context.getString(R.string.update_error_version) }
        val signers = apkSigners(installed)
        require(signers.isNotEmpty() && signers == apkSigners(archive)) { context.getString(R.string.update_error_signature) }
    }

    @Suppress("DEPRECATION")
    private fun apkSigners(info: PackageInfo): Set<Signature> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().toSet()
    }

    fun hasInstallPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(context: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    @Suppress("DEPRECATION")
    fun installerIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val installer = context.packageManager.queryIntentActivities(
            intent, PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY
        ).firstOrNull { it.activityInfo.exported }?.activityInfo
            ?: error(context.getString(R.string.update_install_failed))
        return intent.setClassName(installer.packageName, installer.name)
    }
}
