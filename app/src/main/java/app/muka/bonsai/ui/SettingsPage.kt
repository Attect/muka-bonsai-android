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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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

    // Which model's profile is being edited: the loaded model wins, otherwise
    // the profile chosen in the selector below, otherwise the selected model.
    val profileTarget = uiState.modelPath?.let { java.io.File(it).name }
        ?: uiState.profileFileName
        ?: uiState.selectedModel?.let { viewModel.modelManager.modelFile(it).name }

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

        SettingCard(title = "目标模型") {
            var expanded by remember { mutableStateOf(false) }
            Column {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = profileTarget ?: "选择要配置的模型…",
                        color = if (profileTarget != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    uiState.localModels.forEach { file ->
                        DropdownMenuItem(
                            text = { Text(file.name) },
                            onClick = {
                                viewModel.selectProfile(file.name)
                                expanded = false
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = when {
                        uiState.modelPath != null -> "已加载该模型；以下设置为它独立保存"
                        profileTarget != null -> "模型未加载；以下设置将在加载它时生效"
                        else -> "选择的模型与配置一一绑定，随切换自动切换"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        SectionHeader("生成参数（即时生效）")

        SettingCard(title = "最大输出长度") {
            IntSlider(
                value = params.maxTokens,
                onValueChange = { viewModel.updateParams(params.copy(maxTokens = it)) },
                range = 64..32768,
                step = 256,
                label = "${params.maxTokens} tokens",
                enabled = profileTarget != null
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingCard(title = "温度 (Temperature)") {
            FloatSlider(
                value = params.sampling.temperature,
                onValueChange = { viewModel.updateParams(params.copy(sampling = params.sampling.copy(temperature = it))) },
                valueRange = 0.0f..1.5f,
                label = String.format("%.2f", params.sampling.temperature),
                enabled = profileTarget != null
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingCard(title = "Top-K / Top-P") {
            IntSlider(
                value = params.sampling.topK,
                onValueChange = { viewModel.updateParams(params.copy(sampling = params.sampling.copy(topK = it))) },
                range = 1..200,
                step = 1,
                label = "top_k = ${params.sampling.topK}",
                enabled = profileTarget != null
            )
            Spacer(modifier = Modifier.height(8.dp))
            FloatSlider(
                value = params.sampling.topP,
                onValueChange = { viewModel.updateParams(params.copy(sampling = params.sampling.copy(topP = it))) },
                valueRange = 0.0f..1.0f,
                label = String.format("top_p = %.2f", params.sampling.topP),
                enabled = profileTarget != null
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingCard(title = "惩罚") {
            FloatSlider(
                value = params.sampling.repeatPenalty,
                onValueChange = { viewModel.updateParams(params.copy(sampling = params.sampling.copy(repeatPenalty = it))) },
                valueRange = 0.5f..2.0f,
                label = String.format("重复惩罚 = %.2f（1.0 关闭）", params.sampling.repeatPenalty),
                enabled = profileTarget != null
            )
            Spacer(modifier = Modifier.height(8.dp))
            FloatSlider(
                value = params.sampling.frequencyPenalty,
                onValueChange = { viewModel.updateParams(params.copy(sampling = params.sampling.copy(frequencyPenalty = it))) },
                valueRange = 0.0f..2.0f,
                label = String.format("频率惩罚 = %.2f（0 关闭）", params.sampling.frequencyPenalty),
                enabled = profileTarget != null
            )
            Spacer(modifier = Modifier.height(8.dp))
            FloatSlider(
                value = params.sampling.presencePenalty,
                onValueChange = { viewModel.updateParams(params.copy(sampling = params.sampling.copy(presencePenalty = it))) },
                valueRange = 0.0f..2.0f,
                label = String.format("存在惩罚 = %.2f（0 关闭）", params.sampling.presencePenalty),
                enabled = profileTarget != null
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingCard(title = "种子 (Seed)") {
            OutlinedTextField(
                value = params.sampling.seed.toString(),
                onValueChange = { text ->
                    text.toIntOrNull()?.let { seed ->
                        viewModel.updateParams(params.copy(sampling = params.sampling.copy(seed = seed)))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("-1 = 随机；固定值可复现输出") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = profileTarget != null
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        SectionHeader("加载参数（重新加载模型后生效）")

        SettingCard(title = "上下文长度") {
            val safeContextSize = params.contextSize.coerceIn(2048, maxContext)
            IntSlider(
                value = safeContextSize,
                onValueChange = { viewModel.updateParams(params.copy(contextSize = it)) },
                range = 2048..maxContext,
                step = 2048,
                label = "$safeContextSize tokens（当前模型上限：$maxContext）",
                enabled = profileTarget != null
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingCard(title = "推理线程数") {
            val maxThreads = Runtime.getRuntime().availableProcessors()
            val safeThreadCount = params.threadCount.coerceIn(1, maxThreads)
            IntSlider(
                value = safeThreadCount,
                onValueChange = { viewModel.updateParams(params.copy(threadCount = it)) },
                range = 1..maxThreads,
                step = 1,
                label = "$safeThreadCount 线程（处理器线程数：$maxThreads）",
                enabled = profileTarget != null
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingCard(title = "KV 缓存量化") {
            KvCacheType.entries.forEach { type ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = params.kvCacheType == type,
                            onClick = { viewModel.updateParams(params.copy(kvCacheType = type)) },
                            enabled = profileTarget != null
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = params.kvCacheType == type,
                        onClick = { viewModel.updateParams(params.copy(kvCacheType = type)) },
                        enabled = profileTarget != null
                    )
                    Text(
                        text = type.label,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Text(
                text = "量化可显著减少 KV 缓存内存（Q4 约为 1/4），长上下文收益更大",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingCard(title = "系统提示词") {
            OutlinedTextField(
                value = params.systemPrompt,
                onValueChange = { viewModel.updateParams(params.copy(systemPrompt = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("系统提示词") },
                minLines = 3,
                maxLines = 6,
                enabled = profileTarget != null
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { viewModel.reloadModel() },
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState.modelPath != null
        ) {
            Text("重新加载模型以应用加载参数")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { viewModel.unloadModel() },
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState.modelPath != null
        ) {
            Text("卸载模型")
        }

        Spacer(modifier = Modifier.height(12.dp))

        SectionHeader("全局")

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
                            text = "开启后外部应用可通过 OpenAI 兼容接口调用（需先加载模型，或由请求自动切换）",
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

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "API 请求自动切换模型",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (uiState.apiAutoSwitchModel)
                            "按请求中的 model 字段自动加载对应本地模型；无法匹配时返回 404"
                        else
                            "忽略请求中的 model 字段，始终使用当前加载的模型",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = uiState.apiAutoSwitchModel,
                    onCheckedChange = { viewModel.setApiAutoSwitchModel(it) }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedButton(
            onClick = { viewModel.selectTab(Screen.About) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("关于")
        }

        if (BuildConfig.DEBUG) {
            Spacer(modifier = Modifier.height(8.dp))

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
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp)
    )
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
    label: String,
    enabled: Boolean = true
) {
    val stepsCount = ((range.last - range.first) / step) - 1
    Slider(
        value = value.toFloat(),
        onValueChange = { onValueChange(it.toInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = stepsCount.coerceAtLeast(0),
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled
    )
    Text(text = label, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun FloatSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    label: String,
    enabled: Boolean = true
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled
    )
    Text(text = label, style = MaterialTheme.typography.bodySmall)
}
