package com.example.hermesassistant

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Compact "over other apps" assistant surface, in the spirit of the
 * Google Assistant floating panel.
 *
 * A TYPE_APPLICATION_OVERLAY added from the foreground service. Unlike
 * the Compose activity it does NOT take focus or cover the whole
 * screen: the user keeps their app visible, the assistant floats in a
 * small rounded card near the bottom with live status, a mini orb, and
 * the mic action. The card body expands to the full Compose sheet; the
 * mic starts/stops listening in place.
 *
 * Not a new component — a view managed by the existing FGS, observing
 * the app-scoped AssistantViewModel singleton (same state as the
 * activity, no second WebSocket/audio stack).
 */
class AssistantOverlay(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: ComposeView? = null
    private var onExpand: (() -> Unit)? = null
    private var onMic: (() -> Unit)? = null

    /** Whether the system grants draw-over permission right now. */
    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    /**
     * Show the floating card. [onExpand] runs when the card body is
     * tapped (launch the full sheet); [onMic] runs on the mic action.
     */
    @SuppressLint("InflateParams")
    fun show(onExpand: () -> Unit, onMic: () -> Unit) {
        hide()
        if (!canShow()) return
        this.onExpand = onExpand
        this.onMic = onMic

        val composeView = ComposeView(context).apply {
            setContent {
                OverlayCard(
                    onExpand = { this@AssistantOverlay.onExpand?.invoke() },
                    onMic = { this@AssistantOverlay.onMic?.invoke() },
                )
            }
        }

        // Compact card: wrap content, bottom-centered, above the gesture
        // nav bar. NOT focusable so the underlying app keeps the keyboard.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (24 * context.resources.displayMetrics.density).toInt()
        }
        try {
            wm.addView(composeView, params)
            view = composeView
        } catch (e: Exception) {
            view = null
        }
    }

    fun hide() {
        val v = view ?: return
        view = null
        onExpand = null
        onMic = null
        try {
            wm.removeView(v)
        } catch (e: Exception) {
            // Already removed
        }
    }

    @Composable
    private fun OverlayCard(onExpand: () -> Unit, onMic: () -> Unit) {
        val vm = AppViewModelProvider.viewModel
        val state by vm.uiState.collectAsState()

        // Auto-hide shortly after the turn settles back to idle.
        LaunchedEffect(state.voiceActive, state.statusState) {
            if (!state.voiceActive && state.statusState == StatusRingView.State.IDLE) {
                delay(4000)
                hide()
            }
        }

        Row(
            modifier = Modifier
                .padding(16.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(20.dp))
                .clickable(onClick = onExpand)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Mini orb: colored dot matching the state
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(orbColor(state), CircleShape),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.status,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    maxLines = 1,
                )
                Text(
                    text = state.subTextLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onMic) {
                val (icon, desc, tint) = when {
                    state.voiceActive -> Triple(
                        Icons.Default.Stop,
                        "Stop listening",
                        MaterialTheme.colorScheme.error,
                    )
                    state.hasParkedAudio -> Triple(
                        Icons.Default.PlayArrow,
                        "Play response",
                        MaterialTheme.colorScheme.primary,
                    )
                    else -> Triple(
                        Icons.Default.Mic,
                        "Tap to speak",
                        MaterialTheme.colorScheme.primary,
                    )
                }
                Icon(imageVector = icon, contentDescription = desc, tint = tint)
            }
            IconButton(onClick = { hide() }) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    private fun orbColor(state: AssistantUiState): Color = when (state.statusState) {
        StatusRingView.State.IDLE -> Color(0xFF3B82F6)
        StatusRingView.State.CONNECTED -> Color(0xFF10B981)
        StatusRingView.State.LISTENING -> Color(0xFFF59E0B)
        StatusRingView.State.THINKING -> Color(0xFF8B5CF6)
        StatusRingView.State.SPEAKING -> Color(0xFFEC4899)
    }
}
