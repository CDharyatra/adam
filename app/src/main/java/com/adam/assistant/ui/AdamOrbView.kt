package com.adam.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Adam's particle-sphere animation.
 *
 * A 3D globe of green dots (Fibonacci-sphere distribution, like dotted-globe
 * artwork) projected to 2D every frame — nothing is a flat image, and it is
 * never static: an infinite redraw loop ([postInvalidateOnAnimation]) keeps
 * every mode in motion (rotation never stops, even when idle):
 *  - the globe slowly rotates with a tilted axis and gentle surface breathing
 *  - LISTENING  → sonar ripple bands race over the surface
 *  - THINKING   → the sphere melts into an organic blob (noise displacement)
 *  - SPEAKING   → waveform bands pulse with [voiceLevel]
 *  - SETUP      → dots fly in from a scattered cloud with [downloadProgress]
 *
 * Custom-drawn, zero dependencies. Drive it with [orbMode].
 */
class AdamOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class OrbMode {
        IDLE,
        SETUP,
        WAKEWORD,
        LISTENING,
        THINKING,
        SPEAKING
    }

    var orbMode: OrbMode = OrbMode.IDLE
        set(value) {
            field = value
            invalidate()
        }

    var downloadProgress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** Live voice level 0..1 driving the SPEAKING bands. */
    var voiceLevel: Float = 0.5f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    companion object {
        private const val N = 1500
        private const val GOLDEN_ANGLE = 2.3999632f

        private const val GREEN = 0xFF00E676.toInt()
        private const val GREEN_BRIGHT = 0xFF69F0AE.toInt()
        private const val GREEN_DIM = 0xFF1B5E20.toInt()
    }

    private val startTime = SystemClock.elapsedRealtime()
    private var shownProgress = 0f

    // unit-sphere base points + stable per-point phase
    private val bx = FloatArray(N)
    private val by = FloatArray(N)
    private val bz = FloatArray(N)
    private val ph = FloatArray(N)

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** 32-step dim→green→bright palette (no per-frame color allocs). */
    private val palette = IntArray(32) { i ->
        val t = i / 31f
        if (t < 0.5f) lerpColor(GREEN_DIM, GREEN, t * 2f)
        else lerpColor(GREEN, GREEN_BRIGHT, (t - 0.5f) * 2f)
    }

    init {
        for (i in 0 until N) {
            val y = 1f - 2f * (i + 0.5f) / N
            val r = sqrt((1f - y * y).coerceAtLeast(0f))
            val th = i * GOLDEN_ANGLE
            bx[i] = r * cos(th)
            by[i] = y
            bz[i] = r * sin(th)
            ph[i] = hash(i, 7f)
        }
    }

    fun setAssistantState(stateName: String) {
        orbMode = when (stateName) {
            "DOWNLOADING", "INITIALISING" -> OrbMode.SETUP
            "WAKEWORD_LISTENING" -> OrbMode.WAKEWORD
            "CONVERSATION_LISTENING", "LISTENING" -> OrbMode.LISTENING
            "CONVERSATION_PROCESSING", "PROCESSING" -> OrbMode.THINKING
            "CONVERSATION_SPEAKING", "SPEAKING" -> OrbMode.SPEAKING
            else -> OrbMode.IDLE
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) {
            postInvalidateOnAnimation()
            return
        }
        val t = (SystemClock.elapsedRealtime() - startTime) / 1000f
        shownProgress += (downloadProgress - shownProgress) * 0.08f

        val rotSpeed = when (orbMode) {
            OrbMode.IDLE -> 0.5f
            OrbMode.SETUP -> 0.8f
            OrbMode.WAKEWORD -> 0.6f
            OrbMode.LISTENING -> 1.4f
            OrbMode.THINKING -> 2.2f
            OrbMode.SPEAKING -> 1.0f
        }
        val angA = t * rotSpeed
        val cosA = cos(angA)
        val sinA = sin(angA)

        val tiltWobble = if (orbMode == OrbMode.THINKING) 0.45f else 0.12f
        val tilt = 0.42f + tiltWobble * sin(t * 0.5f)
        val cosT = cos(tilt)
        val sinT = sin(tilt)

        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) * 0.30f
        val dotBase = minOf(width, height) * 0.0062f

        for (i in 0 until N) {
            val ox = bx[i]
            val oy = by[i]
            val oz = bz[i]
            val h = ph[i]

            // ── surface displacement (the morph) ──
            var glow = 0f
            val disp = when (orbMode) {
                OrbMode.THINKING -> {
                    0.50f * (0.62f * sin(2.1f * ox + t * 2.1f + h * 3f) *
                            sin(2.3f * oy - t * 1.6f) +
                            0.38f * sin(3.4f * oz + t * 2.4f))
                }
                OrbMode.LISTENING -> {
                    val band = sin(oy * 9f - t * 7f)
                    if (band > 0f) glow = band
                    0.26f * band
                }
                OrbMode.SPEAKING -> {
                    val band = sin(oy * 11f + t * 6f)
                    if (band > 0f) glow = band * (0.4f + 0.6f * voiceLevel)
                    (0.08f + 0.30f * voiceLevel) * band
                }
                OrbMode.SETUP -> {
                    0.05f * sin(3f * ox + t * 2f) * sin(3f * oy - t * 1.7f)
                }
                else -> {
                    0.035f * sin(2.5f * ox + t * 1.5f + h * 6.283f) *
                            sin(2.5f * oy - t * 1.2f)
                }
            }

            // radial morph, then Y-rotation, then X-tilt
            val rr = 1f + disp
            val x = ox * rr
            val y = oy * rr
            val z = oz * rr
            val x1 = x * cosA + z * sinA
            val z1 = -x * sinA + z * cosA
            val y2 = y * cosT - z1 * sinT
            val z2 = y * sinT + z1 * cosT

            var sx = cx + x1 * radius
            var sy = cy + y2 * radius
            val depth = ((z2 / 1.5f + 1f) * 0.5f).coerceIn(0f, 1f)
            var lum = depth * 0.85f + glow * 0.35f
            var alphaMul = 1f

            // ── SETUP: fly in from a scattered cloud ──
            if (orbMode == OrbMode.SETUP) {
                val appear = ((shownProgress * 1.25f - h * 0.45f) / 0.55f)
                    .coerceIn(0f, 1f)
                if (appear <= 0f) continue
                val e = appear * appear * (3f - 2f * appear) // smoothstep
                val spread = 2.2f + h * 1.5f
                sx = cx + (sx - cx) * (spread + (1f - spread) * e)
                sy = cy + (sy - cy) * (spread + (1f - spread) * e) +
                        (h - 0.5f) * radius * 0.4f * (1f - e)
                alphaMul = e
                lum = (lum * (0.4f + 0.6f * e)).coerceIn(0f, 1f)
            }

            val rad = dotBase * (0.35f + 0.65f * depth)
            if (rad < 0.7f && alphaMul < 0.05f) continue

            dotPaint.color = palette[(lum.coerceIn(0f, 1f) * 31f).toInt().coerceIn(0, 31)]
            dotPaint.alpha = ((30 + 225 * lum.coerceIn(0f, 1f)) * alphaMul)
                .toInt().coerceIn(0, 255)
            canvas.drawCircle(sx, sy, rad, dotPaint)
        }

        // loop forever — this call is what keeps every mode moving
        postInvalidateOnAnimation()
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun hash(i: Int, salt: Float): Float {
        val x = sin(i * 12.9898f + salt * 78.233f) * 43758.5453f
        return (x - floor(x)).toFloat()
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val ar = Color.red(a); val ag = Color.green(a); val ab = Color.blue(a)
        val br = Color.red(b); val bg = Color.green(b); val bb = Color.blue(b)
        return Color.rgb(
            (ar + (br - ar) * t).toInt(),
            (ag + (bg - ag) * t).toInt(),
            (ab + (bb - ab) * t).toInt()
        )
    }
}
