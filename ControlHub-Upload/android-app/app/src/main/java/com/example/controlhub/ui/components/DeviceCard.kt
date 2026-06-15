package com.example.controlhub.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.scale
import com.example.controlhub.DeviceInfo
import com.example.controlhub.RelayState
import com.example.controlhub.RelayViewModel
import com.example.controlhub.ui.theme.ManropeFontFamily

/**
 * Expandable device card inspired by the Stitch Aqualink design.
 *
 * Collapsed: Icon + name + status + small toggle
 * Expanded: Editable name + large toggle + clock dial + day selector + save button
 */
@Composable
fun DeviceCard(
    device: DeviceInfo,
    relayState: RelayState,
    viewModel: RelayViewModel,
    isExpanded: Boolean,
    onExpandToggle: () -> Unit
) {
    val alpha by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0.85f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness    = Spring.StiffnessMediumLow
        ),
        label = "cardAlpha"
    )

    var selectedTimeTab by remember { mutableIntStateOf(0) } // 0 = ON, 1 = OFF

    // Revert unsaved UI draft values to the stored SharedPreferences values when closing the card
    LaunchedEffect(isExpanded) {
        if (!isExpanded) {
            viewModel.reloadState(device.id)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isExpanded)
                MaterialTheme.colorScheme.surfaceContainerHigh
            else
                MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isExpanded) 0.dp else 0.dp
        )
    ) {
        Box {
            // Active glow indicator (only when expanded and relay is on)
            if (isExpanded && relayState.isRelayOn.value) {
                Box(
                    modifier = Modifier
                        .size(128.dp)
                        .align(Alignment.TopEnd)
                        .offset(x = 40.dp, y = (-40).dp)
                        .blur(48.dp)
                        .alpha(0.12f)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape
                        )
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(if (isExpanded) 20.dp else 0.dp)
            ) {
                // ═══════════════════════════════════════
                // Header: Icon + Name + Toggle
                // ═══════════════════════════════════════
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (!isExpanded) Modifier.clickable { onExpandToggle() } else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Device icon
                        DeviceIcon(
                            iconType = device.iconType.value,
                            isActive = relayState.isRelayOn.value
                        )

                        if (isExpanded) {
                            // Editable name
                            BasicTextField(
                                value = device.name.value,
                                onValueChange = { newName ->
                                    viewModel.renameDevice(device.id, newName)
                                },
                                textStyle = TextStyle(
                                    fontFamily = ManropeFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = MaterialTheme.typography.titleLarge.fontSize,
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            // Static name + status + working hours
                            Column {
                                Text(
                                    text = device.name.value,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (relayState.isAutoMode.value) {
                                    // Calculate working hours
                                    val onTotal = to24Hour(relayState.onHour.value, relayState.onPeriod.value) * 60 + relayState.onMinute.value
                                    val offTotal = to24Hour(relayState.offHour.value, relayState.offPeriod.value) * 60 + relayState.offMinute.value
                                    val durationMin = if (offTotal > onTotal) offTotal - onTotal else (24 * 60 - onTotal + offTotal)
                                    val durationHrs = durationMin / 60
                                    val durationMins = durationMin % 60
                                    val durationText = if (durationMins > 0) "${durationHrs}h ${durationMins}m" else "${durationHrs}h"

                                    val onTimeStr = String.format("%02d:%02d %s", relayState.onHour.value, relayState.onMinute.value, relayState.onPeriod.value)
                                    val offTimeStr = String.format("%02d:%02d %s", relayState.offHour.value, relayState.offMinute.value, relayState.offPeriod.value)

                                    if (relayState.isRelayOn.value) {
                                        Text(
                                            text = "On • Off at $offTimeStr • $durationText",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                                        )
                                    } else {
                                        Text(
                                            text = "Off • On at $onTimeStr • $durationText",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                } else {
                                    Text(
                                        text = if (relayState.isRelayOn.value) "On • Manual" else "Off • Manual",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (relayState.isRelayOn.value)
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // Toggle switch
                    CustomToggle(
                        checked = relayState.isRelayOn.value,
                        onCheckedChange = { isChecked ->
                            relayState.isRelayOn.value = isChecked
                            if (!relayState.isAutoMode.value) {
                                viewModel.sendCommand(device.id, isChecked)
                                viewModel.saveStateManually(device.id)
                            }
                        },
                        enabled = !relayState.isAutoMode.value && viewModel.isConnected.value,
                        isLarge = isExpanded
                    )
                }

                // ═══════════════════════════════════════
                // Expanded Content
                // ═══════════════════════════════════════
                AnimatedVisibility(
                    visible = isExpanded,
                    enter = expandVertically(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness    = Spring.StiffnessMedium
                        )
                    ) + fadeIn(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness    = Spring.StiffnessMedium
                        )
                    ),
                    exit = shrinkVertically(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness    = Spring.StiffnessMediumLow
                        )
                    ) + fadeOut(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness    = Spring.StiffnessMediumLow
                        )
                    )
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        // Auto Mode toggle
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = if (relayState.isAutoMode.value) "Auto Mode" else "Manual Mode",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (relayState.isAutoMode.value) "Runs on schedule"
                                    else "Direct control",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = relayState.isAutoMode.value,
                                onCheckedChange = { isChecked ->
                                    relayState.isAutoMode.value = isChecked
                                    if (viewModel.isConnected.value) {
                                        viewModel.saveStateManually(device.id, showConfirmation = false)
                                        if (!isChecked) {
                                            viewModel.sendCommand(device.id, relayState.isRelayOn.value)
                                        }
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                                    uncheckedBorderColor = MaterialTheme.colorScheme.outline
                                )
                            )
                        }

                        // Schedule section (only visible in Auto mode)
                        AnimatedVisibility(
                            visible = relayState.isAutoMode.value,
                            enter = expandVertically(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness    = Spring.StiffnessMedium
                                )
                            ) + fadeIn(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness    = Spring.StiffnessMedium
                                )
                            ),
                            exit = shrinkVertically(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness    = Spring.StiffnessMediumLow
                                )
                            ) + fadeOut(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness    = Spring.StiffnessMediumLow
                                )
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainer)
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                // Clock Dial Picker
                                ClockDialPicker(
                                    selectedTab = selectedTimeTab,
                                    onTabSelected = { selectedTimeTab = it },
                                    onHour = relayState.onHour.value,
                                    onMinute = relayState.onMinute.value,
                                    onPeriod = relayState.onPeriod.value,
                                    offHour = relayState.offHour.value,
                                    offMinute = relayState.offMinute.value,
                                    offPeriod = relayState.offPeriod.value,
                                    onTimeChanged = { isOnTime, hour, minute, period ->
                                        if (isOnTime) {
                                            relayState.onHour.value = hour
                                            relayState.onMinute.value = minute
                                            relayState.onPeriod.value = period
                                        } else {
                                            relayState.offHour.value = hour
                                            relayState.offMinute.value = minute
                                            relayState.offPeriod.value = period
                                        }
                                    }
                                )

                                // Day Selector
                                DaySelector(
                                    daysMask = relayState.daysMask.value,
                                    onDaysMaskChanged = { relayState.daysMask.value = it }
                                )
                            }
                        }

                        // Save Settings Button
                        Button(
                            onClick = { viewModel.saveStateManually(device.id) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            enabled = viewModel.isConnected.value
                        ) {
                            Text(
                                text = "Save Settings",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (viewModel.isConnected.value)
                                    MaterialTheme.colorScheme.onPrimary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Delete Device Button
                        OutlinedButton(
                            onClick = { viewModel.removeDevice(device.id) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = "Delete Device",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Collapse button
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(onClick = onExpandToggle) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Collapse",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Device icon with colored background based on icon type and active state.
 */
@Composable
fun DeviceIcon(
    iconType: String,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val (emoji, bgColor) = when (iconType) {
        "lightbulb" -> "💡" to MaterialTheme.colorScheme.primary
        "co2" -> "🫧" to MaterialTheme.colorScheme.tertiary
        "water_drop" -> "💧" to MaterialTheme.colorScheme.primary
        "thermostat" -> "🌡️" to MaterialTheme.colorScheme.secondary
        else -> "⚡" to MaterialTheme.colorScheme.primary
    }

    Box(
        modifier = modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(bgColor.copy(alpha = if (isActive) 0.15f else 0.08f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = emoji,
            style = MaterialTheme.typography.headlineMedium
        )
    }
}

/**
 * Custom toggle switch styled to match the Abyssal Flow design.
 */
@Composable
private fun CustomToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
    isLarge: Boolean = false
) {
    Switch(
        modifier = Modifier.scale(if (isLarge) 1.5f else 1.25f),
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.surfaceDim,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            uncheckedThumbColor = MaterialTheme.colorScheme.surfaceDim,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
            disabledCheckedThumbColor = MaterialTheme.colorScheme.surfaceDim.copy(alpha = 0.6f),
            disabledCheckedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
            disabledUncheckedThumbColor = MaterialTheme.colorScheme.surfaceDim.copy(alpha = 0.6f),
            disabledUncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f)
        )
    )
}

/**
 * Convert 12-hour format to 24-hour for duration calculations.
 */
private fun to24Hour(hour: Int, period: String): Int {
    return when {
        period == "AM" && hour == 12 -> 0
        period == "PM" && hour == 12 -> 12
        period == "PM" -> hour + 12
        else -> hour
    }
}
