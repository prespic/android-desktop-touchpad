package com.pixeltouchpad.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.sqrt

/**
 * TouchpadView - captures touch gestures on the phone screen and translates them
 * into cursor movement, clicks, and scroll events for the external display.
 *
 * Gestures:
 * - Single finger drag: move cursor (relative movement)
 * - Single finger tap (short touch, little movement): left click
 * - Two finger vertical drag: scroll
 */
class TouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // --- Configuration ---
    var sensitivity = 2.5f
    var scrollSensitivity = 0.03f
    private val tapMaxDuration = 200L   // ms
    private val tapMaxDistance = 30f     // px on phone screen

    // --- Cursor state (absolute position on external display) ---
    var cursorX = 0f
        private set
    var cursorY = 0f
        private set
    var displayWidth = 1920f
    var displayHeight = 1080f

    // --- Touch tracking ---
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchStartTime = 0L
    private var activePointerCount = 0
    private var isScrolling = false
    private var lastScrollY = 0f
    private var hasMoved = false

    // --- Callbacks ---
    var onCursorMove: ((x: Float, y: Float) -> Unit)? = null
    var onClick: ((x: Float, y: Float) -> Unit)? = null
    var onScroll: ((x: Float, y: Float, vScroll: Float) -> Unit)? = null

    // --- Drawing ---
    private val bgPaint = Paint().apply {
        color = Color.parseColor("#1a1a2e")
    }
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#16213e")
        strokeWidth = 1f
    }
    private val touchPaint = Paint().apply {
        color = Color.parseColor("#0f3460")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.parseColor("#e94560")
        textSize = 40f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    private val coordPaint = Paint().apply {
        color = Color.parseColor("#888888")
        textSize = 32f
        textAlign = Paint.Align.LEFT
        isAntiAlias = true
    }

    // Active touch indicator position
    private var drawTouchX = -1f
    private var drawTouchY = -1f

    init {
        isClickable = true
        isFocusable = true
    }

    fun resetCursor() {
        cursorX = displayWidth / 2
        cursorY = displayHeight / 2
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                touchStartX = event.x
                touchStartY = event.y
                touchStartTime = System.currentTimeMillis()
                activePointerCount = 1
                isScrolling = false
                hasMoved = false
                drawTouchX = event.x
                drawTouchY = event.y
                invalidate()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                activePointerCount = event.pointerCount
                if (activePointerCount >= 2) {
                    isScrolling = true
                    lastScrollY = averageY(event)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isScrolling && event.pointerCount >= 2) {
                    // --- Two-finger scroll ---
                    val currentY = averageY(event)
                    val deltaY = currentY - lastScrollY
                    lastScrollY = currentY

                    if (kotlin.math.abs(deltaY) > 1f) {
                        onScroll?.invoke(cursorX, cursorY, -deltaY * scrollSensitivity)
                    }

                } else if (!isScrolling && event.pointerCount == 1) {
                    // --- Single-finger cursor movement ---
                    val dx = (event.x - lastTouchX) * sensitivity
                    val dy = (event.y - lastTouchY) * sensitivity
                    lastTouchX = event.x
                    lastTouchY = event.y

                    if (kotlin.math.abs(dx) > 0.5f || kotlin.math.abs(dy) > 0.5f) {
                        hasMoved = true
                        cursorX = (cursorX + dx).coerceIn(0f, displayWidth - 1f)
                        cursorY = (cursorY + dy).coerceIn(0f, displayHeight - 1f)
                        onCursorMove?.invoke(cursorX, cursorY)
                    }

                    drawTouchX = event.x
                    drawTouchY = event.y
                    invalidate()
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                activePointerCount = event.pointerCount - 1
            }

            MotionEvent.ACTION_UP -> {
                // Detect tap
                if (!isScrolling && activePointerCount <= 1) {
                    val elapsed = System.currentTimeMillis() - touchStartTime
                    val distX = event.x - touchStartX
                    val distY = event.y - touchStartY
                    val dist = sqrt(distX * distX + distY * distY)

                    if (elapsed < tapMaxDuration && dist < tapMaxDistance && !hasMoved) {
                        onClick?.invoke(cursorX, cursorY)
                    }
                }

                activePointerCount = 0
                isScrolling = false
                hasMoved = false
                drawTouchX = -1f
                drawTouchY = -1f
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                activePointerCount = 0
                isScrolling = false
                drawTouchX = -1f
                drawTouchY = -1f
                invalidate()
            }
        }
        return true
    }

    private fun averageY(event: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until event.pointerCount) {
            sum += event.getY(i)
        }
        return sum / event.pointerCount
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Grid lines
        val gridSpacing = 80f
        var gx = gridSpacing
        while (gx < width) {
            canvas.drawLine(gx, 0f, gx, height.toFloat(), gridPaint)
            gx += gridSpacing
        }
        var gy = gridSpacing
        while (gy < height) {
            canvas.drawLine(0f, gy, width.toFloat(), gy, gridPaint)
            gy += gridSpacing
        }

        // Touch indicator
        if (drawTouchX >= 0 && drawTouchY >= 0) {
            touchPaint.alpha = 100
            canvas.drawCircle(drawTouchX, drawTouchY, 60f, touchPaint)
            touchPaint.alpha = 200
            canvas.drawCircle(drawTouchX, drawTouchY, 20f, touchPaint)
        }

        // Status text at top
        val statusText = if (isScrolling) "⇕ SCROLL" else "TOUCHPAD"
        canvas.drawText(statusText, width / 2f, 60f, textPaint)

        // Cursor coordinates at bottom
        val coordText = "Cursor: %.0f, %.0f  |  Display: %.0f×%.0f".format(
            cursorX, cursorY, displayWidth, displayHeight
        )
        canvas.drawText(coordText, 20f, height - 20f, coordPaint)
    }
}
