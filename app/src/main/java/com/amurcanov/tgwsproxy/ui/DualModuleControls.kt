package com.amurcanov.tgwsproxy.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.amurcanov.tgwsproxy.R

/**
 * One module's visible state and command. The switch's accessible name is
 * [actionDescription], its accessible state is [statusText], and the identity of
 * the module is [moduleLabel]; all three are separate per module so toggling one
 * never describes or commands the other.
 */
class ModuleControls(
    val moduleLabel: String,
    val visualState: CursorVisualState,
    val actionDescription: String,
    val statusText: String,
    val addressText: String,
    val onToggle: () -> Unit,
)

/**
 * The paired main-screen controls: TG-WS on the left, ByeByeDPI on the right,
 * each with its own switch, status and address underneath. "Apply in Telegram"
 * stays a TG-WS action below the pair. The current text that used to sit to the
 * right of the single switch is relocated under its module, and the module
 * order never changes on narrow screens — labels wrap instead.
 */
@Composable
fun DualModuleControls(
    tg: ModuleControls,
    dpi: ModuleControls,
    applyInTelegramEnabled: Boolean,
    onApplyInTelegram: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            ModuleControl(tg, Modifier.weight(1f))
            ModuleControl(dpi, Modifier.weight(1f))
        }

        BrandButton(
            text = stringResource(R.string.apply_in_telegram),
            onClick = onApplyInTelegram,
            enabled = applyInTelegramEnabled,
            trailing = "→"
        )
    }
}

@Composable
private fun ModuleControl(module: ModuleControls, modifier: Modifier = Modifier) {
    val statusColor by animateColorAsState(
        targetValue = if (module.visualState == CursorVisualState.On) Brand.accent else Brand.muted,
        label = "module_status_color"
    )
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MonoLabel(module.moduleLabel)
        Spacer(Modifier.height(6.dp))
        CursorSwitch(
            state = module.visualState,
            description = module.actionDescription,
            stateText = module.statusText,
            onClick = module.onToggle
        )
        Spacer(Modifier.height(8.dp))
        MonoLabel(module.statusText, color = statusColor)
        Spacer(Modifier.height(4.dp))
        Text(
            text = module.addressText,
            style = MaterialTheme.typography.bodyMedium,
            color = Brand.muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
