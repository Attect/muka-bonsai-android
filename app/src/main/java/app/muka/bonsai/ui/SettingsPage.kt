package app.muka.bonsai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.muka.bonsai.BuildConfig
import app.muka.bonsai.llama.InferenceParams
import app.muka.bonsai.llama.KvCacheType
import app.muka.bonsai.model.DownloadSource

@Composable
fun SettingsPage(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsState()
    val params = uiState.params

    // Max context of the currently loaded model (GGUF metadata); falls back to 32768.
    val maxContext = (uiState.modelPath
        ?.let { uiState.modelContextLengths[it] }
        ?: 32768).coerceAtLeast(2048)

    var contextSize by remember { mutableIntStateOf(params.contextSize) }
    var maxTokens by remember { mutableIntStateOf(params.maxTokens) }
    var threadCount by remember { mutableIntStateOf(params.threadCount) }
    var temperature by remember { mutableFloatStateOf(params.temperature) }
    var systemPrompt by remember { mutableStateOf(params.systemPrompt) }
    var kvCacheType by remember { mutableStateOf(params.kvCacheType) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(16.dp))

        SettingCard(title = "上下文长度") {
            val safeContextSize = contextSize.coerceIn(2048, maxContext)
            IntSlider(
                value = safeContextSize,
                onValueChange = { contextSize = it },
                range = 2048..maxContext,
                step = 2048,
                label = "$safeContextSize tokens（当前模型上限：$maxContext）"
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingCard(title = "最大输出长度") {
            IntSlider(
                value = maxTokens,
                onValueChange = { maxTokens = it },
                range = 64..32768,
                step = 256,
                label = "$maxTokens tokens"
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingCard(title = "推理线程数") {
            val maxThreads = remember { Runtime.getRuntime().availableProcessors() }
            val safeThreadCount = threadCount.coerceIn(1, maxThreads)
            IntSlider(
                value = safeThreadCount,
                onValueChange = { threadCount = it },
                range = 1..maxThreads,
                step = 1,
                label = "$safeThreadCount 线程（处理器线程数：$maxThreads）"
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingCard(title = "温度 (Temperature)") {
            Text(text = String.format("%.2f", temperature))
            Slider(
                value = temperature,
                onValueChange = { temperature = it },
                valueRange = 0.0f..1.5f,
                steps = 14,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingCard(title = "KV 缓存量化") {
            KvCacheType.entries.forEach { type ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = kvCacheType == type,
                            onClick = { kvCacheType = type }
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = kvCacheType == type,
                        onClick = { kvCacheType = type }
                    )
                    Text(
                        text = type.label,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Text(
                text = "量化可显著减少 KV 缓存内存（Q4 约为 1/4），长上下文收益更大；需重新加载模型后生效",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingCard(title = "系统提示词") {
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("系统提示词") },
                minLines = 3,
                maxLines = 6
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingCard(title = "下载源") {
            DownloadSource.entries.forEach { source ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = uiState.downloadSource == source,
                            onClick = { viewModel.setDownloadSource(source) }
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = uiState.downloadSource == source,
                        onClick = { viewModel.setDownloadSource(source) }
                    )
                    Text(
                        text = source.label,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Text(
                text = "国内网络建议保持默认的 HF 镜像；立即生效，对之后发起的下载有效",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingCard(title = "OpenAI API 服务") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (uiState.apiServerRunning) "运行中" else "已停止",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (uiState.apiServerRunning) {
                        Spacer(modifier = Modifier.height(4.dp))
                        app.muka.bonsai.api.OpenAiServer.localIpAddresses().forEach { ip ->
                            Text(
                                text = "http://$ip:${uiState.apiServerPort}/v1",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = "端点：GET /v1/models · POST /v1/chat/completions",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "开启后外部应用可通过 OpenAI 兼容接口调用（需先加载模型）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    uiState.apiServerError?.let { error ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "错误：$error",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Switch(
                    checked = uiState.apiServerRunning,
                    onCheckedChange = { viewModel.setApiServerEnabled(it) }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                viewModel.updateParams(
                    InferenceParams(
                        contextSize = contextSize.coerceIn(2048, maxContext),
                        maxTokens = maxTokens,
                        temperature = temperature,
                        threadCount = threadCount,
                        systemPrompt = systemPrompt,
                        kvCacheType = kvCacheType
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("保存设置")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                viewModel.updateParams(
                    InferenceParams(
                        contextSize = contextSize.coerceIn(2048, maxContext),
                        maxTokens = maxTokens,
                        temperature = temperature,
                        threadCount = threadCount,
                        systemPrompt = systemPrompt,
                        kvCacheType = kvCacheType
                    )
                )
                viewModel.reloadModel()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState.modelPath != null
        ) {
            Text("应用设置并重新加载模型")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { viewModel.unloadModel() },
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState.modelPath != null
        ) {
            Text("卸载模型")
        }

        if (BuildConfig.DEBUG) {
            Spacer(modifier = Modifier.height(24.dp))

            OutlinedButton(
                onClick = { viewModel.selectTab(Screen.Test) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Mock 渲染测试")
            }
        }
    }
}

@Composable
private fun SettingCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun IntSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    step: Int,
    label: String
) {
    val stepsCount = ((range.last - range.first) / step) - 1
    Slider(
        value = value.toFloat(),
        onValueChange = { onValueChange(it.toInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = stepsCount.coerceAtLeast(0),
        modifier = Modifier.fillMaxWidth()
    )
    Text(text = label, style = MaterialTheme.typography.bodySmall)
}
