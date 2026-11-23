package com.hiresia.audioplayer.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Draws the "Lagu Disukai / Liked Songs" banner background with optional horizontal gradient
 * and decorative circle arc on the right side.
 */
class HomeDisukaiBannerBgView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var baseColor: Int = Color.parseColor("#DF6E46")
        set(value) {
            field = value
            invalidate()
        }

    var endColor: Int? = null
        set(value) {
            field = value
            invalidate()
        }

    var arcColor: Int = Color.parseColor("#CC5A33")
        set(value) {
            field = value
            invalidate()
        }

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val clipPath = Path()
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val corner = 24f * resources.displayMetrics.density
        rect.set(0f, 0f, w, h)
        clipPath.reset()
        clipPath.addRoundRect(rect, corner, corner, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(clipPath)

        val gradEnd = endColor
        if (gradEnd != null) {
            basePaint.shader = LinearGradient(0f, 0f, w, 0f, baseColor, gradEnd, Shader.TileMode.CLAMP)
        } else {
            basePaint.shader = null
            basePaint.color = baseColor
        }
        canvas.drawRect(rect, basePaint)

        // Decorative subtle circle on the right third
        arcPaint.color = arcColor
        canvas.drawCircle(w * 0.86f, h * 0.52f, h * 0.85f, arcPaint)

        canvas.restore()
    }
}

