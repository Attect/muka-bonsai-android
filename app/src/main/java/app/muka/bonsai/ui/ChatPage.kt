package app.muka.bonsai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.muka.bonsai.ui.components.ThinkSection
import app.muka.bonsai.ui.markdown.MarkdownText
import app.muka.bonsai.ui.markdown.ThinkParser

@Composable
fun ChatPage(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        TopStatusBar(
            modelName = uiState.modelPath?.let { File(it).name } ?: "未加载模型",
            engineState = uiState.engineState,
            onClear = viewModel::clearMessages,
            modifier = Modifier.fillMaxWidth()
        )

        Box(modifier = Modifier.weight(1f)) {
            MessageList(
                messages = uiState.messages,
                modifier = Modifier.fillMaxSize()
            )
            PerformanceCard(
                metrics = uiState.metrics,
                isGenerating = uiState.isGenerating,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            )
        }

        ChatInput(
            value = uiState.inputText,
            onValueChange = viewModel::onInputChange,
            onSend = viewModel::sendMessage,
            onStop = viewModel::stopGeneration,
            isGenerating = uiState.isGenerating,
            enabled = uiState.modelPath != null,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun TopStatusBar(
    modelName: String,
    engineState: app.muka.bonsai.llama.InferenceEngine.State,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = modelName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "状态：${stateName(engineState)}",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            IconButton(onClick = onClear) {
                Icon(Icons.Default.ClearAll, contentDescription = "Clear chat")
            }
        }
    }
}

@Composable
internal fun MessageList(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // reverseLayout anchors the newest message to the viewport bottom: bubble
    // growth (streaming text AND async mermaid/MathJax SVG landing) extends
    // upward, so following the stream needs no corrective scroll at all and
    // cannot flicker. While the user scrolls up, Compose pins the first
    // visible item, keeping their reading position stable.
    var followBottom by remember { mutableStateOf(true) }
    val isAtBottom by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset <= 48
        }
    }
    // Follow mode changes only when a scroll (user or programmatic) settles.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) {
                followBottom = isAtBottom
                if (followBottom &&
                    (listState.firstVisibleItemIndex != 0 ||
                        listState.firstVisibleItemScrollOffset > 0)
                ) {
                    // Settled within the bottom tolerance: snap to exact end.
                    listState.scrollToItem(0)
                }
            }
        }
    }

    // New message (sent or received): always jump to the end.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            followBottom = true
            listState.animateScrollToItem(0)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        reverseLayout = true,
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            count = messages.size,
            key = { messages.size - 1 - it }
        ) { i ->
            MessageBubble(messages[messages.size - 1 - i])
        }
    }
}

@Composable
internal fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == ChatMessage.Role.User
    val background = if (isUser) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.secondaryContainer

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = background),
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = if (isUser) "你" else "Bonsai",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (isUser) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    val parsed = remember(message.text) { ThinkParser.split(message.text) }
                    if (parsed.hasThink) {
                        ThinkSection(
                            think = parsed.think,
                            isThinking = parsed.isThinking && message.isGenerating,
                        )
                        if (parsed.answer.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }
                    if (parsed.answer.isNotEmpty()) {
                        MarkdownText(
                            text = parsed.answer,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isGenerating: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .padding(8.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(if (enabled) "输入消息…" else "请在“模型”页加载模型") },
            enabled = enabled && !isGenerating,
            modifier = Modifier.weight(1f),
            maxLines = 4
        )
        if (isGenerating) {
            IconButton(onClick = onStop) {
                Icon(Icons.Default.Stop, contentDescription = "停止")
            }
        } else {
            IconButton(
                onClick = onSend,
                enabled = enabled && value.isNotBlank()
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
            }
        }
    }
}

@Composable
private fun PerformanceCard(
    metrics: PerformanceMetrics,
    isGenerating: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.95f)
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = "性能指标",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            MetricRow("已生成", "${metrics.tokensGenerated}")
            MetricRow("速度", String.format("%.2f tok/s", metrics.tokensPerSecond))
            MetricRow("耗时", "${metrics.totalDurationMs / 1000}s")
            MetricRow("Native 堆", "${metrics.nativeHeapMb} MB")
            MetricRow("总 PSS", "${metrics.totalPssMb} MB")
            if (isGenerating) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "生成中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(modifier = Modifier.width(160.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun File(path: String): java.io.File = java.io.File(path)

private fun stateName(state: app.muka.bonsai.llama.InferenceEngine.State): String = when (state) {
    is app.muka.bonsai.llama.InferenceEngine.State.Uninitialized -> "未初始化"
    is app.muka.bonsai.llama.InferenceEngine.State.Initializing -> "初始化中"
    is app.muka.bonsai.llama.InferenceEngine.State.Initialized -> "已初始化"
    is app.muka.bonsai.llama.InferenceEngine.State.LoadingModel -> "加载中"
    is app.muka.bonsai.llama.InferenceEngine.State.UnloadingModel -> "卸载中"
    is app.muka.bonsai.llama.InferenceEngine.State.ModelReady -> "模型就绪"
    is app.muka.bonsai.llama.InferenceEngine.State.Benchmarking -> "基准测试中"
    is app.muka.bonsai.llama.InferenceEngine.State.ProcessingSystemPrompt -> "处理系统提示词"
    is app.muka.bonsai.llama.InferenceEngine.State.ProcessingUserPrompt -> "处理用户输入"
    is app.muka.bonsai.llama.InferenceEngine.State.Generating -> "生成中"
    is app.muka.bonsai.llama.InferenceEngine.State.Error -> "错误"
}
