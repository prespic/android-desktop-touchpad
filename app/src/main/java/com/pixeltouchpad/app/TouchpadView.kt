package com.pixeltouchpad.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * TouchpadView - captures touch gestures and translates them to cursor/click/scroll events.
 *
 * Gestures:
 * - 1 finger drag: move cursor
 * - 1 finger tap: left click
 * - 2 finger tap (no movement): right click
 * - 2 finger same direction: scroll
 * - 2 finger pinch (distance changes): zoom (Ctrl+scroll)
 * - 1 finger hold + 2nd finger added: drag (hold left button + move)
 * - 3 finger swipe L/R/U/D: back / recent / app drawer / notifications
 */
class TouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class SwipeDirection { LEFT, RIGHT, UP, DOWN }

    // --- Configuration ---
    var sensitivity = 1.5f
    var scrollSensitivity = 0.08f
    private val tapMaxDuration = 200L   // ms
    private val tapMaxDistance = 30f     // px
    private val dragHoldTime = 250L     // ms before 1st finger counts as "hold"
    private val threeFingerSwipeThreshold = 100f // px minimum swipe
    private val pinchZoomThreshold = 30f // px change in finger distance

    // --- Cursor state ---
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
    private var maxPointerCountInGesture = 0
    private var hasMoved = false

    // Two-finger state
    private var isScrolling = false
    private var lastScrollY = 0f
    private var twoFingerTapStartTime = 0L
    private var twoFingerMoved = false
    private var initialPinchDistance = 0f
    private var lastPinchDistance = 0f
    private var isPinching = false

    // Tap-and-drag state
    private var isDragMode = false
    private var firstFingerStationary = false

    // Three-finger state
    private var isThreeFingerGesture = false
    private var threeFingerStartX = 0f
    private var threeFingerStartY = 0f
    private var threeFingerLastX = 0f
    private var threeFingerLastY = 0f

    // --- Callbacks ---
    var onCursorMove: ((x: Float, y: Float) -> Unit)? = null
    var onClick: ((x: Float, y: Float) -> Unit)? = null
    var onRightClick: ((x: Float, y: Float) -> Unit)? = null
    var onScroll: ((x: Float, y: Float, vScroll: Float) -> Unit)? = null
    var onPinchZoom: ((zoomDelta: Float) -> Unit)? = null
    var onDragStart: (() -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null
    var onThreeFingerSwipe: ((direction: SwipeDirection) -> Unit)? = null

    // --- Drawing ---
    private val bgPaint = Paint().apply { color = Color.parseColor("#1a1a2e") }
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#16213e")
        strokeWidth = 1f
    }
    private val touchPaint = Paint().apply {
        color = Color.parseColor("#0f3460")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val statusPaint = Paint().apply {
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
    private var drawTouchX = -1f
    private var drawTouchY = -1f
    private var gestureLabel = ""

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
                maxPointerCountInGesture = 1
                isScrolling = false
                isThreeFingerGesture = false
                isDragMode = false
                isPinching = false
                hasMoved = false
                twoFingerMoved = false
                firstFingerStationary = false
                gestureLabel = ""
                drawTouchX = event.x
                drawTouchY = event.y
                invalidate()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                activePointerCount = event.pointerCount
                maxPointerCountInGesture = maxOf(maxPointerCountInGesture, event.pointerCount)

                if (activePointerCount == 2) {
                    val elapsed = System.currentTimeMillis() - touchStartTime
                    val dist = sqrt(
                        (event.getX(0) - touchStartX).let { it * it } +
                        (event.getY(0) - touchStartY).let { it * it }
                    )

                    // Check if first finger was held stationary = tap-and-drag
                    if (elapsed > dragHoldTime && dist < tapMaxDistance && !hasMoved) {
                        isDragMode = true
                        gestureLabel = "DRAG"
                        onDragStart?.invoke()
                    } else {
                        // Normal two-finger gesture (scroll / pinch / right-click tap)
                        isScrolling = true
                        lastScrollY = averageY(event)
                        twoFingerTapStartTime = System.currentTimeMillis()
                        twoFingerMoved = false
                        initialPinchDistance = fingerDistance(event)
                        lastPinchDistance = initialPinchDistance
                        isPinching = false
                    }
                }

                if (activePointerCount >= 3) {
                    isScrolling = false
                    isPinching = false
                    isDragMode = false
                    isThreeFingerGesture = true
                    threeFingerStartX = averageX(event)
                    threeFingerStartY = averageY(event)
                    threeFingerLastX = threeFingerStartX
                    threeFingerLastY = threeFingerStartY
                    gestureLabel = "3-FINGER"
                }
            }

            MotionEvent.ACTION_MOVE -> {
                when {
                    isThreeFingerGesture && event.pointerCount >= 3 -> {
                        // Track latest average position for swipe direction
                        threeFingerLastX = averageX(event)
                        threeFingerLastY = averageY(event)
                    }

                    isDragMode -> {
                        // Drag mode: move cursor with left button held
                        if (event.pointerCount >= 2) {
                            // Use the second finger for movement
                            val idx = if (event.pointerCount > 1) 1 else 0
                            val dx = (event.getX(idx) - lastTouchX) * sensitivity
                            val dy = (event.getY(idx) - lastTouchY) * sensitivity
                            lastTouchX = event.getX(idx)
                            lastTouchY = event.getY(idx)

                            if (abs(dx) > 0.5f || abs(dy) > 0.5f) {
                                cursorX = (cursorX + dx).coerceIn(0f, displayWidth - 1f)
                                cursorY = (cursorY + dy).coerceIn(0f, displayHeight - 1f)
                                onCursorMove?.invoke(cursorX, cursorY)
                            }
                        }
                        drawTouchX = event.x
                        drawTouchY = event.y
                        invalidate()
                    }

                    isScrolling && event.pointerCount >= 2 -> {
                        // Check for pinch vs scroll
                        val currentDist = fingerDistance(event)
                        val distDelta = currentDist - initialPinchDistance
                        val currentY = averageY(event)
                        val scrollDeltaY = currentY - lastScrollY

                        if (!isPinching && abs(distDelta) > pinchZoomThreshold) {
                            // Finger distance changed significantly → pinch zoom
                            isPinching = true
                            gestureLabel = "ZOOM"
                        }

                        if (isPinching) {
                            val pinchDelta = currentDist - lastPinchDistance
                            lastPinchDistance = currentDist
                            if (abs(pinchDelta) > 2f) {
                                twoFingerMoved = true
                                onPinchZoom?.invoke(pinchDelta)
                            }
                        } else {
                            // Normal scroll (both fingers same direction)
                            lastScrollY = currentY
                            if (abs(scrollDeltaY) > 1f) {
                                twoFingerMoved = true
                                gestureLabel = "SCROLL"
                                onScroll?.invoke(cursorX, cursorY, -scrollDeltaY * scrollSensitivity)
                            }
                        }
                    }

                    !isScrolling && !isThreeFingerGesture && !isDragMode && event.pointerCount == 1 -> {
                        // Single-finger cursor movement
                        val dx = (event.x - lastTouchX) * sensitivity
                        val dy = (event.y - lastTouchY) * sensitivity
                        lastTouchX = event.x
                        lastTouchY = event.y

                        if (abs(dx) > 0.5f || abs(dy) > 0.5f) {
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
            }

            MotionEvent.ACTION_POINTER_UP -> {
                activePointerCount = event.pointerCount - 1

                // When second finger lifts during drag, end drag
                if (isDragMode && activePointerCount < 2) {
                    // Keep drag mode active until full ACTION_UP
                }
            }

            MotionEvent.ACTION_UP -> {
                when {
                    isThreeFingerGesture && maxPointerCountInGesture >= 3 -> {
                        // Evaluate three-finger swipe direction
                        val swipeDx = threeFingerLastX - threeFingerStartX
                        val swipeDy = threeFingerLastY - threeFingerStartY
                        val absDx = abs(swipeDx)
                        val absDy = abs(swipeDy)

                        if (absDx > threeFingerSwipeThreshold || absDy > threeFingerSwipeThreshold) {
                            if (absDx > absDy) {
                                // Horizontal swipe
                                if (swipeDx < 0) onThreeFingerSwipe?.invoke(SwipeDirection.LEFT)
                                else onThreeFingerSwipe?.invoke(SwipeDirection.RIGHT)
                            } else {
                                // Vertical swipe
                                if (swipeDy < 0) onThreeFingerSwipe?.invoke(SwipeDirection.UP)
                                else onThreeFingerSwipe?.invoke(SwipeDirection.DOWN)
                            }
                        }
                    }

                    isDragMode -> {
                        onDragEnd?.invoke()
                    }

                    maxPointerCountInGesture == 2 && !twoFingerMoved && !isPinching -> {
                        // Two-finger tap = right click
                        val elapsed = System.currentTimeMillis() - twoFingerTapStartTime
                        if (elapsed < tapMaxDuration) {
                            onRightClick?.invoke(cursorX, cursorY)
                        }
                    }

                    !isScrolling && maxPointerCountInGesture <= 1 -> {
                        // Single-finger tap = left click
                        val elapsed = System.currentTimeMillis() - touchStartTime
                        val distX = event.x - touchStartX
                        val distY = event.y - touchStartY
                        val dist = sqrt(distX * distX + distY * distY)

                        if (elapsed < tapMaxDuration && dist < tapMaxDistance && !hasMoved) {
                            onClick?.invoke(cursorX, cursorY)
                        }
                    }
                }

                // Reset all state
                activePointerCount = 0
                maxPointerCountInGesture = 0
                isScrolling = false
                isThreeFingerGesture = false
                isDragMode = false
                isPinching = false
                hasMoved = false
                gestureLabel = ""
                drawTouchX = -1f
                drawTouchY = -1f
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                if (isDragMode) onDragEnd?.invoke()
                activePointerCount = 0
                maxPointerCountInGesture = 0
                isScrolling = false
                isThreeFingerGesture = false
                isDragMode = false
                isPinching = false
                gestureLabel = ""
                drawTouchX = -1f
                drawTouchY = -1f
                invalidate()
            }
        }
        return true
    }

    private fun averageX(event: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until event.pointerCount) sum += event.getX(i)
        return sum / event.pointerCount
    }

    private fun averageY(event: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until event.pointerCount) sum += event.getY(i)
        return sum / event.pointerCount
    }

    private fun fingerDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Grid
        val gridSpacing = 80f
        var gx = gridSpacing
        while (gx < width) { canvas.drawLine(gx, 0f, gx, height.toFloat(), gridPaint); gx += gridSpacing }
        var gy = gridSpacing
        while (gy < height) { canvas.drawLine(0f, gy, width.toFloat(), gy, gridPaint); gy += gridSpacing }

        // Touch indicator
        if (drawTouchX >= 0 && drawTouchY >= 0) {
            touchPaint.alpha = 100
            canvas.drawCircle(drawTouchX, drawTouchY, 60f, touchPaint)
            touchPaint.alpha = 200
            canvas.drawCircle(drawTouchX, drawTouchY, 20f, touchPaint)
        }

        // Gesture label
        if (gestureLabel.isNotEmpty()) {
            canvas.drawText(gestureLabel, width / 2f, 60f, statusPaint)
        }

        // Cursor coordinates
        val coordText = "%.0f, %.0f".format(cursorX, cursorY)
        canvas.drawText(coordText, 20f, height - 20f, coordPaint)
    }
}
