package com.example.controlhub

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.controlhub.ui.theme.ControlHubTheme
import kotlinx.coroutines.delay

@SuppressLint("CustomSplashScreen")
class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ControlHubTheme {
                SplashScreen {
                    startActivity(Intent(this@SplashActivity, MainActivity::class.java))
                    finish()
                    @Suppress("DEPRECATION")
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                }
            }
        }
    }
}

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val isDark = true
    
    // Background colors: Light Mode is vibrant cyan (#00fff0), Dark Mode is #1abfc4
    val bgColor = if (isDark) Color(0xFF1ABFC4) else Color(0xFF00FFF0)
    
    // Circle container parameters: Light Mode has a white circle, Dark Mode has deep navy
    val circleBgColor = if (isDark) Color(0xFF0B1326) else Color.White
    val circleBorderColor = if (isDark) Color(0xFF44DDC1).copy(alpha = 0.4f) else Color.White
    val logoResId = if (isDark) R.drawable.logo else R.drawable.logo_black
    
    // Branding text colors: contrasting with the vibrant cyan/teal backgrounds
    val brandLabelColor = if (isDark) Color(0xFF0B1326).copy(alpha = 0.6f) else Color(0xFF0B1326).copy(alpha = 0.5f)
    val brandNameColor = if (isDark) Color(0xFF0B1326).copy(alpha = 0.9f) else Color(0xFF0B1326).copy(alpha = 0.8f)

    // Match status/navigation bars to the splash screen background
    val context = LocalContext.current
    SideEffect {
        val window = (context as? Activity)?.window
        window?.let {
            val argbColor = bgColor.toArgb()
            it.statusBarColor = argbColor
            it.navigationBarColor = argbColor
            val controller = WindowCompat.getInsetsController(it, it.decorView)
            // Both backgrounds are light enough that dark status bar icons look best
            controller.isAppearanceLightStatusBars = true
            controller.isAppearanceLightNavigationBars = true
        }
    }

    // Fade-in animation
    val contentAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        contentAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(500, easing = EaseOutCubic)
        )
        // Wait then navigate
        delay(1000)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        // Decorative ambient glow circles (Top-left shadow, Bottom-right shadow)
        Box(
            modifier = Modifier
                .size(380.dp)
                .offset(x = (-80).dp, y = 200.dp)
                .blur(100.dp)
                .alpha(if (isDark) 0.25f else 0.18f)
                .background(
                    color = Color(0xFF0B1326),
                    shape = RoundedCornerShape(50)
                )
        )
        Box(
            modifier = Modifier
                .size(300.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 60.dp, y = (-120).dp)
                .blur(120.dp)
                .alpha(if (isDark) 0.18f else 0.12f)
                .background(
                    color = Color(0xFF0B1326),
                    shape = RoundedCornerShape(50)
                )
        )

        // Centered Logo with circular background container
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(contentAlpha.value),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(240.dp) // Increased circle size from 180.dp to 240.dp
                    .shadow(
                        elevation = 8.dp,
                        shape = CircleShape,
                        clip = false
                    )
                    .background(circleBgColor, shape = CircleShape)
                    .border(1.5.dp, circleBorderColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = logoResId),
                    contentDescription = "ControlHub Logo",
                    modifier = Modifier
                        .width(200.dp) // Adjusted logo width to 200.dp to keep it inside the 240.dp circle
                        .padding(horizontal = 8.dp)
                )
            }
        }

        // Bottom branding - "BY AquaSquare"
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .alpha(contentAlpha.value),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "BY",
                style = MaterialTheme.typography.labelSmall,
                color = brandLabelColor,
                letterSpacing = MaterialTheme.typography.labelSmall.letterSpacing * 2
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "AquaSquare",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = brandNameColor
            )
        }
    }
}
