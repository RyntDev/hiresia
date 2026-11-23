package com.hiresia.audioplayer.ui

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.TransitionDrawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import com.hiresia.audioplayer.repository.AudioSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Generates an atmospheric, luminous zoomed-and-blurred album-art gradient backdrop
 * that naturally preserves the cover's top-to-bottom color flow while keeping brightness
 * balanced for crisp foreground legibility.
 */
object AlbumArtBlurHelper {

    private const val CANVAS_WIDTH = 108
    private const val CANVAS_HEIGHT = 216
    private const val BLUR_RADIUS_PRIMARY = 22
    private const val BLUR_RADIUS_POLISH = 16

    suspend fun applyBlurredBackground(
        context: Context,
        sourceBitmap: Bitmap?,
        bgImageView: ImageView,
        overlayView: View?,
        onEdgeColorsReady: ((topColor: Int, bottomColor: Int) -> Unit)? = null
    ) {
        val scheme = AudioSettingsRepository.getCurrentScheme()
        val isDarkTheme = (AudioSettingsRepository.themeMode == AudioSettingsRepository.THEME_MODE_DARK &&
            AudioSettingsRepository.paletteIndex != AudioSettingsRepository.PALETTE_C_AEGEAN)

        val result = withContext(Dispatchers.Default) {
            try {
                val effectiveBitmap = sourceBitmap ?: BitmapFactory.decodeResource(
                    context.resources,
                    AudioSettingsRepository.getDefaultCoverResId()
                ) ?: return@withContext null

                // 1. Compose portrait mesh (base stretched top->bottom + 1.35x center zoom depth layer)
                val mesh = Bitmap.createBitmap(CANVAS_WIDTH, CANVAS_HEIGHT, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(mesh)
                val basePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

                val fullSrc = Rect(0, 0, effectiveBitmap.width, effectiveBitmap.height)
                val fullDst = Rect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT)
                canvas.drawBitmap(effectiveBitmap, fullSrc, fullDst, basePaint)

                val zoomW = max(1, (effectiveBitmap.width / 1.35f).toInt())
                val zoomH = max(1, (effectiveBitmap.height / 1.35f).toInt())
                val left = (effectiveBitmap.width - zoomW) / 2
                val top = (effectiveBitmap.height - zoomH) / 2
                val zoomSrc = Rect(left, top, left + zoomW, top + zoomH)
                val zoomPaint = Paint(basePaint).apply { alpha = 85 }
                canvas.drawBitmap(effectiveBitmap, zoomSrc, fullDst, zoomPaint)

                // 2. Luminous brightness + subtle vibrancy remap (keeps exact album colors readable)
                remapLuminousColors(mesh, isDarkTheme, scheme.page)

                // 3. Two-pass StackBlur approximates a silky Gaussian ambient mesh without banding
                val pass1 = stackBlur(mesh, BLUR_RADIUS_PRIMARY)
                val blurred = stackBlur(pass1, BLUR_RADIUS_POLISH)
                if (pass1 !== mesh && pass1 !== blurred) pass1.recycle()
                if (mesh !== blurred) mesh.recycle()

                // 4. Sample actual blurred top and bottom edge colors
                val topColor = sampleEdgeAverageColor(blurred, topEdge = true)
                val bottomColor = sampleEdgeAverageColor(blurred, topEdge = false)

                Triple(blurred, topColor, bottomColor)
            } catch (_: Throwable) {
                null
            }
        }

        withContext(Dispatchers.Main) {
            if (result != null) {
                val (blurredBitmap, topColor, bottomColor) = result
                crossfadeToBitmap(context, bgImageView, blurredBitmap)
                if (overlayView != null) {
                    overlayView.background = createSubtleVeilGradient(topColor, bottomColor, scheme.page)
                }
                onEdgeColorsReady?.invoke(topColor, bottomColor)
            } else {
                bgImageView.setImageDrawable(null)
                bgImageView.alpha = 1.0f
                overlayView?.background = createSubtleVeilGradient(scheme.page, scheme.surface, scheme.page)
                onEdgeColorsReady?.invoke(scheme.page, scheme.page)
            }
        }
    }

