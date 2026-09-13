package com.example.tascyn.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.example.tascyn.R
import kotlin.math.abs
import kotlin.math.sin

/**
 * ChatGPT-style voice mode waveform animation.
 *
 * Draws 5 symmetric rounded bars that pulse with sinusoidal motion.
 * The center bar is always tallest; amplitude scales proportionally
 * to microphone RMS input fed via [setAmplitude].
 *
 * Colors follow the Tascyn white-canvas design system:
 * bars are color_void (#0E0E10) on a transparent (white overlay) background.
 */
class VoiceWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val BAR_COUNT = 5
        // Phase offsets per bar: symmetric, center bar = 0 phase (tallest at peak)
        private val BAR_PHASE_OFFSETS = floatArrayOf(
            (Math.PI).toFloat(),        // bar 0 (leftmost)  — opposite phase
            (Math.PI / 2).toFloat(),    // bar 1
            0f,                          // bar 2 (center)   — full height at peak
            (Math.PI / 2).toFloat(),    // bar 3
            (Math.PI).toFloat()         // bar 4 (rightmost) — opposite phase
        )
        // Multiplier so each bar has a unique maximum scale
        private val BAR_HEIGHT_MULTIPLIERS = floatArrayOf(0.55f, 0.75f, 1.0f, 0.75f, 0.55f)

        private const val IDLE_FREQUENCY_HZ = 1.5f
        private const val ACTIVE_FREQUENCY_HZ = 2.8f

        private const val BAR_WIDTH_DP = 18f          // Very thick black bars
        private const val BAR_GAP_DP = 6f             // Tight gap between bars
        private const val BAR_CORNER_RADIUS_DP = 9f   // Perfectly rounded capsule ends (half of width)
        private const val MAX_BAR_HEIGHT_DP = 82f
        private const val MIN_BAR_HEIGHT_DP = 18f     // At rest, thick circles (18x18dp)
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.color_void)
    }

    private val barRectF = RectF()

    // dp → px conversions
    private val barWidthPx = BAR_WIDTH_DP * resources.displayMetrics.density
    private val barGapPx = BAR_GAP_DP * resources.displayMetrics.density
    private val barCornerPx = BAR_CORNER_RADIUS_DP * resources.displayMetrics.density
    private val maxBarHeightPx = MAX_BAR_HEIGHT_DP * resources.displayMetrics.density
    private val minBarHeightPx = MIN_BAR_HEIGHT_DP * resources.displayMetrics.density

    /** Phase position of the wave animation (0..2π). Driven by ValueAnimator. */
    private var animPhase = 0f

    /** Amplitude from microphone RMS: 0.0 (silent) to 1.0 (loud). */
    private var amplitude = 0f
    /** Smoothed amplitude used for rendering (low-pass filtered). */
    private var smoothedAmplitude = 0f

    /** true = active listening animation; false = idle gentle pulse. */
    private var isListening = false

    private var waveAnimator: ValueAnimator? = null

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /** Feed microphone RMS directly (0.0 – 1.0). Call from onRmsChanged(). */
    fun setAmplitude(value: Float) {
        amplitude = value.coerceIn(0f, 1f)
    }

    /** Start the active listening animation (faster, amplitude-driven). */
    fun startListeningAnimation() {
        isListening = true
        startAnimator()
    }

    /** Transition to idle pulse, then allow caller to dismiss. */
    fun stopListeningAnimation() {
        isListening = false
        amplitude = 0f
        // Let the animator keep running (idle) — the overlay dismisses it on hide
    }

    /** Fully stop and release the animator (call when overlay is gone). */
    fun releaseAnimator() {
        waveAnimator?.cancel()
        waveAnimator = null
        animPhase = 0f
        smoothedAmplitude = 0f
        amplitude = 0f
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Animation internals
    // ─────────────────────────────────────────────────────────────────────────

    private fun startAnimator() {
        waveAnimator?.cancel()
        waveAnimator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
            duration = 800L   // full cycle duration — speed controlled by frequency multiplier
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                animPhase = anim.animatedValue as Float

                // Low-pass smooth the amplitude so bars don't jitter
                val targetAmp = if (isListening) amplitude else 0f
                smoothedAmplitude += (targetAmp - smoothedAmplitude) * 0.18f

                invalidate()
            }
            start()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Drawing
    // ─────────────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val frequency = if (isListening) ACTIVE_FREQUENCY_HZ else IDLE_FREQUENCY_HZ

        // Total width occupied by bars
        val totalBarsWidth = BAR_COUNT * barWidthPx + (BAR_COUNT - 1) * barGapPx
        val startX = cx - totalBarsWidth / 2f

        // Guarantee bars never reach or clip at the top and bottom bounds of the view
        val safetyMarginPx = 10f * resources.displayMetrics.density
        val safeMaxHeightPx = (height - paddingTop - paddingBottom - safetyMarginPx * 2)
            .coerceAtLeast(minBarHeightPx)
        val effectiveMaxBarHeight = maxBarHeightPx.coerceAtMost(safeMaxHeightPx)
        val dynamicRange = (effectiveMaxBarHeight - minBarHeightPx)

        for (i in 0 until BAR_COUNT) {
            val phaseForBar = animPhase * frequency + BAR_PHASE_OFFSETS[i]
            // sin oscillation: 0..1
            val sineValue = (sin(phaseForBar.toDouble()).toFloat() + 1f) / 2f

            // Dynamic height based on phase and smoothed audio amplitude
            val amplitudeScale = 0.35f + smoothedAmplitude * 0.65f // 0.35x idle -> 1.0x at loud volume
            val barDynamicRange = dynamicRange * BAR_HEIGHT_MULTIPLIERS[i]
            val barHeight = (minBarHeightPx + barDynamicRange * sineValue * amplitudeScale)
                .coerceIn(minBarHeightPx, safeMaxHeightPx)

            val barLeft = startX + i * (barWidthPx + barGapPx)
            val barTop = cy - barHeight / 2f
            val barRight = barLeft + barWidthPx
            val barBottom = cy + barHeight / 2f

            barRectF.set(barLeft, barTop, barRight, barBottom)
            canvas.drawRoundRect(barRectF, barCornerPx, barCornerPx, barPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        releaseAnimator()
    }
}
