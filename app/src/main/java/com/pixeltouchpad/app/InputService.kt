package com.pixeltouchpad.app

import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.io.File
import java.lang.reflect.Method

/**
 * Shizuku UserService - runs with shell (ADB) privileges.
 *
 * Injection strategy priority:
 * 1. InputManagerGlobal.injectInputEvent (Android 14+/16)
 * 2. InputManager.getInstance().injectInputEvent (legacy)
 * 3. Instrumentation.sendPointerSync
 * 4. Shell "input" commands (tap only, no hover)
 */
class InputService : IInputService.Stub() {

    private val initLog = mutableListOf<String>()

    // Active strategy
    private enum class Strategy { INPUT_MANAGER_GLOBAL, INPUT_MANAGER_LEGACY, INSTRUMENTATION, SHELL }
    private var activeStrategy = Strategy.SHELL

    // ---- Reflection handles ----

    private var imInstance: Any? = null
    private var injectMethod: Method? = null
    private var setDisplayIdMethod: Method? = null

    // Instrumentation
    private var instrInstance: Any? = null
    private var sendPointerSyncMethod: Method? = null

    init {
        // Try setDisplayId (shared across strategies)
        try {
            setDisplayIdMethod = InputEvent::class.java.getDeclaredMethod(
                "setDisplayId",
                Int::class.javaPrimitiveType
            )
            initLog.add("✓ setDisplayId method found")
        } catch (e: Exception) {
            initLog.add("✗ setDisplayId: ${e.message}")
        }

        // Strategy 1: InputManagerGlobal (Android 14+/16)
        try {
            val imgClass = Class.forName("android.hardware.input.InputManagerGlobal")
            imInstance = imgClass.getDeclaredMethod("getInstance").invoke(null)

            // Try 2-arg version first: injectInputEvent(InputEvent, int)
            injectMethod = imgClass.getDeclaredMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            activeStrategy = Strategy.INPUT_MANAGER_GLOBAL
            initLog.add("✓ InputManagerGlobal.getInstance() OK")
            initLog.add("✓ InputManagerGlobal.injectInputEvent(InputEvent, int) found")
        } catch (e: Exception) {
            initLog.add("✗ InputManagerGlobal: ${e.cause?.message ?: e.message}")
        }

        // Strategy 2: Legacy InputManager.getInstance()
        if (activeStrategy == Strategy.SHELL) {
            try {
                imInstance = InputManager::class.java
                    .getDeclaredMethod("getInstance")
                    .invoke(null)
                injectMethod = InputManager::class.java.getDeclaredMethod(
                    "injectInputEvent",
                    InputEvent::class.java,
                    Int::class.javaPrimitiveType
                )
                activeStrategy = Strategy.INPUT_MANAGER_LEGACY
                initLog.add("✓ InputManager.getInstance() OK (legacy)")
            } catch (e: Exception) {
                initLog.add("✗ InputManager legacy: ${e.cause?.message ?: e.message}")
            }
        }

        // Strategy 3: Instrumentation.sendPointerSync
        if (activeStrategy == Strategy.SHELL) {
            try {
                val instrClass = Class.forName("android.app.Instrumentation")
                instrInstance = instrClass.getDeclaredConstructor().newInstance()
                sendPointerSyncMethod = instrClass.getDeclaredMethod(
                    "sendPointerSync",
                    MotionEvent::class.java
                )
                activeStrategy = Strategy.INSTRUMENTATION
                initLog.add("✓ Instrumentation.sendPointerSync ready")
            } catch (e: Exception) {
                initLog.add("✗ Instrumentation: ${e.message}")
            }
        }

        initLog.add("→ Active strategy: $activeStrategy")
    }

    // ---- Injection via InputManager/InputManagerGlobal ----

    private fun buildMouseEvent(
        displayId: Int,
        action: Int,
        x: Float,
        y: Float,
        buttonState: Int = 0,
        vScroll: Float = 0f
    ): MotionEvent {
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
                if (vScroll != 0f) setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
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
        return event
    }

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

