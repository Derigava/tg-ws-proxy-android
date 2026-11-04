package com.amurcanov.tgwsproxy

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.input.pointer.pointerInput

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amurcanov.tgwsproxy.ui.AccessSetupDialog
import com.amurcanov.tgwsproxy.ui.AppUpdateDialog
import com.amurcanov.tgwsproxy.ui.hasCriticalGap
import com.amurcanov.tgwsproxy.ui.rememberAccessOpener
import com.amurcanov.tgwsproxy.ui.rememberAccessRows
import com.amurcanov.tgwsproxy.ui.Brand
import com.amurcanov.tgwsproxy.ui.BrandIcon
import com.amurcanov.tgwsproxy.ui.ThemeModes
import com.amurcanov.tgwsproxy.ui.ConnectionTab
import com.amurcanov.tgwsproxy.ui.InfoTab
import com.amurcanov.tgwsproxy.ui.LogsTab
import com.amurcanov.tgwsproxy.ui.SettingsTab
import com.amurcanov.tgwsproxy.ui.TgWsProxyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.BUFFERED
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val localized = newBase.withAppLanguage()
        Locale.setDefault(localized.resources.configuration.locales[0])
        super.attachBaseContext(localized)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            val context = LocalContext.current
            val settingsStore = remember { SettingsStore(context) }
            val themeMode by settingsStore.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeModes.DARK)

            LaunchedEffect(settingsStore) {
                settingsStore.migrateLegacyDefaults()
            }

            TgWsProxyTheme(themeMode = themeMode) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                        density = androidx.compose.ui.platform.LocalDensity.current.density,
                        fontScale = 1f
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Brand.bg)
                    ) {
                        MainContent(settingsStore)
                    }
                }
            }
        }
    }
}

