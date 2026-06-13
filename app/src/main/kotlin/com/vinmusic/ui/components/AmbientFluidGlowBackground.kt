package com.vinmusic.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vinmusic.ui.utils.ColorExtractor

@Composable
fun AmbientFluidGlowBackground(palette: ColorExtractor.MusicPalette) {
    val infiniteTransition = rememberInfiniteTransition(label = "fluid_glow")

    val t1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 25000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "t1"
    )
    val t2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "t2"
    )
    val t3 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 32000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "t3"
    )

    val scale1 by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale1"
    )
    val scale2 by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale2"
    )

    val animatedGradTop by animateColorAsState(targetValue = palette.gradTop, animationSpec = tween(1200))
    val animatedGradMid by animateColorAsState(targetValue = palette.gradMid, animationSpec = tween(1200))
    val animatedAccent by animateColorAsState(targetValue = palette.accent, animationSpec = tween(1200))

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .blur(40.dp)
    ) {
        val w = size.width
        val h = size.height

        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    animatedGradMid.copy(alpha = 0.4f),
                    Color(0xFF0E0E11)
                )
            ),
            size = size
        )

        val x1 = w * 0.25f + (w * 0.15f) * kotlin.math.cos(t1)
        val y1 = h * 0.3f + (h * 0.1f) * kotlin.math.sin(t1)
        val r1 = (w * 0.5f) * scale1
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(animatedAccent.copy(alpha = 0.5f), Color.Transparent),
                center = Offset(x1, y1),
                radius = r1
            ),
            center = Offset(x1, y1),
            radius = r1
        )

        val x2 = w * 0.7f + (w * 0.12f) * kotlin.math.cos(t2 + 1f)
        val y2 = h * 0.4f + (h * 0.12f) * kotlin.math.sin(t2 * 2f)
        val r2 = (w * 0.55f) * scale2
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(animatedGradTop.copy(alpha = 0.45f), Color.Transparent),
                center = Offset(x2, y2),
                radius = r2
            ),
            center = Offset(x2, y2),
            radius = r2
        )

        val x3 = w * 0.5f + (w * 0.2f) * kotlin.math.sin(t3)
        val y3 = h * 0.65f + (h * 0.05f) * kotlin.math.cos(t3)
        val r3 = w * 0.6f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(animatedGradMid.copy(alpha = 0.4f), Color.Transparent),
                center = Offset(x3, y3),
                radius = r3
            ),
            center = Offset(x3, y3),
            radius = r3
        )
    }
}
