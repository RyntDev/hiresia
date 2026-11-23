package com.hiresia.audioplayer.ui

import android.content.Context
import android.graphics.Rect
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.widget.AppCompatTextView

/**
 * An AppCompatTextView subclass that guarantees continuous, uninterrupted marquee scrolling
 * for long titles without getting stuck when touches, clicks, or seekbar interactions occur.
 */
class MarqueeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private val marqueeStarter = Runnable {
        isSelected = false
        isSelected = true
    }

    init {
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.MARQUEE
        marqueeRepeatLimit = -1
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength(32)
    }

    override fun isFocused(): Boolean = true

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        if (focused) {
            super.onFocusChanged(true, direction, previouslyFocusedRect)
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (hasWindowFocus) {
            super.onWindowFocusChanged(true)
        }
    }

    fun restartMarquee() {
        removeCallbacks(marqueeStarter)
        post(marqueeStarter)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) {
            restartMarquee()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) {
            restartMarquee()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restartMarquee()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(marqueeStarter)
        super.onDetachedFromWindow()
        isSelected = false
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        restartMarquee()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && w != oldw) {
            restartMarquee()
        }
    }
}
