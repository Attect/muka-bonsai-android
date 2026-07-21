package app.muka.bonsai.ui.markdown

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.caverock.androidsvg.SVG
import kotlin.math.ceil
import kotlin.math.min

/** Natural size of an SVG document in its own px units. */
class SvgSize(val width: Float, val height: Float)

/** Returns the document size, falling back to the viewBox when width/height are relative. */
fun svgNaturalSize(svg: String): SvgSize? = runCatching {
    val parsed = SVG.getFromString(svg)
    var w = parsed.documentWidth
    var h = parsed.documentHeight
    if (w <= 0f || h <= 0f) {
        val viewBox = parsed.documentViewBox
        if (viewBox != null) {
            w = viewBox.width()
            h = viewBox.height()
        }
    }
    if (w <= 0f || h <= 0f) null else SvgSize(w, h)
}.getOrNull()

private const val MAX_RASTER_SIDE = 4096

/**
 * Rasterizes [svg] into a bitmap of exactly [widthPx] x [heightPx].
 * [tint] replaces every `currentColor` occurrence (MathJax output) so formulas
 * follow the surrounding text color. Returns null on any parse/render failure.
 */
fun rasterizeSvg(svg: String, tint: Color?, widthPx: Int, heightPx: Int): Bitmap? = runCatching {
    val source = if (tint != null) {
        val hex = String.format("#%06X", tint.toArgb() and 0xFFFFFF)
        svg.replace("currentColor", hex)
    } else {
        svg
    }
    val parsed = SVG.getFromString(source)
    parsed.setDocumentWidth(widthPx.toFloat())
    parsed.setDocumentHeight(heightPx.toFloat())
    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawPicture(parsed.renderToPicture(widthPx, heightPx))
    bitmap
}.getOrNull()

/**
 * Displays an SVG string at [widthDp] x [heightDp], rasterized at screen density.
 * Raster size is clamped to [MAX_RASTER_SIDE] px per side, preserving aspect.
 */
@Composable
fun SvgImage(
    svg: String,
    tint: Color?,
    widthDp: Float,
    heightDp: Float,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val density = LocalDensity.current
    val bitmap = remember(svg, tint, widthDp, heightDp, density.density) {
        val scale = min(
            min(MAX_RASTER_SIDE / (widthDp * density.density), MAX_RASTER_SIDE / (heightDp * density.density)),
            1f
        )
        val wPx = ceil(widthDp * density.density * scale).toInt().coerceAtLeast(1)
        val hPx = ceil(heightDp * density.density * scale).toInt().coerceAtLeast(1)
        rasterizeSvg(svg, tint, wPx, hPx)
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = modifier.then(Modifier.size(widthDp.dp, heightDp.dp)),
        )
    }
}
