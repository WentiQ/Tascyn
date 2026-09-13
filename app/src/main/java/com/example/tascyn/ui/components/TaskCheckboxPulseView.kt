package com.example.tascyn.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.example.tascyn.R
import com.example.tascyn.data.Task
import com.example.tascyn.data.TaskStatus

class TaskCheckboxPulseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class State {
        NORMAL,
        IN_PROGRESS,
        PROCRASTINATED,
        COMPLETED
    }

    private var currentState: State = State.NORMAL

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }

    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }

    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private var pulseFraction: Float = 0f
    private var pulseAnimator: ValueAnimator? = null

    private val checkPath = Path()

    init {
        setupAnimator()
    }

    private fun setupAnimator() {
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                pulseFraction = it.animatedValue as Float
                if (currentState == State.IN_PROGRESS || currentState == State.PROCRASTINATED) {
                    invalidate()
                }
            }
        }
    }

    fun setState(task: Task, isCurrentWorkingSession: Boolean = false) {
        val now = System.currentTimeMillis()
        val newState = when {
            task.isCompleted -> State.COMPLETED
            isCurrentWorkingSession || task.status == TaskStatus.IN_PROGRESS -> State.IN_PROGRESS
            task.dueDate != null && now > task.dueDate!! -> State.PROCRASTINATED
            else -> State.NORMAL
        }

        if (currentState != newState) {
            currentState = newState
            if (currentState == State.IN_PROGRESS || currentState == State.PROCRASTINATED) {
                if (pulseAnimator?.isStarted != true) {
                    pulseAnimator?.start()
                }
            } else {
                pulseAnimator?.cancel()
            }
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (currentState == State.IN_PROGRESS || currentState == State.PROCRASTINATED) {
            pulseAnimator?.start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator?.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = (Math.min(width, height) / 2f) - 6f
        if (baseRadius <= 0) return

        when (currentState) {
            State.COMPLETED -> {
                // Solid green fill
                fillPaint.color = Color.parseColor("#10B981")
                canvas.drawCircle(cx, cy, baseRadius, fillPaint)

                // White checkmark
                val size = baseRadius * 0.9f
                checkPath.reset()
                checkPath.moveTo(cx - size * 0.45f, cy)
                checkPath.lineTo(cx - size * 0.1f, cy + size * 0.35f)
                checkPath.lineTo(cx + size * 0.5f, cy - size * 0.35f)
                canvas.drawPath(checkPath, checkPaint)
            }

            State.IN_PROGRESS -> {
                // Pulsing Green Outer Wave
                val pulseRadius = baseRadius + (pulseFraction * 5.5f)
                val pulseAlpha = ((1f - pulseFraction) * 200).toInt().coerceIn(0, 255)
                pulsePaint.color = Color.parseColor("#10B981")
                pulsePaint.alpha = pulseAlpha
                canvas.drawCircle(cx, cy, pulseRadius, pulsePaint)

                // Main Ring (Green)
                strokePaint.color = Color.parseColor("#10B981")
                canvas.drawCircle(cx, cy, baseRadius, strokePaint)

                // Inner Glow Core (Green)
                fillPaint.color = Color.parseColor("#10B981")
                fillPaint.alpha = 180 + (Math.sin(pulseFraction * Math.PI * 2) * 60).toInt().coerceIn(0, 75)
                canvas.drawCircle(cx, cy, baseRadius * 0.42f, fillPaint)
            }

            State.PROCRASTINATED -> {
                // Pulsing Yellow/Amber Outer Wave
                val pulseRadius = baseRadius + (pulseFraction * 5.5f)
                val pulseAlpha = ((1f - pulseFraction) * 220).toInt().coerceIn(0, 255)
                pulsePaint.color = Color.parseColor("#F59E0B")
                pulsePaint.alpha = pulseAlpha
                canvas.drawCircle(cx, cy, pulseRadius, pulsePaint)

                // Main Ring (Yellow/Amber)
                strokePaint.color = Color.parseColor("#F59E0B")
                canvas.drawCircle(cx, cy, baseRadius, strokePaint)

                // Inner Amber Core
                fillPaint.color = Color.parseColor("#F59E0B")
                fillPaint.alpha = 180 + (Math.sin(pulseFraction * Math.PI * 2) * 60).toInt().coerceIn(0, 75)
                canvas.drawCircle(cx, cy, baseRadius * 0.42f, fillPaint)
            }

            State.NORMAL -> {
                // Clean neutral outline circle
                strokePaint.color = ContextCompat.getColor(context, R.color.color_card_border)
                canvas.drawCircle(cx, cy, baseRadius, strokePaint)
            }
        }
    }
}
