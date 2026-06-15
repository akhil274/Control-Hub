package com.example.controlhub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import com.example.controlhub.ui.components.BluetoothConnectionSection
import com.example.controlhub.ui.components.DeviceCard
import com.example.controlhub.ui.components.DrawerContent
import com.example.controlhub.ui.components.RTCTimeSyncButton
import com.example.controlhub.ui.theme.ControlHubTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: RelayViewModel by viewModels {
        RelayViewModelFactory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ControlHubTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: RelayViewModel) {
    val isDarkTheme = isSystemInDarkTheme()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // State for overflow menu
    var showOverflowMenu by remember { mutableStateOf(false) }
    // State for add device dialog
    var showAddDeviceDialog by remember { mutableStateOf(false) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DrawerContent()
        },
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f),
        content = {
            Scaffold(
                topBar = {
                    // Frosted glass-effect top bar
                    TopAppBar(
                        title = {
                            Image(
                                painter = painterResource(
                                    id = if (isDarkTheme) R.drawable.logo else R.drawable.logo_black
                                ),
                                contentDescription = "ControlHub Logo",
                                modifier = Modifier
                                    .height(32.dp)
                                    .fillMaxWidth()
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = {
                                scope.launch { drawerState.open() }
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = "Open Drawer",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        },
                        actions = {
                            // Bluetooth status indicator (compact)
                            if (viewModel.isConnected.value) {
                                Box(
                                    modifier = Modifier
                                        .padding(end = 4.dp)
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(com.example.controlhub.ui.theme.GreenSuccess)
                                )
                            }


                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.6f)
                        )
                    )
                },
                // Floating Action Button — Add Device
                floatingActionButton = {
                    if (viewModel.devices.size < RelayViewModel.MAX_DEVICES) {
                        FloatingActionButton(
                            onClick = { showAddDeviceDialog = true },
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            shape = CircleShape
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add Device",
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                },
                containerColor = MaterialTheme.colorScheme.background
            ) { padding ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // ═══════════════════════════════════════
                    // Bluetooth Connection Section
                    // ═══════════════════════════════════════
                    item {
                        BluetoothConnectionSection(viewModel)
                    }

                    // ═══════════════════════════════════════
                    // Ecosystem Header + RTC Sync
                    // ═══════════════════════════════════════
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Column {
                                Text(
                                    text = "My Devices",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Real-time status & schedules",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            RTCTimeSyncButton(viewModel)
                        }
                    }

                    // ═══════════════════════════════════════
                    // Device Cards List
                    // ═══════════════════════════════════════
                    items(
                        items = viewModel.devices,
                        key = { it.id }
                    ) { device ->
                        val relayState = viewModel.getRelayState(device.id)
                        val isExpanded = viewModel.expandedDeviceId.value == device.id

                        DeviceCard(
                            device = device,
                            relayState = relayState,
                            viewModel = viewModel,
                            isExpanded = isExpanded,
                            onExpandToggle = {
                                viewModel.expandedDeviceId.value =
                                    if (isExpanded) -1 else device.id
                            }
                        )
                    }

                    if (viewModel.devices.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No devices added yet.\nClick the '+' icon to add your devices.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }

                    // Bottom spacer for FAB clearance
                    item {
                        Spacer(modifier = Modifier.height(80.dp))
                    }
                }

                // ═══════════════════════════════════════
                // Dialogs
                // ═══════════════════════════════════════

                // RTC time set confirmation dialog
                if (viewModel.rtcTimeSetEvent.value) {
                    AlertDialog(
                        onDismissRequest = { viewModel.resetRtcTimeSetEvent() },
                        title = {
                            Text(
                                "✅ RTC Time Set",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        text = {
                            Text(
                                "The RTC time has been successfully synchronized.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { viewModel.resetRtcTimeSetEvent() }) {
                                Text("Done", color = MaterialTheme.colorScheme.primary)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(24.dp)
                    )
                }

                // Settings saved confirmation dialog
                if (viewModel.settingsSavedEvent.value) {
                    AlertDialog(
                        onDismissRequest = { viewModel.resetSettingsSavedEvent() },
                        title = {
                            Text(
                                "✅ Settings Saved",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        text = {
                            Text(
                                "Your schedule has been saved and sent to the device.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { viewModel.resetSettingsSavedEvent() }) {
                                Text("Done", color = MaterialTheme.colorScheme.primary)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(24.dp)
                    )
                }

                // Add Device Dialog
                if (showAddDeviceDialog) {
                    AddDeviceDialog(
                        usedSockets = viewModel.getUsedSocketIds(),
                        onDismiss = { showAddDeviceDialog = false },
                        onAdd = { name, icon, socketId ->
                            viewModel.addDevice(name, icon, socketId)
                            showAddDeviceDialog = false
                        }
                    )
                }
            }
        }
    )
}

/**
 * Dialog for adding a new device.
 * User picks a name and an icon type.
 */
@Composable
fun AddDeviceDialog(
    usedSockets: Set<Int>,
    onDismiss: () -> Unit,
    onAdd: (name: String, iconType: String, socketId: Int) -> Unit
) {
    var deviceName by remember { mutableStateOf("") }
    var selectedIcon by remember { mutableStateOf("lightbulb") }

    // Pick first available socket by default
    var selectedSocket by remember(usedSockets) {
        mutableIntStateOf((1..RelayViewModel.MAX_DEVICES).firstOrNull { it !in usedSockets } ?: 1)
    }

    val iconOptions = listOf(
        "lightbulb" to "💡 Light",
        "co2" to "🫧 CO₂",
        "water_drop" to "💧 Pump",
        "thermostat" to "🌡️ Heater"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Add Device",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Device name input
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = { deviceName = it },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    label = {
                        Text(
                            "Device Name",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                )

                // Icon type selection
                Text(
                    "Device Type",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())
                ) {
                    iconOptions.forEach { (iconKey, label) ->
                        val isSelected = selectedIcon == iconKey
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedIcon = iconKey },
                            label = {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                selectedLabelColor = MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }

                // Output Socket selection
                Text(
                    "Output Socket",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())
                ) {
                    (1..RelayViewModel.MAX_DEVICES).forEach { socketId ->
                        val isUsed = socketId in usedSockets
                        val isSelected = selectedSocket == socketId
                        FilterChip(
                            selected = isSelected,
                            onClick = { if (!isUsed) selectedSocket = socketId },
                            label = {
                                Text(
                                    text = "S$socketId",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                selectedLabelColor = MaterialTheme.colorScheme.primary,
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            ),
                            enabled = !isUsed || isSelected,
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val name = deviceName.ifBlank {
                        iconOptions.find { it.first == selectedIcon }?.second?.substringAfter(" ") ?: "Device"
                    }
                    onAdd(name, selectedIcon, selectedSocket)
                },
                enabled = selectedSocket in 1..RelayViewModel.MAX_DEVICES && selectedSocket !in usedSockets
            ) {
                Text("Add", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(24.dp)
    )
}