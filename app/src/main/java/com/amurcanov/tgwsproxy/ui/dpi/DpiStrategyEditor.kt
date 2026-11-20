package com.amurcanov.tgwsproxy.ui.dpi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.dpi.DpiSettingsStore
import com.amurcanov.tgwsproxy.ui.BrandSegmented
import com.amurcanov.tgwsproxy.ui.BrandField
import com.amurcanov.tgwsproxy.ui.BrandMultilineField
import com.amurcanov.tgwsproxy.ui.SectionLabel
import com.amurcanov.tgwsproxy.ui.SwitchRow

private fun DpiSettingsStore.string(key: String, default: String) =
    (currentSettings()[key] as? String) ?: default

private fun DpiSettingsStore.bool(key: String, default: Boolean) =
    (currentSettings()[key] as? Boolean) ?: default

private fun DpiSettingsStore.int(key: String, default: Int) =
    ((currentSettings()[key] as? Number)?.toInt()) ?: default

/**
 * Standard desync strategy editor (parity rows U-01..U-26). Each edit is
 * validated and persisted through [DpiSettingsStore.updateSettings] so an
 * invalid value never partially mutates the stored document.
 */
@Composable
internal fun DpiStrategyEditor() {
    val context = LocalContext.current
    val store = remember(context) { DpiSettingsStore(context) }

    var method by remember { mutableStateOf(store.string("byedpi_desync_method", "oob")) }
    var splitPosition by remember { mutableStateOf(store.int("byedpi_split_position", 1).toString()) }
    var splitAtHost by remember { mutableStateOf(store.bool("byedpi_split_at_host", false)) }
    var desyncHttp by remember { mutableStateOf(store.bool("byedpi_desync_http", true)) }
    var desyncHttps by remember { mutableStateOf(store.bool("byedpi_desync_https", true)) }
    var desyncUdp by remember { mutableStateOf(store.bool("byedpi_desync_udp", true)) }
    var hostsMode by remember { mutableStateOf(store.string("byedpi_hosts_mode", "disable")) }
    var hostsBlacklist by remember { mutableStateOf(store.string("byedpi_hosts_blacklist", "")) }
    var hostsWhitelist by remember { mutableStateOf(store.string("byedpi_hosts_whitelist", "")) }
    var defaultTtl by remember { mutableStateOf(store.int("byedpi_default_ttl", 0).toString()) }
    var noDomain by remember { mutableStateOf(store.bool("byedpi_no_domain", false)) }
    var tcpFastOpen by remember { mutableStateOf(store.bool("byedpi_tcp_fast_open", false)) }
    var dropSack by remember { mutableStateOf(store.bool("byedpi_drop_sack", false)) }

    fun persist(key: String, value: Any?) {
        runCatching { store.updateSettings(mapOf(key to value)) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier) {
        SectionLabel(stringResource(R.string.dpi_strategy_desync))
        BrandSegmented(
            options = listOf("none", "split", "disorder", "fake", "oob", "disoob").map { it to it },
            selected = method,
            onSelect = { method = it; persist("byedpi_desync_method", it) },
        )

        SectionLabel(stringResource(R.string.dpi_strategy_split))
        BrandField(
            value = splitPosition,
            onValueChange = { filtered ->
                val digits = filtered.filter(Char::isDigit)
                splitPosition = digits
                digits.toIntOrNull()?.let { persist("byedpi_split_position", it) }
            },
            placeholder = stringResource(R.string.dpi_strategy_split_position),
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
        )
        SwitchRow(
            title = stringResource(R.string.dpi_strategy_split_at_host),
            checked = splitAtHost,
            onCheckedChange = { splitAtHost = it; persist("byedpi_split_at_host", it) },
        )

        SectionLabel(stringResource(R.string.dpi_strategy_protocols))
        SwitchRow(
            title = stringResource(R.string.dpi_strategy_desync_http),
            checked = desyncHttp,
            onCheckedChange = { desyncHttp = it; persist("byedpi_desync_http", it) },
        )
        SwitchRow(
            title = stringResource(R.string.dpi_strategy_desync_https),
            checked = desyncHttps,
            onCheckedChange = { desyncHttps = it; persist("byedpi_desync_https", it) },
        )
        SwitchRow(
            title = stringResource(R.string.dpi_strategy_desync_udp),
            checked = desyncUdp,
            onCheckedChange = { desyncUdp = it; persist("byedpi_desync_udp", it) },
        )

        SectionLabel(stringResource(R.string.dpi_strategy_hosts))
        BrandSegmented(
            options = listOf("disable", "blacklist", "whitelist").map { it to it },
            selected = hostsMode,
            onSelect = { hostsMode = it; persist("byedpi_hosts_mode", it) },
        )
        if (hostsMode == "blacklist") {
            BrandMultilineField(
                value = hostsBlacklist,
                onValueChange = { hostsBlacklist = it; persist("byedpi_hosts_blacklist", it) },
                placeholder = stringResource(R.string.dpi_strategy_hosts_blacklist),
            )
        } else if (hostsMode == "whitelist") {
            BrandMultilineField(
                value = hostsWhitelist,
                onValueChange = { hostsWhitelist = it; persist("byedpi_hosts_whitelist", it) },
                placeholder = stringResource(R.string.dpi_strategy_hosts_whitelist),
            )
        }

        SectionLabel(stringResource(R.string.dpi_strategy_other))
        BrandField(
            value = defaultTtl,
            onValueChange = { filtered ->
                val digits = filtered.filter(Char::isDigit)
                defaultTtl = digits
                digits.toIntOrNull()?.let { persist("byedpi_default_ttl", it) }
            },
            placeholder = stringResource(R.string.dpi_strategy_default_ttl),
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
        )
        SwitchRow(
            title = stringResource(R.string.dpi_strategy_no_domain),
            checked = noDomain,
            onCheckedChange = { noDomain = it; persist("byedpi_no_domain", it) },
        )
        SwitchRow(
            title = stringResource(R.string.dpi_strategy_tcp_fast_open),
            checked = tcpFastOpen,
            onCheckedChange = { tcpFastOpen = it; persist("byedpi_tcp_fast_open", it) },
        )
        SwitchRow(
            title = stringResource(R.string.dpi_strategy_drop_sack),
            checked = dropSack,
            onCheckedChange = { dropSack = it; persist("byedpi_drop_sack", it) },
        )
    }
}
