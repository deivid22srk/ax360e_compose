package aenu.ax360e.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import aenu.ax360e.ui.model.CoverArtLoader
import aenu.ax360e.ui.model.GameItem
import androidx.compose.foundation.Image
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders a single game row. The cover thumbnail is loaded asynchronously:
 *
 *   1. If the [GameItem] already carries a [GameItem.icon] (GOD games extract
 *      one from the STFS package) — use that.
 *   2. Otherwise, query [CoverArtLoader] which checks memory → disk → TheGamesDB.
 *
 * While the cover is loading we show a small spinner; if loading finishes with
 * no art available, we fall back to the SportsEsports placeholder icon.
 */
@Composable
fun GameCard(
    game: GameItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            GameThumbnail(game)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = game.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = game.uri,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun GameThumbnail(game: GameItem) {
    val context = LocalContext.current

    // Three-state load so we can distinguish "still loading" from
    // "finished but no cover available" (otherwise the spinner would spin forever).
    val state by produceState<ThumbState>(
        initialValue = ThumbState.Loading,
        key1 = game.name,
        key2 = game.icon
    ) {
        value = withContext(Dispatchers.IO) { loadThumb(context, game) }
    }

    Surface(
        modifier = Modifier.size(72.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        when (val s = state) {
            is ThumbState.Loaded -> Image(
                bitmap = s.bitmap,
                contentDescription = game.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            ThumbState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            ThumbState.Missing -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SportsEsports,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private sealed interface ThumbState {
    data object Loading : ThumbState
    data class Loaded(val bitmap: ImageBitmap) : ThumbState
    data object Missing : ThumbState
}

private fun loadThumb(context: android.content.Context, game: GameItem): ThumbState {
    // Built-in icon from GOD/STFS — use it directly without network.
    if (game.icon != null) {
        return runCatching {
            BitmapFactory.decodeByteArray(game.icon, 0, game.icon.size)?.asImageBitmap()
        }.getOrNull()?.let { ThumbState.Loaded(it) } ?: ThumbState.Missing
    }
    // ISO / ZAR — fetch cover from TheGamesDB via the loader.
    val bytes = CoverArtLoader.load(context, game.name) ?: return ThumbState.Missing
    return runCatching {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()?.let { ThumbState.Loaded(it) } ?: ThumbState.Missing
}
