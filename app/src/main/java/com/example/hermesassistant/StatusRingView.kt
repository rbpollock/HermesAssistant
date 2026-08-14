package com.example.hermesassistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min
import kotlin.math.sin

/**
 * Animated circular status indicator.
 *
 * States drive both the color and the animation pattern:
 *  - IDLE:      slow blue breathing ring (waiting for wake word)
 *  - CONNECTED: steady green ring (server link alive)
 *  - LISTENING: amber expanding double pulse (mic capturing)
 *  - THINKING:  purple rotating arc (agent working)
 *  - SPEAKING:  pink arcs sweeping outward (audio playing)
 */
class StatusRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class State(val color: Int) {
        IDLE(0xFF3B82F6.toInt()),      // blue
        CONNECTED(0xFF10B981.toInt()), // green
        LISTENING(0xFFF59E0B.toInt()), // amber
        THINKING(0xFF8B5CF6.toInt()),  // purple
        SPEAKING(0xFFEC4899.toInt()),  // pink
    }

    var state: State = State.IDLE
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
        start()
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(6f)
        strokeCap = Paint.Cap.ROUND
    }
    private val thinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val base = min(width, height) / 2f - dp(14f)
        val t = animator.animatedValue as Float
        val color = state.color

        // 1) Outer breathing glow
        val breath = 0.5f + 0.5f * sin(2.0 * Math.PI * t).toFloat()
        thinPaint.color = color
        thinPaint.alpha = (35 + 70 * breath).toInt()
        canvas.drawCircle(cx, cy, base + dp(10f) + dp(6f) * breath, thinPaint)

        // 2) Main ring / arcs per state
        when (state) {
            State.LISTENING -> {
                // Fast expanding double pulse (mic live)
                val p1 = (t * 2) % 1f
                val p2 = (t * 2 + 0.5f) % 1f
                ringPaint.color = color
                for (p in listOf(p1, p2)) {
                    ringPaint.alpha = (230 * (1f - p)).toInt()
                    canvas.drawCircle(cx, cy, base * (0.65f + 0.35f * p), ringPaint)
                }
                fillPaint.color = color
                fillPaint.alpha = 70
                canvas.drawCircle(cx, cy, base * 0.55f, fillPaint)
            }
            State.THINKING -> {
                // Rotating arc (agent working)
                ringPaint.color = color
                ringPaint.alpha = 230
                val start = t * 360f
                canvas.drawArc(cx - base, cy - base, cx + base, cy + base, start, 280f, false, ringPaint)
                fillPaint.color = color
                fillPaint.alpha = 45
                canvas.drawCircle(cx, cy, base * 0.4f, fillPaint)
            }
            State.SPEAKING -> {
                // Sweeping arcs (audio playing)
                ringPaint.color = color
                ringPaint.alpha = 230
                val sweep = 70f + 40f * breath
                canvas.drawArc(cx - base, cy - base, cx + base, cy + base, t * 360f, sweep, false, ringPaint)
                canvas.drawArc(cx - base, cy - base, cx + base, cy + base, t * 360f + 180f, sweep, false, ringPaint)
                fillPaint.color = color
                fillPaint.alpha = 60
                canvas.drawCircle(cx, cy, base * 0.5f, fillPaint)
            }
            else -> {
                // IDLE / CONNECTED: steady ring + slow rotating accent arc
                ringPaint.color = color
                ringPaint.alpha = 210
                canvas.drawCircle(cx, cy, base, ringPaint)
                thinPaint.color = color
                thinPaint.alpha = (120 + 80 * breath).toInt()
                canvas.drawArc(
                    cx - base, cy - base, cx + base, cy + base,
                    t * 360f, 60f, false, thinPaint
                )
                fillPaint.color = color
                fillPaint.alpha = 55
                canvas.drawCircle(cx, cy, base * 0.5f, fillPaint)
            }
        }

        // 3) Center dot
        fillPaint.color = color
        fillPaint.alpha = 200
        canvas.drawCircle(cx, cy, dp(7f), fillPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }
}
