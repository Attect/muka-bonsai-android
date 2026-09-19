package app.muka.bonsai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Text to analyze plus the in-memory filter over the resulting tokens. */
data class VocabInput(
    val text: String = "",
    val filter: String = "",
    val regex: Boolean = false,
    val ignoreCase: Boolean = true,
)

/** One distinct token of the analyzed text and how often it occurred. */
data class VocabFreq(val id: Int, val text: String, val count: Int)

/** Outcome of a vocabulary analysis run. */
data class VocabAnalysis(
    val charCount: Int,
    val tokenCount: Int,
    val uniqueCount: Int,
    /** Whether detokenizing the whole sequence reproduces the input verbatim. */
    val restoredOk: Boolean,
    val restored: String?,
    val freqs: List<VocabFreq>,
)

@Composable
fun VocabPage(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsState()
    val input = uiState.vocabInput
    val analysis = uiState.vocabAnalysis

    val pattern = if (input.regex && input.filter.isNotEmpty()) {
        runCatching {
            Regex(input.filter, if (input.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
        }.getOrNull()
    } else null
    val regexBroken = input.regex && input.filter.isNotEmpty() && pattern == null

    val rows = when {
        analysis == null || input.filter.isEmpty() -> analysis?.freqs ?: emptyList()
        pattern != null -> analysis.freqs.filter { pattern.containsMatchIn(it.text) }
        regexBroken -> emptyList()
        else -> analysis.freqs.filter {
            it.text.contains(input.filter, ignoreCase = input.ignoreCase)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(Modifier.padding(top = 16.dp)) {
                Text("词表分析", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "用当前加载模型的分词器统计输入文本的分词频次，并检验这些 token 能否还原成原文。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = input.text,
                    onValueChange = { viewModel.setVocabInput(input.copy(text = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("待分析的输入文本") },
                    minLines = 4,
                    maxLines = 8,
                )
                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { viewModel.analyzeVocabulary() },
                        enabled = input.text.isNotEmpty() && !uiState.vocabAnalyzing,
                    ) { Text("分析") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            val transcript = uiState.messages
                                .filter { it.role == ChatMessage.Role.User }
                                .joinToString("\n") { it.text }
                            viewModel.setVocabInput(input.copy(text = transcript))
                        },
                        enabled = uiState.messages.any { it.role == ChatMessage.Role.User },
                    ) { Text("取用对话输入") }
                }

                if (uiState.vocabAnalyzing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                uiState.vocabError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        if (analysis != null) {
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "${uiState.vocabSource ?: "当前模型"}：${analysis.charCount} 字符 -> ${analysis.tokenCount} token，去重 ${analysis.uniqueCount}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            if (analysis.restoredOk) "可还原：是" else "可还原：否（见下方还原结果）",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (analysis.restoredOk) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                        if (!analysis.restoredOk && analysis.restored != null) {
                            Spacer(Modifier.height(4.dp))
                            Text("还原文本：${analysis.restored.escaped()}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = input.filter,
                    onValueChange = { viewModel.setVocabInput(input.copy(filter = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(if (input.regex) "正则过滤 token" else "过滤包含…的 token") },
                    supportingText = {
                        Text(
                            when {
                                regexBroken -> "正则无法编译"
                                else -> "例：<| 或  (?:[^<]|<(?!<\\|))*+"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { viewModel.setVocabInput(input.copy(regex = !input.regex)) }
                    ) { Text(if (input.regex) "模式：正则" else "模式：子串") }
                    Spacer(Modifier.width(8.dp))
                    Text("忽略大小写", style = MaterialTheme.typography.bodySmall)
                    Checkbox(
                        checked = input.ignoreCase,
                        onCheckedChange = { viewModel.setVocabInput(input.copy(ignoreCase = it)) },
                    )
                }
                Text(
                    "显示 ${rows.size} / ${analysis.uniqueCount} 个 token",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            items(rows, key = { it.id }) { freq ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${freq.count}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(48.dp),
                        )
                        Text(
                            "#${freq.id}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(72.dp),
                        )
                        Text(
                            freq.text.escaped(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }

        if (analysis == null && !uiState.vocabAnalyzing && uiState.vocabError == null) {
            item {
                Text(
                    "先在模型页加载一个模型，然后输入文本或取用对话内容进行分析。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Make control characters visible so token text stays readable in a list. */
private fun String.escaped(): String {
    val src = this
    return buildString(src.length) {
        for (c in src) when {
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c.isISOControl() -> append("\\u%04X".format(c.code))
            else -> append(c)
        }
    }
}
