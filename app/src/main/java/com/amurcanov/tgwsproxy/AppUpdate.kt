package com.amurcanov.tgwsproxy

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

const val UPDATE_CHECK_NEVER = -1
const val DEFAULT_UPDATE_CHECK_INTERVAL_HOURS = 12
const val PRIVATE_UPDATE_MESSAGE = "Тестовая сборка: репозиторий закрыт. APK устанавливается вручную из GitHub."
private const val RELEASE_API = "https://api.github.com/repos/Derigava/tg-ws-proxy-android/releases/latest"
private const val RELEASE_PREFIX = "https://github.com/Derigava/tg-ws-proxy-android/releases/"

fun updateIntervalHoursToMillis(hours: Int): Long? = when {
    hours <= 0 -> null
    else -> hours * 60L * 60L * 1000L
}

fun updateIntervalLabel(context: Context, hours: Int): String = when (hours) {
    7 -> context.getString(R.string.interval_7h)
    24 -> context.getString(R.string.interval_24h)
    48 -> context.getString(R.string.interval_48h)
    UPDATE_CHECK_NEVER -> context.getString(R.string.interval_never)
    else -> context.getString(R.string.interval_hours, hours)
}

fun updateIntervalLabel(hours: Int): String = when (hours) {
    7 -> "7 h"
    24 -> "24 h"
    48 -> "48 h"
    UPDATE_CHECK_NEVER -> "Never"
    else -> "$hours h"
}

data class AppReleaseInfo(
    val versionTag: String,
    val releaseUrl: String,
    val source: RemoteVersionSource = RemoteVersionSource.Release,
    val versionCode: Long,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256: String,
    val checksumsUrl: String
)

internal fun AppReleaseInfo.cacheJson(): String = JSONObject()
    .put("versionTag", versionTag).put("releaseUrl", releaseUrl).put("versionCode", versionCode)
    .put("apkName", apkName).put("apkUrl", apkUrl).put("apkSize", apkSize)
    .put("sha256", sha256).put("checksumsUrl", checksumsUrl).toString()

internal fun cachedReleaseInfo(json: String): AppReleaseInfo? = runCatching {
    if (json.isBlank()) return@runCatching null
    val value = JSONObject(json)
    AppReleaseInfo(value.getString("versionTag"), value.getString("releaseUrl"),
        versionCode = value.getLong("versionCode"), apkName = value.getString("apkName"),
        apkUrl = value.getString("apkUrl"), apkSize = value.getLong("apkSize"),
        sha256 = value.getString("sha256"), checksumsUrl = value.getString("checksumsUrl"))
}.getOrNull()

/** Serializes UI/background checks and reuses a fresh persisted result. */
object AppUpdateRepository {
    private val checkLock = Mutex()

    suspend fun check(context: Context, force: Boolean = false): AppReleaseInfo? {
        if (!BuildConfig.APP_UPDATES_ENABLED) return null
        return checkLock.withLock {
            val store = SettingsStore(context.applicationContext)
            val hours = store.updateCheckIntervalHours.first()
            if (!force && hours == UPDATE_CHECK_NEVER) return@withLock null
            val interval = updateIntervalHoursToMillis(hours)
                ?: updateIntervalHoursToMillis(DEFAULT_UPDATE_CHECK_INTERVAL_HOURS)!!
            val checkedAt = System.currentTimeMillis()
            val lastCheckAt = store.updateLastCheckAt.first()
            if (!force && lastCheckAt > 0 && checkedAt - lastCheckAt < interval) {
                return@withLock store.updateLatestRelease.first()
            }
            val release = fetchLatestReleaseInfo()
            store.saveUpdateCheckResult(checkedAt, release,
                if (release == null) context.getString(R.string.update_check_failed_short) else "")
            release
        }
    }
}

enum class RemoteVersionSource { Release, Tag }
const val UPDATE_DIALOG_ACTION_POSTPONED = "postponed"
const val UPDATE_DIALOG_ACTION_UPDATE = "update"

