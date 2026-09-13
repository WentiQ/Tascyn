package com.example.tascyn.ui.components

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

/**
 * Custom overlay layout that expands as a circular disc centered at the AI nav button.
 *
 * The circular arc boundary expands outward from the AI button position and stops
 * when its apex touches the exact middle of the screen (height / 2).
 *
 * Features:
 * - Pure white canvas (#FFFFFF) inside the disc
 * - Soft dark dim scrim (30% void-black) outside the disc
 * - Crisp subtle border along the circular arc boundary
 * - Clips all child content strictly inside the disc boundary
 */
class VoiceDiscOverlayLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var discCenterX: Float = 0f
    var discCenterY: Float = 0f
    var discRadius: Float = 0f
        private set

    private var targetRadius: Float = 0f
    private var dimAlpha: Float = 0f

    private var animator: ValueAnimator? = null
    private val clipPath = Path()

    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val arcBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = Color.parseColor("#E5E7EB")
    }

    private val scrimColor = Color.parseColor("#0E0E10")

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /**
     * Expand the disc outward from (cx, cy) until the top of its arc
     * touches the middle of the screen (height / 2).
     */
    fun startExpand(cx: Float, cy: Float, onExpanded: (() -> Unit)? = null) {
        discCenterX = cx
        discCenterY = cy

        // Target radius: apex of the circular arc touches height / 2 (middle of the screen)
        val screenMidY = height / 2f
        targetRadius = if (cy > screenMidY) {
            cy - screenMidY
        } else {
            height * 0.48f
        }

        animator?.cancel()
        visibility = VISIBLE

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 340L
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                discRadius = targetRadius * fraction
                dimAlpha = fraction
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    discRadius = targetRadius
                    dimAlpha = 1f
                    invalidate()
                    onExpanded?.invoke()
                }
            })
            start()
        }
    }

    /**
     * Shrink the disc back down into the AI button.
     */
    fun startCollapse(onCollapsed: (() -> Unit)? = null) {
        animator?.cancel()

        val startRadius = discRadius
        val startAlpha = dimAlpha

        animator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 260L
            interpolator = AccelerateInterpolator(1.8f)
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                discRadius = startRadius * fraction
                dimAlpha = startAlpha * fraction
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    discRadius = 0f
                    dimAlpha = 0f
                    visibility = GONE
                    onCollapsed?.invoke()
                }
            })
            start()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (discRadius <= 0f && visibility != VISIBLE) {
            return
        }

        // 1. Draw subtle dark dim scrim outside the disc (top half of screen)
        if (dimAlpha > 0f) {
            val alphaInt = (dimAlpha * 75).toInt().coerceIn(0, 255)
            canvas.drawColor(Color.argb(alphaInt, Color.red(scrimColor), Color.green(scrimColor), Color.blue(scrimColor)))
        }

        // 2. Draw solid white circular disc
        if (discRadius > 0f) {
            canvas.drawCircle(discCenterX, discCenterY, discRadius, discPaint)
            canvas.drawCircle(discCenterX, discCenterY, discRadius, arcBorderPaint)

            // 3. Clip all children inside the disc boundary
            val saveCount = canvas.save()
            clipPath.reset()
            clipPath.addCircle(discCenterX, discCenterY, discRadius, Path.Direction.CW)
            canvas.clipPath(clipPath)

            super.dispatchDraw(canvas)

            canvas.restoreToCount(saveCount)
        }
    }
}
