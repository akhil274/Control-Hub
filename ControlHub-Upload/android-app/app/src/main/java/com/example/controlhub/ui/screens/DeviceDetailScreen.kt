package com.example.controlhub.ui.screens

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Co2
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.controlhub.DeviceInfo
import com.example.controlhub.RelayViewModel
import com.example.controlhub.ui.components.ClockDialPicker
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import com.example.controlhub.ui.components.DaySelector
import com.example.controlhub.ui.components.DeviceIcon
import com.example.controlhub.ui.theme.ManropeFontFamily
import kotlinx.coroutines.launch

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(
    device: DeviceInfo,
    viewModel: RelayViewModel,
    onBack: () -> Unit
) {
    // Intercept system back button/swipe gesture to navigate back to list instead of closing app
    BackHandler(enabled = true) {
        onBack()
    }

    val isDarkTheme = true
    val relayState = viewModel.getRelayState(device.id)
    val isDeviceOn = relayState.isRelayOn.value

    val deviceThemeColor = when (device.iconType.value) {
        "lightbulb" -> Color(0xFFF59E0B) // Amber/Yellow
        "co2" -> Color(0xFF10B981)       // Green
        "water_drop" -> Color(0xFF3B82F6) // Blue
        "thermostat" -> Color(0xFFE25C5C) // Softer Coral Red
        else -> Color(0xFF0D9488)         // Teal
    }

    var selectedTab by rememberSaveable { mutableIntStateOf(if (relayState.isAutoMode.value) { if (relayState.modeType.value == 1) 2 else 1 } else 0) }
    var selectedTimeTab by rememberSaveable { mutableIntStateOf(0) } // For schedule ON/OFF selection inside Auto Mode
    var showEditDialog by rememberSaveable { mutableStateOf(false) }
    var showStopTimerDialog by rememberSaveable { mutableStateOf(false) }
    var pendingTabSwitch by remember { mutableStateOf<Int?>(null) }

    // Dynamic background color matching device category and ON/OFF status (ONLY in Manual Tab)
    val targetBgColor = if (selectedTab == 0 && isDeviceOn) {
        when (device.iconType.value) {
            "lightbulb" -> if (isDarkTheme) Color(0xFF3E2D00) else Color(0xFFFEF3C7) // Soft Amber
            "co2" -> if (isDarkTheme) Color(0xFF142F1B) else Color(0xFFDCFCE7)        // Soft Mint Green
            "water_drop" -> if (isDarkTheme) Color(0xFF0F253F) else Color(0xFFE0F2FE)  // Soft Sky Blue
            "thermostat" -> if (isDarkTheme) Color(0xFF3F1313) else Color(0xFFFEE2E2)  // Soft Coral Red
            else -> if (isDarkTheme) Color(0xFF003D3D) else Color(0xFFE0F7F7)          // Soft Teal
        }
    } else {
        MaterialTheme.colorScheme.background
    }

    // Animate color changes smoothly for a premium, responsive feel
    val animatedBgColor by animateColorAsState(
        targetValue = targetBgColor,
        animationSpec = tween(durationMillis = 350, easing = EaseInOutQuad),
        label = "BackgroundTransition"
    )

    // Update system status bar and navigation bar to match the animated background color dynamically
    val view = LocalView.current
    val context = view.context
    val window = (context as? Activity)?.window
    val standardBgColor = MaterialTheme.colorScheme.background
    if (window != null) {
        SideEffect {
            window.statusBarColor = animatedBgColor.toArgb()
            window.navigationBarColor = animatedBgColor.toArgb()
        }
        DisposableEffect(Unit) {
            onDispose {
                window.statusBarColor = standardBgColor.toArgb()
                window.navigationBarColor = standardBgColor.toArgb()
            }
        }
    }

    Scaffold(
        topBar = {
            // Sleek, compact top bar containing Back, centered Icon+Name, and Edit button
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Action Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Left: Back button
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Go Back",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Right: Edit button
                    IconButton(
                        onClick = { showEditDialog = true },
                        modifier = Modifier
                            .size(48.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit Device",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Centered Larger Category Icon (64.dp)
                val isIconActive = isDeviceOn || (selectedTab == 1 && relayState.isAutoMode.value)
                DeviceIcon(
                    iconType = device.iconType.value,
                    isActive = isIconActive,
                    modifier = Modifier.size(64.dp)
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Centered Device Name text
                Text(
                    text = device.name.value,
                    fontFamily = ManropeFontFamily,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = animatedBgColor, // Seamlessly blends with the animated background
                tonalElevation = 0.dp // Flat premium design matching Stitch
            ) {
                // Manual Tab
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = {
                        // Check if Timer mode is currently active
                        if (relayState.isAutoMode.value && relayState.modeType.value == 1) {
                            pendingTabSwitch = 0
                            showStopTimerDialog = true
                        } else {
                            relayState.isAutoMode.value = false
                            viewModel.saveStateManually(device.id, showConfirmation = false)
                            selectedTab = 0
                        }
                    },
                    icon = { Icon(Icons.Default.PowerSettingsNew, contentDescription = "Manual") },
                    label = { Text("Manual", fontFamily = ManropeFontFamily, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = deviceThemeColor,
                        selectedTextColor = deviceThemeColor,
                        indicatorColor = deviceThemeColor.copy(alpha = 0.15f),
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                )

                // Auto Tab
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = {
                        // Check if Timer mode is currently active
                        if (relayState.isAutoMode.value && relayState.modeType.value == 1) {
                            pendingTabSwitch = 1
                            showStopTimerDialog = true
                        } else {
                            selectedTab = 1
                        }
                    },
                    icon = { Icon(Icons.Default.Schedule, contentDescription = "Auto") },
                    label = { Text("Auto", fontFamily = ManropeFontFamily, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = deviceThemeColor,
                        selectedTextColor = deviceThemeColor,
                        indicatorColor = deviceThemeColor.copy(alpha = 0.15f),
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                )

                // Timer Tab
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = {
                        // Check if Auto mode (non-timer) is active
                        if (relayState.isAutoMode.value && relayState.modeType.value == 0) {
                            // Switching from Auto to Timer: disable Auto first
                            relayState.isAutoMode.value = false
                            viewModel.saveStateManually(device.id, showConfirmation = false)
                        }
                        selectedTab = 2
                    },
                    icon = { Icon(Icons.Default.HourglassEmpty, contentDescription = "Timer") },
                    label = { Text("Timer", fontFamily = ManropeFontFamily, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = deviceThemeColor,
                        selectedTextColor = deviceThemeColor,
                        indicatorColor = deviceThemeColor.copy(alpha = 0.15f),
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                )
            }
        },
        containerColor = animatedBgColor
    ) { innerPadding ->
        Crossfade(
            targetState = selectedTab,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            label = "TabContentTransition"
        ) { tab ->
            when (tab) {
                0 -> ManualTabContent(device = device, viewModel = viewModel, isDeviceOn = isDeviceOn)
                1 -> AutoTabContent(
                    device = device,
                    viewModel = viewModel,
                    selectedTimeTab = selectedTimeTab,
                    onTimeTabSelected = { selectedTimeTab = it },
                    deviceThemeColor = deviceThemeColor,
                    onSaveAndClose = onBack
                )
                2 -> TimerTabContent(
                    device = device,
                    viewModel = viewModel,
                    deviceThemeColor = deviceThemeColor,
                    onSaveAndClose = onBack
                )
            }
        }
    }

    // Small popup edit dialog for configuring Name, Icon, and Socket Pin
    if (showEditDialog) {
        EditDeviceDialog(
            device = device,
            viewModel = viewModel,
            onDismiss = { showEditDialog = false }
        )
    }

    // Confirmation dialog when leaving Timer mode
    if (showStopTimerDialog) {
        AlertDialog(
            onDismissRequest = {
                showStopTimerDialog = false
                pendingTabSwitch = null
            },
            title = {
                Text(
                    "Stop Timer Mode?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    "Switching modes will stop the current pulse cycle. It will not resume from where it left off \u2014 it restarts fresh next time Timer mode is enabled.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showStopTimerDialog = false
                    val targetTab = pendingTabSwitch
                    pendingTabSwitch = null
                    // Disable auto mode and switch to manual
                    relayState.isAutoMode.value = false
                    relayState.modeType.value = 0
                    relayState.isRelayOn.value = false
                    viewModel.sendCommand(device.id, false)
                    viewModel.saveStateManually(device.id, showConfirmation = false)
                    if (targetTab != null) selectedTab = targetTab
                }) {
                    Text("Yes, Switch", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showStopTimerDialog = false
                    pendingTabSwitch = null
                }) {
                    Text("No, Keep Timer Running", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(24.dp)
        )
    }
}

@Composable
fun ManualTabContent(
    device: DeviceInfo,
    viewModel: RelayViewModel,
    isDeviceOn: Boolean
) {
    val relayState = viewModel.getRelayState(device.id)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Large circular premium tactile toggle button
            val buttonSize = 180.dp
            val buttonColor = if (isDeviceOn) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            }

            Box(
                modifier = Modifier
                    .size(buttonSize)
                    .clip(CircleShape)
                    .background(buttonColor)
                    .clickable(enabled = viewModel.isConnected.value && !viewModel.rtcFailedState.value && !viewModel.rtcLostPowerState.value) {
                        val newState = !isDeviceOn
                        relayState.isAutoMode.value = false
                        relayState.isRelayOn.value = newState
                        viewModel.sendCommand(device.id, newState)
                        viewModel.saveStateManually(device.id, showConfirmation = false)
                    }
                    .border(
                        width = 4.dp,
                        color = if (isDeviceOn) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = if (isDeviceOn) "Turn Off" else "Turn On",
                    tint = if (isDeviceOn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(72.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Informative Status text below
            Text(
                text = if (viewModel.rtcFailedState.value) {
                    "Hardware Error: RTC Failed"
                } else if (viewModel.rtcLostPowerState.value) {
                    "RTC Time Not Set"
                } else if (relayState.isAutoMode.value) {
                    "Auto Mode Active"
                } else if (!viewModel.isConnected.value) {
                    "Offline"
                } else if (isDeviceOn) {
                    "Device is Running"
                } else {
                    "Device is Off"
                },
                fontFamily = ManropeFontFamily,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = if (viewModel.rtcFailedState.value || viewModel.rtcLostPowerState.value) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )

            if (relayState.isAutoMode.value) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Toggling will disable Auto Mode and take manual control.",
                    fontFamily = ManropeFontFamily,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
        }
    }
}

@Composable
fun AutoTabContent(
    device: DeviceInfo,
    viewModel: RelayViewModel,
    selectedTimeTab: Int,
    onTimeTabSelected: (Int) -> Unit,
    deviceThemeColor: Color,
    onSaveAndClose: () -> Unit
) {
    val relayState = viewModel.getRelayState(device.id)

    val onHourScrollState = rememberLazyListState(
        initialFirstVisibleItemIndex = 1800 + (relayState.onHour.value - 1)
    )
    val onMinuteScrollState = rememberLazyListState(
        initialFirstVisibleItemIndex = 9000 + relayState.onMinute.value
    )
    val onPeriodScrollState = rememberLazyListState(
        initialFirstVisibleItemIndex = if (relayState.onPeriod.value.trim().uppercase() == "AM") 0 else 1
    )

    val offHourScrollState = rememberLazyListState(
        initialFirstVisibleItemIndex = 1800 + (relayState.offHour.value - 1)
    )
    val offMinuteScrollState = rememberLazyListState(
        initialFirstVisibleItemIndex = 9000 + relayState.offMinute.value
    )
    val offPeriodScrollState = rememberLazyListState(
        initialFirstVisibleItemIndex = if (relayState.offPeriod.value.trim().uppercase() == "AM") 0 else 1
    )

    // Sync external model -> scroll position (in case of Bluetooth updates)
    LaunchedEffect(relayState.onHour.value) {
        val target = 1800 + (relayState.onHour.value - 1)
        if (onHourScrollState.firstVisibleItemIndex % 12 != (relayState.onHour.value - 1)) {
            onHourScrollState.scrollToItem(target)
        }
    }
    LaunchedEffect(relayState.onMinute.value) {
        val target = 9000 + relayState.onMinute.value
        if (onMinuteScrollState.firstVisibleItemIndex % 60 != relayState.onMinute.value) {
            onMinuteScrollState.scrollToItem(target)
        }
    }
    LaunchedEffect(relayState.onPeriod.value) {
        val target = if (relayState.onPeriod.value.trim().uppercase() == "AM") 0 else 1
        if (onPeriodScrollState.firstVisibleItemIndex != target) {
            onPeriodScrollState.scrollToItem(target)
        }
    }
    
    LaunchedEffect(relayState.offHour.value) {
        val target = 1800 + (relayState.offHour.value - 1)
        if (offHourScrollState.firstVisibleItemIndex % 12 != (relayState.offHour.value - 1)) {
            offHourScrollState.scrollToItem(target)
        }
    }
    LaunchedEffect(relayState.offMinute.value) {
        val target = 9000 + relayState.offMinute.value
        if (offMinuteScrollState.firstVisibleItemIndex % 60 != relayState.offMinute.value) {
            offMinuteScrollState.scrollToItem(target)
        }
    }
    LaunchedEffect(relayState.offPeriod.value) {
        val target = if (relayState.offPeriod.value.trim().uppercase() == "AM") 0 else 1
        if (offPeriodScrollState.firstVisibleItemIndex != target) {
            offPeriodScrollState.scrollToItem(target)
        }
    }

    val currentOnHour by remember { derivedStateOf { (onHourScrollState.firstVisibleItemIndex % 12) + 1 } }
    val currentOnMinute by remember { derivedStateOf { onMinuteScrollState.firstVisibleItemIndex % 60 } }
    val currentOnPeriod by remember { derivedStateOf { if (onPeriodScrollState.firstVisibleItemIndex == 0) "AM" else "PM" } }

    val currentOffHour by remember { derivedStateOf { (offHourScrollState.firstVisibleItemIndex % 12) + 1 } }
    val currentOffMinute by remember { derivedStateOf { offMinuteScrollState.firstVisibleItemIndex % 60 } }
    val currentOffPeriod by remember { derivedStateOf { if (offPeriodScrollState.firstVisibleItemIndex == 0) "AM" else "PM" } }

    val hourScrollState = if (selectedTimeTab == 0) onHourScrollState else offHourScrollState
    val minuteScrollState = if (selectedTimeTab == 0) onMinuteScrollState else offMinuteScrollState
    val periodScrollState = if (selectedTimeTab == 0) onPeriodScrollState else offPeriodScrollState

    var showEnableAutoWarning by remember { mutableStateOf(false) }
    var showNoRepeatDaysWarning by remember { mutableStateOf(false) }
    var showSameTimeWarning by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Auto Mode switch card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Enable Auto Mode",
                    fontFamily = ManropeFontFamily,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Switch(
                    checked = relayState.isAutoMode.value,
                    onCheckedChange = { isChecked ->
                        relayState.isAutoMode.value = isChecked
                    },
                    enabled = viewModel.isConnected.value && !viewModel.rtcFailedState.value && !viewModel.rtcLostPowerState.value
                )
            }
        }

        // 3D Drum Clock Dial Picker
        ClockDialPicker(
            selectedTab = selectedTimeTab,
            onTabSelected = onTimeTabSelected,
            onHour = currentOnHour,
            onMinute = currentOnMinute,
            onPeriod = currentOnPeriod,
            offHour = currentOffHour,
            offMinute = currentOffMinute,
            offPeriod = currentOffPeriod,
            hourScrollState = hourScrollState,
            minuteScrollState = minuteScrollState,
            periodScrollState = periodScrollState
        )

        // Day Selector
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Box(modifier = Modifier.padding(14.dp)) {
                DaySelector(
                    daysMask = relayState.daysMask.value,
                    onDaysMaskChanged = { newMask ->
                        relayState.daysMask.value = newMask
                    }
                )
            }
        }

        // Save Settings Button
        Button(
            onClick = {
                val onTotal = calcTotal(currentOnHour, currentOnMinute, currentOnPeriod)
                val offTotal = calcTotal(currentOffHour, currentOffMinute, currentOffPeriod)
                if (!relayState.isAutoMode.value) {
                    showEnableAutoWarning = true
                } else if (relayState.daysMask.value == 0) {
                    showNoRepeatDaysWarning = true
                } else if (onTotal == offTotal) {
                    showSameTimeWarning = true
                } else {
                    // Synchronously save the derived scroll positions to the relayState
                    relayState.onHour.value = currentOnHour
                    relayState.onMinute.value = currentOnMinute
                    relayState.onPeriod.value = currentOnPeriod

                    relayState.offHour.value = currentOffHour
                    relayState.offMinute.value = currentOffMinute
                    relayState.offPeriod.value = currentOffPeriod

                    viewModel.saveStateManually(device.id, showConfirmation = true)
                    onSaveAndClose() // Collapse card immediately; save outcome shown via snackbar
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = deviceThemeColor,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            enabled = viewModel.isConnected.value && !viewModel.rtcFailedState.value && !viewModel.rtcLostPowerState.value
        ) {
            Text(
                text = "Save Settings",
                fontFamily = ManropeFontFamily,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    if (showEnableAutoWarning) {
        AlertDialog(
            onDismissRequest = { showEnableAutoWarning = false },
            title = {
                Text(
                    "Enable Auto Mode",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    "Please enable Auto Mode to save the settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = { showEnableAutoWarning = false }) {
                    Text("OK", color = deviceThemeColor, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(24.dp)
        )
    }

    if (showNoRepeatDaysWarning) {
        AlertDialog(
            onDismissRequest = { showNoRepeatDaysWarning = false },
            title = {
                Text(
                    "No Repeat Days Selected",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    "Please select at least one repeat day for this schedule to run.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = { showNoRepeatDaysWarning = false }) {
                    Text("OK", color = deviceThemeColor, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(24.dp)
        )
    }

    if (showSameTimeWarning) {
        AlertDialog(
            onDismissRequest = { showSameTimeWarning = false },
            title = {
                Text(
                    "Same ON/OFF Time Selected",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    "The ON and OFF times cannot be identical. Please select distinct times.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = { showSameTimeWarning = false }) {
                    Text("OK", color = deviceThemeColor, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(24.dp)
        )
    }
}

@Composable
fun TimerTabContent(
    device: DeviceInfo,
    viewModel: RelayViewModel,
    deviceThemeColor: Color,
    onSaveAndClose: () -> Unit
) {
    val relayState = viewModel.getRelayState(device.id)

    // Local state for pulse duration editing
    var pulseOnHours by rememberSaveable { mutableIntStateOf(relayState.pulseOnSec.value / 3600) }
    var pulseOnMinutes by rememberSaveable { mutableIntStateOf((relayState.pulseOnSec.value % 3600) / 60) }
    var pulseOnSeconds by rememberSaveable { mutableIntStateOf(relayState.pulseOnSec.value % 60) }
    var pulseOffHours by rememberSaveable { mutableIntStateOf(relayState.pulseOffSec.value / 3600) }
    var pulseOffMinutes by rememberSaveable { mutableIntStateOf((relayState.pulseOffSec.value % 3600) / 60) }
    var pulseOffSeconds by rememberSaveable { mutableIntStateOf(relayState.pulseOffSec.value % 60) }
    var isInfiniteLocal by rememberSaveable { mutableStateOf(relayState.isInfinite.value) }

    // Time picker scroll states for windowed mode (reuse same idea as Auto tab)
    val onHourScrollState = rememberLazyListState(
        initialFirstVisibleItemIndex = 1800 + (relayState.onHour.value - 1)
    )
    val onMinuteScrollState = rememberLazyListState(
        initialFirstVisibleItemIndex = 9000 + relayState.onMinute.value
    )
    val onPeriodScrollState = rememberLazyListState(
        initialFirstVisibleItemIndex = if (relayState.onPeriod.value.trim().uppercase() == "AM") 0 else 1
    )
    val offHourScrollState = rememberLazyListState(
        initialFirstVisibleItemIndex = 1800 + (relayState.offHour.value - 1)
    )
    val offMinuteScrollState = rememberLazyListState(
        initialFirstVisibleItemIndex = 9000 + relayState.offMinute.value
    )
    val offPeriodScrollState = rememberLazyListState(
        initialFirstVisibleItemIndex = if (relayState.offPeriod.value.trim().uppercase() == "AM") 0 else 1
    )

    val currentOnHour by remember { derivedStateOf { (onHourScrollState.firstVisibleItemIndex % 12) + 1 } }
    val currentOnMinute by remember { derivedStateOf { onMinuteScrollState.firstVisibleItemIndex % 60 } }
    val currentOnPeriod by remember { derivedStateOf { if (onPeriodScrollState.firstVisibleItemIndex == 0) "AM" else "PM" } }
    val currentOffHour by remember { derivedStateOf { (offHourScrollState.firstVisibleItemIndex % 12) + 1 } }
    val currentOffMinute by remember { derivedStateOf { offMinuteScrollState.firstVisibleItemIndex % 60 } }
    val currentOffPeriod by remember { derivedStateOf { if (offPeriodScrollState.firstVisibleItemIndex == 0) "AM" else "PM" } }

    var selectedTimeTab by rememberSaveable { mutableIntStateOf(0) }
    val hourScrollState = if (selectedTimeTab == 0) onHourScrollState else offHourScrollState
    val minuteScrollState = if (selectedTimeTab == 0) onMinuteScrollState else offMinuteScrollState
    val periodScrollState = if (selectedTimeTab == 0) onPeriodScrollState else offPeriodScrollState

    var showEnableTimerWarning by remember { mutableStateOf(false) }
    var showMinDurationWarning by remember { mutableStateOf(false) }
    var showNoRepeatDaysWarning by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    var showSameTimeWarning by remember { mutableStateOf(false) }

    // Compute total seconds for display
    val totalOnSec = pulseOnHours * 3600 + pulseOnMinutes * 60 + pulseOnSeconds
    val totalOffSec = pulseOffHours * 3600 + pulseOffMinutes * 60 + pulseOffSeconds

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // -- Timer Mode Enable Switch --
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Enable Timer Mode",
                    fontFamily = ManropeFontFamily,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Switch(
                    checked = relayState.isAutoMode.value && relayState.modeType.value == 1,
                    onCheckedChange = { isChecked ->
                        if (isChecked) {
                            if (relayState.isRelayOn.value) {
                                relayState.isRelayOn.value = false
                                viewModel.sendCommand(device.id, false)
                            }
                            relayState.isAutoMode.value = true
                            relayState.modeType.value = 1
                        } else {
                            relayState.isAutoMode.value = false
                            relayState.modeType.value = 0
                        }
                    },
                    enabled = viewModel.isConnected.value && !viewModel.rtcFailedState.value && !viewModel.rtcLostPowerState.value
                )
            }
        }

        // -- Run Indefinitely (24/7) Switch --
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Run Indefinitely",
                        fontFamily = ManropeFontFamily,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Pulse cycle runs 24/7 (ignores time window)",
                        fontFamily = ManropeFontFamily,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Switch(
                    checked = isInfiniteLocal,
                    onCheckedChange = { isInfiniteLocal = it },
                    enabled = viewModel.isConnected.value && !viewModel.rtcFailedState.value && !viewModel.rtcLostPowerState.value
                )
            }
        }

        // -- Time Window (only shown if NOT infinite) --
        AnimatedVisibility(
            visible = !isInfiniteLocal,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 3D Drum Clock Dial Picker
                ClockDialPicker(
                    selectedTab = selectedTimeTab,
                    onTabSelected = { selectedTimeTab = it },
                    onHour = currentOnHour,
                    onMinute = currentOnMinute,
                    onPeriod = currentOnPeriod,
                    offHour = currentOffHour,
                    offMinute = currentOffMinute,
                    offPeriod = currentOffPeriod,
                    hourScrollState = hourScrollState,
                    minuteScrollState = minuteScrollState,
                    periodScrollState = periodScrollState
                )

                // Day Selector
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Box(modifier = Modifier.padding(14.dp)) {
                        DaySelector(
                            daysMask = relayState.daysMask.value,
                            onDaysMaskChanged = { newMask ->
                                relayState.daysMask.value = newMask
                            }
                        )
                    }
                }
            }
        }

        // -- Pulse Configuration Card --
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Text(
                    text = "Pulse Configuration",
                    fontFamily = ManropeFontFamily,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // -- ON Duration --
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "ON Duration",
                            fontFamily = ManropeFontFamily,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = formatDuration(totalOnSec),
                            fontFamily = ManropeFontFamily,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = deviceThemeColor
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Hours slider
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Hr",
                            fontFamily = ManropeFontFamily,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(32.dp)
                        )
                        Slider(
                            value = pulseOnHours.toFloat(),
                            onValueChange = { pulseOnHours = it.toInt() },
                            valueRange = 0f..24f,
                            steps = 23,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = deviceThemeColor,
                                activeTrackColor = deviceThemeColor,
                                inactiveTrackColor = deviceThemeColor.copy(alpha = 0.24f)
                            )
                        )
                        Text(
                            text = "${pulseOnHours}h",
                            fontFamily = ManropeFontFamily,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = deviceThemeColor,
                            modifier = Modifier.width(36.dp),
                            textAlign = TextAlign.End
                        )
                    }

                    // Minutes slider
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Min",
                            fontFamily = ManropeFontFamily,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(32.dp)
                        )
                        Slider(
                            value = pulseOnMinutes.toFloat(),
                            onValueChange = { pulseOnMinutes = it.toInt() },
                            valueRange = 0f..59f,
                            steps = 58,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = deviceThemeColor,
                                activeTrackColor = deviceThemeColor,
                                inactiveTrackColor = deviceThemeColor.copy(alpha = 0.24f)
                            )
                        )
                        Text(
                            text = "${pulseOnMinutes}m",
                            fontFamily = ManropeFontFamily,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = deviceThemeColor,
                            modifier = Modifier.width(36.dp),
                            textAlign = TextAlign.End
                        )
                    }

                    // Seconds slider
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Sec",
                            fontFamily = ManropeFontFamily,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(32.dp)
                        )
                        Slider(
                            value = pulseOnSeconds.toFloat(),
                            onValueChange = { pulseOnSeconds = it.toInt() },
                            valueRange = 0f..59f,
                            steps = 58,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = deviceThemeColor,
                                activeTrackColor = deviceThemeColor,
                                inactiveTrackColor = deviceThemeColor.copy(alpha = 0.24f)
                            )
                        )
                        Text(
                            text = "${pulseOnSeconds}s",
                            fontFamily = ManropeFontFamily,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = deviceThemeColor,
                            modifier = Modifier.width(36.dp),
                            textAlign = TextAlign.End
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // -- OFF Duration --
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "OFF Duration",
                            fontFamily = ManropeFontFamily,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = formatDuration(totalOffSec),
                            fontFamily = ManropeFontFamily,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = deviceThemeColor
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Hours slider
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Hr",
                            fontFamily = ManropeFontFamily,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(32.dp)
                        )
                        Slider(
                            value = pulseOffHours.toFloat(),
                            onValueChange = { pulseOffHours = it.toInt() },
                            valueRange = 0f..24f,
                            steps = 23,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = deviceThemeColor,
                                activeTrackColor = deviceThemeColor,
                                inactiveTrackColor = deviceThemeColor.copy(alpha = 0.24f)
                            )
                        )
                        Text(
                            text = "${pulseOffHours}h",
                            fontFamily = ManropeFontFamily,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = deviceThemeColor,
                            modifier = Modifier.width(36.dp),
                            textAlign = TextAlign.End
                        )
                    }

                    // Minutes slider
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Min",
                            fontFamily = ManropeFontFamily,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(32.dp)
                        )
                        Slider(
                            value = pulseOffMinutes.toFloat(),
                            onValueChange = { pulseOffMinutes = it.toInt() },
                            valueRange = 0f..59f,
                            steps = 58,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = deviceThemeColor,
                                activeTrackColor = deviceThemeColor,
                                inactiveTrackColor = deviceThemeColor.copy(alpha = 0.24f)
                            )
                        )
                        Text(
                            text = "${pulseOffMinutes}m",
                            fontFamily = ManropeFontFamily,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = deviceThemeColor,
                            modifier = Modifier.width(36.dp),
                            textAlign = TextAlign.End
                        )
                    }

                    // Seconds slider
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Sec",
                            fontFamily = ManropeFontFamily,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(32.dp)
                        )
                        Slider(
                            value = pulseOffSeconds.toFloat(),
                            onValueChange = { pulseOffSeconds = it.toInt() },
                            valueRange = 0f..59f,
                            steps = 58,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = deviceThemeColor,
                                activeTrackColor = deviceThemeColor,
                                inactiveTrackColor = deviceThemeColor.copy(alpha = 0.24f)
                            )
                        )
                        Text(
                            text = "${pulseOffSeconds}s",
                            fontFamily = ManropeFontFamily,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = deviceThemeColor,
                            modifier = Modifier.width(36.dp),
                            textAlign = TextAlign.End
                        )
                    }
                }
            }
        }

        // -- Save Settings Button --
        Button(
            onClick = {
                val isTimerEnabled = relayState.isAutoMode.value && relayState.modeType.value == 1
                val onTotal = calcTotal(currentOnHour, currentOnMinute, currentOnPeriod)
                val offTotal = calcTotal(currentOffHour, currentOffMinute, currentOffPeriod)
                if (!isTimerEnabled) {
                    showEnableTimerWarning = true
                } else if (totalOnSec < 1 || totalOffSec < 1) {
                    showMinDurationWarning = true
                } else if (!isInfiniteLocal && relayState.daysMask.value == 0) {
                    showNoRepeatDaysWarning = true
                } else if (!isInfiniteLocal && onTotal == offTotal) {
                    showSameTimeWarning = true
                } else {
                    // Save time window values
                    relayState.onHour.value = currentOnHour
                    relayState.onMinute.value = currentOnMinute
                    relayState.onPeriod.value = currentOnPeriod
                    relayState.offHour.value = currentOffHour
                    relayState.offMinute.value = currentOffMinute
                    relayState.offPeriod.value = currentOffPeriod

                    // Save pulse settings
                    relayState.isInfinite.value = isInfiniteLocal
                    relayState.pulseOnSec.value = totalOnSec
                    relayState.pulseOffSec.value = totalOffSec

                    viewModel.saveStateManually(device.id, showConfirmation = true)
                    onSaveAndClose()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = deviceThemeColor,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            enabled = viewModel.isConnected.value && !viewModel.rtcFailedState.value && !viewModel.rtcLostPowerState.value
        ) {
            Text(
                text = "Save Settings",
                fontFamily = ManropeFontFamily,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    // Warning: Timer not enabled
    if (showEnableTimerWarning) {
        AlertDialog(
            onDismissRequest = { showEnableTimerWarning = false },
            title = {
                Text(
                    "Enable Timer Mode",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    "Please enable Timer Mode to save the settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = { showEnableTimerWarning = false }) {
                    Text("OK", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(24.dp)
        )
    }

    // Warning: Minimum duration
    if (showMinDurationWarning) {
        AlertDialog(
            onDismissRequest = { showMinDurationWarning = false },
            title = {
                Text(
                    "Invalid Duration",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    "Both ON and OFF durations must be at least 1 second.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = { showMinDurationWarning = false }) {
                    Text("OK", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(24.dp)
        )
    }

    if (showNoRepeatDaysWarning) {
        AlertDialog(
            onDismissRequest = { showNoRepeatDaysWarning = false },
            title = {
                Text(
                    "No Repeat Days Selected",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    "Please select at least one repeat day for this schedule to run.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = { showNoRepeatDaysWarning = false }) {
                    Text("OK", color = deviceThemeColor, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(24.dp)
        )
    }

    if (showSameTimeWarning) {
        AlertDialog(
            onDismissRequest = { showSameTimeWarning = false },
            title = {
                Text(
                    "Same ON/OFF Time Selected",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    "The start and end times cannot be identical. Please select distinct times.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = { showSameTimeWarning = false }) {
                    Text("OK", color = deviceThemeColor, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(24.dp)
        )
    }
}

/**
 * Formats a duration in seconds to a human-readable string.
 * e.g., 7265 -> "2h 1m 5s", 65 -> "1m 5s", 5 -> "5s"
 */
private fun formatDuration(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return buildString {
        if (hours > 0) append("${hours}h ")
        if (minutes > 0 || hours > 0) append("${minutes}m ")
        append("${seconds}s")
    }.trim()
}

private fun calcTotal(h: Int, m: Int, period: String): Int {
    val hr24 = if (period.trim().uppercase() == "PM") {
        if (h == 12) 12 else h + 12
    } else {
        if (h == 12) 0 else h
    }
    return hr24 * 60 + m
}


/**
 * Edit Device Dialog
 * Allows modifying the name, category/icon, and output socket of a device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditDeviceDialog(
    device: DeviceInfo,
    viewModel: RelayViewModel,
    onDismiss: () -> Unit
) {
    var deviceName by rememberSaveable { mutableStateOf(device.name.value) }
    var selectedIcon by rememberSaveable { mutableStateOf(device.iconType.value) }

    val usedSockets = viewModel.getUsedSocketIds()
    // Available sockets include those that are currently unused plus the device's own current socket
    val availableSockets = (1..RelayViewModel.MAX_DEVICES).filter { it !in usedSockets || it == device.id }
    var selectedSocket by rememberSaveable { mutableIntStateOf(device.id) }

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
                "Edit Device Settings",
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
                // Name Field
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
                            Color.Transparent
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

                // Output Pin/Socket Selection
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
                    availableSockets.forEach { socketId ->
                        val isSelected = selectedSocket == socketId
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedSocket = socketId },
                            label = {
                                Text(
                                    text = "S$socketId",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                selectedLabelColor = MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val finalName = deviceName.ifBlank {
                        when (selectedIcon) {
                            "lightbulb" -> "Light"
                            "co2" -> "CO2"
                            "water_drop" -> "Pump"
                            "thermostat" -> "Heater"
                            else -> "Device"
                        }
                    }
                    viewModel.renameDevice(device.id, finalName)
                    viewModel.changeDeviceIcon(device.id, selectedIcon)
                    if (selectedSocket != device.id) {
                        viewModel.changeDeviceSocket(device.id, selectedSocket)
                    }
                    onDismiss()
                }
            ) {
                Text("Save", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        viewModel.removeDevice(device.id)
                        viewModel.activeDetailDevice.value = null
                        onDismiss()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(24.dp)
    )
}
