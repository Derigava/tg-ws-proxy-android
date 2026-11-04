package com.amurcanov.tgwsproxy

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** No account token is shipped in APKs. Builds without the update channel never contact the update service. */
object ApkInstaller {
    private val downloadLock = Mutex()

    suspend fun prepare(context: Context, release: AppReleaseInfo): File = withContext(Dispatchers.IO) {
        check(BuildConfig.APP_UPDATES_ENABLED) { context.getString(R.string.update_disabled_message) }
        downloadLock.withLock {
            val checksums = readUpdateText(release.checksumsUrl)
            require(checksumMatches(checksums, release.apkName, release.sha256)) { context.getString(R.string.update_error_checksum_missing) }
            val directory = File(context.cacheDir, "updates").apply { mkdirs() }
            val target = File(directory, "update-${release.versionCode}.apk")
            if (target.isFile && runCatching { verifyArchive(context, target, release) }.isSuccess) return@withLock target
            target.delete()
            val partial = File(directory, "update-${release.versionCode}.part")
            val connection = openUpdateConnection(release.apkUrl)
            try {
                require(connection.responseCode in 200..299) { context.getString(R.string.update_error_download) }
                var count = 0L
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val size = input.read(buffer)
                            if (size < 0) break
                            count += size
                            require(count <= MAX_UPDATE_APK_BYTES && count <= release.apkSize) { context.getString(R.string.update_error_size) }
                            output.write(buffer, 0, size)
                        }
                    }
                }
                require(count == release.apkSize) { context.getString(R.string.update_error_incomplete) }
                verifyArchive(context, partial, release)
                check(partial.renameTo(target)) { context.getString(R.string.update_error_save) }
                target
            } finally {
                connection.disconnect()
                partial.delete()
            }
        }
    }

    fun checksumMatches(text: String, name: String, digest: String): Boolean = text.lineSequence().any { line ->
        val fields = line.trim().split(Regex("\\s+"), limit = 2)
        fields.size == 2 && fields[0].equals(digest, ignoreCase = true) && fields[1].removePrefix("*") == name
    }

    internal fun verifyArchive(context: Context, apk: File, release: AppReleaseInfo) {
        require(apk.length() == release.apkSize) { context.getString(R.string.update_error_size) }
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val expected = release.sha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        require(MessageDigest.isEqual(digest.digest(), expected)) { context.getString(R.string.update_error_checksum) }
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION")
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: error(context.getString(R.string.update_error_unrecognized))
        @Suppress("DEPRECATION")
        val installed = pm.getPackageInfo(context.packageName, flags)
        require(archive.packageName == context.packageName) { context.getString(R.string.update_error_package) }
        require(PackageInfoCompat.getLongVersionCode(archive) == release.versionCode) { context.getString(R.string.update_error_version) }
        require(release.versionCode > PackageInfoCompat.getLongVersionCode(installed)) { context.getString(R.string.update_error_installed) }
        val currentSigners = signerDigests(installed)
        val downloadedSigners = signerDigests(archive)
        require(currentSigners.isNotEmpty() && currentSigners == downloadedSigners) { context.getString(R.string.update_error_signature) }
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    /** Returns false when Android first needs permission; tap Update again after granting it. */
    fun install(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(context, R.string.update_allow_install, Toast.LENGTH_LONG).show()
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        return true
    }
}
