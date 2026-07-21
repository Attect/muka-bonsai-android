package app.muka.bonsai.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

private val parser: Parser by lazy {
    Parser.builder()
        .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
        .build()
}

/** Parsed block-level model, built once per message text. */
internal sealed interface MdBlock {
    data class TextBlock(val content: InlineContent, val style: BlockStyle) : MdBlock
    data class CodeBlock(val code: String, val language: String?) : MdBlock
    data class MathBlock(val tex: String) : MdBlock
    data class MermaidBlock(val code: String) : MdBlock
    data class QuoteBlock(val children: List<MdBlock>) : MdBlock
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<List<MdBlock>>) : MdBlock
    data class Table(val header: List<InlineContent>, val rows: List<List<InlineContent>>) : MdBlock
    data object Divider : MdBlock
}

internal enum class BlockStyle { PARAGRAPH, H1, H2, H3, H4, H5, H6 }

/**
 * Renders markdown (GFM tables/strikethrough, code, lists, quotes) plus
 * LaTeX (`$$…$$`, `\[…\]`, `$…$`, `\(…\)`) and mermaid fenced blocks.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    if (text.isBlank()) return
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val linkColor = MaterialTheme.colorScheme.primary
    val blocks = remember(text, codeBackground, linkColor) {
        buildBlocks(parser.parse(preprocessDisplayMath(text)), codeBackground, linkColor)
    }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            blocks.forEach { MdBlockView(it, color, style) }
        }
    }
}

// ---------------------------------------------------------------------------
// Block model building
// ---------------------------------------------------------------------------

private fun buildBlocks(node: Node, codeBackground: Color, linkColor: Color): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    var child = node.firstChild
    while (child != null) {
        when (child) {
            is Paragraph -> blocks += MdBlock.TextBlock(
                buildInlineContent(child, codeBackground, linkColor), BlockStyle.PARAGRAPH
            )
            is Heading -> blocks += MdBlock.TextBlock(
                buildInlineContent(child, codeBackground, linkColor),
                BlockStyle.valueOf("H${child.level.coerceIn(1, 6)}")
            )
            is FencedCodeBlock -> {
                val language = child.info?.trim()?.split(' ')?.firstOrNull()?.lowercase()
                when (language) {
                    "math" -> blocks += MdBlock.MathBlock(child.literal.trim())
                    "mermaid" -> blocks += MdBlock.MermaidBlock(child.literal.trim())
                    else -> blocks += MdBlock.CodeBlock(child.literal.trimEnd('\n'), language)
                }
            }
            is IndentedCodeBlock -> blocks += MdBlock.CodeBlock(child.literal.trimEnd('\n'), null)
            is BlockQuote -> blocks += MdBlock.QuoteBlock(buildBlocks(child, codeBackground, linkColor))
            is BulletList -> blocks += buildListBlock(child, ordered = false, codeBackground, linkColor)
            is OrderedList -> blocks += buildListBlock(child, ordered = true, codeBackground, linkColor)
            is ThematicBreak -> blocks += MdBlock.Divider
            is TableBlock -> buildTable(child, codeBackground, linkColor)?.let { blocks += it }
            is HtmlBlock -> blocks += MdBlock.TextBlock(
                InlineContent(AnnotatedString(child.literal.trim()), emptyList()), BlockStyle.PARAGRAPH
            )
            else -> blocks += buildBlocks(child, codeBackground, linkColor)
        }
        child = child.next
    }
    return blocks
}

private fun buildListBlock(
    list: Node,
    ordered: Boolean,
    codeBackground: Color,
    linkColor: Color,
): MdBlock.ListBlock {
    val items = mutableListOf<List<MdBlock>>()
    var item = list.firstChild
    while (item != null) {
        if (item is ListItem) items += buildBlocks(item, codeBackground, linkColor)
        item = item.next
    }
    val start = if (list is OrderedList) list.startNumber else 0
    return MdBlock.ListBlock(ordered, start, items)
}

private fun buildTable(table: TableBlock, codeBackground: Color, linkColor: Color): MdBlock.Table? {
    val header = mutableListOf<InlineContent>()
    val rows = mutableListOf<List<InlineContent>>()
    var section = table.firstChild
    while (section != null) {
        var row = section.firstChild
        while (row != null) {
            if (row is TableRow) {
                val cells = mutableListOf<InlineContent>()
                var cell = row.firstChild
                while (cell != null) {
                    if (cell is TableCell) {
                        cells += buildInlineContent(cell, codeBackground, linkColor, allowMath = false)
                    }
                    cell = cell.next
                }
                when (section) {
                    is TableHead -> header += cells
                    is TableBody -> rows += cells
                }
            }
            row = row.next
        }
        section = section.next
    }
    return if (header.isEmpty() && rows.isEmpty()) null else MdBlock.Table(header, rows)
}

/**
 * Converts whole-line `$$…$$` and `\[…\]` regions into ```math fenced blocks
 * so commonmark parses them as code we can intercept. Existing fenced code is
 * left untouched; unclosed markers (mid-stream) stay plain text.
 */
