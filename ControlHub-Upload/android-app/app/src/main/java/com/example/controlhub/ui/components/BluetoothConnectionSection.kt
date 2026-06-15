package com.example.controlhub.ui.components

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.controlhub.RelayViewModel
import com.example.controlhub.ui.theme.GreenSuccess
import com.example.controlhub.ui.theme.AmberWarning
import com.example.controlhub.ui.theme.Rose400

@Composable
fun BluetoothConnectionSection(viewModel: RelayViewModel) {
    var showDeviceList by remember { mutableStateOf(false) }
    var hasBluetoothPermission by remember { mutableStateOf(false) }

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions: Map<String, Boolean> ->
        hasBluetoothPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions[Manifest.permission.BLUETOOTH_CONNECT] == true &&
                    permissions[Manifest.permission.BLUETOOTH_SCAN] == true
        } else {
            permissions[Manifest.permission.BLUETOOTH] == true &&
                    permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        }
    }

    LaunchedEffect(Unit) {
        val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        bluetoothPermissionLauncher.launch(permissionsToRequest)
    }

    val pairedDevicesWithNames: Map<BluetoothDevice, String> by remember(hasBluetoothPermission) {
        mutableStateOf(
            if (hasBluetoothPermission) {
                viewModel.getPairedDevices().associateWith { device ->
                    try {
                        device.name ?: "Unknown Device"
                    } catch (e: SecurityException) {
                        "Unknown Device"
                    }
                }
            } else {
                emptyMap()
            }
        )
    }

    // Determine connection state for indicator
    val isConnected = viewModel.isConnected.value
    val isConnecting = viewModel.isConnecting.value
    val indicatorColor = when {
        isConnected -> GreenSuccess
        isConnecting -> AmberWarning
        else -> Rose400
    }

    // Compact inline connection bar — no Card wrapper
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Status indicator row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // Pulsing status dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(indicatorColor)
            )
            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = when {
                    !hasBluetoothPermission -> "Bluetooth permissions required"
                    isConnected -> viewModel.selectedDeviceName.value ?: "Connected"
                    isConnecting -> "Connecting..."
                    else -> "Disconnected"
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
        }

        // Action buttons
        if (!hasBluetoothPermission) {
            Button(
                onClick = {
                    val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        arrayOf(
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_SCAN
                        )
                    } else {
                        arrayOf(
                            Manifest.permission.BLUETOOTH,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        )
                    }
                    bluetoothPermissionLauncher.launch(permissionsToRequest)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    "Grant Permissions",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Connect button
                Button(
                    onClick = { showDeviceList = true },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    enabled = !isConnected && !isConnecting
                ) {
                    Text(
                        "Connect",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (!isConnected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Disconnect / Retry button
                if (isConnected) {
                    OutlinedButton(
                        onClick = { viewModel.clearSelectedDevice() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        border = ButtonDefaults.outlinedButtonBorder(enabled = true)
                    ) {
                        Text(
                            "Disconnect",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = { viewModel.reconnect() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            "Retry",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }

    // Device selection dialog
    if (showDeviceList && pairedDevicesWithNames.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showDeviceList = false },
            title = {
                Text(
                    "Select Device",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for ((device, deviceName) in pairedDevicesWithNames) {
                        FilledTonalButton(
                            onClick = {
                                if (hasBluetoothPermission) {
                                    viewModel.setSelectedDevice(device)
                                    showDeviceList = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                        ) {
                            Text(
                                deviceName,
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDeviceList = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.primary)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(24.dp)
        )
    } else if (showDeviceList && pairedDevicesWithNames.isEmpty()) {
        AlertDialog(
            onDismissRequest = { showDeviceList = false },
            title = {
                Text(
                    "No Paired Devices",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    "Please pair a Bluetooth device in your phone's settings first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = { showDeviceList = false }) {
                    Text("OK", color = MaterialTheme.colorScheme.primary)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(24.dp)
        )
    }
}
