package com.amurcanov.tgwsproxy.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.amurcanov.tgwsproxy.R

/** Top of every screen: the mark on the left, where we are on the right, as in the brand's templates. */
@Composable
fun BrandHeader(section: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(if (Brand.isDark) R.drawable.wordmark_dark else R.drawable.wordmark_light),
            contentDescription = "derigava_",
            modifier = Modifier.height(20.dp)
        )
        MonoLabel("TECH / ${section.uppercase()}")
    }
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.headlineMedium,
        color = Brand.ink
    )
}

@Composable
fun MonoLabel(text: String, modifier: Modifier = Modifier, color: Color = Brand.muted) {
    Text(text = text, modifier = modifier, style = MonoLabelStyle, color = color)
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    MonoLabel("> ${text.uppercase()}", modifier.padding(top = 24.dp, bottom = 8.dp))
}

@Composable
fun BrandIcon(@DrawableRes icon: Int, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Icon(painter = painterResource(icon), contentDescription = null, tint = tint, modifier = modifier.size(size))
}

/** Main action. Only one per screen: the accent fill marks what to press next. */
@Composable
fun BrandButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailing: String? = null,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RectangleShape,
        color = if (enabled) Brand.fill else Brand.surface,
        contentColor = if (enabled) Brand.onFill else Brand.muted,
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (trailing != null && enabled) Text(trailing, style = MonoValueStyle)
        }
    }
}

/** Secondary action: a frame instead of a fill. */
@Composable
fun BrandOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RectangleShape,
        color = Color.Transparent,
        contentColor = if (enabled) Brand.accent else Brand.muted,
        border = BorderStroke(1.dp, if (enabled) Brand.accent else Brand.line),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(text, style = MaterialTheme.typography.titleSmall)
        }
    }
}

/** Compact mono action inside a row, for example КОПИРОВАТЬ. Keeps a full touch target. */
@Composable
fun MonoAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp)
    ) {
        MonoLabel(text.uppercase(), color = if (enabled) Brand.accent else Brand.muted)
    }
}

@Composable
fun BrandLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Brand.accent)
    }
}

/** A rectangular switch: Material's pill and circle have no place in a terminal. */
@Composable
fun BrandSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val thumbOffset by animateDpAsState(if (checked) 23.dp else 3.dp, label = "switch_thumb")
    val active = if (checked) Brand.accent else Brand.muted
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .minimumInteractiveComponentSize()
            .alpha(if (enabled) 1f else 0.5f)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp, 24.dp)
                .border(1.dp, if (checked) Brand.accent else Brand.line)
        ) {
            Box(
                modifier = Modifier
                    .offset(x = thumbOffset, y = 3.dp)
                    .size(16.dp)
                    .background(active)
            )
        }
    }
}

/** Title on the left, switch on the right. The whole row keeps the switch's semantics. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) Brand.ink else Brand.muted,
            modifier = Modifier.weight(1f)
        )
        BrandSwitch(checked, onCheckedChange, enabled = enabled)
    }
}

/** One choice out of a few. The selected cell is framed in the accent and bolder, not only recoloured. */
@Composable
fun <T> BrandSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEach { (value, label) ->
            SegmentCell(
                label = label,
                selected = value == selected,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                selectable = true,
                onClick = { onSelect(value) }
            )
        }
    }
}

/** Same cell for independent on/off filters, where several can be active at once. */
@Composable
fun SegmentCell(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selectable: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val base = if (selectable) {
        Modifier.selectable(
            selected = selected,
            enabled = enabled,
            role = Role.RadioButton,
            interactionSource = interaction,
            indication = androidx.compose.material3.ripple(),
            onClick = onClick
        )
    } else {
        Modifier.toggleable(
            value = selected,
            enabled = enabled,
            role = Role.Checkbox,
            interactionSource = interaction,
            indication = androidx.compose.material3.ripple(),
            onValueChange = { onClick() }
        )
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = 48.dp)
            .background(Brand.surface)
            .then(if (selected) Modifier.border(1.dp, if (enabled) Brand.accent else Brand.line) else Modifier)
            .then(base)
            .alpha(if (enabled) 1f else 0.5f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Brand.accent else Brand.muted
        )
    }
}

/** Text input on a surface plate; the accent frame shows where the focus is. */
@Composable
fun BrandField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    textStyle: TextStyle = MonoValueStyle,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        interactionSource = interaction,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = textStyle.copy(color = if (enabled) Brand.ink else Brand.muted),
        cursorBrush = SolidColor(Brand.accent),
        modifier = modifier.heightIn(min = 48.dp),
        decorationBox = { inner ->
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .background(Brand.surface)
                    .then(if (focused) Modifier.border(1.dp, Brand.accent) else Modifier)
                    .padding(horizontal = 12.dp, vertical = 14.dp)
            ) {
                inner()
            }
        }
    )
}

/** Mono label and value, separated by the screen background so the table reads as rows. */
@Composable
fun KeyValueTable(rows: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        rows.forEach { (key, value) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brand.surface)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                MonoLabel(key.uppercase())
                Text(
                    text = value,
                    style = MonoLabelStyle,
                    color = Brand.ink,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** Row that opens something: brand icon, title, explanation and the → glyph. */
@Composable
fun ActionRow(
    title: String,
    subtitle: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Brand.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BrandIcon(icon, tint = Brand.accent, size = 28.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Brand.ink)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Brand.muted)
        }
        Text("→", style = MonoValueStyle, color = Brand.muted)
    }
}

/** Plate for a dialog: square, with a frame instead of elevation and shadow. */
@Composable
fun BrandDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissible: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible
        )
    ) {
        Surface(
            shape = RectangleShape,
            color = Brand.bg,
            border = BorderStroke(1.dp, Brand.line),
            modifier = modifier
        ) {
            Column(
                modifier = Modifier.padding(contentPadding),
                content = content
            )
        }
    }
}