/** Only published stable releases with complete APK metadata are update candidates. */
@Suppress("UNUSED_PARAMETER")
suspend fun fetchLatestReleaseInfo(localVersion: String? = null): AppReleaseInfo? = withContext(Dispatchers.IO) {
    if (!BuildConfig.APP_UPDATES_ENABLED) return@withContext null
    try {
        val release = JSONObject(readUpdateText(RELEASE_API))
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return@withContext null
        val releaseUrl = release.getString("html_url")
        require(releaseUrl.startsWith(RELEASE_PREFIX)) { "Unexpected release repository" }
        val assets = release.getJSONArray("assets")
        fun asset(name: String): JSONObject? = (0 until assets.length())
            .map { assets.getJSONObject(it) }.singleOrNull { it.optString("name") == name }
        fun assetUrl(value: JSONObject): String = value.getString("browser_download_url").also {
            require(it.startsWith(RELEASE_PREFIX + "download/")) { "Unexpected asset repository" }
        }
        val metadataAsset = asset("update.json") ?: return@withContext null
        val checksumAsset = asset("SHA256SUMS") ?: return@withContext null
        val metadata = JSONObject(readUpdateText(assetUrl(metadataAsset)))
        val versionCode = metadata.getLong("versionCode")
        val versionName = metadata.getString("versionName").trim().removePrefix("v")
        val apkName = metadata.getString("apk")
        val sha256 = metadata.getString("sha256").lowercase()
        require(versionCode > 0 && versionName.isNotEmpty())
        require(Regex("[A-Za-z0-9._-]+\\.apk").matches(apkName)) { "Invalid APK name" }
        require(Regex("[0-9a-f]{64}").matches(sha256)) { "Invalid APK checksum" }
        val apkAsset = asset(apkName) ?: return@withContext null
        val apkSize = apkAsset.getLong("size")
        require(apkSize in 1..MAX_UPDATE_APK_BYTES) { "Invalid APK size" }
        AppReleaseInfo("v$versionName", releaseUrl, versionCode = versionCode,
            apkName = apkName, apkUrl = assetUrl(apkAsset), apkSize = apkSize,
            sha256 = sha256, checksumsUrl = assetUrl(checksumAsset))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("TgWsProxy", "Update check unavailable: ${e.javaClass.simpleName}")
        null
    }
}

fun isNewerVersion(local: String, remote: String): Boolean {
    fun parts(value: String) = Regex("\\d+(?:\\.\\d+)*").find(value)?.value
        ?.split('.')?.mapNotNull { it.toLongOrNull() }.orEmpty()
    val localParts = parts(local)
    val remoteParts = parts(remote)
    if (remoteParts.isEmpty()) return false
    for (i in 0 until maxOf(localParts.size, remoteParts.size)) {
        val comparison = remoteParts.getOrElse(i) { 0L }.compareTo(localParts.getOrElse(i) { 0L })
        if (comparison != 0) return comparison > 0
    }
    return false
}

internal const val MAX_UPDATE_APK_BYTES = 200L * 1024L * 1024L
internal fun openUpdateConnection(url: String): HttpURLConnection {
    require(URL(url).protocol == "https") { "HTTPS required for updates" }
    return (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        useCaches = false
        setRequestProperty("User-Agent", "DerigavaTGWS/${BuildConfig.VERSION_NAME}")
        setRequestProperty("Accept", if (url.startsWith("https://api.github.com/")) "application/vnd.github+json" else "application/octet-stream")
        setRequestProperty("Cache-Control", "no-cache")
    }
}

internal fun readUpdateText(url: String): String {
    val connection = openUpdateConnection(url)
    try {
        if (connection.responseCode !in 200..299) throw IOException("Update server HTTP ${connection.responseCode}")
        connection.inputStream.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val size = stream.read(buffer)
                if (size < 0) break
                require(output.size() + size <= 1024 * 1024) { "Update metadata too large" }
                output.write(buffer, 0, size)
            }
            return output.toByteArray().toString(Charsets.UTF_8)
        }
    } finally { connection.disconnect() }
}
