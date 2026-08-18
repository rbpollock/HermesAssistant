package com.example.hermesassistant.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos

/** The four invocation states the orb communicates. */
enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING }

/**
 * Audio-reactive orb for the assistant sheet. LISTENING uses the REAL
 * mic amplitude (rms 0..1) from the STT pipeline; THINKING pulses
 * slowly; SPEAKING uses the response playback state (pulsing warm);
 * IDLE is a quiet resting glow.
 */
@Composable
fun ListeningOrb(
    state: OrbState,
    rmsLevel: Float,
    modifier: Modifier = Modifier,
    sizeDp: Int = 150,
) {
    val infinite = rememberInfiniteTransition(label = "orb")
    val pulse by infinite.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    // Smooth transitions between states so the orb never jumps.
    val targetColor = when (state) {
        OrbState.IDLE -> Color(0xFF334155)
        OrbState.LISTENING -> Color(0xFF34D399)
        OrbState.THINKING -> Color(0xFFFBBF24)
        OrbState.SPEAKING -> Color(0xFF60A5FA)
    }
    val crossfade by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(400),
        label = "colorCrossfade",
    )
    // Rebuild the actual color by lerping from the idle slate.
    val baseColor = lerpColor(Color(0xFF334155), targetColor, crossfade)

    val scale = when (state) {
        OrbState.IDLE -> 0.92f
        OrbState.LISTENING -> 0.95f + (0.08f * rmsLevel)
        OrbState.THINKING -> 0.9f + (0.1f * pulse)
        OrbState.SPEAKING -> 0.94f + (0.06f * pulse)
    }

    Box(modifier = modifier.size(sizeDp.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(sizeDp.dp)) {
            val r = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            // Outer glow ring (pulses with activity)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(baseColor.copy(alpha = 0.35f), Color.Transparent),
                    center = center,
                    radius = r,
                ),
                radius = r * (0.9f + (0.25f * pulse)),
                center = center,
            )

            // Inner orb
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        baseColor.copy(alpha = 0.9f),
                        baseColor.copy(alpha = 0.25f),
                    ),
                    center = center,
                    radius = r * scale,
                ),
                radius = r * scale,
                center = center,
            )

            // Listening: waveform rings that ripple with real amplitude
            if (state == OrbState.LISTENING) {
                val ringAlpha = (0.2f + (0.5f * rmsLevel)) * (0.6f + 0.4f * pulse)
                for (i in 1..3) {
                    val phase = (i * 0.33f + pulse * 0.5f) % 1f
                    drawCircle(
                        color = baseColor.copy(alpha = ringAlpha * (1f - phase)),
                        radius = r * (scale + 0.15f + phase * 0.35f),
                        center = center,
                        style = Stroke(width = 3f),
                    )
                }
            }

            // Thinking: orbiting dot (a rotating tick) instead of rings
            if (state == OrbState.THINKING) {
                val angle = pulse * 2f * Math.PI.toFloat()
                val orbitR = r * 0.85f
                val dotX = center.x + kotlin.math.cos(angle) * orbitR
                val dotY = center.y + kotlin.math.sin(angle) * orbitR
                drawCircle(
                    color = baseColor,
                    radius = 6f,
                    center = Offset(dotX, dotY),
                )
            }

            // Speaking: steady bright core (audio already conveys motion)
            if (state == OrbState.SPEAKING) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.9f), baseColor.copy(alpha = 0.5f)),
                        center = center,
                        radius = r * 0.7f,
                    ),
                    radius = r * 0.7f * scale,
                    center = center,
                )
            }
        }
    }
}

/** Linear interpolate between two colors (0..1 t). */
private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val tt = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * tt,
        green = a.green + (b.green - a.green) * tt,
        blue = a.blue + (b.blue - a.blue) * tt,
        alpha = a.alpha + (b.alpha - a.alpha) * tt,
    )
}
