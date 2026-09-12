package com.example.tascyn.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.example.tascyn.R

/**
 * 3dp Ultra-thin Precision Progress Bar engineered according to the Tascyn Design System.
 */
class PrecisionProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.color_mist)
        style = Paint.Style.FILL
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.color_void)
        style = Paint.Style.FILL
    }

    private var progress: Float = 0f // 0.0 to 1.0
    private var animatedProgress: Float = 0f
    private val barRect = RectF()
    private val fillRect = RectF()
    private var progressAnimator: ValueAnimator? = null

    fun setProgress(newProgress: Float, animated: Boolean = true) {
        val target = newProgress.coerceIn(0f, 1f)
        if (!animated) {
            progress = target
            animatedProgress = target
            invalidate()
            return
        }

        progress = target
        progressAnimator?.cancel()
        progressAnimator = ValueAnimator.ofFloat(animatedProgress, target).apply {
            duration = 450L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                animatedProgress = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun getProgress(): Float = progress

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val w = width.toFloat()
        val radius = h / 2f

        // Draw track
        barRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(barRect, radius, radius, trackPaint)

        // Draw fill
        if (animatedProgress > 0f) {
            val fillWidth = (w * animatedProgress).coerceAtLeast(h)
            fillRect.set(0f, 0f, fillWidth, h)
            canvas.drawRoundRect(fillRect, radius, radius, fillPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        progressAnimator?.cancel()
    }
}
