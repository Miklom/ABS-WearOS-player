package org.wearabs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Cover art with a graceful fallback. Plenty of books have no cover, and a blank
 * square in a grid of pictures reads as a bug — so those get a tinted tile with
 * the title's initials instead.
 */
@Composable
fun BookCover(
    model: Any?,
    title: String,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(18.dp)
) {
    var failed by remember(model) { mutableStateOf(model == null) }
    // Both derive purely from the title, so recomputing them per frame would be
    // pure waste in a scrolling list.
    val brush = remember(title) { placeholderBrush(title) }
    val label = remember(title) { initials(title) }

    Box(
        modifier = modifier.clip(shape).background(brush),
        contentAlignment = Alignment.Center
    ) {
        if (failed) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.padding(4.dp)
            )
        } else {
            AsyncImage(
                model = model,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onState = { state ->
                    if (state is AsyncImagePainter.State.Error) failed = true
                }
            )
        }
    }
}

/** Up to two letters, which is all that fits legibly on a watch-sized tile. */
private fun initials(title: String): String =
    title.split(' ', '-', ':')
        .filter { it.isNotBlank() }
        .take(2)
        .map { it.first().uppercaseChar() }
        .joinToString("")
        .ifBlank { "?" }

/**
 * A stable colour per title, so a coverless book keeps the same tile between
 * launches and stays recognisable in the grid.
 */
private fun placeholderBrush(title: String): Brush {
    val hue = ((title.hashCode().toLong() and 0xFFFF) % 360).toFloat()
    val top = Color.hsl(hue, 0.32f, 0.30f)
    val bottom = Color.hsl((hue + 28f) % 360f, 0.30f, 0.18f)
    return Brush.verticalGradient(listOf(top, bottom))
}
