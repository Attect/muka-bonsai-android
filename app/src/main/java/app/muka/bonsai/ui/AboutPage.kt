package app.muka.bonsai.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.muka.bonsai.BuildConfig
import app.muka.bonsai.R

@Composable
fun AboutPage(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { viewModel.selectTab(Screen.Settings) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "关于",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(72.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = "Bonsai 图标",
                    modifier = Modifier.padding(14.dp)
                )
            }
            Column {
                Text(
                    text = "Bonsai",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "版本 ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        AboutCard(title = "简介") {
            Text(
                text = "Muka.Cool 出品的 AI 模型推理引擎与前端，针对 Android 平台进行了适配与优化。",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        AboutCard(title = "出品方") {
            Text(
                text = "Muka.Cool",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { uriHandler.openUri("https://muka.cool") }
            )
        }

        AboutCard(title = "模型") {
            Text(
                text = "模型来自 PrismML",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "支持 Bonsai 1（Q1_0 / Q2_0 g128）与 Bonsai 2（PQ2_0）的本地推理与多模态输入；"
                    + "Bonsai 2 的 PTQ1_0 目前只有 CPU 内核，加载时会提示改用 PQ2_0。",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "https://prismml.com/",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { uriHandler.openUri("https://prismml.com/") }
            )
        }

        AboutCard(title = "开发者") {
            Text(
                text = "Attect，使用 Kimi K3 完成开发",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        AboutCard(title = "开源协议") {
            Text(
                text = "MIT License\nCopyright (c) 2026 Attect",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        TextButton(
            onClick = { viewModel.selectTab(Screen.Licenses) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("查看开源组件许可")
        }
    }
}

@Composable
private fun AboutCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            content()
        }
    }
}

private data class OssComponent(
    val name: String,
    val license: String,
    val licenseFile: String,
    val copyright: String,
    val url: String,
    val note: String? = null,
)

private val ossComponents = listOf(
    OssComponent(
        name = "llama.cpp",
        license = "MIT",
        licenseFile = "mit.txt",
        copyright = "Copyright (c) 2023-2024 The ggml authors",
        url = "https://github.com/ggml-org/llama.cpp",
        note = "含 Q2_0 g128 三值格式本地定制（Attect/llama.cpp-muka）"
    ),
    OssComponent(
        name = "Kotlin",
        license = "Apache-2.0",
        licenseFile = "apache-2.0.txt",
        copyright = "Copyright (c) JetBrains s.r.o.",
        url = "https://kotlinlang.org/"
    ),
    OssComponent(
        name = "AndroidX / Jetpack Compose",
        license = "Apache-2.0",
        licenseFile = "apache-2.0.txt",
        copyright = "Copyright (c) The Android Open Source Project",
        url = "https://developer.android.com/jetpack"
    ),
    OssComponent(
        name = "Material Icons",
        license = "Apache-2.0",
        licenseFile = "apache-2.0.txt",
        copyright = "Copyright (c) Google LLC",
        url = "https://fonts.google.com/icons"
    ),
    OssComponent(
        name = "commonmark-java",
        license = "BSD-2-Clause",
        licenseFile = "bsd-2-clause.txt",
        copyright = "Copyright (c) 2015-2016, Atlassian Pty Ltd",
        url = "https://github.com/commonmark/commonmark-java"
    ),
    OssComponent(
        name = "AndroidSVG",
        license = "Apache-2.0",
        licenseFile = "apache-2.0.txt",
        copyright = "Copyright (c) Paul LeBeau",
        url = "https://bigbadaboom.github.io/androidsvg/"
    ),
    OssComponent(
        name = "MathJax",
        license = "Apache-2.0",
        licenseFile = "apache-2.0.txt",
        copyright = "Copyright (c) The MathJax Consortium",
        url = "https://www.mathjax.org/"
    ),
    OssComponent(
        name = "Mermaid",
        license = "MIT",
        licenseFile = "mit.txt",
        copyright = "Copyright (c) Knut Sveidqvist",
        url = "https://mermaid.js.org/"
    ),
    OssComponent(
        name = "OpenCL Headers / ICD Loader",
        license = "Apache-2.0",
        licenseFile = "apache-2.0.txt",
        copyright = "Copyright (c) The Khronos Group Inc.",
        url = "https://github.com/KhronosGroup/OpenCL-ICD-Loader"
    ),
)

@Composable
fun LicensesPage(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            IconButton(onClick = { viewModel.selectTab(Screen.About) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "开源组件许可",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ossComponents, key = { it.name }) { component ->
                LicenseCard(component)
            }
        }
    }
}

@Composable
private fun LicenseCard(component: OssComponent) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var expanded by remember { mutableStateOf(false) }
    val licenseText = remember(expanded) {
        if (!expanded) null
        else runCatching {
            context.assets.open("licenses/${component.licenseFile}")
                .bufferedReader().use { it.readText() }
                // The SPDX MIT template carries a placeholder copyright line;
                // the real one is already shown on the card above.
                .replace("Copyright (c) <year> <copyright holders>\n", "")
        }.getOrElse { "许可文本加载失败：${it.message}" }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = component.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = component.license,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp
                    else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开"
                )
            }
            Text(
                text = component.copyright,
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                text = component.url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { uriHandler.openUri(component.url) }
            )
            component.note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (expanded && licenseText != null) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = licenseText,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
