package com.example.controlhub.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Co2
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
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
    onCardClick: () -> Unit
) {
    val isDarkTheme = true
    val activeCardBg = if (relayState.isRelayOn.value) {
        when (device.iconType.value) {
            "lightbulb" -> if (isDarkTheme) Color(0xFF3E2D00) else Color(0xFFFEF3C7) // Soft Amber
            "co2" -> if (isDarkTheme) Color(0xFF142F1B) else Color(0xFFDCFCE7)        // Soft Green
            "water_drop" -> if (isDarkTheme) Color(0xFF0F253F) else Color(0xFFE0F2FE)  // Soft Blue
            "thermostat" -> if (isDarkTheme) Color(0xFF3F1313) else Color(0xFFFEE2E2)  // Soft Red
            else -> if (isDarkTheme) Color(0xFF003D3D) else Color(0xFFE0F7F7)          // Soft Teal
        }
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCardClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = activeCardBg
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isDarkTheme) {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.3f) // Subtle outline border in Light Mode
            }
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isDarkTheme) 0.dp else 1.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Device icon
                DeviceIcon(
                    iconType = device.iconType.value,
                    isActive = relayState.isRelayOn.value
                )

                // Static name + status
                Column(
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Text(
                        text = device.name.value,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    val statusText = if (relayState.isAutoMode.value) {
                        if (relayState.modeType.value == 1) {
                            "Timer · Pulse"
                        } else {
                            val timeStr = if (relayState.isRelayOn.value) {
                                String.format(java.util.Locale.US, "%02d:%02d %s", relayState.offHour.value, relayState.offMinute.value, relayState.offPeriod.value)
                            } else {
                                String.format(java.util.Locale.US, "%02d:%02d %s", relayState.onHour.value, relayState.onMinute.value, relayState.onPeriod.value)
                            }
                            if (relayState.isRelayOn.value) "Auto · Off $timeStr" else "Auto · On $timeStr"
                        }
                    } else {
                        if (relayState.isRelayOn.value) "Manual · On" else "Manual · Off"
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (relayState.isRelayOn.value) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }

            // Toggle switch with dedicated padded bounds
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .wrapContentSize(),
                contentAlignment = Alignment.CenterEnd
            ) {
                CustomToggle(
                    checked = relayState.isRelayOn.value,
                    onCheckedChange = { isChecked ->
                        relayState.isRelayOn.value = isChecked
                        if (!relayState.isAutoMode.value) {
                            viewModel.sendCommand(device.id, isChecked)
                        }
                    },
                    enabled = !relayState.isAutoMode.value && viewModel.isConnected.value && !viewModel.rtcFailedState.value && !viewModel.rtcLostPowerState.value,
                    isLarge = false
                )
            }
        }
    }
}

/**
 * Device icon with colored background based on icon type and active state.
 * Uses Material Design vector icons for consistent cross-device appearance.
 */
@Composable
fun DeviceIcon(
    iconType: String,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val isDarkTheme = true
    // Opaque pre-blended vibrant colors matching the ON-state icon circle exactly 
    // (exact mathematical blend of activeCardBg and bgColor at 0.28f alpha)
    val bgColor = when (iconType) {
        "lightbulb" -> Color(0xFF745D2B)   // Vibrant blended Amber
        "co2" -> Color(0xFF346544)         // Vibrant blended Green
        "water_drop" -> Color(0xFF264973)   // Vibrant blended Blue
        "thermostat" -> Color(0xFF743C3C)   // Vibrant blended Red
        else -> Color(0xFF0A676F)           // Vibrant blended Teal
    }

    Box(
        modifier = modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(bgColor), // Solid background prevents color diluting over grey/dark cards
        contentAlignment = Alignment.Center
    ) {
        val iconVector = when (iconType) {
            "lightbulb" -> Icons.Filled.Lightbulb
            "co2" -> Icons.Filled.Co2
            "water_drop" -> Icons.Filled.WaterDrop
            "thermostat" -> Icons.Filled.Thermostat
            else -> Icons.Filled.Bolt
        }
        val iconLabel = when (iconType) {
            "lightbulb" -> "Light"
            "co2" -> "CO2"
            "water_drop" -> "Water"
            "thermostat" -> "Thermostat"
            else -> "Power"
        }
        Icon(
            imageVector = iconVector,
            contentDescription = iconLabel,
            modifier = Modifier.size(28.dp),
            tint = Color.White
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
    Box(
        modifier = Modifier.padding(horizontal = if (isLarge) 6.dp else 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Switch(
            modifier = Modifier.scale(if (isLarge) 1.25f else 1.05f),
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
