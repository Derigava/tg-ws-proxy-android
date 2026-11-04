package com.amurcanov.tgwsproxy.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amurcanov.tgwsproxy.BuildConfig
import com.amurcanov.tgwsproxy.ProxyController
import com.amurcanov.tgwsproxy.ProxyService
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.SettingsStore
import com.amurcanov.tgwsproxy.TelegramConnector
import kotlinx.coroutines.launch

private enum class CursorState { Off, Connecting, On }

@Composable
fun ConnectionTab(
    settingsStore: SettingsStore,
    showAccessWarning: Boolean = false,
    onShowAccess: () -> Unit = {},
) {
    val context = LocalContext.current
    val isRunning by ProxyService.isRunning.collectAsStateWithLifecycle()
    val isVerifiedRunning by ProxyService.isVerifiedRunning.collectAsStateWithLifecycle()
    val activeConnection by ProxyService.activeConnection.collectAsStateWithLifecycle()

    val isReady by settingsStore.isReady.collectAsStateWithLifecycle(initialValue = false)

    // Settings
    val savedPort by settingsStore.port.collectAsStateWithLifecycle(initialValue = "1443")
    val savedCfEnabled by settingsStore.cfproxyEnabled.collectAsStateWithLifecycle(initialValue = true)
    val savedPoolSize by settingsStore.poolSize.collectAsStateWithLifecycle(initialValue = 4)
    val savedSecretKey by settingsStore.secretKey.collectAsStateWithLifecycle(initialValue = "LOADING")

    val scope = rememberCoroutineScope()

    if (!isReady || savedSecretKey == "LOADING") {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                color = Brand.accent,
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp
            )
        }
        return
    }

    // Auto-generate secret if empty
    LaunchedEffect(savedSecretKey) {
        if (savedSecretKey == "") {
            val bytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(bytes)
            val generated = bytes.joinToString("") { "%02x".format(it) }
            scope.launch { settingsStore.saveSecretKey(generated) }
        }
    }

    val isStarting by ProxyService.isStarting.collectAsStateWithLifecycle()
    val cursorState = when {
        isVerifiedRunning -> CursorState.On
        isStarting || isRunning -> CursorState.Connecting
        else -> CursorState.Off
    }
    val statusText = when (cursorState) {
        CursorState.On -> stringResource(R.string.status_connected)
        CursorState.Connecting -> stringResource(R.string.status_connecting)
        CursorState.Off -> stringResource(R.string.status_disconnected)
    }

    val proxyUrl = activeConnection?.let { TelegramConnector.proxyUri(it).toString() }.orEmpty()
    val isActiveVisual = isRunning || isStarting
    val toggle: () -> Unit = {
        if (isActiveVisual) {
            ProxyController.stop(context)
        } else {
            scope.launch {
                ProxyController.startFromSavedSettings(context = context, showInvalidPortToast = true)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp)
    ) {
        BrandHeader(section = stringResource(R.string.nav_proxy))
        Spacer(Modifier.height(8.dp))
        ScreenTitle("TG WS Proxy")
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.proxy_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = Brand.muted
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 20.dp)
                .height(CursorHeight),
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            CursorSwitch(
                state = cursorState,
                description = stringResource(if (isActiveVisual) R.string.action_stop_proxy else R.string.action_start_proxy),
                stateText = statusText,
                onClick = toggle
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .height(CursorHeight),
                verticalArrangement = Arrangement.Bottom
            ) {
                val statusColor by animateColorAsState(
                    targetValue = if (cursorState == CursorState.On) Brand.accent else Brand.muted,
                    label = "connection_status_color"
                )
                MonoLabel(statusText, color = statusColor)
                Spacer(Modifier.height(8.dp))
                val connection = activeConnection
                if (connection != null) {
                    Text(
                        text = "${TelegramConnector.localHost(connection.host)}:${connection.port}",
                        style = MonoValueStyle,
                        color = Brand.ink
                    )
                } else {
                    Text(
                        text = stringResource(R.string.proxy_start_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Brand.muted
                    )
                }
            }
        }

        if (showAccessWarning) {
            AccessWarningRow(onClick = onShowAccess, modifier = Modifier.padding(bottom = 10.dp))
        }

        BrandButton(
            text = stringResource(R.string.apply_in_telegram),
            onClick = { activeConnection?.let { TelegramConnector.open(context, it) } },
            enabled = activeConnection != null,
            trailing = "→"
        )

        activeConnection?.let { connection ->
            BrandLink(
                text = stringResource(R.string.choose_other_client),
                onClick = { TelegramConnector.open(context, connection, chooseAgain = true) }
            )
        } ?: Spacer(Modifier.height(16.dp))

        val modeValue = if (savedCfEnabled) "CF" else stringResource(R.string.direct_mode)
        val connection = activeConnection
        KeyValueTable(
            rows = buildList {
                add(stringResource(R.string.row_mode) to modeValue)
                add(stringResource(R.string.row_pool) to "$savedPoolSize")
                add(stringResource(R.string.row_port) to (connection?.port?.toString() ?: savedPort))
                if (connection != null) add(stringResource(R.string.row_secret) to connection.secret)
                add(stringResource(R.string.row_version) to BuildConfig.VERSION_NAME)
                add(stringResource(R.string.row_core) to BuildConfig.CORE_VERSION)
            }
        )

        Spacer(Modifier.height(10.dp))
        ProxyLinkRow(proxyUrl = proxyUrl, context = context)
    }
}

// Proportions of the cursor in the mark.
private val CursorHeight = 148.dp

@Composable
private fun CursorSwitch(
    state: CursorState,
    description: String,
    stateText: String,
    onClick: () -> Unit,
) {
    val blink = rememberInfiniteTransition(label = "cursor_blink").animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1060
                1f at 0 using LinearEasing
                1f at 529 using LinearEasing
                0f at 530 using LinearEasing
                0f at 1059 using LinearEasing
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "cursor_blink_alpha"
    )
    val fill = when (state) {
        CursorState.On -> Brand.accent
        CursorState.Connecting -> Brand.accent.copy(alpha = blink.value)
        CursorState.Off -> Color.Transparent
    }
    // The glyph stays readable on whatever is behind it right now, including mid-blink.
    val isFilled = state == CursorState.On || (state == CursorState.Connecting && blink.value > 0.5f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(88.dp, CursorHeight)
            .semantics {
                contentDescription = description
                stateDescription = stateText
            }
            .clickable(role = Role.Button, onClick = onClick)
            .background(fill)
            .border(2.dp, Brand.accent)
    ) {
        BrandIcon(R.drawable.ic_power, tint = if (isFilled) Brand.bg else Brand.accent, size = 48.dp)
    }
}

@Composable
private fun ProxyLinkRow(proxyUrl: String, context: Context) {
    val copiedText = stringResource(R.string.copied)
    Surface(
        shape = RectangleShape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, Brand.line),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 4.dp)
        ) {
            Text(
                text = proxyUrl.ifEmpty { stringResource(R.string.proxy_link_hint) },
                style = MonoLabelStyle,
                color = Brand.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 14.dp)
            )
            if (proxyUrl.isNotEmpty()) {
                MonoAction(
                    text = stringResource(R.string.copy),
                    onClick = {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cb.setPrimaryClip(android.content.ClipData.newPlainText("Proxy", proxyUrl))
                        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}
