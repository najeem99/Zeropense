package com.najeemfasil.expense.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.core.formatMoney
import com.najeemfasil.expense.ui.theme.Radius
import com.najeemfasil.expense.ui.theme.Spacing
import com.najeemfasil.expense.ui.theme.colors

/** One device pixel, like React Native's `StyleSheet.hairlineWidth`. */
val Hairline: Dp = Dp.Hairline

/**
 * Scrollable page. [title] renders a large heading for tab screens, which have no header;
 * those also keep clear of the status bar.
 */
@Composable
fun Screen(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = colors
    Box(
        modifier
            .fillMaxSize()
            .background(c.background)
            .then(if (title != null) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier)
            .imePadding()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = Spacing.lg, top = Spacing.lg, end = Spacing.lg, bottom = Spacing.xl * 2),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            if (title != null) ScreenTitle(title)
            content()
        }
    }
}

@Composable
fun ScreenTitle(title: String) {
    Text(
        title,
        color = colors.text,
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = Spacing.xs)
    )
}

@Composable
fun Card(
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    contentPadding: PaddingValues = PaddingValues(Spacing.lg),
    content: @Composable ColumnScope.() -> Unit
) {
    val c = colors
    val shape = RoundedCornerShape(Radius.lg)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .border(Hairline, c.border, shape)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        horizontalAlignment = horizontalAlignment,
        content = content
    )
}

/** Small upper-case heading. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier, uppercase: Boolean = true) {
    Text(
        if (uppercase) text.uppercase() else text,
        modifier = modifier,
        color = colors.textMuted,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp
    )
}

@Composable
fun Body(
    text: String,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = 16.sp,
    fontWeight: FontWeight? = null,
    fontStyle: FontStyle? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE
) {
    val c = colors
    Text(
        text,
        modifier = modifier,
        color = if (color != Color.Unspecified) {
            color
        } else if (muted) {
            c.textMuted
        } else {
            c.text
        },
        fontSize = fontSize,
        fontWeight = fontWeight,
        fontStyle = fontStyle,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

enum class ButtonVariant { Primary, Secondary, Danger, Ghost }

@Composable
fun Button(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Primary,
    enabled: Boolean = true,
    minHeight: Dp = 44.dp,
    horizontalPadding: Dp = Spacing.lg,
    /** Stretch across the column, as buttons do in the Expo app; false inside rows and centred cards. */
    fullWidth: Boolean = true
) {
    val c = colors
    val bg = when (variant) {
        ButtonVariant.Primary -> c.primary
        ButtonVariant.Secondary, ButtonVariant.Danger -> c.surfaceAlt
        ButtonVariant.Ghost -> Color.Transparent
    }
    val fg = when (variant) {
        ButtonVariant.Primary -> c.onPrimary
        ButtonVariant.Secondary -> c.text
        ButtonVariant.Danger -> c.expense
        ButtonVariant.Ghost -> c.primary
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .defaultMinSize(minHeight = minHeight)
            .alpha(
                if (!enabled) {
                    0.4f
                } else if (pressed) {
                    0.75f
                } else {
                    1f
                }
            )
            .clip(RoundedCornerShape(Radius.md))
            .background(bg)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center
    ) {
        Text(title, color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@Composable
fun Chip(label: String, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    val c = colors
    val shape = RoundedCornerShape(Radius.pill)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) c.primary else c.surfaceAlt)
            .border(Hairline, if (selected) c.primary else c.border, shape)
            .semantics { this.selected = selected }
            .clickable(enabled = onClick != null, role = Role.Button) { onClick?.invoke() }
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
    ) {
        Text(label, color = if (selected) c.onPrimary else c.text, fontSize = 14.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipRow(modifier: Modifier = Modifier, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        content = content
    )
}

/** Label + text input. Multiline fields are at least 110 dp tall. */
@Composable
fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    multiline: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    autoFocus: Boolean = false
) {
    val c = colors
    val shape = RoundedCornerShape(Radius.md)
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Label(label)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(color = c.text, fontSize = 16.sp),
            cursorBrush = SolidColor(c.primary),
            singleLine = !multiline,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(c.surface)
                        .border(Hairline, c.border, shape)
                        .then(if (multiline) Modifier.heightIn(min = 110.dp) else Modifier)
                        .padding(Spacing.md)
                ) {
                    if (value.isEmpty()) Text(placeholder, color = c.textMuted, fontSize = 16.sp)
                    inner()
                }
            }
        )
    }
}