        val event = buildMouseEvent(displayId, action, x, y, buttonState, vScroll)
        return try {
            val result = inject.invoke(im, event, 2) // 2 = INJECT_INPUT_EVENT_MODE_ASYNC
            event.recycle()
            result as? Boolean ?: false
        } catch (e: Exception) {
            event.recycle()
            false
        }
    }

    // ---- Injection via Instrumentation ----

    private fun injectViaInstrumentation(
        displayId: Int,
        action: Int,
        x: Float,
        y: Float,
        buttonState: Int = 0,
        vScroll: Float = 0f
    ): Boolean {
        val instr = instrInstance ?: return false
        val send = sendPointerSyncMethod ?: return false

        val event = buildMouseEvent(displayId, action, x, y, buttonState, vScroll)
        return try {
            send.invoke(instr, event)
            event.recycle()
            true
        } catch (e: Exception) {
            event.recycle()
            false
        }
    }

    // ---- Shell fallback ----

    private fun execShell(cmd: String, timeoutMs: Long = 5000): Pair<Int, String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return Pair(-1, "TIMEOUT after ${timeoutMs}ms")
            }
            val stdout = process.inputStream.bufferedReader().readText().trim()
            val stderr = process.errorStream.bufferedReader().readText().trim()
            val output = if (stderr.isNotEmpty()) "$stdout\n$stderr".trim() else stdout
            Pair(process.exitValue(), output)
        } catch (e: Exception) {
            Pair(-1, "Exception: ${e.message}")
        }
    }

    // ---- Unified inject method ----

    private fun inject(
        displayId: Int,
        action: Int,
        x: Float,
        y: Float,
        buttonState: Int = 0,
        vScroll: Float = 0f
    ): Boolean {
        return when (activeStrategy) {
            Strategy.INPUT_MANAGER_GLOBAL, Strategy.INPUT_MANAGER_LEGACY -> {
                val ok = injectViaInputManager(displayId, action, x, y, buttonState, vScroll)
                if (!ok) {
                    // Try Instrumentation as fallback
                    injectViaInstrumentation(displayId, action, x, y, buttonState, vScroll)
                } else true
            }
            Strategy.INSTRUMENTATION -> {
                injectViaInstrumentation(displayId, action, x, y, buttonState, vScroll)
            }
            Strategy.SHELL -> false
        }
    }

    // ---- AIDL implementation ----

    override fun moveCursor(displayId: Int, x: Float, y: Float) {
        if (activeStrategy != Strategy.SHELL) {
            inject(displayId, MotionEvent.ACTION_HOVER_MOVE, x, y)
        }
        // Shell can't do HOVER_MOVE, skip
    }

    override fun click(displayId: Int, x: Float, y: Float) {
        if (activeStrategy != Strategy.SHELL) {
            inject(displayId, MotionEvent.ACTION_DOWN, x, y,
                buttonState = MotionEvent.BUTTON_PRIMARY)
            try { Thread.sleep(16) } catch (_: Exception) {}
            inject(displayId, MotionEvent.ACTION_UP, x, y, buttonState = 0)
        } else {
            execShell("input -d $displayId tap ${x.toInt()} ${y.toInt()}")
        }
    }

    override fun scroll(displayId: Int, x: Float, y: Float, vScroll: Float) {
        if (activeStrategy != Strategy.SHELL) {
            inject(displayId, MotionEvent.ACTION_SCROLL, x, y, vScroll = vScroll)
        }
    }

    // ===========================================================
    //  COMPREHENSIVE DIAGNOSTICS
    // ===========================================================

    override fun diagnose(): String {
        val sb = StringBuilder()
        sb.appendLine("=== PixelTouchpad Diagnostics ===")
        sb.appendLine("Timestamp: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        sb.appendLine()

        // 1. Process & device
        sb.appendLine("-- 1. Device --")
        sb.appendLine("UID: ${android.os.Process.myUid()} PID: ${android.os.Process.myPid()}")
        sb.appendLine("SDK: ${android.os.Build.VERSION.SDK_INT} (${android.os.Build.VERSION.RELEASE})")
        sb.appendLine("${android.os.Build.MODEL} (${android.os.Build.DEVICE})")
        val (_, idOut) = execShell("id")
        sb.appendLine("id: $idOut")
        sb.appendLine()

        // 2. Init log
        sb.appendLine("-- 2. Init --")
        initLog.forEach { sb.appendLine(it) }
        sb.appendLine()

        // 3. Live injection tests
        sb.appendLine("-- 3. Injection Tests --")

        // 3a. InputManagerGlobal
        sb.appendLine("3a. InputManagerGlobal:")
        try {
            val imgClass = Class.forName("android.hardware.input.InputManagerGlobal")
            val img = imgClass.getDeclaredMethod("getInstance").invoke(null)
            val imgInject = imgClass.getDeclaredMethod(
                "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType
            )
            for (displayId in listOf(0, 1, 2)) {
                val event = buildMouseEvent(displayId, MotionEvent.ACTION_HOVER_MOVE, 100f, 100f)
                try {
                    val r = imgInject.invoke(img, event, 2) // ASYNC
                    sb.appendLine("  d=$displayId HOVER: $r")
                } catch (e: Exception) {
                    sb.appendLine("  d=$displayId HOVER: ERR ${e.cause?.message ?: e.message}")
                }
                event.recycle()
            }
            // Also test tap (DOWN+UP)
            for (displayId in listOf(0, 1)) {
                val downEvt = buildMouseEvent(displayId, MotionEvent.ACTION_DOWN, 100f, 100f,
                    buttonState = MotionEvent.BUTTON_PRIMARY)
                try {
                    val r = imgInject.invoke(img, downEvt, 2)
                    sb.appendLine("  d=$displayId DOWN: $r")
                } catch (e: Exception) {
                    sb.appendLine("  d=$displayId DOWN: ERR ${e.cause?.message ?: e.message}")
                }
                downEvt.recycle()

                val upEvt = buildMouseEvent(displayId, MotionEvent.ACTION_UP, 100f, 100f)
                try { imgInject.invoke(img, upEvt, 2) } catch (_: Exception) {}
                upEvt.recycle()
            }
        } catch (e: Exception) {
            sb.appendLine("  FAILED: ${e.cause?.message ?: e.message}")
        }

        // 3b. Instrumentation
        sb.appendLine()
        sb.appendLine("3b. Instrumentation:")
        try {
            val instrClass = Class.forName("android.app.Instrumentation")
            val instr = instrClass.getDeclaredConstructor().newInstance()
            val send = instrClass.getDeclaredMethod("sendPointerSync", MotionEvent::class.java)
            for (displayId in listOf(0, 1)) {
                val event = buildMouseEvent(displayId, MotionEvent.ACTION_HOVER_MOVE, 200f, 200f)
                try {
                    send.invoke(instr, event)
                    sb.appendLine("  d=$displayId HOVER: OK")
                } catch (e: Exception) {
                    sb.appendLine("  d=$displayId HOVER: ${e.cause?.message ?: e.message}")
                }
                event.recycle()
            }
        } catch (e: Exception) {
            sb.appendLine("  FAILED: ${e.message}")
        }

        // 3c. Shell
        sb.appendLine()
        sb.appendLine("3c. Shell commands:")
        val shellTests = listOf(
            "input tap 100 100",
            "input -d 0 tap 100 100",
            "input -d 1 tap 100 100",
            "input motionevent DOWN 100 100",
            "input motionevent MOVE 100 200",
            "input motionevent UP 100 200",
        )
        for (cmd in shellTests) {
            val (rc, out) = execShell(cmd)
            val status = if (rc == 0 && out.isEmpty()) "OK" else "rc=$rc ${out.take(60)}"
            sb.appendLine("  [$status] $cmd")
        }

        sb.appendLine()

        // 4. /dev/uinput
        sb.appendLine("-- 4. /dev/uinput --")
        val uinputFile = File("/dev/uinput")
        sb.appendLine("exists: ${uinputFile.exists()} canWrite: ${uinputFile.canWrite()}")
        val (_, lsU) = execShell("ls -la /dev/uinput 2>&1")
        sb.appendLine(lsU)
        try {
            val fd = java.io.FileOutputStream(uinputFile)
            sb.appendLine("open for write: OK")
            fd.close()
        } catch (e: Exception) {
            sb.appendLine("open for write: ${e.message}")
        }

        sb.appendLine()

        // 5. SELinux
        sb.appendLine("-- 5. SELinux --")
        val (_, enforce) = execShell("getenforce 2>&1")
        sb.appendLine("getenforce: $enforce")
        val (_, ctx) = execShell("cat /proc/self/attr/current 2>&1")
        sb.appendLine("context: $ctx")

        sb.appendLine()

        // 6. Displays
        sb.appendLine("-- 6. Displays --")
        val (_, dispInfo) = execShell("dumpsys display | grep -E 'mDisplayId|DisplayDeviceInfo' | head -15 2>&1")
        dispInfo.lines().forEach { sb.appendLine("  ${it.trim()}") }

        sb.appendLine()
        sb.appendLine("=== END ===")
        return sb.toString()
    }

    override fun destroy() {}
}
