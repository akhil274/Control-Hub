package com.example.controlhub.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.controlhub.R

@Composable
fun DrawerContent() {
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        drawerContentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.width(280.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 24.dp)
        ) {
            // Header — uses spacing instead of dividers per Abyssal Flow "No-Line" rule
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "ControlHub",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Smart Relay Controller",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Social links section — no dividers, just spacing
            Text(
                text = "FOLLOW US",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = MaterialTheme.typography.labelSmall.letterSpacing * 2,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )

            DrawerLink(
                iconResId = R.drawable.youtube,
                label = "YouTube",
                url = "https://www.youtube.com/@aquasquare/featured"
            )

            DrawerLink(
                iconResId = R.drawable.instagram,
                label = "Instagram",
                url = "https://www.instagram.com/aqua_square_?igsh=MTNlcmh1YTRsYjJu"
            )

            DrawerLink(
                iconResId = R.drawable.facebook,
                label = "Facebook",
                url = "https://www.facebook.com/share/16R2TbYQmD/"
            )

            Spacer(modifier = Modifier.height(24.dp))

            // About — no divider before it
            var showAboutDialog by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAboutDialog = true }
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "About",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(16.dp))
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
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    text = {
                        Text(
                            text = "ControlHub is an app designed to control relays (e.g., Light and CO2) via Bluetooth, connectable to an ESP32 with provided code. Created with help from AI (Grok and ChatGPT) by a first-time developer with no prior coding experience, this is an experimental project. Expect some bugs as a disclaimer. The app allows manual relay toggling, automatic scheduling with time settings, and RTC time synchronization. It's extensible—more relays can be added. For the full code to customize or add relays, I can provide it upon request.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { showAboutDialog = false }) {
                            Text("OK", color = MaterialTheme.colorScheme.primary)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(24.dp)
                )
            }
        }
    }
}

@Composable
private fun DrawerLink(iconResId: Int, label: String, url: String) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                context.startActivity(intent)
            }
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(id = iconResId),
            contentDescription = "$label Icon",
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(4.dp))
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