private data class NavItem(
    val label: String,
    @androidx.annotation.DrawableRes val icon: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainContent(settingsStore: SettingsStore) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var dragTargetIndex by remember { mutableIntStateOf(-1) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val updatePostponeUntil by settingsStore.updatePostponeUntil.collectAsStateWithLifecycle(initialValue = 0L)
    val updatePostponeVersion by settingsStore.updatePostponeVersion.collectAsStateWithLifecycle(initialValue = "")
    val updateCheckIntervalHours by settingsStore.updateCheckIntervalHours.collectAsStateWithLifecycle(
        initialValue = UPDATE_CHECK_NEVER
    )
    val updateLastCheckAt by settingsStore.updateLastCheckAt.collectAsStateWithLifecycle(initialValue = 0L)
    val cachedRelease by settingsStore.updateLatestRelease.collectAsStateWithLifecycle(initialValue = null)
    var pendingRelease by remember { mutableStateOf<AppReleaseInfo?>(null) }
    var updateBusy by remember { mutableStateOf(false) }
    val currentVersion = remember { "v${BuildConfig.VERSION_NAME.removePrefix("v")}" }
    val currentUpdatePostponeUntil by rememberUpdatedState(updatePostponeUntil)
    val currentUpdatePostponeVersion by rememberUpdatedState(updatePostponeVersion)
    val accessPromptShown by settingsStore.accessPromptShown.collectAsStateWithLifecycle(initialValue = true)
    val accessRows = rememberAccessRows()
    val openAccess = rememberAccessOpener()
    var showAccessDialog by rememberSaveable { mutableStateOf(false) }
    val navItems = listOf(
        NavItem(stringResource(R.string.nav_proxy), R.drawable.ic_dt_network),
        NavItem(stringResource(R.string.settings), R.drawable.ic_dt_settings),
        NavItem(stringResource(R.string.nav_logs), R.drawable.ic_dt_terminal),
        NavItem(stringResource(R.string.nav_info), R.drawable.ic_dt_documentation)
    )
    val safeBottomInset = with(density) { WindowInsets.safeDrawing.getBottom(density).toDp() }
    val navOverlayReserve = safeBottomInset + 68.dp

    DisposableEffect(Unit) {
        LogManager.startListening()
        onDispose { LogManager.stopListening() }
    }

    LaunchedEffect(updateCheckIntervalHours, updateLastCheckAt) {
        if (!BuildConfig.APP_UPDATES_ENABLED || updateCheckIntervalHours == UPDATE_CHECK_NEVER) return@LaunchedEffect

        val intervalMillis = updateIntervalHoursToMillis(updateCheckIntervalHours)
            ?: updateIntervalHoursToMillis(DEFAULT_UPDATE_CHECK_INTERVAL_HOURS)
            ?: 12L * 60L * 60L * 1000L

        if (updateLastCheckAt > 0L) {
            val nextCheckAt = updateLastCheckAt + intervalMillis
            val now = System.currentTimeMillis()
            if (nextCheckAt > now) {
                delay(nextCheckAt - now)
            }
        }

        if (!isActive) return@LaunchedEffect

        AppUpdateRepository.check(context)
    }

    // A background worker can discover a release while no Activity exists.
    // Show its persisted candidate when the UI next observes the result.
    LaunchedEffect(cachedRelease, updatePostponeVersion, updatePostponeUntil, selectedTab) {
        // InfoTab owns the explicit manual-check dialog; show just one popup.
        if (!BuildConfig.APP_UPDATES_ENABLED || selectedTab == 3) return@LaunchedEffect
        val release = cachedRelease ?: return@LaunchedEffect
        val isPostponed = currentUpdatePostponeVersion == release.versionTag &&
            System.currentTimeMillis() < currentUpdatePostponeUntil
        if (release.versionCode > BuildConfig.VERSION_CODE && !isPostponed) {
            settingsStore.saveUpdateDialogShown(release.versionTag, System.currentTimeMillis())
            pendingRelease = release
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        containerColor = Color.Transparent,
    ) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
            .pointerInput(selectedTab) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        totalDrag = 0f
                        dragTargetIndex = -1
                        dragProgress = 0f
                    },
                    onDragCancel = {
                        dragTargetIndex = -1
                        dragProgress = 0f
                    },
                    onDragEnd = {
                        if (dragTargetIndex in navItems.indices && dragProgress >= 0.5f) {
                            selectedTab = dragTargetIndex
                        }
                        dragTargetIndex = -1
                        dragProgress = 0f
                    }
                ) { change, dragAmount ->
                    change.consume()
                    totalDrag += dragAmount
                    if (abs(totalDrag) < 12f) {
                        dragTargetIndex = -1
                        dragProgress = 0f
                        return@detectHorizontalDragGestures
                    }

                    val candidate = if (totalDrag < 0f) selectedTab + 1 else selectedTab - 1
                    if (candidate !in navItems.indices) {
                        dragTargetIndex = -1
                        dragProgress = 0f
                        return@detectHorizontalDragGestures
                    }

                    dragTargetIndex = candidate
                    dragProgress = (abs(totalDrag) / 180f).coerceIn(0f, 1f)
                }
            }
        ) {
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    fadeIn(tween(300)) togetherWith fadeOut(tween(225))
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = navOverlayReserve),
                label = "tab_content"
            ) { page ->
                when (page) {
                    0 -> ConnectionTab(
                        settingsStore = settingsStore,
                        showAccessWarning = accessRows.hasCriticalGap(),
                        onShowAccess = { showAccessDialog = true }
                    )
                    1 -> SettingsTab(settingsStore)
                    2 -> LogsTab(settingsStore)
                    3 -> InfoTab(settingsStore)
                }
            }

            ProxyNavigationBar(
                navItems = navItems,
                selectedTab = selectedTab,
                dragTargetIndex = dragTargetIndex,
                dragProgress = dragProgress,
                onTabSelected = { index ->
                    selectedTab = index
                    dragTargetIndex = -1
                    dragProgress = 0f
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    if (showAccessDialog || (!accessPromptShown && accessRows.any { it.granted == false })) {
        AccessSetupDialog(
            rows = accessRows,
            onOpen = openAccess,
            onDone = {
                showAccessDialog = false
                scope.launch { settingsStore.saveAccessPromptShown() }
            }
        )
    }

    pendingRelease?.let { release ->
        AppUpdateDialog(
            release = release,
            busy = updateBusy,
            onPostpone = {
                pendingRelease = null
                Toast.makeText(context, context.getString(R.string.update_postponed_24h), Toast.LENGTH_SHORT).show()
                scope.launch {
                    val now = System.currentTimeMillis()
                    settingsStore.saveUpdatePostpone(
                        version = release.versionTag,
                        until = now + 24L * 60L * 60L * 1000L
                    )
                    settingsStore.saveUpdateDialogAction(
                        version = release.versionTag,
                        action = UPDATE_DIALOG_ACTION_POSTPONED,
                        actedAt = now
                    )
                }
            },
            onUpdate = {
                updateBusy = true
                scope.launch {
                    try {
                        val apk = ApkInstaller.prepare(context, release)
                        if (ApkInstaller.install(context, apk)) pendingRelease = null
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Toast.makeText(context, e.message ?: context.getString(R.string.update_install_failed), Toast.LENGTH_LONG).show()
                    } finally {
                        updateBusy = false
                    }
                    settingsStore.saveUpdateDialogAction(
                        version = release.versionTag,
                        action = UPDATE_DIALOG_ACTION_UPDATE,
                        actedAt = System.currentTimeMillis()
                    )
                }
            }
        )
    }
}

@Composable
private fun ProxyNavigationBar(
    navItems: List<NavItem>,
    selectedTab: Int,
    dragTargetIndex: Int,
    dragProgress: Float,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedColor = Brand.accent
    val unselectedColor = Brand.muted
    val indicatorIndex = remember { Animatable(selectedTab.toFloat()) }
    val visualIndex = indicatorIndex.value

    LaunchedEffect(selectedTab) {
        if (dragTargetIndex !in navItems.indices) {
            indicatorIndex.animateTo(selectedTab.toFloat(), tween(durationMillis = 220))
        }
    }

    LaunchedEffect(selectedTab, dragTargetIndex, dragProgress) {
        if (dragTargetIndex in navItems.indices) {
            indicatorIndex.snapTo(selectedTab.toFloat() + (dragTargetIndex - selectedTab) * dragProgress)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(Brand.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
    ) {
        val itemWidth = maxWidth / navItems.size
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Brand.line)
        )
        Box(
            modifier = Modifier
                .offset(x = itemWidth * visualIndex)
                .width(itemWidth)
                .height(2.dp)
                .background(selectedColor)
        )
        Row(modifier = Modifier.fillMaxWidth().height(64.dp)) {
            navItems.forEachIndexed { index, item ->
                val emphasis = (1f - abs(index - visualIndex)).coerceIn(0f, 1f)
                val tint = lerp(unselectedColor, selectedColor, emphasis)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = index == selectedTab,
                            role = androidx.compose.ui.semantics.Role.Tab,
                            onClick = { onTabSelected(index) }
                        ),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    BrandIcon(item.icon, tint = tint, size = 26.dp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * Optimized LogManager: uses a Channel + batching approach to avoid
 * creating a new list on every single log line — reduces GC pressure
 * and eliminates UI jank caused by high-frequency log updates.
 *
 * Key optimizations:
 * - Channel-based buffering: log lines are queued, not applied immediately
 * - Batch processing: up to 20 lines applied per tick (every 150ms)
 * - Array-backed list with cap of 50: avoids growing/shrinking allocations
 * - Duplicate merging: last-entry count increment done in-place conceptually
 */
object LogManager {
    val logs = MutableStateFlow<List<LogEntry>>(emptyList())
    private var job: Job? = null
    private var logcatProcess: Process? = null
    private val nextKey = AtomicLong(0)

    // Buffered channel — absorbs bursts of log lines without blocking the reader
    private val logChannel = Channel<LogEntry>(capacity = BUFFERED)

    fun startListening() {
        if (job?.isActive == true) return
        job = CoroutineScope(Dispatchers.IO).launch {
            // Start logcat reader coroutine
            val readerJob = launch(Dispatchers.IO) {
                try {
                    val pid = android.os.Process.myPid()
                    val process = ProcessBuilder("logcat", "-v", "tag", "--pid", pid.toString())
                        .redirectErrorStream(true)
                        .start()
                        
                    logcatProcess = process
                    
                    process.inputStream.bufferedReader().use { reader ->
                        while (isActive) {
                            val line = try { reader.readLine() } catch (e: Exception) { null } ?: break
                            val entry = parseLine(line) ?: continue
                            logChannel.trySend(entry)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    logcatProcess?.destroy()
                    logcatProcess = null
                }
            }

            // Batch consumer: collects queued entries and applies in batches
            launch {
                val pendingBatch = mutableListOf<LogEntry>()
                while (isActive) {
                    // Drain the channel (non-blocking)
                    var received = logChannel.tryReceive()
                    while (received.isSuccess) {
                        pendingBatch.add(received.getOrThrow())
                        if (pendingBatch.size >= 20) break // cap batch size
                        received = logChannel.tryReceive()
                    }

                    if (pendingBatch.isNotEmpty()) {
                        // Apply batch to state — single list mutation
                        logs.value = applyBatch(logs.value, pendingBatch)
                        pendingBatch.clear()
                    }

                    // Throttle updates — 150ms between UI refreshes
                    delay(150)
                }
            }

            readerJob.join()
        }
    }

    /**
     * Efficiently applies a batch of new entries to the current log list.
     * Merges consecutive duplicates and caps at 50 entries.
     */
    private fun applyBatch(current: List<LogEntry>, batch: List<LogEntry>): List<LogEntry> {
        val result = ArrayDeque(current)
        for (entry in batch) {
            var merged = false
            val searchDepth = minOf(result.size, 10)
            for (i in result.indices.reversed().take(searchDepth)) {
                if (result[i].message == entry.message) {
                    val existing = result.removeAt(i)
                    result.addLast(existing.copy(count = existing.count + 1))
                    merged = true
                    break
                }
            }
            if (!merged) {
                result.addLast(entry)
            }
        }
        while (result.size > 50) {
            result.removeFirst()
        }
        return result.toList()
    }

    fun stopListening() {
        job?.cancel()
        job = null
        logcatProcess?.destroy()
        logcatProcess = null
    }

    fun clearLogs() {
        logs.value = emptyList()
    }

    private fun parseLine(raw: String): LogEntry? {
        var message: String
        val isError: Boolean
        val priority: Int

        when {
            raw.contains("[ERROR]") -> {
                message = raw.substringAfter("[ERROR]").trim()
                isError = true
                priority = 6 // Log.ERROR
            }
            raw.contains("[WARN]") -> {
                message = raw.substringAfter("[WARN]").trim()
                isError = false // WARN is not ERROR, but distinctive
                priority = 5 // Log.WARN
            }
            raw.contains("[DEBUG]") -> {
                message = raw.substringAfter("[DEBUG]").trim()
                isError = false
                priority = 3 // Log.DEBUG
            }
            raw.contains("TgWsProxy") -> {
                // Info doesn't have a prefix, so we strip basically everything up to the actual message
                var msg = raw.substringAfter("TgWsProxy:").trim()
                if (msg.startsWith("[ERROR]") || msg.startsWith("[WARN]") || msg.startsWith("[DEBUG]")) {
                     return null // Handled above, but just in case
                }

                // Strip dynamic metrics like ↑3.3KB ↓1.1KB 0.3с so that lines can collapse
                if (msg.contains("↑")) {
                    msg = msg.substringBefore("↑").trim()
                }
                if (msg.contains("↓")) {
                    msg = msg.substringBefore("↓").trim()
                }

                message = msg
                isError = false
                priority = 4 // Log.INFO
            }
            else -> return null
        }

        // Remove emojis and stickers
        val emojiRegex = Regex("[\\x{1F300}-\\x{1F5FF}\\x{1F900}-\\x{1F9FF}\\x{1F600}-\\x{1F64F}\\x{1F680}-\\x{1F6FF}\\x{2600}-\\x{26FF}\\x{2700}-\\x{27BF}\\x{1F1E6}-\\x{1F1FF}\\x{1F191}-\\x{1F251}\\x{1F004}\\x{1F0CF}\\x{1F170}-\\x{1F171}\\x{1F17E}-\\x{1F17F}\\x{1F18E}\\x{3030}\\x{2B50}\\x{2B55}\\x{2934}-\\x{2935}\\x{2B05}-\\x{2B07}\\x{2B1B}-\\x{2B1C}\\x{3297}\\x{3299}\\x{303D}\\x{00A9}\\x{00AE}\\x{2122}\\x{23F3}\\x{24C2}\\x{23E9}-\\x{23EF}\\x{25B6}\\x{23F8}-\\x{23FA}⚠✅❌⚡🔥🔄🔗]")
        message = message.replace(emojiRegex, "").trim()
        // The core's startup banner frames itself with rulers; they say nothing in a log view.
        if (message.isEmpty() || message.all { it == '=' || it == '-' }) return null

        val isEssential = listOf(
            "pool", "key:", "started", "address:", "error", "failed", "blocked",
            "Пул", "Ключ:", "запущен", "Адрес:", "ошибка", "провалены", "заблокирован"
        ).any { marker -> message.contains(marker, ignoreCase = true) }

        return LogEntry(
            key = "log_${nextKey.getAndIncrement()}",
            message = message,
            count = 1,
            isError = isError,
            priority = priority,
            isEssential = isEssential
        )
    }}
