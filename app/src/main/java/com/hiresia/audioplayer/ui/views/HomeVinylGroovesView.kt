package com.hiresia.audioplayer.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Draws the concentric vinyl record grooves + bright center label on the right half
 * of the "Campuran Malam Ini" hero card, adapting to Zest Terang and Aegean Terang palettes.
 */
class HomeVinylGroovesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var labelColor: Int = Color.parseColor("#C8EE6B")
        set(value) {
            field = value
            invalidate()
        }

    var grooveColor: Int = Color.parseColor("#24422D")
        set(value) {
            field = value
            invalidate()
        }

    var holeColor: Int = Color.parseColor("#182E20")
        set(value) {
            field = value
            invalidate()
        }

    private val groovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val discFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val density = resources.displayMetrics.density
        // Position the vinyl center slightly to the right of the view's horizontal center
        val cx = w * 0.76f
        val cy = h * 0.50f
        val maxRadius = h * 0.72f

        // Subtle dark disc base
        discFillPaint.color = holeColor
        discFillPaint.alpha = 90
        canvas.drawCircle(cx, cy, maxRadius, discFillPaint)

        // Concentric vinyl grooves
        groovePaint.color = grooveColor
        groovePaint.strokeWidth = 1.4f * density
        val labelRadius = 38f * density
        val numGrooves = 11
        val step = (maxRadius - labelRadius - 6f * density) / numGrooves
        for (i in 1..numGrooves) {
            val r = labelRadius + i * step
            canvas.drawCircle(cx, cy, r, groovePaint)
        }

        // Bright center label circle
        labelPaint.color = labelColor
        canvas.drawCircle(cx, cy, labelRadius, labelPaint)

        // Center spindle hole
        holePaint.color = holeColor
        canvas.drawCircle(cx, cy, 6.5f * density, holePaint)
    }
}