    private fun remapLuminousColors(bitmap: Bitmap, isDarkTheme: Boolean, pageBg: Int) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val hsl = FloatArray(3)
        for (i in pixels.indices) {
            val c = pixels[i]
            ColorUtils.colorToHSL(c, hsl)
            val s = hsl[1]
            val l = hsl[2]

            if (isDarkTheme) {
                hsl[1] = if (s < 0.06f) s else (s * 1.20f).coerceAtMost(0.72f)
                hsl[2] = 0.16f + (l * 0.22f)
                pixels[i] = ColorUtils.HSLToColor(hsl)
            } else {
                // Keep exact hue from cover; lift shadows warmly and brighten midtones slightly
                val isVeryDarkNeutral = (l < 0.08f && s < 0.12f)
                hsl[1] = when {
                    s < 0.05f -> 0.04f
                    else -> (s * 1.24f).coerceIn(0.28f, 0.78f)
                }
                hsl[2] = (0.65f + (l.coerceIn(0f, 1f).pow(0.85f) * 0.24f)).coerceIn(0.65f, 0.89f)
                var mapped = ColorUtils.HSLToColor(hsl)
                if (isVeryDarkNeutral) {
                    mapped = ColorUtils.blendARGB(mapped, pageBg, 0.28f)
                }
                pixels[i] = mapped
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun sampleEdgeAverageColor(bitmap: Bitmap, topEdge: Boolean): Int {
        val w = bitmap.width
        val h = bitmap.height
        val bandH = max(1, (h * 0.15f).toInt())
        val startY = if (topEdge) 0 else max(0, h - bandH)

        val pixels = IntArray(w * bandH)
        bitmap.getPixels(pixels, 0, w, 0, startY, w, bandH)

        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        for (p in pixels) {
            rSum += Color.red(p)
            gSum += Color.green(p)
            bSum += Color.blue(p)
        }
        val count = max(1, pixels.size)
        return Color.rgb(
            (rSum / count).toInt(),
            (gSum / count).toInt(),
            (bSum / count).toInt()
        )
    }

    private fun createSubtleVeilGradient(topColor: Int, bottomColor: Int, pageBg: Int): GradientDrawable {
        val bottomSoft = ColorUtils.blendARGB(bottomColor, pageBg, 0.25f)
        val stops = intArrayOf(
            ColorUtils.setAlphaComponent(topColor, 48),
            Color.TRANSPARENT,
            ColorUtils.setAlphaComponent(bottomSoft, 42),
            ColorUtils.setAlphaComponent(bottomSoft, 95)
        )
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, stops).apply {
            gradientType = GradientDrawable.LINEAR_GRADIENT
        }
    }

    private fun crossfadeToBitmap(context: Context, imageView: ImageView, newBitmap: Bitmap) {
        val nextDrawable = BitmapDrawable(context.resources, newBitmap)
        val rawPrev = imageView.drawable
        val prevLayer: Drawable? = when (rawPrev) {
            is TransitionDrawable -> if (rawPrev.numberOfLayers >= 2) rawPrev.getDrawable(1) else rawPrev
            else -> rawPrev
        }

        if (prevLayer == null) {
            imageView.setImageDrawable(nextDrawable)
            imageView.alpha = 0f
            imageView.animate()
                .alpha(1.0f)
                .setDuration(350)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            imageView.animate().cancel()
            imageView.alpha = 1.0f
            val transition = TransitionDrawable(arrayOf(prevLayer, nextDrawable)).apply {
                isCrossFadeEnabled = true
            }
            imageView.setImageDrawable(transition)
            transition.startTransition(420)
        }
    }

    /**
     * High-speed Mario Klingemann StackBlur algorithm for ultra-smooth frosted blur.
     */
    private fun stackBlur(sentBitmap: Bitmap, radius: Int): Bitmap {
        val config = sentBitmap.config ?: Bitmap.Config.ARGB_8888
        val bitmap = sentBitmap.copy(config, true)
        if (radius < 1) return bitmap

        val w = bitmap.width
        val h = bitmap.height
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        var rsum: Int
        var gsum: Int
        var bsum: Int
        var x: Int
        var y: Int
        var i: Int
        var p: Int
        var yp: Int
        var yi: Int
        var yw: Int
        val vmin = IntArray(max(w, h))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        for (idx in 0 until 256 * divsum) {
            dv[idx] = idx / divsum
        }

        yi = 0
        yw = 0

        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var sir: IntArray
        var rbs: Int
        val r1 = radius + 1
        var routsum: Int
        var goutsum: Int
        var boutsum: Int
        var rinsum: Int
        var ginsum: Int
        var binsum: Int

        y = 0
        while (y < h) {
            boutsum = 0
            goutsum = 0
            routsum = 0
            binsum = 0
            ginsum = 0
            rinsum = 0
            bsum = 0
            gsum = 0
            rsum = 0
            i = -radius
            while (i <= radius) {
                p = pix[yi + min(wm, max(i, 0))]
                sir = stack[i + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff
                rbs = r1 - abs(i)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
                i++
            }
            stackpointer = radius

            x = 0
            while (x < w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (y == 0) {
                    vmin[x] = min(x + radius + 1, wm)
                }
                p = pix[yw + vmin[x]]

                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi++
                x++
            }
            yw += w
            y++
        }

        x = 0
        while (x < w) {
            boutsum = 0
            goutsum = 0
            routsum = 0
            binsum = 0
            ginsum = 0
            rinsum = 0
            bsum = 0
            gsum = 0
            rsum = 0
            yp = -radius * w
            i = -radius
            while (i <= radius) {
                yi = max(0, yp) + x
                sir = stack[i + radius]
                sir[0] = r[yi]
                sir[1] = g[yi]
                sir[2] = b[yi]
                rbs = r1 - abs(i)
                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
                if (i < hm) {
                    yp += w
                }
                i++
            }
            yi = x
            stackpointer = radius
            y = 0
            while (y < h) {
                pix[yi] = (-0x1000000 and pix[yi]) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (x == 0) {
                    vmin[y] = min(y + r1, hm) * w
                }
                p = x + vmin[y]

                sir[0] = r[p]
                sir[1] = g[p]
                sir[2] = b[p]

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi += w
                y++
            }
            x++
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
        return bitmap
    }
}
