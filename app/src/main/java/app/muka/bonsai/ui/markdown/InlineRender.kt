package app.muka.bonsai.ui.markdown

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text

const val URL_ANNOTATION_TAG = "URL"

/** One inline math island: [id] keys the Compose inlineContent map. */
class MathSegment(val id: String, val tex: String)

/** Pre-built inline content of one text block, ready for a Compose Text. */
class InlineContent(
    val annotated: AnnotatedString,
    val mathSegments: List<MathSegment>,
) {
    val hasLinks: Boolean
        get() = annotated.getStringAnnotations(URL_ANNOTATION_TAG, 0, annotated.length).isNotEmpty()
}

internal sealed interface InlineSegment {
    data class Literal(val text: String) : InlineSegment
    data class Math(val tex: String) : InlineSegment
}

/**
 * Splits [text] into literal and inline-math segments.
 * Recognizes `$…$`, `$$…$$` and `\(…\)`; escaped `\$`, unpaired delimiters and
 * currency-like usages ("$5 and $6") stay literal.
 */
internal fun splitInlineMath(text: String): List<InlineSegment> {
    val segments = mutableListOf<InlineSegment>()
    var literalStart = 0
    var i = 0

    fun flushLiteral(end: Int) {
        if (end > literalStart) segments += InlineSegment.Literal(text.substring(literalStart, end))
    }

    while (i < text.length) {
        var contentStart = -1
        var closer: String? = null
        when {
            text.startsWith("\\(", i) -> {
                contentStart = i + 2
                closer = "\\)"
            }
            text.startsWith("$$", i) -> {
                contentStart = i + 2
                closer = "$$"
            }
            text[i] == '$' &&
                (i == 0 || text[i - 1] != '\\') &&
                i + 1 < text.length &&
                !text[i + 1].isWhitespace() &&
                text[i + 1] != '$' -> {
                contentStart = i + 1
                closer = "$"
            }
        }
        if (closer == null) {
            i++
            continue
        }

        var closeIdx = -1
        var j = contentStart
        while (j <= text.length - closer.length) {
            if (closer == "$" && text.startsWith("$$", j)) {
                j += 2
                continue
            }
            if (text.startsWith(closer, j) && text[j - 1] != '\\') {
                // closing `$` must not follow a space nor precede a digit (currency guard)
                if (text[j - 1].isWhitespace()) {
                    j++
                    continue
                }
                if (closer == "$" && j + 1 < text.length && text[j + 1].isDigit()) {
                    j++
                    continue
                }
                closeIdx = j
                break
            }
            j++
        }

        val tex = if (closeIdx >= 0) text.substring(contentStart, closeIdx) else null
        if (closeIdx < 0 || tex.isNullOrBlank() || tex.contains('\n')) {
            i++
            continue
        }

        flushLiteral(i)
        segments += InlineSegment.Math(tex)
        i = closeIdx + closer.length
        literalStart = i
    }
    flushLiteral(text.length)
    return segments
}

/**
 * Builds [InlineContent] from the inline children of [node].
 * When [allowMath] is false (e.g. inside table cells), math stays literal text.
 */
fun buildInlineContent(
    node: Node,
    codeBackground: Color,
    linkColor: Color,
    allowMath: Boolean = true,
): InlineContent {
    val mathSegments = mutableListOf<MathSegment>()
    val annotated = AnnotatedString.Builder().apply {
        appendChildren(node, codeBackground, linkColor, allowMath, mathSegments)
    }.toAnnotatedString()
    return InlineContent(annotated, mathSegments)
}

private fun AnnotatedString.Builder.appendChildren(
    node: Node,
    codeBackground: Color,
    linkColor: Color,
    allowMath: Boolean,
    mathSegments: MutableList<MathSegment>,
) {
    var child = node.firstChild
    while (child != null) {
        when (child) {
            is Text -> appendLiteral(child.literal, allowMath, mathSegments)
            is SoftLineBreak, is HardLineBreak -> append('\n')
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendChildren(child, codeBackground, linkColor, allowMath, mathSegments)
            }
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendChildren(child, codeBackground, linkColor, allowMath, mathSegments)
            }
            is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                appendChildren(child, codeBackground, linkColor, allowMath, mathSegments)
            }
            is Code -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 0.9.em,
                    background = codeBackground,
                )
            ) {
                append(child.literal)
            }
            is Link -> {
                pushStringAnnotation(URL_ANNOTATION_TAG, child.destination)
                withStyle(
                    SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                ) {
                    appendChildren(child, codeBackground, linkColor, allowMath, mathSegments)
                }
                pop()
            }
            is Image -> appendLiteral(child.firstChildLiteral(), allowMath, mathSegments)
            is HtmlInline -> append(child.literal)
            else -> appendChildren(child, codeBackground, linkColor, allowMath, mathSegments)
        }
        child = child.next
    }
}

private fun Node.firstChildLiteral(): String {
    val first = firstChild
    return if (first is Text) first.literal else ""
}

private fun AnnotatedString.Builder.appendLiteral(
    literal: String,
    allowMath: Boolean,
    mathSegments: MutableList<MathSegment>,
) {
    if (!allowMath || (!literal.contains('$') && !literal.contains("\\("))) {
        append(literal)
        return
    }
    for (segment in splitInlineMath(literal)) {
        when (segment) {
            is InlineSegment.Literal -> append(segment.text)
            is InlineSegment.Math -> {
                val id = "math-${mathSegments.size}-${segment.tex.hashCode()}"
                mathSegments += MathSegment(id, segment.tex)
                appendInlineContent(id, segment.tex)
            }
        }
    }
}
