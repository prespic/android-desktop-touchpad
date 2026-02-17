package com.pixeltouchpad.app

import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent

/**
 * Shizuku UserService - runs in a separate process with shell (ADB) privileges.
 * Injects mouse input events via InputManager on the target display.
 *
 * IMPORTANT: This class must NOT reference any Activity, Context, or UI classes.
 * It runs as a standalone process managed by Shizuku.
 */
class InputService : IInputService.Stub() {

    companion object {
        private const val INJECT_MODE_ASYNC = 0
    }

    private val inputManager: Any? by lazy {
        try {
            InputManager::class.java
                .getDeclaredMethod("getInstance")
                .invoke(null)
        } catch (e: Exception) {
            null
        }
    }

    private val injectMethod by lazy {
        try {
            InputManager::class.java.getDeclaredMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
        } catch (e: Exception) {
            null
        }
    }

    private val setDisplayIdMethod by lazy {
        try {
            InputEvent::class.java.getDeclaredMethod(
                "setDisplayId",
                Int::class.javaPrimitiveType
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Injects a MotionEvent with SOURCE_MOUSE on the specified display.
     */
    private fun injectMotionEvent(
        displayId: Int,
        action: Int,
        x: Float,
        y: Float,
        buttonState: Int = 0,
        vScroll: Float = 0f
    ) {
        val im = inputManager ?: return
        val inject = injectMethod ?: return

        val now = SystemClock.uptimeMillis()

        val properties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            }
        )

        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = if (action == MotionEvent.ACTION_HOVER_MOVE ||
                    action == MotionEvent.ACTION_SCROLL
                ) 0f else 1f
                size = 1f
                if (vScroll != 0f) {
                    setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
                }
            }
        )

        val event = MotionEvent.obtain(
            /* downTime */ now,
            /* eventTime */ now,
            /* action */ action,
            /* pointerCount */ 1,
            /* pointerProperties */ properties,
            /* pointerCoords */ coords,
            /* metaState */ 0,
            /* buttonState */ buttonState,
            /* xPrecision */ 1.0f,
            /* yPrecision */ 1.0f,
            /* deviceId */ 0,
            /* edgeFlags */ 0,
            /* source */ InputDevice.SOURCE_MOUSE,
            /* flags */ 0
        )

        // Set the target display
        try {
            setDisplayIdMethod?.invoke(event, displayId)
        } catch (_: Exception) {
        }

        // Inject
        try {
            inject.invoke(im, event, INJECT_MODE_ASYNC)
        } catch (_: Exception) {
        }

        event.recycle()
    }

    // ---- IInputService AIDL implementation ----

    override fun moveCursor(displayId: Int, x: Float, y: Float) {
        injectMotionEvent(
            displayId = displayId,
            action = MotionEvent.ACTION_HOVER_MOVE,
            x = x,
            y = y
        )
    }

    override fun click(displayId: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        // ACTION_DOWN
        injectMotionEvent(
            displayId = displayId,
            action = MotionEvent.ACTION_DOWN,
            x = x,
            y = y,
            buttonState = MotionEvent.BUTTON_PRIMARY
        )
        // Small delay for realism
        try { Thread.sleep(16) } catch (_: Exception) {}
        // ACTION_UP
        injectMotionEvent(
            displayId = displayId,
            action = MotionEvent.ACTION_UP,
            x = x,
            y = y,
            buttonState = 0
        )
    }

    override fun scroll(displayId: Int, x: Float, y: Float, vScroll: Float) {
        injectMotionEvent(
            displayId = displayId,
            action = MotionEvent.ACTION_SCROLL,
            x = x,
            y = y,
            vScroll = vScroll
        )
    }

    override fun destroy() {
        // Nothing to clean up
    }
}
