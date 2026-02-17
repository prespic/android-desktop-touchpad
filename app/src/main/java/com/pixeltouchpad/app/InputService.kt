package com.pixeltouchpad.app

import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.lang.reflect.Method

/**
 * Shizuku UserService - runs with shell (ADB) privileges.
 * Tries InputManager API first, falls back to shell commands.
 */
class InputService : IInputService.Stub() {

    private var useShellFallback = false
    private val errors = mutableListOf<String>()

    // ---- InputManager reflection ----

    private var imInstance: Any? = null
    private var injectMethod: Method? = null
    private var setDisplayIdMethod: Method? = null

    init {
        try {
            imInstance = InputManager::class.java
                .getDeclaredMethod("getInstance")
                .invoke(null)
            errors.add("✓ InputManager.getInstance() OK")
        } catch (e: Exception) {
            errors.add("✗ InputManager.getInstance(): ${e.message}")
            useShellFallback = true
        }

        try {
            injectMethod = InputManager::class.java.getDeclaredMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            errors.add("✓ injectInputEvent method found")
        } catch (e: Exception) {
            errors.add("✗ injectInputEvent: ${e.message}")
            useShellFallback = true
        }

        try {
            setDisplayIdMethod = InputEvent::class.java.getDeclaredMethod(
                "setDisplayId",
                Int::class.javaPrimitiveType
            )
            errors.add("✓ setDisplayId method found")
        } catch (e: Exception) {
            errors.add("✗ setDisplayId: ${e.message}")
            // Not fatal - we can try without it
        }

        errors.add(if (useShellFallback) "→ Using SHELL fallback" else "→ Using InputManager API")
    }

    // ---- InputManager injection ----

    private fun injectViaInputManager(
        displayId: Int,
        action: Int,
        x: Float,
        y: Float,
        buttonState: Int = 0,
        vScroll: Float = 0f
    ): Boolean {
        val im = imInstance ?: return false
        val inject = injectMethod ?: return false

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
                    action == MotionEvent.ACTION_SCROLL) 0f else 1f
                size = 1f
                if (vScroll != 0f) {
                    setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
                }
            }
        )

        val event = MotionEvent.obtain(
            now, now, action, 1,
            properties, coords,
            0, buttonState,
            1.0f, 1.0f, 0, 0,
            InputDevice.SOURCE_MOUSE, 0
        )

        try {
            setDisplayIdMethod?.invoke(event, displayId)
        } catch (_: Exception) {}

        return try {
            val result = inject.invoke(im, event, 2) // 2 = INJECT_INPUT_EVENT_MODE_ASYNC
            event.recycle()
            result as? Boolean ?: false
        } catch (e: Exception) {
            event.recycle()
            if (errors.size < 20) errors.add("inject fail: ${e.cause?.message ?: e.message}")
            false
        }
    }

    // ---- Shell command fallback ----

    private fun shellMove(displayId: Int, x: Float, y: Float) {
        try {
            val cmd = "input -d $displayId motionevent HOVER_MOVE ${x.toInt()} ${y.toInt()}"
            Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)).waitFor()
        } catch (_: Exception) {
            // Try without -d flag
            try {
                val cmd = "input motionevent HOVER_MOVE ${x.toInt()} ${y.toInt()}"
                Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)).waitFor()
            } catch (_: Exception) {}
        }
    }

    private fun shellTap(displayId: Int, x: Float, y: Float) {
        try {
            val cmd = "input -d $displayId tap ${x.toInt()} ${y.toInt()}"
            Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)).waitFor()
        } catch (_: Exception) {
            try {
                val cmd = "input tap ${x.toInt()} ${y.toInt()}"
                Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)).waitFor()
            } catch (_: Exception) {}
        }
    }

    // ---- AIDL implementation ----

    override fun moveCursor(displayId: Int, x: Float, y: Float) {
        if (!useShellFallback) {
            val ok = injectViaInputManager(displayId, MotionEvent.ACTION_HOVER_MOVE, x, y)
            if (!ok && !useShellFallback) {
                useShellFallback = true
                errors.add("! InputManager inject returned false, switching to shell")
            }
        }
        if (useShellFallback) {
            shellMove(displayId, x, y)
        }
    }

    override fun click(displayId: Int, x: Float, y: Float) {
        if (!useShellFallback) {
            injectViaInputManager(displayId, MotionEvent.ACTION_DOWN, x, y,
                buttonState = MotionEvent.BUTTON_PRIMARY)
            try { Thread.sleep(16) } catch (_: Exception) {}
            injectViaInputManager(displayId, MotionEvent.ACTION_UP, x, y, buttonState = 0)
        } else {
            shellTap(displayId, x, y)
        }
    }

    override fun scroll(displayId: Int, x: Float, y: Float, vScroll: Float) {
        if (!useShellFallback) {
            injectViaInputManager(displayId, MotionEvent.ACTION_SCROLL, x, y, vScroll = vScroll)
        }
        // Shell fallback for scroll is unreliable, skip for now
    }

    override fun diagnose(): String {
        val sb = StringBuilder()
        sb.appendLine("=== PixelTouchpad Diagnostics ===")
        sb.appendLine("Process UID: ${android.os.Process.myUid()}")
        sb.appendLine("Process PID: ${android.os.Process.myPid()}")
        sb.appendLine("Shell fallback: $useShellFallback")
        sb.appendLine()
        sb.appendLine("--- Init log ---")
        errors.forEach { sb.appendLine(it) }

        // Test injection on display 0
        sb.appendLine()
        sb.appendLine("--- Live test (display 0) ---")
        try {
            val result = injectViaInputManager(0, MotionEvent.ACTION_HOVER_MOVE, 100f, 100f)
            sb.appendLine("Test inject result: $result")
        } catch (e: Exception) {
            sb.appendLine("Test inject exception: ${e.message}")
        }

        return sb.toString()
    }

    override fun destroy() {}
}
