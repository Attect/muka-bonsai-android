package app.muka.bonsai.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel

private data class NavItem(
    val screen: Screen,
    val label: String,
    val icon: ImageVector,
)

private val navItems = listOf(
    NavItem(Screen.Chat, "聊天", Icons.AutoMirrored.Filled.Chat),
    NavItem(Screen.Models, "模型", Icons.Default.Download),
    NavItem(Screen.Settings, "设置", Icons.Default.Settings),
)

@Composable
fun MainScreen(viewModel: ChatViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(uiState.infoMessage) {
        uiState.infoMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearInfo()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                navItems.forEach { item ->
                    NavigationBarItem(
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                        selected = uiState.selectedTab == item.screen,
                        onClick = { viewModel.selectTab(item.screen) }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        when (uiState.selectedTab) {
            Screen.Chat -> ChatPage(
                viewModel = viewModel,
                modifier = Modifier.padding(padding)
            )
            Screen.Models -> ModelsPage(
                viewModel = viewModel,
                modifier = Modifier.padding(padding)
            )
            Screen.Settings -> SettingsPage(
                viewModel = viewModel,
                modifier = Modifier.padding(padding)
            )
            Screen.Test -> RenderTestPage(
                modifier = Modifier.padding(padding)
            )
        }
    }
}
