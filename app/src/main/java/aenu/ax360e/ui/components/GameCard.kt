package aenu.ax360e.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import aenu.ax360e.ui.model.GameItem

/**
 * v2 redesign game card.
 *
 * Aspect ratio 3:4 (poster style). Cover art fills the entire card; a
 * gradient scrim + info overlay sits at the bottom showing the game name
 * and the storage URI tail (so the user can identify which ISO is which
 * when multiple games share a similar title).
 *
 * No elevation, no separate footer section — keeps the grid dense and
 * cinematic, matching the reference mockup.
 */
@Composable
fun GameCard(
    game: GameItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val iconBitmap = remember(game.uri, game.icon) {
        game.icon?.let { bytes ->
            runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }

    val cardShape = RoundedCornerShape(16.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .clip(cardShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // Cover art / icon placeholder
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap,
                contentDescription = game.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            // Centered gamepad icon, scaled up to feel like cover art
            Icon(
                imageVector = Icons.Default.SportsEsports,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.85f)
            )
            // Subtle background tint behind the icon
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
            )
        }

        // Bottom info scrim + text (overlaid on the cover art)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Transparent,
                            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f)
                        ),
                        startY = 200f
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = game.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = game.uri.substringAfterLast('/').ifEmpty { game.uri },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