/** A list row with a hairline divider under it. */
@Composable
fun Row(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    right: (@Composable () -> Unit)? = null
) {
    val c = colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (pressed) 0.6f else 1f)
            .clickable(interactionSource = interaction, indication = null, enabled = onClick != null) {
                onClick?.invoke()
            }
            .bottomHairline(c.border)
            .padding(vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Body(title, maxLines = 1)
            if (!subtitle.isNullOrEmpty()) Body(subtitle, muted = true, fontSize = 13.sp, maxLines = 1)
        }
        right?.invoke()
    }
}

/** Draws a one-pixel line along the bottom edge. */
fun Modifier.bottomHairline(color: Color, width: Dp = Hairline): Modifier = drawBehind {
    val stroke = if (width == Dp.Hairline) 1f else width.toPx()
    val y = size.height - stroke / 2
    drawLine(color, Offset(0f, y), Offset(size.width, y), stroke)
}

/** Centred card for empty states, with optional actions under the message. */
@Composable
fun Empty(title: String, message: String? = null, actions: (@Composable () -> Unit)? = null) {
    Card(
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.xl)
    ) {
        Body(title, fontWeight = FontWeight.SemiBold)
        if (message != null) Body(message, muted = true, textAlign = TextAlign.Center)
        actions?.invoke()
    }
}

@Composable
fun MonthSwitcher(label: String, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Button("‹", onPrev, variant = ButtonVariant.Ghost, fullWidth = false)
        Body(label, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Button("›", onNext, variant = ButtonVariant.Ghost, fullWidth = false)
    }
}

/** Checkbox row used by the SMS setup. */
@Composable
fun CheckRow(checked: Boolean, onClick: () -> Unit, title: String, subtitle: String? = null, badge: String? = null) {
    val c = colors
    val box = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Checkbox,
                onClick = onClick
            )
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(box)
                .background(if (checked) c.primary else Color.Transparent)
                .border(2.dp, if (checked) c.primary else c.border, box),
            contentAlignment = Alignment.Center
        ) {
            if (checked) Text("✓", color = c.onPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Body(title, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (subtitle != null) Body(subtitle, muted = true, fontSize = 13.sp, maxLines = 2)
        }
        if (badge != null) Body(badge, muted = true, fontSize = 12.sp)
    }
}

/** Card with a spinner and a status line, shown while something long runs. */
@Composable
fun SpinnerCard(text: String) {
    Card(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(Modifier.size(28.dp), color = colors.primary, strokeWidth = 3.dp)
        Body(text, muted = true)
    }
}

/**
 * A money amount. Money out is shown negative in the normal text colour; money in with a "+"
 * in the income colour.
 */
@Composable
fun Amount(
    minor: Long,
    currency: Currency,
    modifier: Modifier = Modifier,
    direction: Direction? = null,
    fontSize: TextUnit = 16.sp
) {
    val c = colors
    val signed = if (direction == Direction.DEBIT) -minor else minor
    Text(
        formatMoney(signed, currency, signed = direction == Direction.CREDIT),
        modifier = modifier,
        color = if (direction == Direction.CREDIT) c.income else c.text,
        fontSize = fontSize,
        fontWeight = FontWeight.SemiBold,
        style = TextStyle(fontFeatureSettings = "tnum"),
        maxLines = 1
    )
}
