package com.example.controlhub

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Decorative ambient glow circles
        Box(
            modifier = Modifier
                .size(380.dp)
                .offset(x = (-80).dp, y = 200.dp)
                .blur(100.dp)
                .alpha(0.06f)
                .background(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(50)
                )
        )
        Box(
            modifier = Modifier
                .size(300.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 60.dp, y = (-120).dp)
                .blur(120.dp)
                .alpha(0.05f)
                .background(
                    color = MaterialTheme.colorScheme.secondary,
                    shape = RoundedCornerShape(50)
                )
        )

        // Centered app icon only
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(contentAlpha.value),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { context ->
                    android.widget.ImageView(context).apply {
                        setImageResource(R.mipmap.ic_launcher)
                    }
                },
                modifier = Modifier.size(120.dp)
            )
        }

        // Bottom branding — "BY AquaSquare" only
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
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                letterSpacing = MaterialTheme.typography.labelSmall.letterSpacing * 2
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "AquaSquare",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}
