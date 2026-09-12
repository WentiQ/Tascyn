package com.example.tascyn.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.example.tascyn.R

/**
 * Custom View implementing Tascyn's signature "Interlocking Geometry":
 * Ring + Rounded Rectangular Frame with brushed metallic finish.
 *
 * Supports:
 * - STATIC_METALLIC: Sleek brand motif for icons, headers, buttons
 * - AI_PROCESSING: Smooth mechanical rotation & oscillation for AI parsing
 * - TASK_COMPLETION: Precision mechanical morph into checkmark
 */
class InterlockingGeometryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode {
        STATIC_METALLIC,
        AI_PROCESSING,
        TASK_COMPLETION
    }

    private var currentMode: Mode = Mode.STATIC_METALLIC

    var isDarkBackground: Boolean = false
        set(value) {
            field = value
            updateGradients(width, height)
            invalidate()
        }

    // Paint objects
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val rectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.color_success)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.color_white)
    }

    // Geometry parameters
    private val rectBounds = RectF()
    private val pathRect = Path()
    private val pathCheck = Path()

    // Animation progress states
    private var rotationAngle = 0f
    private var oscillationPhase = 0f
    private var completionProgress = 0f

    private var aiAnimator: ValueAnimator? = null
    private var completionAnimator: ValueAnimator? = null

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setMode(mode: Mode) {
        if (currentMode == mode) return
        currentMode = mode

        when (mode) {
            Mode.STATIC_METALLIC -> {
                aiAnimator?.cancel()
                completionAnimator?.cancel()
                rotationAngle = 0f
                oscillationPhase = 0f
                completionProgress = 0f
                invalidate()
            }
            Mode.AI_PROCESSING -> {
                completionAnimator?.cancel()
                startAiAnimation()
            }
            Mode.TASK_COMPLETION -> {
                aiAnimator?.cancel()
                startCompletionAnimation()
            }
        }
    }

    private fun startAiAnimation() {
        aiAnimator?.cancel()
        aiAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 2400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                rotationAngle = anim.animatedValue as Float
                oscillationPhase = Math.sin(Math.toRadians(rotationAngle.toDouble())).toFloat()
                invalidate()
            }
            start()
        }
    }

    fun startCompletionAnimation(onFinished: (() -> Unit)? = null) {
        currentMode = Mode.TASK_COMPLETION
        completionAnimator?.cancel()
        completionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 350L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                completionProgress = anim.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onFinished?.invoke()
                }
            })
            start()
        }
    }

    fun resetCompletion() {
        completionAnimator?.cancel()
        completionProgress = 0f
        currentMode = Mode.STATIC_METALLIC
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGradients(w, h)
    }

    private fun updateGradients(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return

        val stroke = w * 0.08f
        ringPaint.strokeWidth = stroke
        rectPaint.strokeWidth = stroke
        checkPaint.strokeWidth = stroke * 1.1f

        // Brushed metallic gradient for ring (Graphite -> Silver -> White highlight -> Graphite)
        val metallicColors = if (isDarkBackground) {
            intArrayOf(
                Color.parseColor("#FFFFFF"),
                Color.parseColor("#E4E4E7"),
                Color.parseColor("#71717A"),
                Color.parseColor("#FFFFFF"),
                Color.parseColor("#E4E4E7"),
                Color.parseColor("#FFFFFF")
            )
        } else {
            intArrayOf(
                ContextCompat.getColor(context, R.color.color_void),
                ContextCompat.getColor(context, R.color.color_graphite),
                ContextCompat.getColor(context, R.color.color_silver),
                ContextCompat.getColor(context, R.color.color_white),
                ContextCompat.getColor(context, R.color.color_graphite),
                ContextCompat.getColor(context, R.color.color_void)
            )
        }
        val positions = floatArrayOf(0f, 0.25f, 0.5f, 0.7f, 0.85f, 1f)

        val ringShader = SweepGradient(w / 2f, h / 2f, metallicColors, positions)
        ringPaint.shader = ringShader

        val rectShader = if (isDarkBackground) {
            LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                Color.parseColor("#FFFFFF"),
                Color.parseColor("#D4D4D8"),
                Shader.TileMode.CLAMP
            )
        } else {
            LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                ContextCompat.getColor(context, R.color.color_carbon),
                ContextCompat.getColor(context, R.color.color_silver),
                Shader.TileMode.CLAMP
            )
        }
        rectPaint.shader = rectShader
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val size = Math.min(width, height).toFloat()
        val padding = size * 0.12f
        val radius = (size / 2f) - padding

        canvas.save()

        when (currentMode) {
            Mode.STATIC_METALLIC -> {
                drawInterlockingLogo(canvas, cx, cy, radius, 0f, 0f)
            }
            Mode.AI_PROCESSING -> {
                canvas.rotate(rotationAngle, cx, cy)
                drawInterlockingLogo(canvas, cx, cy, radius, oscillationPhase * 4f, oscillationPhase * 0.05f)
            }
            Mode.TASK_COMPLETION -> {
                drawCompletionMorph(canvas, cx, cy, radius, completionProgress)
            }
        }

        canvas.restore()
    }

    private fun drawInterlockingLogo(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        offset: Float,
        scaleOffset: Float
    ) {
        // 1. Draw Ring
        canvas.drawCircle(cx, cy, radius * (0.85f + scaleOffset), ringPaint)

        // 2. Draw Interlocking Rounded Rectangular Frame
        val rw = radius * 1.35f
        val rh = radius * 0.85f
        val cr = radius * 0.3f

        rectBounds.set(
            cx - rw / 2f + offset,
            cy - rh / 2f - offset,
            cx + rw / 2f + offset,
            cy + rh / 2f - offset
        )
        pathRect.reset()
        pathRect.addRoundRect(rectBounds, cr, cr, Path.Direction.CW)

        canvas.drawPath(pathRect, rectPaint)
    }

    private fun drawCompletionMorph(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        progress: Float
    ) {
        if (progress < 0.4f) {
            // Expanding ring & contracting rect
            val scale = 1f + progress * 0.3f
            canvas.drawCircle(cx, cy, radius * scale * 0.85f, ringPaint)

            val rw = radius * 1.35f * (1f - progress * 1.5f)
            val rh = radius * 0.85f * (1f - progress * 1.5f)
            if (rw > 0 && rh > 0) {
                rectBounds.set(cx - rw / 2f, cy - rh / 2f, cx + rw / 2f, cy + rh / 2f)
                pathRect.reset()
                pathRect.addRoundRect(rectBounds, radius * 0.2f, radius * 0.2f, Path.Direction.CW)
                canvas.drawPath(pathRect, rectPaint)
            }
        } else {
            // Filled circle expanding with green checkmark
            val circleProgress = (progress - 0.4f) / 0.6f
            fillPaint.color = ContextCompat.getColor(context, R.color.color_success)
            canvas.drawCircle(cx, cy, radius * 0.85f, fillPaint)

            // Draw Checkmark
            val checkSize = radius * 0.7f
            val startX = cx - checkSize * 0.4f
            val startY = cy
            val midX = cx - checkSize * 0.05f
            val midY = cy + checkSize * 0.35f
            val endX = cx + checkSize * 0.45f
            val endY = cy - checkSize * 0.25f

            checkPaint.color = Color.WHITE
            pathCheck.reset()

            if (circleProgress < 0.5f) {
                val p1 = circleProgress / 0.5f
                pathCheck.moveTo(startX, startY)
                pathCheck.lineTo(startX + (midX - startX) * p1, startY + (midY - startY) * p1)
            } else {
                val p2 = (circleProgress - 0.5f) / 0.5f
                pathCheck.moveTo(startX, startY)
                pathCheck.lineTo(midX, midY)
                pathCheck.lineTo(midX + (endX - midX) * p2, midY + (endY - midY) * p2)
            }
            canvas.drawPath(pathCheck, checkPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        aiAnimator?.cancel()
        completionAnimator?.cancel()
    }
}
