package com.example.controlhub

import android.Manifest

import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.controlhub.ui.theme.ControlHubTheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: RelayViewModel by viewModels {
        RelayViewModelFactory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
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

    override fun onDestroy() {
        super.onDestroy()
        viewModel.cleanup()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: RelayViewModel) {
    val selectedTab = remember { mutableStateOf(0) }
    val tabs = listOf("Light", "CO2")
    val isDarkTheme = isSystemInDarkTheme()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DrawerContent()
        },
        content = {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Image(
                                painter = painterResource(
                                    id = if (isDarkTheme) R.drawable.logo else R.drawable.logo_black
                                ),
                                contentDescription = "App Logo",
                                modifier = Modifier
                                    .height(40.dp)
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
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    )
                }
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                ) {
                    BluetoothConnectionSection(viewModel)

                    // TabRow for selecting Light or CO2
                    TabRow(
                        selectedTabIndex = selectedTab.value,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTab.value == index,
                                onClick = { selectedTab.value = index },
                                enabled = true,
                                modifier = Modifier.height(48.dp),
                                content = {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = title,
                                            color = if (selectedTab.value == index)
                                                MaterialTheme.colorScheme.onSurface
                                            else
                                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        if (selectedTab.value == index) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .align(Alignment.BottomCenter)
                                                    .height(2.dp)
                                                    .background(MaterialTheme.colorScheme.primary)
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }

                    // Display control section based on selected tab
                    when (selectedTab.value) {
                        0 -> RelayControlSection(viewModel, "Light")
                        1 -> RelayControlSection(viewModel, "CO2")
                    }

                    RTCTimeSyncButton(viewModel)

                    // Show RTC time set confirmation dialog
                    if (viewModel.rtcTimeSetEvent.value) {
                        AlertDialog(
                            onDismissRequest = { viewModel.resetRtcTimeSetEvent() },
                            title = {
                                Text(
                                    "RTC Time Set",
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            text = {
                                Text(
                                    "The RTC time has been successfully set.",
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = { viewModel.resetRtcTimeSetEvent() }
                                ) {
                                    Text("OK", color = MaterialTheme.colorScheme.primary)
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    }

                    // Show settings saved confirmation dialog
                    if (viewModel.settingsSavedEvent.value) {
                        AlertDialog(
                            onDismissRequest = { viewModel.resetSettingsSavedEvent() },
                            title = {
                                Text(
                                    "Settings Saved",
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            text = {
                                Text(
                                    "Your schedule settings have been successfully saved and sent to the device.",
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = { viewModel.resetSettingsSavedEvent() }
                                ) {
                                    Text("OK", color = MaterialTheme.colorScheme.primary)
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    }
                }
            }
        }
    )
}

@Composable
fun DrawerContent() {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(250.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = "ControlHub",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // YouTube Link
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.youtube.com/@aquasquare/featured")
                    )
                    context.startActivity(intent)
                }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = R.drawable.youtube),
                contentDescription = "YouTube Icon",
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "YouTube",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Instagram Link
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.instagram.com/aqua_square_?igsh=MTNlcmh1YTRsYjJu")
                    )
                    context.startActivity(intent)
                }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = R.drawable.instagram),
                contentDescription = "Instagram Icon",
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Instagram",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Facebook Link
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.facebook.com/share/16R2TbYQmD/")
                    )
                    context.startActivity(intent)
                }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = R.drawable.facebook),
                contentDescription = "Facebook Icon",
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Facebook",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // About Section
        var showAboutDialog by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showAboutDialog = true }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "About Icon",
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "About",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        if (showAboutDialog) {
            AlertDialog(
                onDismissRequest = { showAboutDialog = false },
                title = {
                    Text(
                        "About ControlHub",
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                text = {
                    Text(
                        text = "ControlHub is an app designed to control relays (e.g., Light and CO2) via Bluetooth, connectable to an ESP32 with provided code. Created with help from AI (Grok and ChatGPT) by a first-time developer with no prior coding experience, this is an experimental project. Expect some bugs as a disclaimer. The app allows manual relay toggling, automatic scheduling with time settings, and RTC time synchronization. It's extensible—more relays can be added. For the full code to customize or add relays, I can provide it upon request.",
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { showAboutDialog = false }
                    ) {
                        Text("OK", color = MaterialTheme.colorScheme.primary)
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface
            )
        }
    }
}

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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Connection status message
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Text(
                text = when {
                    !hasBluetoothPermission -> "Bluetooth permissions are required to connect to devices."
                    viewModel.disconnectionEvent.value -> "Connection lost! Attempting to reconnect..."
                    viewModel.isConnected.value -> "Connected to ${viewModel.selectedDeviceName.value ?: "Unknown Device"}"
                    viewModel.hasLastConnectedDevice() -> "Attempting to reconnect to last device..."
                    else -> "Not connected"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (viewModel.disconnectionEvent.value)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp),
                textAlign = TextAlign.Center
            )
        }

        // Buttons based on connection state
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
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Grant Bluetooth Permissions", color = MaterialTheme.colorScheme.onPrimary)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(
                    onClick = { showDeviceList = true },
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    ),
                    enabled = !viewModel.isConnected.value
                ) {
                    Text("Connect to Device", color = MaterialTheme.colorScheme.onPrimary)
                }

                Button(
                    onClick = {
                        if (viewModel.isConnected.value) {
                            viewModel.clearSelectedDevice()
                        } else {
                            viewModel.reconnect()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (viewModel.isConnected.value)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.primary,
                        disabledContainerColor = if (viewModel.isConnected.value)
                            MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                        else
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    ),
                    enabled = true
                ) {
                    Text(
                        if (viewModel.isConnected.value) "Disconnect" else "Retry",
                        color = if (viewModel.isConnected.value)
                            MaterialTheme.colorScheme.onError
                        else
                            MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }

        // Device selection dialog
        if (showDeviceList && pairedDevicesWithNames.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { showDeviceList = false },
                title = { Text("Select Device", color = MaterialTheme.colorScheme.onSurface) },
                text = {
                    Column {
                        for ((device, deviceName) in pairedDevicesWithNames) {
                            Button(
                                onClick = {
                                    if (hasBluetoothPermission) {
                                        viewModel.setSelectedDevice(device)
                                        showDeviceList = false
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text(deviceName, color = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { showDeviceList = false }
                    ) {
                        Text("Cancel", color = MaterialTheme.colorScheme.primary)
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface
            )
        } else if (showDeviceList && pairedDevicesWithNames.isEmpty()) {
            AlertDialog(
                onDismissRequest = { showDeviceList = false },
                title = {
                    Text(
                        "No Paired Devices",
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                text = {
                    Text(
                        "Please pair a Bluetooth device in your device settings.",
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { showDeviceList = false }
                    ) {
                        Text("OK", color = MaterialTheme.colorScheme.primary)
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayControlSection(viewModel: RelayViewModel, relayName: String) {
    val state = viewModel.getRelayState(relayName)
    val surfaceGradient = Brush.verticalGradient(
        colors = listOf(MaterialTheme.colorScheme.surface, Color(0xFF2E2A3B))
    )

    // Calculate duration for Auto mode
    val durationText = if (state.isAutoMode.value) {
        val onHourIn24 = if (state.onPeriod.value == "PM" && state.onHour.value != 12)
            state.onHour.value + 12
        else if (state.onPeriod.value == "AM" && state.onHour.value == 12)
            0
        else
            state.onHour.value

        val offHourIn24 = if (state.offPeriod.value == "PM" && state.offHour.value != 12)
            state.offHour.value + 12
        else if (state.offPeriod.value == "AM" && state.offHour.value == 12)
            0
        else
            state.offHour.value

        val onTimeInMinutes = onHourIn24 * 60 + state.onMinute.value
        var offTimeInMinutes = offHourIn24 * 60 + state.offMinute.value

        if (offTimeInMinutes < onTimeInMinutes) {
            offTimeInMinutes += 1440
        }

        val durationMinutes = offTimeInMinutes - onTimeInMinutes
        val hours = durationMinutes / 60
        val minutes = durationMinutes % 60
        "Duration: ${if (hours > 0) "$hours hr " else ""}${if (minutes > 0) "$minutes min" else ""}".trim()
    } else {
        ""
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(brush = surfaceGradient)
                .padding(16.dp)
        ) {
            Text(
                text = "$relayName Relay Control",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp),
                color = MaterialTheme.colorScheme.onSurface
            )

            // Relay ON/OFF toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (state.isRelayOn.value) "Relay is ON" else "Relay is OFF",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Switch(
                    checked = state.isRelayOn.value,
                    onCheckedChange = { isChecked ->
                        state.isRelayOn.value = isChecked
                        if (!state.isAutoMode.value) {
                            viewModel.sendCommand(relayName, isChecked)
                            viewModel.saveStateManually(relayName)
                        }
                    },
                    enabled = !state.isAutoMode.value && viewModel.isConnected.value,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.error,
                        checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        uncheckedTrackColor = MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                    )
                )
            }

            // Auto/Manual mode toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (state.isAutoMode.value) "Mode: Auto" else "Mode: Manual",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Switch(
                    checked = state.isAutoMode.value,
                    onCheckedChange = { isChecked ->
                        state.isAutoMode.value = isChecked
                        if (!isChecked && viewModel.isConnected.value) {
                            viewModel.sendCommand(relayName, state.isRelayOn.value)
                        }
                    },
                    enabled = true,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.error,
                        checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        uncheckedTrackColor = MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                    )
                )
            }

            // Auto mode settings
            if (state.isAutoMode.value) {
                TimePicker(
                    label = "ON Time",
                    hour = state.onHour,
                    minute = state.onMinute,
                    period = state.onPeriod,
                    enabled = true
                )
                Spacer(modifier = Modifier.height(16.dp))
                TimePicker(
                    label = "OFF Time",
                    hour = state.offHour,
                    minute = state.offMinute,
                    period = state.offPeriod,
                    enabled = true
                )
                Spacer(modifier = Modifier.height(16.dp))
                if (durationText.isNotEmpty()) {
                    Text(
                        text = durationText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }
                Button(
                    onClick = { viewModel.saveStateManually(relayName) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    ),
                    enabled = viewModel.isConnected.value
                ) {
                    Text("Save Settings", color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePicker(
    label: String,
    hour: MutableState<Int>,
    minute: MutableState<Int>,
    period: MutableState<String>,
    enabled: Boolean
) {
    val hours = (1..12).toList()
    val minutes = (0..59).toList()
    val periods = listOf("AM", "PM")

    var showHourPicker by remember { mutableStateOf(false) }
    var showMinutePicker by remember { mutableStateOf(false) }
    var showPeriodPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 8.dp),
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                OutlinedTextField(
                    value = hour.value.toString(),
                    onValueChange = {},
                    label = { Text("Hour", color = MaterialTheme.colorScheme.onSurface) },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    trailingIcon = {
                        IconButton(onClick = { showHourPicker = true }, enabled = enabled) {
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = "Select Hour",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    shape = RoundedCornerShape(50),
                    enabled = enabled
                )
                if (showHourPicker) {
                    AlertDialog(
                        onDismissRequest = { showHourPicker = false },
                        title = { Text("Select Hour", color = MaterialTheme.colorScheme.onSurface) },
                        text = {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 300.dp)
                            ) {
                                items(hours) { h ->
                                    Button(
                                        onClick = {
                                            hour.value = h
                                            showHourPicker = false
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        shape = RoundedCornerShape(50),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary
                                        )
                                    ) {
                                        Text(h.toString(), color = MaterialTheme.colorScheme.onPrimary)
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = { showHourPicker = false }
                            ) {
                                Text("Cancel", color = MaterialTheme.colorScheme.primary)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                OutlinedTextField(
                    value = minute.value.toString().padStart(2, '0'),
                    onValueChange = {},
                    label = { Text("Minute", color = MaterialTheme.colorScheme.onSurface) },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    trailingIcon = {
                        IconButton(onClick = { showMinutePicker = true }, enabled = enabled) {
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = "Select Minute",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    shape = RoundedCornerShape(50),
                    enabled = enabled
                )
                if (showMinutePicker) {
                    AlertDialog(
                        onDismissRequest = { showMinutePicker = false },
                        title = {
                            Text(
                                "Select Minute",
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        text = {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 300.dp)
                            ) {
                                items(minutes) { m ->
                                    Button(
                                        onClick = {
                                            minute.value = m
                                            showMinutePicker = false
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        shape = RoundedCornerShape(50),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary
                                        )
                                    ) {
                                        Text(
                                            m.toString().padStart(2, '0'),
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = { showMinutePicker = false }
                            ) {
                                Text("Cancel", color = MaterialTheme.colorScheme.primary)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                }
            }

            Box(
                modifier = Modifier.weight(1f)
            ) {
                OutlinedTextField(
                    value = period.value,
                    onValueChange = {},
                    label = { Text("Period", color = MaterialTheme.colorScheme.onSurface) },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    trailingIcon = {
                        IconButton(onClick = { showPeriodPicker = true }, enabled = enabled) {
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = "Select Period",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    shape = RoundedCornerShape(50),
                    enabled = enabled
                )
                if (showPeriodPicker) {
                    AlertDialog(
                        onDismissRequest = { showPeriodPicker = false },
                        title = {
                            Text(
                                "Select Period",
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        text = {
                            Column {
                                periods.forEach { p ->
                                    Button(
                                        onClick = {
                                            period.value = p
                                            showPeriodPicker = false
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        shape = RoundedCornerShape(50),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary
                                        )
                                    ) {
                                        Text(p, color = MaterialTheme.colorScheme.onPrimary)
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = { showPeriodPicker = false }
                            ) {
                                Text("Cancel", color = MaterialTheme.colorScheme.primary)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                }
            }
        }
    }
}

@Composable
fun RTCTimeSyncButton(viewModel: RelayViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(
            onClick = { viewModel.setRTCTime() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            ),
            enabled = viewModel.isConnected.value
        ) {
            Text("Sync RTC Time", color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}