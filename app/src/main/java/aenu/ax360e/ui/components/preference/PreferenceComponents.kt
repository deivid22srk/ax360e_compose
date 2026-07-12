package aenu.ax360e.ui.components.preference

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private const val DisabledAlpha = 0.38f
private const val MediumAlpha = 0.67f

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
    style: TextStyle = MaterialTheme.typography.bodyMedium,
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

@Composable
fun PreferenceHeader(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        modifier = modifier.padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
fun PreferenceIcon(
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    enabled: Boolean = LocalPreferenceState.current
) {
    if (icon == null) return
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = modifier.size(24.dp),
        tint = preferenceColor(enabled, MaterialTheme.colorScheme.onSurfaceVariant)
    )
}

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
                    .padding(horizontal = 16.dp)
                    .heightIn(min = 64.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                leadingIcon?.invoke()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 12.dp),
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
        onClick = { onCheckedChange(!checked) },
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
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        enabled = enabled,
        onClick = onClick,
        modifier = modifier
    )
}
