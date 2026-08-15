package com.example.controlhub

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.controlhub.ui.screens.DevicesScreen
import com.example.controlhub.ui.screens.NotesScreen
import com.example.controlhub.ui.screens.SettingsScreen

enum class AppTab(val route: String, val label: String, val icon: ImageVector) {
    Devices("devices", "Devices", Icons.Default.Home),
    Notes("notes", "Notes", Icons.Default.Edit),
    Settings("settings", "Settings", Icons.Default.Settings)
}

@Composable
fun AppNavigation(viewModel: RelayViewModel) {
    var currentTab by remember { mutableStateOf(AppTab.Devices) }
    val showBottomBar = viewModel.activeDetailDevice.value == null
    val context = LocalContext.current

    // Centralized one-shot event consumption — keyed on the event value so
    // LaunchedEffect re-triggers even for rapid successive events.

    val rtcTimeSet = viewModel.rtcTimeSetEvent.value
    LaunchedEffect(rtcTimeSet) {
        if (rtcTimeSet) {
            android.widget.Toast.makeText(context, "RTC Time synchronized successfully!", android.widget.Toast.LENGTH_SHORT).show()
            viewModel.resetRtcTimeSetEvent()
        }
    }

    val rtcSyncFailed = viewModel.rtcTimeSyncFailedEvent.value
    LaunchedEffect(rtcSyncFailed) {
        if (rtcSyncFailed) {
            android.widget.Toast.makeText(context, "RTC Time sync failed! Check RTC hardware.", android.widget.Toast.LENGTH_SHORT).show()
            viewModel.resetRtcTimeSyncFailedEvent()
        }
    }

    val disconnected = viewModel.disconnectionEvent.value
    LaunchedEffect(disconnected) {
        if (disconnected) {
            viewModel.activeDetailDevice.value = null
            android.widget.Toast.makeText(context, "Device disconnected.", android.widget.Toast.LENGTH_SHORT).show()
            viewModel.disconnectionEvent.value = false
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = NavigationBarDefaults.Elevation
                ) {
                    AppTab.entries.forEach { tab ->
                        val selected = currentTab == tab
                        NavigationBarItem(
                            selected = selected,
                            onClick = { currentTab = tab },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.label
                                )
                            },
                            label = {
                                Text(
                                    text = tab.label,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        )
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Crossfade(
            targetState = currentTab,
            modifier = Modifier
                .fillMaxSize()
                .padding(if (showBottomBar) paddingValues else PaddingValues(0.dp)),
            label = "TabTransition"
        ) { tab ->
            when (tab) {
                AppTab.Devices -> {
                    DevicesScreen(viewModel = viewModel)
                }
                AppTab.Notes -> {
                    NotesScreen(viewModel = viewModel)
                }
                AppTab.Settings -> {
                    SettingsScreen(viewModel = viewModel)
                }
            }
        }
    }
}
