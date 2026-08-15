package com.example.controlhub.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Co2
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.controlhub.R
import com.example.controlhub.RelayViewModel
import com.example.controlhub.ui.components.DeviceCard
import com.example.controlhub.ui.theme.GreenSuccess

@Composable
fun DevicesScreen(viewModel: RelayViewModel) {
    val isConnected by viewModel.isConnected

    if (isConnected) {
        ConnectedDevicesScreen(viewModel = viewModel)
    } else {
        DisconnectedScreen(viewModel = viewModel)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConnectedDevicesScreen(viewModel: RelayViewModel) {
    val isDarkTheme = true
    var showAddDeviceDialog by remember { mutableStateOf(false) }
    var showDisconnectConfirmation by remember { mutableStateOf(false) }

    val activeDetailDevice = viewModel.activeDetailDevice.value

    if (activeDetailDevice != null) {
        DeviceDetailScreen(
            device = activeDetailDevice,
            viewModel = viewModel,
            onBack = {
                // DO NOT call reloadState() here.
                // saveStateManually() is async: it waits for firmware ACK before
                // writing to SharedPreferences. Calling reloadState() right after
                // onBack fires (before the ACK arrives) loads stale prefs and
                // overwrites the correct in-memory isAutoMode=true the user just set.
                // reloadState() is now called inside saveStateManually() after a
                // confirmed save, which is the correct time to sync.
                viewModel.activeDetailDevice.value = null
            }
        )
    } else {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                // Clean centered logo matching Disconnected screen
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(top = 16.dp, bottom = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(
                            id = if (isDarkTheme) R.drawable.logo else R.drawable.logo_black
                        ),
                        contentDescription = "ControlHub Logo",
                        modifier = Modifier.height(38.dp)
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                // 1. Scrollable content (LazyColumn) covering the full area, but with contentPadding at the bottom to avoid being covered by the aquarium scene
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = padding.calculateTopPadding()),
                    contentPadding = PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        top = 8.dp,
                        bottom = 140.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header Section: Devices title + Status chip aligned horizontally
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Devices",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            // Connected device chip with green status dot
                            // Long-pressing it triggers the disconnect confirmation popup
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .combinedClickable(
                                        onClick = {
                                            showDisconnectConfirmation = true
                                        },
                                        onLongClick = {
                                            showDisconnectConfirmation = true
                                        }
                                    )
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(GreenSuccess)
                                    )
                                    Icon(
                                        imageVector = Icons.Default.Bluetooth,
                                        contentDescription = "Connected",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = viewModel.selectedDeviceName.value ?: "ControlCore",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    if (viewModel.rtcFailedState.value) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                ),
                                border = BorderStroke(
                                    width = 2.dp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Warning,
                                            contentDescription = "Warning",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Text(
                                            text = "RTC HARDWARE FAILURE",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                    Text(
                                        text = "The Real-Time Clock (RTC) chip is unresponsive or returned invalid time. A safety shutdown was initiated: all connected devices/relays have been forced OFF to prevent damage or safety hazards.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Text(
                                        text = "All controls are disabled. Please check the hardware connections. You can try to Sync RTC Time in settings to recover.",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    }

                    if (viewModel.rtcLostPowerState.value) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                ),
                                border = BorderStroke(
                                    width = 2.dp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Schedule,
                                            contentDescription = "Time Warning",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Text(
                                            text = "RTC TIME NOT SET",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                    Text(
                                        text = "The controller lost power and the RTC time is not set. All schedules are disabled and connected devices/relays have been forced OFF for safety.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Text(
                                        text = "Sync time from app to restore scheduling.",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                                    )
                                    Button(
                                        onClick = { viewModel.setRTCTime(isManual = true) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            contentColor = MaterialTheme.colorScheme.onError
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Sync,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Sync Time from App", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    // 2. Device Cards List
                    items(
                        items = viewModel.devices,
                        key = { it.id }
                    ) { device ->
                        val relayState = viewModel.getRelayState(device.id)

                        DeviceCard(
                            device = device,
                            relayState = relayState,
                            viewModel = viewModel,
                            onCardClick = {
                                viewModel.activeDetailDevice.value = device
                            }
                        )
                    }

                    // 3. Add-Device Card below the last device card
                    if (viewModel.devices.size < RelayViewModel.MAX_DEVICES) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(80.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f))
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                        shape = RoundedCornerShape(16.dp)
                                    )
                                    .clickable { showAddDeviceDialog = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Add",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Text(
                                        text = "Add Device",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }

                    // 4. Empty State
                    if (viewModel.devices.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 60.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No devices added yet.\nClick the 'Add Device' button to start.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                }

                // 2. Planted Aquarium Scene Graphic fixed at the bottom
                // Aligned to BottomCenter of Box, stretching edge-to-edge
                Image(
                    painter = painterResource(id = R.drawable.planted_aquarium_scene),
                    contentDescription = "Planted Aquarium Scene",
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(130.dp),
                    contentScale = ContentScale.FillWidth,
                    alignment = Alignment.BottomCenter
                )
            }   }

            // =======================================
            // Dialogs
            // =======================================

            // Disconnect confirmation popup dialog (on long-pressing connected chip)
            if (showDisconnectConfirmation) {
                AlertDialog(
                    onDismissRequest = { showDisconnectConfirmation = false },
                    title = {
                        Text(
                            "Disconnect Device",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    text = {
                        Text(
                            "Disconnect from ControlCore?",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.clearSelectedDevice()
                                showDisconnectConfirmation = false
                            }
                        ) {
                            Text("Disconnect", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDisconnectConfirmation = false }) {
                            Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            "Settings Saved",
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

            // Settings save failed dialog
            if (viewModel.settingsSaveFailedEvent.value) {
                AlertDialog(
                    onDismissRequest = { viewModel.resetSettingsSaveFailedEvent() },
                    title = {
                        Text(
                            "Save Failed",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    text = {
                        Text(
                            "Failed to save settings. Please ensure the device is connected and try again.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { viewModel.resetSettingsSaveFailedEvent() }) {
                            Text("Dismiss", color = MaterialTheme.colorScheme.error)
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

/**
 * Dialog for adding a new device.
 * User picks a name and an icon type.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
        "lightbulb" to Icons.Filled.Lightbulb,
        "co2" to Icons.Filled.Co2,
        "water_drop" to Icons.Filled.WaterDrop,
        "thermostat" to Icons.Filled.Thermostat
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Add Device",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
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
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                )

                // Category Icon Selection
                Text(
                    "Device Category Icon",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    iconOptions.forEach { (type, icon) ->
                        val isSelected = selectedIcon == type
                        val chipBgColor = if (isSelected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        }
                        val chipBorderColor = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            androidx.compose.ui.graphics.Color.Transparent
                        }

                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(chipBgColor)
                                .border(
                                    width = if (isSelected) 2.dp else 0.dp,
                                    color = chipBorderColor,
                                    shape = CircleShape
                                )
                                .clickable { selectedIcon = type },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = type,
                                modifier = Modifier.size(24.dp),
                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Output Socket selection
                Text(
                    "Output Socket Assignment",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
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
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val name = deviceName.ifBlank {
                        when (selectedIcon) {
                            "lightbulb" -> "Light"
                            "co2" -> "CO2"
                            "water_drop" -> "Pump"
                            "thermostat" -> "Heater"
                            else -> "Device"
                        }
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
