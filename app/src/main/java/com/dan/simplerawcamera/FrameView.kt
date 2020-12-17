package com.dan.simplerawcamera


import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import java.util.*
import kotlin.concurrent.timer


class FrameView : View {

    companion object {
        fun dpToPx(dp: Int): Int {
            return (dp * Resources.getSystem().getDisplayMetrics().density).toInt()
        }

        const val SHOW_FOCUS_TIMEOUT = 1000L
        val LINE_WIDTH = dpToPx(1)
    }

    private val mPaintDark = Paint()
    private val mPaintLight = Paint()

    private var mShowRatio = false
    private var mRatioWidth = 3
    private var mRatioHeight = 2

    private var mShowGrid = true

    private var mShowFocusZone = false
    private var mShowFocusZoneRect = Rect(0, 0, 0, 0)
    private val mShowFocusLight = Paint()
    private var mShowFocusTimer: Timer? = null

    constructor(context: Context) : super(context, null) { init() }
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs, 0) { init() }
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) { init() }

    private fun init() {
        with(mPaintDark) {
            strokeWidth = LINE_WIDTH.toFloat()
            color = Color.rgb(0, 0, 0)
            style = Paint.Style.STROKE
        }

        with(mPaintLight) {
            strokeWidth = LINE_WIDTH.toFloat()
            color = Color.rgb(192, 192, 192)
            style = Paint.Style.STROKE
        }

        with(mShowFocusLight) {
            strokeWidth = LINE_WIDTH.toFloat()
            color = Color.rgb(255, 255, 0)
            style = Paint.Style.STROKE
        }
    }

    fun showRatio(show: Boolean, ratioWidth: Int = 4, ratioHeight: Int = 3) {
        if (mShowRatio != show || mRatioWidth != ratioWidth || mRatioHeight != ratioHeight) {
            mShowRatio = show
            mRatioWidth = ratioWidth
            mRatioHeight = ratioHeight
            invalidate()
        }
    }

    fun showGrid(show: Boolean) {
        if (mShowGrid != show) {
            mShowGrid = show
            invalidate()
        }
    }

    fun showFocusZone(rect: Rect) {
        if (!mShowFocusZone || !mShowFocusZoneRect.equals(rect)) {
            hideFocusZone()

            mShowFocusZoneRect = rect
            mShowFocusZone = true
            invalidate()

            mShowFocusTimer = timer(null, false, SHOW_FOCUS_TIMEOUT, SHOW_FOCUS_TIMEOUT) {
                hideFocusZone()
            }
        }
    }

    fun hideFocusZone() {
        if (mShowFocusZone) {
            mShowFocusTimer?.cancel()
            mShowFocusTimer = null

            mShowFocusZone = false
            invalidate()
        }
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        if(null == canvas) return

        val windowWidth = width
        val windowHeight = height
        var gridWidth = windowWidth
        var gridHeight = windowHeight
        var gridX = 0
        var gridY = 0

        //dark frame
        canvas.drawRect(
            Rect(
                LINE_WIDTH / 2,
                LINE_WIDTH / 2,
                windowWidth - LINE_WIDTH / 2,
                windowHeight - LINE_WIDTH / 2),
            mPaintDark)

        //dark ratio frame
        if (mShowRatio) {
            gridWidth = windowWidth
            gridHeight = gridWidth * mRatioHeight / mRatioWidth

            if (gridWidth > windowWidth || gridHeight > windowHeight) {
                gridHeight = windowHeight
                gridWidth = gridHeight * mRatioWidth / mRatioHeight
            }

            gridX = (windowWidth - gridWidth) / 2
            gridY = (windowHeight - gridHeight) / 2

            canvas.drawRect(
                Rect(
                    gridX + LINE_WIDTH / 2,
                    gridY + LINE_WIDTH / 2,
                    gridX + gridWidth - LINE_WIDTH / 2,
                    gridY + gridHeight - LINE_WIDTH / 2),
                mPaintDark)
        }

        //dark grid
        if (mShowGrid) {
            canvas.drawRect(
                Rect(
                    gridX + gridWidth / 3  + LINE_WIDTH / 2,
                    gridY + LINE_WIDTH / 2,
                    gridX + 2 * gridWidth / 3 - LINE_WIDTH / 2,
                    gridY + gridHeight - LINE_WIDTH / 2),
                mPaintDark)

            canvas.drawRect(
                Rect(
                    gridX + LINE_WIDTH / 2,
                    gridY + gridHeight / 3 + LINE_WIDTH / 2,
                    gridX + gridWidth - LINE_WIDTH / 2,
                    gridY + 2 * gridHeight / 3 - LINE_WIDTH / 2),
                mPaintDark)
        }

        //light frame
        canvas.drawRect(
            Rect(
                LINE_WIDTH + LINE_WIDTH / 2,
                LINE_WIDTH + LINE_WIDTH / 2,
                windowWidth - LINE_WIDTH - LINE_WIDTH / 2,
                windowHeight - LINE_WIDTH - LINE_WIDTH / 2),
            mPaintLight)

        //light ratio frame
        if (mShowRatio) {
            canvas.drawRect(
                Rect(
                    gridX + LINE_WIDTH + LINE_WIDTH / 2,
                    gridY + LINE_WIDTH + LINE_WIDTH / 2,
                    gridX + gridWidth - LINE_WIDTH - LINE_WIDTH / 2,
                    gridY + gridHeight - LINE_WIDTH - LINE_WIDTH / 2),
                mPaintLight)
        }

        //light grid
        if (mShowGrid) {
            canvas.drawRect(
                Rect(
                    gridX + gridWidth / 3 + LINE_WIDTH + LINE_WIDTH / 2,
                    gridY + LINE_WIDTH + LINE_WIDTH / 2,
                    gridX + 2 * gridWidth / 3 - LINE_WIDTH - LINE_WIDTH / 2,
                    gridY + gridHeight - LINE_WIDTH - LINE_WIDTH / 2),
                mPaintLight)

            canvas.drawRect(
                Rect(
                    gridX + LINE_WIDTH + LINE_WIDTH / 2,
                    gridY + gridHeight / 3 + LINE_WIDTH + LINE_WIDTH / 2,
                    gridX + gridWidth - LINE_WIDTH - LINE_WIDTH / 2,
                    gridY + 2 * gridHeight / 3 - LINE_WIDTH - LINE_WIDTH / 2),
                mPaintLight)
        }

        if (mShowFocusZone) {
            val x1 = mShowFocusZoneRect.left * windowWidth / 100
            val y1 = mShowFocusZoneRect.top * windowHeight / 100
            val x2 = mShowFocusZoneRect.right * windowWidth / 100
            val y2 = mShowFocusZoneRect.bottom * windowHeight / 100
            canvas.drawRect(Rect(x1 - LINE_WIDTH / 2, y1 - LINE_WIDTH / 2, x2 + LINE_WIDTH / 2, y2 + LINE_WIDTH / 2), mPaintDark)
            canvas.drawRect(Rect(x1 + LINE_WIDTH / 2, y1 + LINE_WIDTH / 2, x2 - LINE_WIDTH / 2, y2 - LINE_WIDTH / 2), mShowFocusLight)
        }
    }
}