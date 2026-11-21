package com.amurcanov.tgwsproxy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.ServiceSelection

@Composable
internal fun DualModuleControls(
    selection: ServiceSelection,
    onSelectionChange: (ServiceSelection) -> Unit,
    visualState: CursorVisualState,
    status: String,
    powerDescription: String,
    onPower: () -> Unit,
    showVpnWarning: Boolean,
    socksMode: Boolean,
    applyInTelegramEnabled: Boolean,
    onApplyInTelegram: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        ServiceRow(stringResource(R.string.home_telegram), stringResource(R.string.home_tg_subtitle), stringResource(R.string.home_proxy), selection.telegram) { onSelectionChange(selection.copy(telegram = it)) }
        ServiceRow(stringResource(R.string.home_youtube), stringResource(R.string.home_vpn_subtitle), stringResource(R.string.home_vpn), selection.youtube) { onSelectionChange(selection.copy(youtube = it)) }
        ServiceRow(stringResource(R.string.home_discord), stringResource(R.string.home_vpn_subtitle), stringResource(R.string.home_vpn), selection.discord) { onSelectionChange(selection.copy(discord = it)) }
        if (selection.needsDpi && !socksMode) {
            Text(stringResource(R.string.home_vpn_selection_warning), Modifier.padding(vertical = 8.dp), color = Brand.alert, style = MaterialTheme.typography.bodyMedium)
        }
        if (showVpnWarning) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).border(1.dp, Brand.line)) {
                Spacer(Modifier.width(4.dp).heightIn(min = 64.dp).background(Brand.alert))
                Text(stringResource(R.string.home_vpn_warning), Modifier.padding(12.dp), color = Brand.alert, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (socksMode && selection.needsDpi) Text(stringResource(R.string.home_socks_warning), Modifier.padding(vertical = 8.dp), color = Brand.alert, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            CursorSwitch(visualState, powerDescription, status, onPower)
            Text(status, color = if (visualState == CursorVisualState.On) Brand.accent else Brand.muted, style = MaterialTheme.typography.bodyMedium)
        }
        BrandButton(text = stringResource(R.string.apply_in_telegram), onClick = onApplyInTelegram, enabled = applyInTelegramEnabled, trailing = "→")
    }
}

@Composable
private fun ServiceRow(name: String, subtitle: String, tag: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onChecked).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(24.dp).border(2.dp, Brand.accent).background(if (checked) Brand.accent else Brand.bg), contentAlignment = Alignment.Center) {
            if (checked) Text("✓", color = Brand.bg, style = MaterialTheme.typography.labelMedium)
        }
        Column(Modifier.weight(1f)) {
            Text(name, color = Brand.ink, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, color = Brand.muted, style = MaterialTheme.typography.bodyMedium)
        }
        MonoLabel(tag)
    }
}
