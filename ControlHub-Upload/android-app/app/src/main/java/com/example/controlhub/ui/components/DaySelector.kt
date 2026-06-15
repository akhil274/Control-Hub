package com.example.controlhub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Day-of-week selector with 7 circular buttons.
 * Includes a "Select All" toggle.
 *
 * The daysMask uses a bitmask:
 * - bit 0 = Monday
 * - bit 1 = Tuesday
 * - bit 2 = Wednesday
 * - bit 3 = Thursday
 * - bit 4 = Friday
 * - bit 5 = Saturday
 * - bit 6 = Sunday
 *
 * @param daysMask current bitmask (0-127)
 * @param onDaysMaskChanged callback when mask changes
 */
@Composable
fun DaySelector(
    daysMask: Int,
    onDaysMaskChanged: (Int) -> Unit
) {
    val dayLabels = listOf("M", "T", "W", "T", "F", "S", "S")
    val allDaysMask = 0x7F  // 127 = all 7 bits set

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header with "Select All" toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "REPEAT DAYS",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = MaterialTheme.typography.labelSmall.letterSpacing * 2
            )
            Text(
                text = if (daysMask == allDaysMask) "Deselect All" else "Select All",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    onDaysMaskChanged(if (daysMask == allDaysMask) 0 else allDaysMask)
                }
            )
        }

        // Day buttons row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            dayLabels.forEachIndexed { index, label ->
                val isActive = (daysMask and (1 shl index)) != 0

                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            if (isActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable {
                            val newMask = daysMask xor (1 shl index)
                            onDaysMaskChanged(newMask)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isActive) MaterialTheme.colorScheme.surfaceDim
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
