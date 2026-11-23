package com.hiresia.audioplayer.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.hiresia.audioplayer.repository.AudioSettingsRepository
import kotlin.math.abs

/**
 * Tactile vertical scroll slider docked beside a ScrollView.
 * Lets users drag smoothly up/down without accidentally touching EQ faders or graph nodes.
 */
class VerticalScrollBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var progress: Float = 0.0f
        set(value) {
            val clamped = value.coerceIn(0.0f, 1.0f)
            if (abs(field - clamped) > 0.001f) {
                field = clamped
                invalidate()
            }
        }

    var viewportFraction: Float = 0.45f
        set(value) {
            val clamped = value.coerceIn(0.15f, 1.0f)
            if (abs(field - clamped) > 0.005f) {
                field = clamped
                invalidate()
            }
        }

    var isScrollable: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var onUserScrollChanged: ((Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private var isDragging = false

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.4f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val trackRect = RectF()
    private val thumbRect = RectF()

    private fun computeGeometry(): Triple<Float, Float, Float> {
        val padY = 12f * density
        val top = padY
        val bottom = (height - padY).coerceAtLeast(top + 1f)
        val trackHeight = (bottom - top).coerceAtLeast(1f)
        val minThumb = 46f * density
        val maxThumb = (trackHeight * 0.55f).coerceAtLeast(minThumb)
        val thumbH = (trackHeight * viewportFraction).coerceIn(minThumb, maxThumb)
        return Triple(top, bottom, thumbH)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || !isScrollable) return false
        val (top, bottom, thumbH) = computeGeometry()
        val travel = (bottom - top - thumbH).coerceAtLeast(1f)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                updateFromY(event.y, top, thumbH, travel)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    updateFromY(event.y, top, thumbH, travel)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFromY(y: Float, top: Float, thumbH: Float, travel: Float) {
        val thumbCenter = (y - top - thumbH * 0.5f).coerceIn(0f, travel)
        val frac = (thumbCenter / travel).coerceIn(0.0f, 1.0f)
        progress = frac
        onUserScrollChanged?.invoke(frac)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scheme = AudioSettingsRepository.getCurrentScheme()
        val cx = width * 0.5f
        val (top, bottom, thumbH) = computeGeometry()

        // Track pill
        val trackHalfW = 2.5f * density
        trackRect.set(cx - trackHalfW, top, cx + trackHalfW, bottom)
        trackPaint.color = scheme.line
        canvas.drawRoundRect(trackRect, trackHalfW, trackHalfW, trackPaint)

        // Draggable thumb pill
        val travel = (bottom - top - thumbH).coerceAtLeast(0f)
        val thumbTop = top + travel * progress
        val thumbHalfW = (if (isDragging) 6.0f else 4.5f) * density
        thumbRect.set(cx - thumbHalfW, thumbTop, cx + thumbHalfW, thumbTop + thumbH)
        thumbPaint.color = if (isScrollable) scheme.accent else scheme.line2
        canvas.drawRoundRect(thumbRect, thumbHalfW, thumbHalfW, thumbPaint)

        // Subtle tactile grip bars inside thumb
        if (isScrollable) {
            gripPaint.color = scheme.onAccent
            val midY = thumbTop + thumbH * 0.5f
            val gripHalfW = thumbHalfW * 0.45f
            val spacing = 4f * density
            canvas.drawLine(cx - gripHalfW, midY - spacing, cx + gripHalfW, midY - spacing, gripPaint)
            canvas.drawLine(cx - gripHalfW, midY, cx + gripHalfW, midY, gripPaint)
            canvas.drawLine(cx - gripHalfW, midY + spacing, cx + gripHalfW, midY + spacing, gripPaint)
        }
    }
}