internal fun preprocessDisplayMath(text: String): String {
    if (!text.contains("$$") && !text.contains("\\[")) return text
    val out = StringBuilder()
    var inFence = false
    var fenceMarker = ""
    var mathOpen = false
    for (line in text.split('\n')) {
        val trimmed = line.trim()
        if (mathOpen) {
            if (trimmed == "$$" || trimmed == "\\]") {
                out.append("```\n")
                mathOpen = false
            } else {
                out.append(line).append('\n')
            }
            continue
        }
        when {
            inFence -> {
                out.append(line).append('\n')
                if (trimmed.startsWith(fenceMarker)) inFence = false
            }
            trimmed.startsWith("```") -> {
                inFence = true
                fenceMarker = "```"
                out.append(line).append('\n')
            }
            trimmed.startsWith("~~~") -> {
                inFence = true
                fenceMarker = "~~~"
                out.append(line).append('\n')
            }
            trimmed == "$$" || trimmed == "\\[" -> {
                out.append("```math\n")
                mathOpen = true
            }
            trimmed.length > 4 && trimmed.startsWith("$$") && trimmed.endsWith("$$") -> {
                out.append("```math\n").append(trimmed.substring(2, trimmed.length - 2)).append("\n```\n")
            }
            trimmed.length > 4 && trimmed.startsWith("\\[") && trimmed.endsWith("\\]") -> {
                out.append("```math\n").append(trimmed.substring(2, trimmed.length - 2)).append("\n```\n")
            }
            else -> out.append(line).append('\n')
        }
    }
    return out.toString().trimEnd()
}

// ---------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------

@Composable
private fun MdBlockView(block: MdBlock, color: Color, bodyStyle: TextStyle) {
    when (block) {
        is MdBlock.TextBlock -> InlineText(
            content = block.content,
            color = color,
            style = when (block.style) {
                BlockStyle.PARAGRAPH -> bodyStyle
                BlockStyle.H1 -> MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                BlockStyle.H2 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                BlockStyle.H3 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                BlockStyle.H4 -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                BlockStyle.H5, BlockStyle.H6 -> MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
            },
        )
        is MdBlock.CodeBlock -> CodeBlockSurface(block.code)
        is MdBlock.MathBlock -> DisplayMath(
            tex = block.tex,
            color = if (color != Color.Unspecified) color else MaterialTheme.colorScheme.onSurface,
            fontScale = bodyStyle.fontSize.value / 16f,
        )
        is MdBlock.MermaidBlock -> MermaidView(block.code)
        is MdBlock.QuoteBlock -> QuoteView(block, color, bodyStyle)
        is MdBlock.ListBlock -> ListView(block, color, bodyStyle)
        is MdBlock.Table -> TableView(block, color, bodyStyle)
        MdBlock.Divider -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun InlineText(content: InlineContent, color: Color, style: TextStyle) {
    val uriHandler = LocalUriHandler.current
    val fontScale = if (style.fontSize.isSp) style.fontSize.value / 16f else 1f
    val mathSizes = remember { mutableStateMapOf<String, Pair<Float, Float>>() }
    val inlineContent = content.mathSegments.associate { segment ->
        val (w, h) = mathSizes[segment.id] ?: (segment.tex.length * 8f to 20f)
        segment.id to InlineTextContent(
            Placeholder(
                width = (w * fontScale).sp,
                height = (h * fontScale).sp,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
            )
        ) {
            InlineMathImage(segment.tex, color, fontScale) { nw, nh ->
                mathSizes[segment.id] = nw to nh
            }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = content.annotated,
        color = color,
        style = style,
        inlineContent = inlineContent,
        onTextLayout = { layout = it },
        modifier = if (content.hasLinks) {
            Modifier.pointerInput(content.annotated) {
                detectTapGestures { position ->
                    val offset = layout?.getOffsetForPosition(position) ?: return@detectTapGestures
                    content.annotated
                        .getStringAnnotations(URL_ANNOTATION_TAG, offset, offset)
                        .firstOrNull()
                        ?.let { runCatching { uriHandler.openUri(it.item) } }
                }
            }
        } else {
            Modifier
        },
    )
}

@Composable
internal fun CodeBlockSurface(code: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = code,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(10.dp),
        )
    }
}

@Composable
private fun QuoteView(block: MdBlock.QuoteBlock, color: Color, bodyStyle: TextStyle) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            block.children.forEach { MdBlockView(it, color, bodyStyle) }
        }
    }
}

@Composable
private fun ListView(block: MdBlock.ListBlock, color: Color, bodyStyle: TextStyle) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        block.items.forEachIndexed { index, itemBlocks ->
            val marker = if (block.ordered) "${block.start + index}." else "•"
            Row {
                Text(
                    text = marker,
                    color = color,
                    style = bodyStyle,
                    modifier = Modifier.width(if (block.ordered) 26.dp else 16.dp),
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    itemBlocks.forEach { MdBlockView(it, color, bodyStyle) }
                }
            }
        }
    }
}

@Composable
private fun TableView(table: MdBlock.Table, color: Color, bodyStyle: TextStyle) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .clip(RoundedCornerShape(6.dp))
            .border(0.5.dp, borderColor, RoundedCornerShape(6.dp))
    ) {
        if (table.header.isNotEmpty()) {
            Row(modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                table.header.forEach { cell ->
                    TableCellView(cell, color, bodyStyle.copy(fontWeight = FontWeight.Bold), borderColor)
                }
            }
        }
        table.rows.forEach { row ->
            Row {
                row.forEach { cell -> TableCellView(cell, color, bodyStyle, borderColor) }
            }
        }
    }
}

@Composable
private fun TableCellView(cell: InlineContent, color: Color, style: TextStyle, borderColor: Color) {
    Text(
        text = cell.annotated,
        color = color,
        style = style,
        modifier = Modifier
            .border(0.5.dp, borderColor)
            .defaultMinSize(minWidth = 64.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}
