package com.hiresia.audioplayer.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.hiresia.audioplayer.repository.AudioSettingsRepository
import java.util.Locale
import kotlin.math.abs

/**
 * Vertical tactile fader view for audiophile parametric & graphic EQ faders.
 * Features:
 * - ±15.0 dB range with subtle center 0 dB snap
 * - Frequency + filter type / Q footer tap opens Parametric Band Editor
 * - Long press opens Parametric Band Editor; double-tap resets band gain to 0 dB
 */
class VerticalFaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var gainDb: Float = 0.0f
        set(value) {
            val clamped = value.coerceIn(-15.0f, 15.0f)
            if (abs(field - clamped) > 0.01f) {
                field = clamped
                invalidate()
            }
        }

    var frequencyText: String = "1 kHz"
        set(value) {
            field = value
            invalidate()
        }

    var filterMetaText: String = "PK 1.4"
        set(value) {
            field = value
            invalidate()
        }

    var isBandEnabled: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    /** Non-null for gain-less filter types (e.g. "LPF" / "HPF"): locks gain drag and opens PEQ editor on tap. */
    var gainDisabledLabel: String? = null
        set(value) {
            field = value
            invalidate()
        }

    var onGainChanged: ((Float) -> Unit)? = null
    var onEditBandRequested: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private var touchingFooter = false

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val centerTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.5f * density
    }

    private val activeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val topDbTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10.5f * density
    }

    private val bottomFreqTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 11f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val bottomMetaTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 9.5f * density
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!touchingFooter) {
                gainDb = 0.0f
                onGainChanged?.invoke(0.0f)
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                return true
            }
            return false
        }

        override fun onLongPress(e: MotionEvent) {
            touchingFooter = false
            parent?.requestDisallowInterceptTouchEvent(false)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onEditBandRequested?.invoke()
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        gestureDetector.onTouchEvent(event)

        val topTrack = 28f * density
        val bottomTrack = height - 42f * density
        val trackRange = bottomTrack - topTrack

        if (trackRange <= 0) return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.y > bottomTrack + 6f * density || gainDisabledLabel != null) {
                    touchingFooter = true
                    return true
                }
                touchingFooter = false
                parent?.requestDisallowInterceptTouchEvent(true)
                updateGainFromY(event.y, topTrack, bottomTrack)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touchingFooter && gainDisabledLabel == null) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    updateGainFromY(event.y, topTrack, bottomTrack)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (touchingFooter) {
                    touchingFooter = false
                    if (gainDisabledLabel != null || event.y > bottomTrack + 2f * density) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onEditBandRequested?.invoke()
                    }
                    return true
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                touchingFooter = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateGainFromY(y: Float, topTrack: Float, bottomTrack: Float) {
        val clampedY = y.coerceIn(topTrack, bottomTrack)
        val fraction = 1.0f - ((clampedY - topTrack) / (bottomTrack - topTrack))
        var newDb = (fraction * 30.0f) - 15.0f

        // Snap gently to 0.0 dB if close to center
        if (abs(newDb) < 0.25f) {
            newDb = 0.0f
        }

        gainDb = newDb
        onGainChanged?.invoke(newDb)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val scheme = AudioSettingsRepository.getCurrentScheme()
        val cx = width / 2f
        val topTrack = 28f * density
        val bottomTrack = height - 42f * density
        val midY = (topTrack + bottomTrack) / 2f

        val effectiveGain = if (gainDisabledLabel != null) 0.0f else gainDb
        val fraction = ((effectiveGain + 15.0f) / 30.0f).coerceIn(0.0f, 1.0f)
        val thumbY = bottomTrack - (fraction * (bottomTrack - topTrack))

        // 1. Top dB label
        val dbStr = when {
            !isBandEnabled -> "OFF"
            gainDisabledLabel != null -> gainDisabledLabel!!
            abs(gainDb) < 0.05f -> "0 dB"
            gainDb > 0 -> String.format(Locale.US, "+%.1f", gainDb)
            else -> String.format(Locale.US, "%.1f", gainDb)
        }

        topDbTextPaint.color = if (isBandEnabled && abs(gainDb) >= 0.1f) scheme.accent else scheme.muted
        canvas.drawText(dbStr, cx, 18f * density, topDbTextPaint)

        // 2. Base vertical track
        trackPaint.color = scheme.line
        canvas.drawLine(cx, topTrack, cx, bottomTrack, trackPaint)

        // 3. Center zero tick line
        centerTickPaint.color = scheme.line2
        val tickHalf = 9f * density
        canvas.drawLine(cx - tickHalf, midY, cx + tickHalf, midY, centerTickPaint)

        // 4. Bi-directional active fill bar
        val activeColor = if (isBandEnabled) scheme.accent else scheme.muted
        if (abs(thumbY - midY) > 1f) {
            activeFillPaint.color = activeColor
            canvas.drawLine(cx, midY, cx, thumbY, activeFillPaint)
        }

        // 5. Thumb knob
        thumbPaint.color = activeColor
        val thumbRadius = 8.5f * density
        canvas.drawCircle(cx, thumbY, thumbRadius, thumbPaint)

        // 6. Bottom frequency + parametric filter/Q labels (tappable for PEQ editor)
        bottomFreqTextPaint.color = if (isBandEnabled) scheme.text else scheme.muted
        canvas.drawText(frequencyText, cx, height - 22f * density, bottomFreqTextPaint)

        bottomMetaTextPaint.color = if (isBandEnabled) scheme.accent else scheme.muted
        canvas.drawText(filterMetaText, cx, height - 8f * density, bottomMetaTextPaint)
    }
}
