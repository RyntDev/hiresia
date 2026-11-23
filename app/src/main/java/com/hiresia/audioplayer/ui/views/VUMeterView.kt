package com.hiresia.audioplayer.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class VUMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var leftLevel: Float = 0.0f
    private var rightLevel: Float = 0.0f
    private var leftPeakHold: Float = 0.0f
    private var rightPeakHold: Float = 0.0f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#15181E")
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8892B0")
        textSize = 24f
        isFakeBoldText = true
    }

    private val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676") // Audiophile Green
        style = Paint.Style.FILL
    }

    private val warnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD600") // Warm Amber
        style = Paint.Style.FILL
    }

    private val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1744") // Red Clip
        style = Paint.Style.FILL
    }

    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val barRect = RectF()

    fun setLevels(left: Float, right: Float) {
        leftLevel = left.coerceIn(0.0f, 1.2f)
        rightLevel = right.coerceIn(0.0f, 1.2f)

        leftPeakHold = max(leftLevel, leftPeakHold * 0.94f)
        rightPeakHold = max(rightLevel, rightPeakHold * 0.94f)

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val corner = 8f
        barRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(barRect, corner, corner, bgPaint)

        val channelHeight = (h - 24f) / 2f
        val maxBarWidth = w - 60f

        drawChannel(canvas, "L", 8f, channelHeight, leftLevel, leftPeakHold, maxBarWidth)
        drawChannel(canvas, "R", 16f + channelHeight, channelHeight, rightLevel, rightPeakHold, maxBarWidth)
    }

    private fun drawChannel(
        canvas: Canvas,
        label: String,
        top: Float,
        chHeight: Float,
        level: Float,
        peak: Float,
        maxWidth: Float
    ) {
        val bottom = top + chHeight
        canvas.drawText(label, 12f, top + chHeight * 0.72f, textPaint)

        val barStart = 45f
        val segments = 28
        val segSpacing = 3f
        val segWidth = (maxWidth - (segments * segSpacing)) / segments
        val filledSegments = (level * segments).toInt().coerceIn(0, segments)
        val peakSegment = (peak * segments).toInt().coerceIn(0, segments - 1)

        for (i in 0 until segments) {
            val segLeft = barStart + i * (segWidth + segSpacing)
            val segRight = segLeft + segWidth
            barRect.set(segLeft, top + 2f, segRight, bottom - 2f)

            if (i < filledSegments) {
                val paint = when {
                    i >= segments - 3 -> clipPaint
                    i >= segments - 8 -> warnPaint
                    else -> normalPaint
                }
                canvas.drawRoundRect(barRect, 2f, 2f, paint)
            } else {
                val dimPaint = Paint().apply {
                    color = Color.parseColor("#222834")
                }
                canvas.drawRoundRect(barRect, 2f, 2f, dimPaint)
            }
        }

        if (peakSegment in 0 until segments) {
            val peakLeft = barStart + peakSegment * (segWidth + segSpacing)
            barRect.set(peakLeft, top + 2f, peakLeft + segWidth, bottom - 2f)
            canvas.drawRoundRect(barRect, 2f, 2f, peakPaint)
        }
    }
}
