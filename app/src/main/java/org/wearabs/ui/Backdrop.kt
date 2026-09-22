package org.wearabs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Size
import org.wearabs.net.Covers

/**
 * The book's cover behind the screen content.
 *
 * The image is deliberately requested at a tiny size and scaled up: bilinear
 * filtering turns that into a soft wash for free, which is far kinder to a watch
 * GPU than a real blur pass running behind a scrolling list. A radial scrim on
 * top keeps text readable whatever the artwork looks like.
 *
 * Only two full-screen draws, and only one when there is no cover. This sits
 * underneath a scrolling list, so every extra full-screen layer is overdraw paid
 * on every frame of every fling.
 */
@Composable
fun CoverBackdrop(
    model: Any?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val context = LocalContext.current
    // Decoding at ~96px is what produces the soft wash. Coil reuses the same
    // cached source as the sharp cover, so this costs no extra fetch.
    val request = remember(model, context) {
        model?.let {
            ImageRequest.Builder(context)
                .data(it)
                .size(Size(Covers.BACKDROP, Covers.BACKDROP))
                .build()
        }
    }
    val scrim = remember { backdropScrim() }

    if (request == null) {
        Box(modifier = modifier.fillMaxSize().background(Color.Black), content = content)
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Drawn opaque, with the dimming folded into the scrim below: one
        // alpha blend instead of two, for the same result on screen.
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(modifier = Modifier.fillMaxSize().background(scrim))
        content()
    }
}

/** Darkest where the title and controls sit, lighter out at the rim. */
private fun backdropScrim(): Brush = Brush.radialGradient(
    colors = listOf(
        Color.Black.copy(alpha = 0.90f),
        Color.Black.copy(alpha = 0.78f),
        Color.Black.copy(alpha = 0.62f)
    )
)
