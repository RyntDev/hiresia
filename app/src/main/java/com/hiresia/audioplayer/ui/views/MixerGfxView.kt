package com.hiresia.audioplayer.ui.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class MixerGfxView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var masterVolume: Float = 1.0f // 0.0 to 1.0
        set(value) {
            field = value.coerceIn(0.0f, 1.0f)
            invalidate()
        }

    var onVolumeChanged: ((Float) -> Unit)? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#12151B")
        style = Paint.Style.FILL
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E2430")
        style = Paint.Style.FILL
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF") // Cyan accent
        style = Paint.Style.FILL
    }

    private val faderKnobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD700") // Gold accent
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8892B0")
        textSize = 22f
        textAlign = Paint.Align.CENTER
    }

    private val dbMarks = floatArrayOf(0f, -6f, -12f, -18f, -24f, -36f, -48f, -96f)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1. Background
        canvas.drawRoundRect(0f, 0f, w, h, 16f, 16f, bgPaint)

        val trackWidth = 32f
        val faderCenterX = w / 2f
        val topY = 40f
        val bottomY = h - 60f
        val usableHeight = bottomY - topY

        // 2. Draw dB scale marks on the left
        for (db in dbMarks) {
            val ratio = if (db == -96f) 0.0f else (1.0f + db / 96f).coerceIn(0f, 1f)
            val y = bottomY - (ratio * usableHeight)
            val label = if (db == -96f) "-∞" else "${db.toInt()}dB"
            canvas.drawText(label, faderCenterX - 70f, y + 8f, textPaint)
            canvas.drawLine(faderCenterX - 35f, y, faderCenterX - 20f, y, trackPaint)
        }

        // 3. Draw Track
        val trackRect = RectF(faderCenterX - trackWidth / 2f, topY, faderCenterX + trackWidth / 2f, bottomY)
        canvas.drawRoundRect(trackRect, 16f, 16f, trackPaint)

        // 4. Draw Active Volume Level Fill
        val fillY = bottomY - (masterVolume * usableHeight)
        val activeFillRect = RectF(faderCenterX - trackWidth / 2f, fillY, faderCenterX + trackWidth / 2f, bottomY)
        canvas.drawRoundRect(activeFillRect, 16f, 16f, fillPaint)

        // 5. Draw Fader Knob
        val knobWidth = 80f
        val knobHeight = 28f
        val knobRect = RectF(
            faderCenterX - knobWidth / 2f,
            fillY - knobHeight / 2f,
            faderCenterX + knobWidth / 2f,
            fillY + knobHeight / 2f
        )
        canvas.drawRoundRect(knobRect, 8f, 8f, faderKnobPaint)

        // 6. Draw Bottom Percentage Label
        val percentText = "${(masterVolume * 100).toInt()}%"
        canvas.drawText(percentText, faderCenterX, h - 20f, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val h = height.toFloat()
        val topY = 40f
        val bottomY = h - 60f
        val usableHeight = bottomY - topY

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val touchY = event.y.coerceIn(topY, bottomY)
                val newVol = 1.0f - ((touchY - topY) / usableHeight)
                masterVolume = newVol
                onVolumeChanged?.invoke(masterVolume)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
