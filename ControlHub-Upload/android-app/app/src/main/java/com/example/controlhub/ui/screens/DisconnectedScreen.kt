package com.example.controlhub.ui.screens

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import android.util.Log
import com.example.controlhub.R
import com.example.controlhub.RelayViewModel

@Composable
fun DisconnectedScreen(viewModel: RelayViewModel) {
    val isDarkTheme = true
    val isScanning = viewModel.isScanning.value
    val isConnecting = viewModel.isConnecting.value
    val scannedDevices = viewModel.scannedDevices

    var hasBluetoothPermission by remember { mutableStateOf(false) }
    var showLocationWarningDialog by remember { mutableStateOf(false) }
    var showPermissionSettingsDialog by remember { mutableStateOf(false) }
    var permissionRequestedOnce by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val activity = context as? android.app.Activity

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions: Map<String, Boolean> ->
        val allGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions[Manifest.permission.BLUETOOTH_CONNECT] == true &&
                    permissions[Manifest.permission.BLUETOOTH_SCAN] == true
        } else {
            permissions[Manifest.permission.BLUETOOTH] == true &&
                    permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        }
        hasBluetoothPermission = allGranted
        if (!allGranted && permissionRequestedOnce) {
            // Check if the user selected "Don't ask again" — in that case,
            // shouldShowRequestPermissionRationale returns false AND the
            // permission is still denied. Show dialog directing to App Settings.
            val permanentlyDenied = activity != null && if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (permanentlyDenied) {
                showPermissionSettingsDialog = true
            }
        }
        permissionRequestedOnce = true
    }

    LaunchedEffect(Unit) {
        hasBluetoothPermission = viewModel.hasBluetoothPermissions()
    }


    // Interaction state and spring animation for FAB scale on press
    val scanButtonInteractionSource = remember { MutableInteractionSource() }
    val isPressed by scanButtonInteractionSource.collectIsPressedAsState()
    
    val scanButtonScale by animateFloatAsState(
        targetValue = if (isPressed) 0.82f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "ScanButtonScale"
    )

    // Infinite transitions for modern ripple search animation
    val infiniteTransition = rememberInfiniteTransition(label = "RippleAnimation")
    
    val rippleScale1 by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseOutQuad),
            repeatMode = RepeatMode.Restart
        ),
        label = "Ripple1"
    )
    val rippleAlpha1 by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseOutQuad),
            repeatMode = RepeatMode.Restart
        ),
        label = "Alpha1"
    )

    val rippleScale2 by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseOutQuad),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(600)
        ),
        label = "Ripple2"
    )
    val rippleAlpha2 by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseOutQuad),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(600)
        ),
        label = "Alpha2"
    )

    val rippleScale3 by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseOutQuad),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(1200)
        ),
        label = "Ripple3"
    )
    val rippleAlpha3 by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseOutQuad),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(1200)
        ),
        label = "Alpha3"
    )

    var scanAttempted by remember { mutableStateOf(false) }
    LaunchedEffect(isScanning) {
        if (isScanning) {
            scanAttempted = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Top Logo Header (Consistent centering, no resize)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
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

            // 2. Central UI Element (Action button / Ripple / Connecting indicator)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f)
            ) {
                if (isConnecting) {
                    // Connecting UI
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(180.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(100.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 6.dp
                        )
                        Icon(
                            imageVector = Icons.Default.Bluetooth,
                            contentDescription = "Connecting Bluetooth",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(42.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Connecting to ControlCore...",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Setting up BLE secure channel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                } else if (scannedDevices.isNotEmpty()) {
                    // 3. Centered Found Device UI for ControlCore (hiding "+" button and text)
                    val device = scannedDevices.firstOrNull()
                    if (device != null) {
                        val name = try {
                            val rawName = device.name
                            if (rawName.isNullOrBlank() || rawName.length > 20 ||
                                rawName.contains("dysl", ignoreCase = true) ||
                                rawName.matches(Regex(".*[0-9a-fA-F]{12,}.*"))
                            ) {
                                "ControlCore"
                            } else {
                                rawName
                            }
                        } catch (e: SecurityException) {
                            "ControlCore"
                        }
                        
                        val foundCardBg = if (isDarkTheme) {
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF3E2D00), // Soft faded amber/orange dark
                                    Color(0xFF241A00)  // Slightly darker
                                )
                            )
                        } else {
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFFFFFBEB), // Soft cream/peach
                                    Color(0xFFFEF3C7)  // Soft faded amber/orange light
                                )
                            )
                        }
                        
                        val contentColor = if (isDarkTheme) Color.White else Color.Black
                        
                        Card(
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .background(foundCardBg, shape = RoundedCornerShape(24.dp))
                                .border(
                                    width = 1.dp,
                                    color = if (isDarkTheme) Color.White.copy(alpha = 0.2f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(24.dp)
                                ),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = Color.Transparent
                            ),
                            elevation = CardDefaults.cardElevation(
                                defaultElevation = if (isDarkTheme) 0.dp else 1.dp
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text(
                                    text = "DEVICE FOUND",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDarkTheme) Color.White.copy(alpha = 0.8f) else Color.Black.copy(alpha = 0.8f),
                                    letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified
                                )
 
                                // Custom ControlCore Logo
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = if (isDarkTheme) 0.2f else 0.4f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        painter = painterResource(id = R.drawable.ic_controlcore),
                                        contentDescription = "ControlCore Logo",
                                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                                            if (isDarkTheme) Color.White else Color.Black
                                        ),
                                        modifier = Modifier.size(40.dp)
                                    )
                                }
 
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = contentColor,
                                    textAlign = TextAlign.Center
                                )
 
                                Button(
                                    onClick = {
                                        viewModel.stopScan()
                                        viewModel.setSelectedDevice(device)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth(0.8f)
                                        .height(48.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isDarkTheme) Color.White else Color.Black,
                                        contentColor = if (isDarkTheme) Color(0xFF3E2D00) else Color.White
                                    )
                                ) {
                                    if (viewModel.isConnecting.value) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            color = if (isDarkTheme) Color(0xFF3E2D00) else Color.White,
                                            strokeWidth = 3.dp
                                        )
                                    } else {
                                        Text(
                                            text = "Connect",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                if (viewModel.connectionFailedEvent.value) {
                                    Text(
                                        text = "Connection failed. Try again.",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(top = 4.dp),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Disconnected or Scanning UI
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(180.dp)
                    ) {
                        // Ripple rings (visible when scanning)
                        if (isScanning) {
                            Box(
                                modifier = Modifier
                                    .size(100.dp)
                                    .scale(rippleScale1)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = rippleAlpha1))
                            )
                            Box(
                                modifier = Modifier
                                    .size(100.dp)
                                    .scale(rippleScale2)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = rippleAlpha2))
                            )
                            Box(
                                modifier = Modifier
                                    .size(100.dp)
                                    .scale(rippleScale3)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = rippleAlpha3))
                            )
                        } else {
                            // Single soft static background pulse ring when idle
                            Box(
                                modifier = Modifier
                                    .size(120.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.05f))
                            )
                        }

                        // Main scan trigger button (Kept straight - rotation/shake removed)
                        val scanButtonBg = if (isDarkTheme) {
                            Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                                )
                            )
                        } else {
                            Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                                )
                            )
                        }
                        
                        val scanButtonIconTint = if (isDarkTheme) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            Color.White
                        }

                        Box(
                            modifier = Modifier
                                .size(100.dp)
                                .scale(scanButtonScale)
                                .clip(CircleShape)
                                .background(scanButtonBg)
                                .clickable(
                                    interactionSource = scanButtonInteractionSource,
                                    indication = null,
                                    enabled = !isScanning
                                ) {
                                    if (hasBluetoothPermission) {
                                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !viewModel.isLocationEnabled()) {
                                            showLocationWarningDialog = true
                                        } else {
                                            viewModel.startScan()
                                        }
                                    } else {
                                        val permissionsToRequest =
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isScanning) Icons.Default.Bluetooth else Icons.Default.Add,
                                contentDescription = if (isScanning) "Scanning" else "Scan",
                                tint = scanButtonIconTint,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = if (isScanning) "Searching for ControlCore..." else "Tap to find your ControlCore",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    // Helper text: Only show when permissions are missing OR scanning is active with no results OR scan has completed and failed to find any ESP
                    val subtitleText = when {
                        !hasBluetoothPermission -> "Tap here to grant Bluetooth permissions"
                        isScanning && scannedDevices.isEmpty() -> "Ensure your ESP32 device is powered on and close"
                        !isScanning && scanAttempted && scannedDevices.isEmpty() -> "No ControlCore found. Ensure your device is plugged in and ready"
                        else -> ""
                    }

                    if (subtitleText.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = subtitleText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .padding(horizontal = 32.dp)
                                .clickable(!hasBluetoothPermission) {
                                    val permissionsToRequest =
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
                        )
                    }

                    if (viewModel.connectionFailedEvent.value) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Connection failed. Try again.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // 4. Bottom Scan Button / Cancel Trigger
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isScanning && scannedDevices.isEmpty()) {
                    OutlinedButton(
                        onClick = { viewModel.stopScan() },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(
                            "Cancel Scan",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else if (viewModel.hasLastConnectedDevice() && !isConnecting && scannedDevices.isEmpty()) {
                    TextButton(
                        onClick = { viewModel.reconnect() },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(
                            "Reconnect to last device",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.height(48.dp))
                }
            }
        }
    }

    if (showLocationWarningDialog) {
        AlertDialog(
            onDismissRequest = { showLocationWarningDialog = false },
            title = {
                Text(
                    "Location Required",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    "Location services must be enabled to scan for Bluetooth devices.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLocationWarningDialog = false
                        try {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Log.e("DisconnectedScreen", "Could not open location settings: ${e.message}")
                        }
                    }
                ) {
                    Text("Open Settings", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLocationWarningDialog = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(24.dp)
        )
    }

    if (showPermissionSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionSettingsDialog = false },
            title = {
                Text(
                    "Permissions Required",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    "Bluetooth permissions are permanently denied. Please enable them in system settings to scan for devices.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPermissionSettingsDialog = false
                        try {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Log.e("DisconnectedScreen", "Could not open settings: ${e.message}")
                        }
                    }
                ) {
                    Text("Open Settings", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionSettingsDialog = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(24.dp)
        )
    }
}
