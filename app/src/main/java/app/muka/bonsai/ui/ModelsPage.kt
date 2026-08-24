package app.muka.bonsai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.muka.bonsai.model.AVAILABLE_MODELS
import app.muka.bonsai.model.BonsaiModel
import app.muka.bonsai.model.DownloadStatus
import java.io.File

@Composable
fun ModelsPage(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsState()

    // Multi-file import: pick a GGUF model and its mmproj projector together.
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) viewModel.importFiles(uris) }

    // Per-model mmproj association: remember which model the picker targets.
    var associateTarget by remember { mutableStateOf<File?>(null) }
    val mmprojLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val target = associateTarget
        if (uri != null && target != null) viewModel.associateMmproj(target, uri)
        associateTarget = null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Text(
            text = "模型",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(16.dp)
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(AVAILABLE_MODELS) { model ->
                val status = uiState.downloadStatuses[model.id]
                    ?: if (uiState.localModels.any { it.name == model.filename }) {
                        DownloadStatus.Downloaded(viewModel.modelManager.modelFile(model))
                    } else {
                        DownloadStatus.NotDownloaded
                    }

                ModelCard(
                    model = model,
                    status = status,
                    isLoaded = uiState.modelPath == viewModel.modelManager.modelFile(model).absolutePath,
                    mmprojPresent = viewModel.modelManager.hasMmproj(model),
                    mmprojMissing = model.id in uiState.mmprojMissingIds,
                    onDownload = { viewModel.downloadModel(model) },
                    onLoad = { viewModel.loadModel(viewModel.modelManager.modelFile(model)) },
                    onDelete = { viewModel.deleteModel(model) }
                )
            }

            item {
                Text(
                    text = "本地模型",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
            }

            items(uiState.localModels) { file ->
                LocalModelCard(
                    file = file,
                    isLoaded = uiState.modelPath == file.absolutePath,
                    mmprojName = viewModel.modelManager.mmprojFileFor(file)
                        .takeIf { it.exists() }
                        ?.name,
                    onLoad = { viewModel.loadModel(file) },
                    onDelete = { viewModel.deleteLocalFile(file) },
                    onAssociateMmproj = {
                        associateTarget = file
                        mmprojLauncher.launch(arrayOf("*/*"))
                    },
                    onRemoveMmproj = { viewModel.removeMmproj(file) }
                )
            }

            if (uiState.mmprojFiles.isNotEmpty()) {
                item {
                    Text(
                        text = "未配对的多模态投影（mmproj）",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                    )
                }
                items(uiState.mmprojFiles) { mmproj ->
                    OrphanMmprojCard(
                        file = mmproj,
                        pairedModels = uiState.localModels
                            .filter { viewModel.modelManager.mmprojFileFor(it).name == mmproj.name }
                    )
                }
            }
        }

        OutlinedButton(
            onClick = { importLauncher.launch(arrayOf("*/*")) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text("导入本地模型（GGUF / mmproj，可多选）")
        }
    }
}

@Composable
private fun ModelCard(
    model: BonsaiModel,
    status: DownloadStatus,
    isLoaded: Boolean,
    mmprojPresent: Boolean,
    mmprojMissing: Boolean,
    onDownload: () -> Unit,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
) {
    var showDelete by remember { mutableStateOf(false) }
    val totalFootprint = model.footprintGiB + (model.mmprojFootprintGiB ?: 0.0)

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "${model.family} ${model.sizeParam}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${model.filename} · ~${String.format("%.1f", totalFootprint)} GiB",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (model.mmprojFilename != null) {
                        Text(
                            text = if (mmprojPresent) "多模态 · 支持图片输入" else "多模态 · mmproj 未安装",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (mmprojPresent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                when {
                    isLoaded -> {
                        Text(
                            text = "已加载",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val isDownloaded = status.type == DownloadStatus.Type.Downloaded
            val isDownloading = status.type == DownloadStatus.Type.Downloading
            val isFailed = status.type == DownloadStatus.Type.Failed

            if (isDownloaded) {
                Button(onClick = onLoad, modifier = Modifier.fillMaxWidth()) {
                    Text(if (isLoaded) "重新加载" else "加载")
                }
                if (mmprojMissing) {
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = onDownload,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("下载 mmproj（多模态）")
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { showDelete = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "删除")
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("删除")
                }
            } else if (isDownloading) {
                Column {
                    LinearProgressIndicator(
                        progress = { status.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${(status.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            } else if (isFailed) {
                Text(
                    text = status.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("重试")
                }
            } else {
                Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (mmprojMissing) "下载 mmproj" else "下载")
                }
            }
        }
    }

    if (showDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除模型？") },
            text = { Text("这将从设备存储中移除 ${model.filename}${if (model.mmprojFilename != null) " 及其 mmproj" else ""}。") },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { onDelete(); showDelete = false }
                ) { Text("删除") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showDelete = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun LocalModelCard(
    file: File,
    isLoaded: Boolean,
    mmprojName: String?,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
    onAssociateMmproj: () -> Unit,
    onRemoveMmproj: () -> Unit,
) {
    var showDelete by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = String.format("%.2f GiB", file.length() / 1024.0 / 1024.0 / 1024.0),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = if (mmprojName != null) "多模态 · $mmprojName" else "仅文本 · 未关联 mmproj",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (mmprojName != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isLoaded) {
                    Text(
                        text = "已加载",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
                IconButton(onClick = onLoad) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "加载")
                }
                IconButton(onClick = { showDelete = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "删除")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (mmprojName == null) {
                    OutlinedButton(onClick = onAssociateMmproj) {
                        Icon(Icons.Default.AttachFile, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("关联 mmproj…")
                    }
                } else {
                    OutlinedButton(
                        onClick = onAssociateMmproj,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.AttachFile, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("更换 mmproj")
                    }
                    OutlinedButton(onClick = onRemoveMmproj) {
                        Text("移除 mmproj")
                    }
                }
            }
        }
    }

    if (showDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除文件？") },
            text = { Text("这将移除 ${file.name}。") },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { onDelete(); showDelete = false }
                ) { Text("删除") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showDelete = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun OrphanMmprojCard(file: File, pairedModels: List<File>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = pairedModels.takeIf { it.isNotEmpty() }?.joinToString { it.name }
                    ?: "尚未与任何模型配对，可在模型卡片上“关联 mmproj”",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
