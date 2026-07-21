package app.muka.bonsai.ui.markdown

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

sealed interface SvgLoadState {
    data object Loading : SvgLoadState
    data class Success(val svg: String) : SvgLoadState
    data object Error : SvgLoadState
}

@Composable
fun rememberMathSvg(tex: String, display: Boolean): SvgLoadState {
    val state by produceState<SvgLoadState>(SvgLoadState.Loading, tex, display) {
        val svg = JsRenderEngine.renderMath(tex, display)
        value = if (svg != null) SvgLoadState.Success(svg) else SvgLoadState.Error
    }
    return state
}

/**
 * Inline math island placed inside a Text's inlineContent slot.
 * Reports the formula's natural size (baseline px at 16px font) via [onSize]
 * so the caller can size the placeholder correctly.
 */
@Composable
fun InlineMathImage(
    tex: String,
    color: Color,
    fontScale: Float,
    onSize: (width: Float, height: Float) -> Unit,
) {
    when (val state = rememberMathSvg(tex, display = false)) {
        is SvgLoadState.Success -> {
            val size = remember(state.svg) { svgNaturalSize(state.svg) }
            if (size != null) {
                LaunchedEffect(size) { onSize(size.width, size.height) }
                SvgImage(
                    svg = state.svg,
                    tint = color,
                    widthDp = size.width * fontScale,
                    heightDp = size.height * fontScale,
                    contentDescription = tex,
                )
            } else {
                MathFallbackText(tex)
            }
        }
        SvgLoadState.Error -> MathFallbackText(tex)
        SvgLoadState.Loading -> Spacer(Modifier.height(1.dp))
    }
}

/** Display (block) math, centered with horizontal scroll for wide formulas. */
@Composable
fun DisplayMath(tex: String, color: Color, fontScale: Float, modifier: Modifier = Modifier) {
    when (val state = rememberMathSvg(tex, display = true)) {
        is SvgLoadState.Success -> {
            val size = remember(state.svg) { svgNaturalSize(state.svg) }
            if (size != null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                ) {
                    SvgImage(
                        svg = state.svg,
                        tint = color,
                        widthDp = size.width * fontScale,
                        heightDp = size.height * fontScale,
                        contentDescription = tex,
                    )
                }
            } else {
                MathFallbackText(tex)
            }
        }
        SvgLoadState.Error -> MathFallbackText(tex)
        SvgLoadState.Loading -> Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun MathFallbackText(tex: String) {
    Text(
        text = tex,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
