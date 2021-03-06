package com.dan.simplerawcamera

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.abs


/**
 Simulate a button with up/down and/or left/right movements
 */
class SensitiveTextView : AppCompatTextView {

    companion object {
        private fun dpToPx(dp: Int): Int {
            return (dp * Resources.getSystem().displayMetrics.density).toInt()
        }

        val PADDING = dpToPx(1).toFloat()

        val BG_COLOR_NORMAL = Color.argb(0x80, 0, 0, 0 )
        val BG_COLOR_PRESSED = Color.rgb(48, 48, 48 )

        val STEP_X = dpToPx(30)
        val STEP_Y = dpToPx(50)

        const val DIRECTION_NONE = 0
        const val DIRECTION_NOT_DEFINED = 1
        const val DIRECTION_X_AXIS = 2
        const val DIRECTION_Y_AXIS = 3
    }

    private var mStartX: Float = 0f
    private var mStartY: Float = 0f
    private var mDirection: Int = DIRECTION_NONE
    private var mOnMoveXAxis: ((Int)->Unit)? = null
    private var mOnMoveYAxis: ((Int)->Unit)? = null
    private val mTextRect = Rect()

    constructor(context: Context) : super(context, null) { init() }
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs, 0) { init() }
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) { init() }

    private fun init() {
        setBackgroundColor(BG_COLOR_NORMAL)
    }

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)

        if (null == canvas) return

        var charLeft = "+"
        var charRight = "+"
        var charRightIsRotated = false
        var charOffset = 0

        if (DIRECTION_X_AXIS == mDirection || (DIRECTION_NONE == mDirection && null != mOnMoveXAxis && null == mOnMoveYAxis)) {
            charLeft = "<"
            charRight = ">"
        } else if (DIRECTION_Y_AXIS == mDirection || (DIRECTION_NONE == mDirection && null == mOnMoveXAxis && null != mOnMoveYAxis)) {
            charLeft = "^"
            charRight = "^"
            charRightIsRotated = true
            charOffset = dpToPx(5)
        }

        val paint = this.paint

        paint.getTextBounds( charLeft, 0, 1, mTextRect )
        canvas.drawText( charLeft, PADDING, (height + mTextRect.height()) / 2f + charOffset, paint )

        if (charRightIsRotated) {
            canvas.rotate(180f, width - (PADDING + mTextRect.width()) / 2, height / 2f)
        }
        canvas.drawText( charRight, width - PADDING - mTextRect.width(), (height + mTextRect.height()) / 2f + charOffset, paint )
   }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when(ev.action) {
            MotionEvent.ACTION_DOWN -> {
                handleActionDown(ev)
                return true
            }

            MotionEvent.ACTION_MOVE -> handleActionMove(ev)
            MotionEvent.ACTION_UP -> handleActionUp()
        }

        return super.onTouchEvent(ev)
    }

    private fun handleActionMove(ev: MotionEvent) {
        val deltaX = (ev.x - mStartX).toInt()
        val deltaY = (ev.y - mStartY).toInt()
        val absDeltaX = abs(deltaX)
        val absDeltaY = abs(deltaY)

        if (DIRECTION_NOT_DEFINED == mDirection || DIRECTION_NONE == mDirection) {
            if (absDeltaX >= STEP_X && null != mOnMoveXAxis) {
                mDirection = DIRECTION_X_AXIS
                invalidate()
            } else if (absDeltaY >= STEP_Y && null != mOnMoveYAxis) {
                mDirection = DIRECTION_Y_AXIS
                invalidate()
            } else {
                return
            }
        }

        when(mDirection) {
            DIRECTION_X_AXIS -> {
                if (absDeltaX >= STEP_X) {
                    val steps = deltaX / STEP_X
                    mStartX = ev.x
                    mStartY = ev.y
                    mOnMoveXAxis?.invoke(steps)
                }
            }

            DIRECTION_Y_AXIS -> {
                if (absDeltaY >= STEP_Y) {
                    val steps = deltaY / STEP_Y
                    mStartX = ev.x
                    mStartY = ev.y
                    mOnMoveYAxis?.invoke(steps)
                }
            }
        }
    }

    private fun handleActionDown(ev: MotionEvent) {
        mStartX = ev.x
        mStartY = ev.y

        mDirection = if (null != mOnMoveXAxis && null == mOnMoveYAxis) {
            DIRECTION_X_AXIS
        } else if (null == mOnMoveXAxis && null != mOnMoveYAxis) {
            DIRECTION_Y_AXIS
        } else {
            DIRECTION_NOT_DEFINED
        }

        setBackgroundColor(BG_COLOR_PRESSED)
        invalidate()
    }

    private fun handleActionUp() {
        mDirection = DIRECTION_NONE
        setBackgroundColor(BG_COLOR_NORMAL)
        invalidate()
    }

    fun setOnMoveXAxisListener( l: (Int)->Unit ) {
        mOnMoveXAxis = l
    }

    fun setOnMoveYAxisListener( l: (Int)->Unit ) {
        mOnMoveYAxis = l
    }
}