package app.muka.bonsai.ui.markdown

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Renders a mermaid diagram. While the (cached) SVG is being produced or when
 * rendering fails — e.g. an incomplete diagram mid-stream — the source code is
 * shown instead.
 */
@Composable
fun MermaidView(code: String, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val state by produceState<SvgLoadState>(SvgLoadState.Loading, code, dark) {
        // Debounce streaming updates: mermaid rendering is expensive and an
        // incomplete diagram fails anyway, so wait until the code settles.
        delay(400)
        val svg = JsRenderEngine.renderMermaid(code, dark)
        value = if (svg != null) SvgLoadState.Success(svg) else SvgLoadState.Error
    }

    val success = state as? SvgLoadState.Success
    val size = success?.let { remember(it.svg) { svgNaturalSize(it.svg) } }
    if (success == null || size == null) {
        CodeBlockSurface(code, modifier)
        return
    }

    // Cap display width so huge diagrams scroll horizontally instead of shrinking.
    val displayWidth = size.width.coerceAtLeast(0f)
    val displayHeight = size.height
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            SvgImage(
                svg = success.svg,
                tint = null,
                widthDp = displayWidth,
                heightDp = displayHeight,
                contentDescription = "Mermaid 图表",
            )
        }
    }
}
