package com.hiresia.audioplayer.ui.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.hiresia.audioplayer.model.EqBandParam
import com.hiresia.audioplayer.model.EqDefaults
import com.hiresia.audioplayer.model.EqFilterType
import com.hiresia.audioplayer.repository.AudioSettingsRepository
import java.util.Locale
import kotlin.math.*

class EQGfxView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    val numBands = EqDefaults.NUM_BANDS
    private val bands = Array(numBands) { EqDefaults.defaultBands()[it] }
    private var preampDb: Float = 0.0f
    private var selectedBand = -1
    private var activeTouchBand = -1

    var onBandGainChanged: ((bandIndex: Int, gainDb: Float) -> Unit)? = null
    var onBandEditRequested: ((bandIndex: Int) -> Unit)? = null

    private val density = resources.displayMetrics.density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f * density
        style = Paint.Style.STROKE
    }

    private val zeroLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.4f * density
        pathEffect = DashPathEffect(floatArrayOf(8f * density, 6f * density), 0f)
        style = Paint.Style.STROKE
    }

    private val preampLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f * density
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
        style = Paint.Style.STROKE
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9.5f * density
        textAlign = Paint.Align.CENTER
    }

    private val axisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9f * density
        textAlign = Paint.Align.RIGHT
    }

    private val headerBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9.5f * density
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.6f * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val nodeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2f * density
        style = Paint.Style.STROKE
    }

    private val nodeIndexPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 8f * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val curvePath = Path()
    private val fillPath = Path()
    private val nodePoints = Array(numBands) { PointF() }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!isEnabled) return false
            val band = findClosestBand(e.x, e.y)
            if (band != -1) {
                val cur = bands[band]
                bands[band] = cur.copy(gainDb = 0.0f)
                invalidate()
                onBandGainChanged?.invoke(band, 0.0f)
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                return true
            }
            return false
        }

        override fun onLongPress(e: MotionEvent) {
            if (!isEnabled) return
            val band = findClosestBand(e.x, e.y)
            if (band != -1) {
                selectedBand = band
                activeTouchBand = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onBandEditRequested?.invoke(band)
            }
        }
    })

    fun setBandGain(band: Int, gainDb: Float) {
        if (band in 0 until numBands) {
            bands[band] = bands[band].copy(gainDb = gainDb.coerceIn(-15.0f, 15.0f))
            invalidate()
        }
    }

    fun setAllGains(gains: FloatArray) {
        for (i in 0 until min(numBands, gains.size)) {
            bands[i] = bands[i].copy(gainDb = gains[i].coerceIn(-15.0f, 15.0f))
        }
        invalidate()
    }

    fun setBands(newBands: List<EqBandParam>, preamp: Float = preampDb) {
        for (i in 0 until min(numBands, newBands.size)) {
            val b = newBands[i]
            bands[i] = b.copy(
                freqHz = b.freqHz.coerceIn(10f, 24000f),
                gainDb = b.gainDb.coerceIn(-15f, 15f),
                q = b.q.coerceIn(0.1f, 18f)
            )
        }
        preampDb = preamp.coerceIn(-15f, 12f)
        invalidate()
    }

    fun setBandParam(index: Int, param: EqBandParam) {
        if (index in 0 until numBands) {
            bands[index] = param.copy(
                freqHz = param.freqHz.coerceIn(10f, 24000f),
                gainDb = param.gainDb.coerceIn(-15f, 15f),
                q = param.q.coerceIn(0.1f, 18f)
            )
            invalidate()
        }
    }

    fun setPreamp(preamp: Float) {
        preampDb = preamp.coerceIn(-15f, 12f)
        invalidate()
    }

    fun setSelectedBand(index: Int) {
        selectedBand = if (index in 0 until numBands) index else -1
        invalidate()
    }

    fun getBandGain(band: Int): Float = if (band in 0 until numBands) bands[band].gainDb else 0.0f

    private fun padLeft() = 28f * density
    private fun padRight() = 14f * density
    private fun padTop() = 20f * density
    private fun padBottom() = 24f * density

    private fun freqToX(freqHz: Float, left: Float, plotWidth: Float): Float {
        val logMin = log10(20.0)
        val logMax = log10(20000.0)
        val clamped = freqHz.toDouble().coerceIn(20.0, 20000.0)
        val frac = ((log10(clamped) - logMin) / (logMax - logMin)).toFloat()
        return left + frac * plotWidth
    }

    private fun dbToY(db: Float, zeroY: Float, halfHeight: Float): Float {
        val clamped = db.coerceIn(-15.0f, 15.0f)
        return zeroY - (clamped / 15.0f) * halfHeight
    }

    private fun findClosestBand(touchX: Float, touchY: Float): Int {
        var closest = -1
        var minDist = 42f * density
        for (i in 0 until numBands) {
            val dx = touchX - nodePoints[i].x
            val dy = touchY - nodePoints[i].y
            val dist = hypot(dx, dy)
            if (dist < minDist) {
                minDist = dist
                closest = i
            }
        }
        return closest
    }

    private data class BiquadCoeff(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double
    )

    private fun computeBiquad(param: EqBandParam, sr: Double = 48000.0): BiquadCoeff? {
        if (!param.enabled) return null
        val q = param.q.toDouble().coerceIn(0.1, 18.0)

        return when (param.type) {
            EqFilterType.PEAKING -> {
                if (abs(param.gainDb) < 0.02f) return null
                val fc = param.freqHz.toDouble().coerceIn(10.0, sr * 0.495)
                val w0 = 2.0 * PI * fc / sr
                val tanBw = tan((w0 / q) * 0.5)
                val cosW0 = cos(w0)
                val v0 = 10.0.pow(abs(param.gainDb.toDouble()) / 20.0)
                if (param.gainDb > 0f) {
                    val a0 = 1.0 + tanBw
                    BiquadCoeff(
                        (1.0 + v0 * tanBw) / a0,
                        (-2.0 * cosW0) / a0,
                        (1.0 - v0 * tanBw) / a0,
                        (-2.0 * cosW0) / a0,
                        (1.0 - tanBw) / a0
                    )
                } else {
                    val a0 = 1.0 + v0 * tanBw
                    BiquadCoeff(
                        (1.0 + tanBw) / a0,
                        (-2.0 * cosW0) / a0,
                        (1.0 - tanBw) / a0,
                        (-2.0 * cosW0) / a0,
                        (1.0 - v0 * tanBw) / a0
                    )
                }
            }
            EqFilterType.LOW_SHELF -> {
                if (abs(param.gainDb) < 0.02f) return null
                val fc = param.freqHz.toDouble().coerceIn(10.0, sr * 0.49)
                val A = 10.0.pow(param.gainDb.toDouble() / 40.0)
                val w0 = 2.0 * PI * fc / sr
                val sinW0 = sin(w0)
                val cosW0 = cos(w0)
                val beta = 2.0 * sqrt(A) * (sinW0 / (2.0 * q))
                val a0 = (A + 1.0) + (A - 1.0) * cosW0 + beta
                BiquadCoeff(
                    (A * ((A + 1.0) - (A - 1.0) * cosW0 + beta)) / a0,
                    (2.0 * A * ((A - 1.0) - (A + 1.0) * cosW0)) / a0,
                    (A * ((A + 1.0) - (A - 1.0) * cosW0 - beta)) / a0,
                    (-2.0 * ((A - 1.0) + (A + 1.0) * cosW0)) / a0,
                    ((A + 1.0) + (A - 1.0) * cosW0 - beta) / a0
                )
            }
            EqFilterType.HIGH_SHELF -> {
                if (abs(param.gainDb) < 0.02f) return null
                val fc = param.freqHz.toDouble().coerceIn(10.0, sr * 0.49)
                val A = 10.0.pow(param.gainDb.toDouble() / 40.0)
                val w0 = 2.0 * PI * fc / sr
                val sinW0 = sin(w0)
                val cosW0 = cos(w0)
                val beta = 2.0 * sqrt(A) * (sinW0 / (2.0 * q))
                val a0 = (A + 1.0) - (A - 1.0) * cosW0 + beta
                BiquadCoeff(
                    (A * ((A + 1.0) + (A - 1.0) * cosW0 + beta)) / a0,
                    (-2.0 * A * ((A - 1.0) + (A + 1.0) * cosW0)) / a0,
                    (A * ((A + 1.0) + (A - 1.0) * cosW0 - beta)) / a0,
                    (2.0 * ((A - 1.0) - (A + 1.0) * cosW0)) / a0,
                    ((A + 1.0) - (A - 1.0) * cosW0 - beta) / a0
                )
            }
            EqFilterType.LOW_PASS -> {
                val fc = param.freqHz.toDouble().coerceIn(10.0, sr * 0.49)
                val w0 = 2.0 * PI * fc / sr
                val cosW0 = cos(w0)
                val alpha = sin(w0) / (2.0 * q)
                val a0 = 1.0 + alpha
                val nb0 = ((1.0 - cosW0) * 0.5) / a0
                BiquadCoeff(nb0, (1.0 - cosW0) / a0, nb0, (-2.0 * cosW0) / a0, (1.0 - alpha) / a0)
            }
            EqFilterType.HIGH_PASS -> {
                val fc = param.freqHz.toDouble().coerceIn(10.0, sr * 0.49)
                val w0 = 2.0 * PI * fc / sr
                val cosW0 = cos(w0)
                val alpha = sin(w0) / (2.0 * q)
                val a0 = 1.0 + alpha
                val nb0 = ((1.0 + cosW0) * 0.5) / a0
                BiquadCoeff(nb0, (-(1.0 + cosW0)) / a0, nb0, (-2.0 * cosW0) / a0, (1.0 - alpha) / a0)
            }
        }
    }

    private fun evalMagDb(c: BiquadCoeff, freqHz: Double, sr: Double = 48000.0): Double {
        val w = 2.0 * PI * freqHz / sr
        val cw = cos(w)
        val sw = sin(w)
        val c2w = cos(2.0 * w)
        val s2w = sin(2.0 * w)
        val numR = c.b0 + c.b1 * cw + c.b2 * c2w
        val numI = -(c.b1 * sw + c.b2 * s2w)
        val denR = 1.0 + c.a1 * cw + c.a2 * c2w
        val denI = -(c.a1 * sw + c.a2 * s2w)
        val magSq = ((numR * numR + numI * numI) / max(1e-30, denR * denR + denI * denI)).coerceAtLeast(1e-30)
        return 10.0 * log10(magSq)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val scheme = AudioSettingsRepository.getCurrentScheme()
        bgPaint.color = scheme.surface
        gridPaint.color = scheme.line
        zeroLinePaint.color = scheme.line2
        preampLinePaint.color = (scheme.accent and 0x00FFFFFF) or 0x77000000
        labelPaint.color = scheme.muted
        axisLabelPaint.color = scheme.muted
        headerBadgePaint.color = scheme.accent
        curvePaint.color = scheme.accent
        nodePaint.color = scheme.accent
        nodeRingPaint.color = scheme.onAccent

        val left = padLeft()
        val right = w - padRight()
        val top = padTop()
        val bottom = h - padBottom()
        val plotW = (right - left).coerceAtLeast(1f)
        val plotH = (bottom - top).coerceAtLeast(1f)
        val zeroY = top + plotH * 0.5f
        val halfH = plotH * 0.5f

        val accentAlpha = (scheme.accent and 0x00FFFFFF) or 0x3D000000
        val surfaceTrans = (scheme.surface and 0x00FFFFFF)
        fillPaint.shader = LinearGradient(0f, top, 0f, bottom, accentAlpha, surfaceTrans, Shader.TileMode.CLAMP)

        // 1. Background card
        canvas.drawRoundRect(0f, 0f, w, h, 16f * density, 16f * density, bgPaint)

        // 2. Top summary badge
        val preampStr = if (abs(preampDb) < 0.05f) "0.0" else String.format(Locale.US, "%+.1f", preampDb)
        canvas.drawText("20 Hz – 20 kHz  ·  Preamp $preampStr dB", right, 13.5f * density, headerBadgePaint)

        // 3. Horizontal dB Grid (+15, +10, +5, 0, -5, -10, -15)
        val dbLevels = floatArrayOf(15f, 10f, 5f, 0f, -5f, -10f, -15f)
        for (db in dbLevels) {
            val y = dbToY(db, zeroY, halfH)
            canvas.drawLine(left, y, right, y, if (db == 0f) zeroLinePaint else gridPaint)
            if (db == 15f || db == 0f || db == -15f) {
                val txt = when {
                    db > 0f -> "+${db.toInt()}"
                    db == 0f -> "0"
                    else -> "${db.toInt()}"
                }
                canvas.drawText(txt, left - 4f * density, y + 3f * density, axisLabelPaint)
            }
        }

        // Preamp offset reference line if non-zero
        if (abs(preampDb) >= 0.1f) {
            val py = dbToY(preampDb, zeroY, halfH)
            canvas.drawLine(left, py, right, py, preampLinePaint)
        }

        // 4. Logarithmic Frequency Grid (20 Hz - 20 kHz)
        val gridFreqs = listOf(
            20f to "20", 50f to "50", 100f to "100", 200f to "200", 500f to "500",
            1000f to "1k", 2000f to "2k", 5000f to "5k", 10000f to "10k", 20000f to "20k"
        )
        for ((freq, label) in gridFreqs) {
            val gx = freqToX(freq, left, plotW)
            canvas.drawLine(gx, top, gx, bottom, gridPaint)
            canvas.drawText(label, gx, h - 7f * density, labelPaint)
        }

        // 5. Real Combined Mathematical Biquad Frequency Response Curve
        val activeCoeffs = ArrayList<BiquadCoeff>(numBands)
        for (i in 0 until numBands) {
            computeBiquad(bands[i])?.let { activeCoeffs.add(it) }
        }

        curvePath.reset()
        fillPath.reset()

        val steps = 160
        val logMin = log10(20.0)
        val logMax = log10(20000.0)
        val baselineY = dbToY(preampDb, zeroY, halfH)

        canvas.save()
        canvas.clipRect(left, top - 2f * density, right, bottom + 2f * density)

        for (s in 0..steps) {
            val frac = s.toFloat() / steps.toFloat()
            val freq = 10.0.pow(logMin + frac * (logMax - logMin))
            var totalDb = preampDb.toDouble()
            for (c in activeCoeffs) {
                totalDb += evalMagDb(c, freq)
            }
            val x = left + frac * plotW
            val y = (zeroY - (totalDb.toFloat() / 15.0f) * halfH).coerceIn(top, bottom)
            if (s == 0) {
                curvePath.moveTo(x, y)
                fillPath.moveTo(x, baselineY.coerceIn(top, bottom))
                fillPath.lineTo(x, y)
            } else {
                curvePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(right, baselineY.coerceIn(top, bottom))
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(curvePath, curvePaint)
        canvas.restore()

        // 6. Interactive Band Nodes placed at true (Fc, Gain) log coordinates
        for (i in 0 until numBands) {
            val b = bands[i]
            val nx = freqToX(b.freqHz, left, plotW)
            val displayedGain = if (b.type == EqFilterType.LOW_PASS || b.type == EqFilterType.HIGH_PASS) 0f else b.gainDb
            val ny = dbToY(displayedGain, zeroY, halfH)
            nodePoints[i].set(nx, ny)

            val isActive = (i == activeTouchBand || i == selectedBand)
            val radius = if (isActive) 8.5f * density else 6.5f * density

            if (b.enabled) {
                nodePaint.color = scheme.accent
                nodeRingPaint.color = if (isActive) scheme.text else scheme.onAccent
                nodeIndexPaint.color = scheme.onAccent
            } else {
                nodePaint.color = scheme.line2
                nodeRingPaint.color = scheme.muted
                nodeIndexPaint.color = scheme.text
            }

            canvas.drawCircle(nx, ny, radius + 1.5f * density, nodeRingPaint)
            canvas.drawCircle(nx, ny, radius, nodePaint)
            val fm = nodeIndexPaint.fontMetrics
            val textOffset = -(fm.ascent + fm.descent) * 0.5f
            canvas.drawText("${i + 1}", nx, ny + textOffset, nodeIndexPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        gestureDetector.onTouchEvent(event)

        val h = height.toFloat()
        val top = padTop()
        val bottom = h - padBottom()
        val usableHeight = (bottom - top).coerceAtLeast(1f)
        val zeroY = top + usableHeight * 0.5f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val closestBand = findClosestBand(event.x, event.y)
                if (closestBand != -1) {
                    activeTouchBand = closestBand
                    selectedBand = closestBand
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (activeTouchBand != -1) {
                    val b = bands[activeTouchBand]
                    if (b.type != EqFilterType.LOW_PASS && b.type != EqFilterType.HIGH_PASS) {
                        updateBandFromY(activeTouchBand, event.y, zeroY, usableHeight)
                    }
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeTouchBand != -1) {
                    activeTouchBand = -1
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateBandFromY(band: Int, touchY: Float, zeroY: Float, usableHeight: Float) {
        val deltaY = zeroY - touchY
        val maxDelta = usableHeight * 0.5f
        var newGain = ((deltaY / maxDelta) * 15.0f).coerceIn(-15.0f, 15.0f)
        if (abs(newGain) < 0.25f) newGain = 0.0f
        bands[band] = bands[band].copy(gainDb = newGain)
        invalidate()
        onBandGainChanged?.invoke(band, newGain)
    }
}
