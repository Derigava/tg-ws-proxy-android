package com.amurcanov.tgwsproxy.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.amurcanov.tgwsproxy.R

/**
 * Visual state the switch draws. Both modules map their own lifecycle onto this
 * so the pair shares one geometry, animation and indication rule. [On] is the
 * only filled/steady state; [Connecting] blinks and is never presented as a
 * running engine.
 */
enum class CursorVisualState { Off, Connecting, On }

// Proportions of the cursor in the mark, kept identical to the original switch.
internal val CursorHeight = 148.dp

/**
 * The original module switch, extracted verbatim from `ConnectionTab.kt` so it
 * can be reused for both TG-WS and ByeByeDPI without changing its shape or
 * animation. The accessible name is [description] (the action the button will
 * take) and the accessible state is [stateText], so each module exposes its own
 * name and status independently.
 */
@Composable
fun CursorSwitch(
    state: CursorVisualState,
    description: String,
    stateText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
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
        CursorVisualState.On -> Brand.accent
        CursorVisualState.Connecting -> Brand.accent.copy(alpha = blink.value)
        CursorVisualState.Off -> Color.Transparent
    }
    // The glyph stays readable on whatever is behind it right now, including mid-blink.
    val isFilled = state == CursorVisualState.On || (state == CursorVisualState.Connecting && blink.value > 0.5f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
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
