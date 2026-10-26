package com.local.ktv

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.max

/** Two copies share one moving track; wrapping the phase never jumps the visible text. */
class LoopingStatusTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {
    private var phase = 0f
    internal var completedLoops = 0
        private set
    private var lastFrame = 0L
    private var measuredText = ""
    private var measuredSize = 0f
    private var textWidth = 0f
    private val gap get() = 48f * resources.displayMetrics.density
    private val speed get() = 30f * resources.displayMetrics.density

    init {
        setSingleLine(true)
        ellipsize = null
        gravity = Gravity.CENTER_VERTICAL
    }

    override fun onDraw(canvas: Canvas) {
        val value = text?.toString().orEmpty()
        val available = width - paddingLeft - paddingRight
        if (value.isEmpty() || available <= 0) {
            lastFrame = 0L
            super.onDraw(canvas)
            return
        }
        if (value != measuredText || paint.textSize != measuredSize) {
            measuredText = value
            measuredSize = paint.textSize
            textWidth = paint.measureText(value)
        }
        val cycle = max(textWidth, available.toFloat()) + gap
        val now = SystemClock.uptimeMillis()
        if (lastFrame != 0L) {
            val next = phase + (now - lastFrame).coerceAtMost(80L) * speed / 1000f
            if (next >= cycle) completedLoops++
            phase = next % cycle
        } else phase %= cycle
        lastFrame = now
        paint.color = currentTextColor
        val metrics = paint.fontMetrics
        val baseline = paddingTop + (height - paddingTop - paddingBottom - metrics.bottom - metrics.top) / 2f
        val saved = canvas.save()
        canvas.clipRect(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
        var x = paddingLeft - phase
        while (x < width - paddingRight) {
            canvas.drawText(value, x, baseline, paint)
            x += cycle
        }
        canvas.restoreToCount(saved)
        if (isShown && windowVisibility == View.VISIBLE) postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrame = 0L
        postInvalidateOnAnimation()
    }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        lastFrame = 0L
        if (visibility == View.VISIBLE) postInvalidateOnAnimation()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        lastFrame = 0L
        if (visibility == View.VISIBLE) postInvalidateOnAnimation()
    }
}
