package app.muka.bonsai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug-only mock streaming test bench. Streams synthetic token chunks into
 * the same [MessageList] used by the real chat, so scroll-following and
 * markdown/LaTeX/mermaid streaming behavior can be verified without a model.
 */

private class TestPayload(
    val label: String,
    val prompt: String,
    val produce: () -> Iterator<String>,
)

private val payloads = listOf(
    TestPayload("计数 1–500", "mock：从 1 数到 500，每行一个数字") {
        var n = 1
        object : Iterator<String> {
            override fun hasNext() = n <= 500
            override fun next() = "${n++}\n"
        }
    },
    TestPayload("Markdown 混合", "mock：输出一份带公式和流程图的文档") {
        MARKDOWN_SAMPLE.chunked(6).iterator()
    },
    TestPayload("Think 流式", "mock：先思考再回答") {
        THINK_SAMPLE.chunked(6).iterator()
    },
)

@Composable
fun RenderTestPage(modifier: Modifier = Modifier) {
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var streaming by remember { mutableStateOf(false) }
    var selected by remember { mutableIntStateOf(0) }
    var chunksPerSecond by remember { mutableFloatStateOf(60f) }
    var chunkCount by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }

    fun finishStream() {
        job?.cancel()
        job = null
        streaming = false
        val last = messages.lastOrNull() ?: return
        if (last.isGenerating) {
            messages = messages.dropLast(1) + last.copy(isGenerating = false)
        }
    }

    fun startStream() {
        finishStream()
        val payload = payloads[selected]
        chunkCount = 0
        streaming = true
        messages = messages +
            ChatMessage(ChatMessage.Role.User, payload.prompt) +
            ChatMessage(ChatMessage.Role.Assistant, "", isGenerating = true)
        job = scope.launch {
            val iterator = payload.produce()
            while (isActive && iterator.hasNext()) {
                val chunk = iterator.next()
                val last = messages.last()
                messages = messages.dropLast(1) + last.copy(text = last.text + chunk)
                chunkCount++
                delay((1000f / chunksPerSecond).toLong().coerceAtLeast(1))
            }
            finishStream()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Mock 流式渲染测试（无需模型）",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    payloads.forEachIndexed { index, payload ->
                        FilterChip(
                            selected = selected == index,
                            onClick = { selected = index },
                            label = { Text(payload.label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "速度 ${chunksPerSecond.toInt()} 块/秒",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Slider(
                        value = chunksPerSecond,
                        onValueChange = { chunksPerSecond = it },
                        valueRange = 10f..300f,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (streaming) {
                        Button(onClick = { finishStream() }) { Text("停止") }
                    } else {
                        Button(onClick = { startStream() }) { Text("开始流式输出") }
                    }
                    OutlinedButton(
                        onClick = {
                            finishStream()
                            messages = emptyList()
                            chunkCount = 0
                        },
                        enabled = messages.isNotEmpty()
                    ) { Text("清空") }
                    Text(
                        text = if (chunkCount > 0) "已输出 $chunkCount 块" else "",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            MessageList(
                messages = messages,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private val MARKDOWN_SAMPLE = """
# Mock 渲染测试文档

这是一段**加粗**、*斜体*与 `行内代码` 混排的普通段落，用来观察流式更新时的排版稳定性。重复几行让内容足够长，超过一屏后应当自动跟随最新输出。

这是一段**加粗**、*斜体*与 `行内代码` 混排的普通段落，用来观察流式更新时的排版稳定性。重复几行让内容足够长，超过一屏后应当自动跟随最新输出。

## 列表

1. 第一项：流式增长期间视图不应跳动
2. 第二项：上滑可暂停跟随
3. 第三项：滑回底部恢复跟随

- 无序项目 A
- 无序项目 B

## 表格

| 项目 | 状态 | 说明 |
| ---- | ---- | ---- |
| 滚动跟随 | ✅ |  glued to bottom |
| Markdown | ✅ | commonmark 流式 |
| LaTeX | ✅ | MathJax SVG |
| Mermaid | ✅ | v9 SVG |

## 代码

```kotlin
fun fib(n: Int): Int =
    if (n < 2) n else fib(n - 1) + fib(n - 2)
```

## 行内公式

质能方程 ${'$'}E = mc^2${'$'}，以及欧拉公式 ${'$'}e^{i\pi} + 1 = 0${'$'} 应当在文字行内渲染。

## 块级公式

$$
\int_{-\infty}^{+\infty} e^{-x^2}\,dx = \sqrt{\pi}
$$

$$
\frac{d}{dx}\left( \sum_{n=0}^{\infty} a_n x^n \right) = \sum_{n=1}^{\infty} n a_n x^{n-1}
$$

## Mermaid 流程图

```mermaid
graph TD
    A[用户输入] --> B{包含思考?}
    B -->|是| C[渲染 Think 气泡]
    B -->|否| D[渲染 Markdown]
    C --> D
    D --> E[跟随滚动到底部]
```

## 结尾段落

这是文档的结尾，用来把内容继续撑长。流式输出超过一屏后，最新内容应始终停在视图底部，而不是锁定在气泡顶部。

这是文档的结尾，用来把内容继续撑长。流式输出超过一屏后，最新内容应始终停在视图底部，而不是锁定在气泡顶部。

输出完毕。
""".trimIndent()

private val THINK_SAMPLE = """
<think>
用户问了一个 mock 问题，我需要先进行一段足够长的推理，用来验证思考气泡在流式输出时的折叠与展开行为。

第一步，确认前提：木棍长 20 米，城门高 5 米、宽 6 米，对角线为 √61 ≈ 7.81 米。直接通过是不可能的。

第二步，考虑维度：木棍是一维物体，理想情况下可以沿城门平面的法线方向（即门的厚度方向）水平穿过，只要门的通道深度允许。

第三步，排除干扰：题目通常的陷阱是让人计算对角线，但一维细长物体通过二维开口并不受开口对角线长度限制。

整理一下结论，准备输出最终答案。
</think>
**可以。** 城门能过多长的木棍，取决于怎么拿：

- 竖着拿：受门高 5 米限制，不行；
- 横着拿：受门宽 6 米限制，不行；
- 斜着拿：受对角线 √61 ≈ 7.81 米限制，也不行；
- **顺着门的进深方向水平拿**：20 米完全可以直接穿过去。

这是一维物体通过二维开口的经典问题，开口的对角线长度并不构成限制。
""".trimIndent()
