package com.hiresia.audioplayer.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

data class LrcLine(val timeMs: Long, val text: String)

class LrcView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val lines = mutableListOf<LrcLine>()
    private var currentLineIndex = 0

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD700") // Gold accent
        textSize = 38f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    private val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8892B0") // Secondary muted slate
        textSize = 30f
        textAlign = Paint.Align.CENTER
    }

    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4A5568")
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    fun setLyrics(lrcText: String) {
        lines.clear()
        val regex = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\](.*)")
        for (line in lrcText.lines()) {
            val match = regex.find(line.trim())
            if (match != null) {
                val min = match.groupValues[1].toLong()
                val sec = match.groupValues[2].toLong()
                val msStr = match.groupValues[3].padEnd(3, '0')
                val ms = msStr.toLong()
                val totalMs = (min * 60 + sec) * 1000 + ms
                val text = match.groupValues[4].trim()
                if (text.isNotEmpty()) {
                    lines.add(LrcLine(totalMs, text))
                }
            }
        }
        currentLineIndex = 0
        invalidate()
    }

    fun updatePlaybackPosition(positionMs: Long) {
        if (lines.isEmpty()) return
        var activeIdx = 0
        for (i in lines.indices) {
            if (positionMs >= lines[i].timeMs) {
                activeIdx = i
            } else {
                break
            }
        }
        if (activeIdx != currentLineIndex) {
            currentLineIndex = activeIdx
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val centerX = w / 2f
        val centerY = h / 2f

        if (lines.isEmpty()) {
            canvas.drawText("♪  Bit-Perfect Audiophile Playback  ♪", centerX, centerY, emptyPaint)
            return
        }

        val lineHeight = 54f

        // Draw active line at center
        val activeLine = lines[currentLineIndex]
        canvas.drawText(activeLine.text, centerX, centerY, activePaint)

        // Draw preceding lines
        var yPrev = centerY - lineHeight
        for (i in (currentLineIndex - 1) downTo 0) {
            if (yPrev < 30f) break
            canvas.drawText(lines[i].text, centerX, yPrev, normalPaint)
            yPrev -= lineHeight
        }

        // Draw succeeding lines
        var yNext = centerY + lineHeight
        for (i in (currentLineIndex + 1) until lines.size) {
            if (yNext > h - 20f) break
            canvas.drawText(lines[i].text, centerX, yNext, normalPaint)
            yNext += lineHeight
        }
    }
}
