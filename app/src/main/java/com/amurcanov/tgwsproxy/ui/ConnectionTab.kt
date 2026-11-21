package com.amurcanov.tgwsproxy.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amurcanov.tgwsproxy.BuildConfig
import com.amurcanov.tgwsproxy.DpiSession
import com.amurcanov.tgwsproxy.ProxyController
import com.amurcanov.tgwsproxy.ProxyService
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.SettingsStore
import com.amurcanov.tgwsproxy.TelegramConnector
import com.amurcanov.tgwsproxy.dpi.DpiMode
import com.amurcanov.tgwsproxy.dpi.DpiState
import com.amurcanov.tgwsproxy.dpi.VpnPrepareContract
import com.amurcanov.tgwsproxy.dpi.VpnRefuseReason
import androidx.compose.runtime.saveable.rememberSaveable
import com.amurcanov.tgwsproxy.dpi.VpnSlotState
import com.amurcanov.tgwsproxy.dpi.VpnReplacement
import com.amurcanov.tgwsproxy.ui.dpi.DpiStrategyPickerDialog
import com.amurcanov.tgwsproxy.ui.dpi.DpiStrategyTestDialog
import com.amurcanov.tgwsproxy.dpi.VpnStartDecision
import kotlinx.coroutines.launch

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

    // ByeByeDPI module: the controller is the only source of the running verdict.
    val dpiState by DpiSession.state.collectAsStateWithLifecycle()
    val dpiMode by DpiSession.mode.collectAsStateWithLifecycle()
    val dpiEndpoint by DpiSession.endpoint.collectAsStateWithLifecycle()

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
        isVerifiedRunning -> CursorVisualState.On
        isStarting || isRunning -> CursorVisualState.Connecting
        else -> CursorVisualState.Off
    }
    val statusText = when (cursorState) {
        CursorVisualState.On -> stringResource(R.string.status_connected)
        CursorVisualState.Connecting -> stringResource(R.string.status_connecting)
        CursorVisualState.Off -> stringResource(R.string.status_disconnected)
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

    // --- ByeByeDPI state mapping and command flow ---------------------------
    val dpiIsActive = dpiState is DpiState.Running || dpiState is DpiState.Starting || dpiState is DpiState.Stopping
    val dpiVisual = when (dpiState) {
        is DpiState.Running -> CursorVisualState.On
        is DpiState.Starting, is DpiState.Stopping -> CursorVisualState.Connecting
        is DpiState.Off, is DpiState.Failed -> CursorVisualState.Off
    }
    val dpiStatusText = when (dpiState) {
        is DpiState.Running ->
            if (dpiMode == DpiMode.VPN) stringResource(R.string.dpi_status_vpn_running)
            else stringResource(R.string.dpi_status_socks5_running)
        is DpiState.Starting, is DpiState.Stopping -> stringResource(R.string.status_connecting)
        is DpiState.Failed -> stringResource(R.string.status_disconnected)
        is DpiState.Off -> stringResource(R.string.status_disconnected)
    }
    val dpiAddressText = when {
        dpiState is DpiState.Running ->
            if (dpiMode == DpiMode.VPN) stringResource(R.string.dpi_mode_vpn)
            else dpiEndpoint ?: stringResource(R.string.dpi_mode_socks5)
        dpiState is DpiState.Failed -> (dpiState as DpiState.Failed).reason
        dpiMode == DpiMode.VPN -> stringResource(R.string.dpi_start_hint_vpn)
        else -> stringResource(R.string.dpi_start_hint_socks5)
    }
    val dpiActionDescription = if (dpiIsActive) stringResource(R.string.dpi_action_stop)
    else if (dpiMode == DpiMode.VPN) stringResource(R.string.dpi_action_start_vpn)
    else stringResource(R.string.dpi_action_start_socks5)

    // Immutable replacement proof minted before the offer dialog is shown; reused
    // on confirm, never re-minted against a freshly sampled VPN.
    var pendingOffer by remember { mutableStateOf<Triple<Boolean, VpnReplacement, String>?>(null) }
    var pendingConsentAttempt by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingConsentSlot by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingConsentNetworks by rememberSaveable { mutableStateOf<LongArray?>(null) }
    var showStrategyPicker by remember { mutableStateOf(false) }
    var showStrategyTest by rememberSaveable { mutableStateOf(false) }

    // Strings resolved once in composition (stringResource is @Composable), then
    // read by the non-composable command lambdas below.
    val msgConsentLost = stringResource(R.string.dpi_failed, "consent lost")
    val msgLockdown = stringResource(R.string.dpi_failed, "always-on VPN or lockdown")
    val msgSlotChanged = stringResource(R.string.dpi_failed, "VPN slot changed")
    val msgUnavailable = stringResource(R.string.dpi_failed, "VPN slot unavailable")
    val msgOfferUncertain = stringResource(R.string.vpn_offer_body_uncertain)
    val msgConsentDenied = stringResource(R.string.vpn_consent_denied)

    val showDpiError: (String) -> Unit = { message ->
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    // Assigned below once the command lambdas exist; the consent callback
    // re-enters startVpn only after the launcher is available.
    lateinit var prepareConsentLauncher: androidx.activity.result.ActivityResultLauncher<Unit>

    val handleRefusal: (VpnRefuseReason, VpnReplacement?, Boolean, String) -> Unit = { reason, proof, canPrompt, attempt ->
        when (reason) {
            VpnRefuseReason.CONSENT_NOT_GRANTED -> if (canPrompt) {
                pendingConsentSlot = proof?.confirmedSlot?.name
                pendingConsentNetworks = proof?.confirmedNetworks?.toLongArray()
                pendingConsentAttempt = attempt
                prepareConsentLauncher.launch(Unit)
            } else showDpiError(msgConsentLost)
            VpnRefuseReason.CONSENT_LOST -> showDpiError(msgConsentLost)
            VpnRefuseReason.LOCKDOWN -> showDpiError(msgLockdown)
            VpnRefuseReason.SLOT_CHANGED -> showDpiError(msgSlotChanged)
            VpnRefuseReason.OBSERVATION_UNAVAILABLE -> showDpiError(msgUnavailable)
        }
    }

    val startVpn: (VpnReplacement?, Boolean, String) -> Unit = start@{ replacement, canPrompt, attempt ->
        if (!DpiSession.isInteractiveStartCurrent(attempt)) return@start
        val authorizer = DpiSession.vpnAuthorizer(context)
        when (val decision = authorizer.authorize(replacement)) {
            is VpnStartDecision.Start -> DpiSession.start(context, replacement)
            is VpnStartDecision.Offer -> {
                // Capture the proof bound to the *currently observed* VPN before
                // showing the offer; a proof is never minted after confirmation.
                val proof = runCatching { authorizer.confirmReplacement() }.getOrNull()
                if (proof == null) {
                    showDpiError(msgOfferUncertain)
                } else {
                    pendingOffer = Triple(decision.ownerUncertain, proof, attempt)
                }
            }
            is VpnStartDecision.Refuse -> handleRefusal(decision.reason, replacement, canPrompt, attempt)
        }
    }

    val startDpi: () -> Unit = {
        if (dpiMode == DpiMode.VPN) startVpn(null, true, DpiSession.beginInteractiveStart()) else DpiSession.start(context)
    }

    val onToggleDpi: () -> Unit = {
        if (dpiIsActive || pendingConsentAttempt != null || pendingOffer != null) {
            pendingOffer = null
            DpiSession.stop(context)
        } else startDpi()
    }

    prepareConsentLauncher = rememberLauncherForActivityResult(VpnPrepareContract()) { granted ->
        val attempt = pendingConsentAttempt
        val proof = pendingConsentSlot?.let { slot ->
            VpnReplacement(VpnSlotState.valueOf(slot), pendingConsentNetworks?.toSet() ?: emptySet())
        }
        pendingConsentSlot = null
        pendingConsentNetworks = null
        pendingConsentAttempt = null
        if (DpiSession.isInteractiveStartCurrent(attempt)) {
            if (granted) startVpn(proof, false, checkNotNull(attempt)) else {
                DpiSession.cancelInteractiveStart()
                showDpiError(msgConsentDenied)
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
        ScreenTitle(stringResource(R.string.app_name))
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.proxy_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = Brand.muted
        )

        DualModuleControls(
            tg = ModuleControls(
                moduleLabel = "TG-WS",
                visualState = cursorState,
                actionDescription = stringResource(if (isActiveVisual) R.string.action_stop_proxy else R.string.action_start_proxy),
                statusText = statusText,
                addressText = activeConnection?.let { "${TelegramConnector.localHost(it.host)}:${it.port}" }
                    ?: stringResource(R.string.proxy_start_hint),
                onToggle = toggle
            ),
            dpi = ModuleControls(
                moduleLabel = "ByeByeDPI",
                visualState = dpiVisual,
                actionDescription = dpiActionDescription,
                statusText = dpiStatusText,
                addressText = dpiAddressText,
                onToggle = { onToggleDpi() }
            ),
            applyInTelegramEnabled = activeConnection != null,
            onApplyInTelegram = { activeConnection?.let { TelegramConnector.open(context, it) } }
        )

        BrandLink(
            text = stringResource(R.string.dpi_change_strategy),
            onClick = { showStrategyPicker = true },
            modifier = Modifier.padding(bottom = 8.dp)
        )

        BrandLink(
            text = stringResource(R.string.dpi_strategy_test),
            onClick = { showStrategyTest = true },
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (showAccessWarning) {
            AccessWarningRow(onClick = onShowAccess, modifier = Modifier.padding(bottom = 10.dp))
        }

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

    // VPN replacement offer: the proof was captured before this dialog, so the
    // confirm action only re-validates and reuses it.
    pendingOffer?.let { (ownerUncertain, proof, attempt) ->
        BrandDialog(
            onDismiss = { pendingOffer = null; DpiSession.cancelInteractiveStart() },
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            Text(
                stringResource(R.string.vpn_offer_title),
                style = MaterialTheme.typography.titleLarge,
                color = Brand.ink
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(if (ownerUncertain) R.string.vpn_offer_body_uncertain else R.string.vpn_offer_body),
                style = MaterialTheme.typography.bodyMedium,
                color = Brand.muted
            )
            Spacer(Modifier.height(16.dp))
            BrandButton(
                text = stringResource(R.string.vpn_offer_replace),
                onClick = {
                    pendingOffer = null
                    startVpn(proof, true, attempt)
                }
            )
            Spacer(Modifier.height(8.dp))
            BrandOutlineButton(
                text = stringResource(R.string.vpn_offer_socks5),
                onClick = {
                    pendingOffer = null
                    if (DpiSession.isInteractiveStartCurrent(attempt) && DpiSession.switchMode(context, DpiMode.SOCKS5)) DpiSession.start(context)
                }
            )
            Spacer(Modifier.height(8.dp))
            BrandLink(
                text = stringResource(R.string.vpn_offer_cancel),
                onClick = { pendingOffer = null; DpiSession.cancelInteractiveStart() }
            )
        }
    }

    if (showStrategyTest) DpiStrategyTestDialog(onDismiss = { showStrategyTest = false })

    if (showStrategyPicker) {
        DpiStrategyPickerDialog(onDismiss = { showStrategyPicker = false })
    }
}

@Composable
private fun ProxyLinkRow(proxyUrl: String, context: Context) {
    val copiedText = stringResource(R.string.copied)
    Surface(
        shape = RectangleShape,
        color = androidx.compose.ui.graphics.Color.Transparent,
        border = BorderStroke(1.dp, Brand.line),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
    ) {
        androidx.compose.foundation.layout.Row(
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
