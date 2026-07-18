package aenu.ax360e.ui.components.preference

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private const val DisabledAlpha = 0.38f
private const val MediumAlpha = 0.70f

val LocalPreferenceState = compositionLocalOf { true }

private fun preferenceColor(enabled: Boolean, contentColor: Color) =
    if (!enabled) contentColor.copy(alpha = DisabledAlpha) else contentColor

private fun preferenceSubtitleColor(enabled: Boolean, contentColor: Color) =
    if (!enabled) contentColor.copy(alpha = DisabledAlpha) else contentColor.copy(alpha = MediumAlpha)

@Composable
fun PreferenceTitle(
    title: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = LocalPreferenceState.current,
    maxLines: Int = 2,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = preferenceColor(enabled, LocalContentColor.current)
) {
    Text(
        text = title,
        modifier = modifier,
        style = style,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        color = color
    )
}

@Composable
fun PreferenceSubtitle(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = LocalPreferenceState.current,
    maxLines: Int = 2,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    color: Color = preferenceSubtitleColor(enabled, LocalContentColor.current)
) {
    Text(
        text = text,
        modifier = modifier,
        style = style,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        color = color
    )
}

/**
 * Section header used inside grouped preference cards.
 *
 * Rendered as a small, primary-tinted label with optional trailing caption
 * (e.g. "3 items" or an action icon).
 */
@Composable
fun PreferenceHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f, fill = false)
        )
        trailing?.invoke()
    }
}

@Composable
fun PreferenceIcon(
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    enabled: Boolean = LocalPreferenceState.current,
    tint: Color? = null
) {
    if (icon == null) return
    val resolved = tint ?: MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier.size(40.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(
            alpha = if (enabled) 1f else DisabledAlpha
        )
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = preferenceColor(enabled, resolved)
            )
        }
    }
}

/**
 * Container for a single preference row.
 *
 * Visual pattern (M3 settings):
 *   [leading icon in a circular surface]   title             [trailing value/chevron]
 *                                          subtitle (muted)
 *
 * The row is fully clickable (ripple covers the whole row), but the trailing
 * content can intercept clicks (e.g. for a Switch) by using its own clickable.
 */
@Composable
fun BasePreference(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    CompositionLocalProvider(LocalPreferenceState provides enabled) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null && enabled) Modifier.clickable(onClick = onClick)
                    else Modifier
                ),
            color = Color.Transparent,
            shape = RoundedCornerShape(0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                leadingIcon?.invoke()
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    title()
                    subtitle?.invoke()
                }
                trailingContent?.invoke()
            }
        }
    }
}

@Composable
fun RegularPreference(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    trailing: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    BasePreference(
        title = { PreferenceTitle(title = title) },
        subtitle = subtitle?.let { { PreferenceSubtitle(text = it) } },
        leadingIcon = if (icon != null) ({ PreferenceIcon(icon = icon) }) else null,
        trailingContent = trailing,
        enabled = enabled,
        onClick = onClick,
        modifier = modifier
    )
}

@Composable
fun SwitchPreference(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    BasePreference(
        title = { PreferenceTitle(title = title) },
        subtitle = subtitle?.let { { PreferenceSubtitle(text = it) } },
        leadingIcon = if (icon != null) ({ PreferenceIcon(icon = icon) }) else null,
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled
            )
        },
        enabled = enabled,
        onClick = { if (enabled) onCheckedChange(!checked) },
        modifier = modifier
    )
}

@Composable
fun ValuePreference(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    highlightValue: Boolean = false
) {
    BasePreference(
        title = {
            PreferenceTitle(
                title = title,
                color = if (highlightValue) MaterialTheme.colorScheme.tertiary
                else preferenceColor(enabled, LocalContentColor.current)
            )
        },
        subtitle = subtitle?.let { { PreferenceSubtitle(text = it) } },
        leadingIcon = if (icon != null) ({ PreferenceIcon(icon = icon) }) else null,
        trailingContent = {
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (highlightValue) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp)
            )
        },
        enabled = enabled,
        onClick = onClick,
        modifier = modifier
    )
}

/**
 * A rounded surface that visually groups a list of preference rows into a
 * single card. M3 recommends grouping related settings into one container
 * instead of leaving them as separate cards — this matches the latest
 * Android system settings look (Android 14+).
 *
 * Usage:
 *   PreferenceGroupCard {
 *       item { SwitchPreference(...) }
 *       item { RegularPreference(...) }
 *   }
 */
@Composable
fun PreferenceGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp
    ) {
        Column { content() }
    }
}
