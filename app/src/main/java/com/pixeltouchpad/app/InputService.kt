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
 * Injection strategy priority (auto-detected at init):
 * 1. InputManagerGlobal.injectInputEvent 3-arg (with targetUid)
 * 2. InputManagerGlobal.injectInputEvent 2-arg
 * 3. InputManager.getInstance() legacy
 * 4. Shell "input" commands
 */
class InputService : IInputService.Stub() {

    private val initLog = mutableListOf<String>()

    // ---- Reflection handles ----

    private var imGlobalInstance: Any? = null
    private var inject2Method: Method? = null  // (InputEvent, int)
    private var inject3Method: Method? = null  // (InputEvent, int, int) - with targetUid
    private var setDisplayIdMethod: Method? = null

    // Which inject approach to use for the external display
    private var useInject3 = false
    private var workingTargetUid = -1
    private var inject3Tested = false

    init {
        // setDisplayId (shared)
        try {
            setDisplayIdMethod = InputEvent::class.java.getDeclaredMethod(
                "setDisplayId", Int::class.javaPrimitiveType
            )
            initLog.add("✓ setDisplayId found")
        } catch (e: Exception) {
            initLog.add("✗ setDisplayId: ${e.message}")
        }

        // InputManagerGlobal
        try {
            val imgClass = Class.forName("android.hardware.input.InputManagerGlobal")
            imGlobalInstance = imgClass.getDeclaredMethod("getInstance").invoke(null)
            initLog.add("✓ InputManagerGlobal OK")

            // 2-arg
            try {
                inject2Method = imgClass.getDeclaredMethod(
                    "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType
                )
                initLog.add("✓ inject(Event, int) found")
            } catch (e: Exception) {
                initLog.add("✗ inject 2-arg: ${e.message}")
            }

            // 3-arg (with targetUid)
            try {
                inject3Method = imgClass.getDeclaredMethod(
                    "injectInputEvent",
                    InputEvent::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                initLog.add("✓ inject(Event, int, int) found")
            } catch (e: Exception) {
                initLog.add("✗ inject 3-arg: ${e.message}")
            }
        } catch (e: Exception) {
            initLog.add("✗ InputManagerGlobal: ${e.cause?.message ?: e.message}")

            // Fallback: legacy InputManager
            try {
                val imClass = InputManager::class.java
                imGlobalInstance = imClass.getDeclaredMethod("getInstance").invoke(null)
                inject2Method = imClass.getDeclaredMethod(
                    "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType
                )
                initLog.add("✓ InputManager legacy OK")
            } catch (e2: Exception) {
                initLog.add("✗ InputManager legacy: ${e2.cause?.message ?: e2.message}")
            }
        }

        initLog.add("→ inject2: ${inject2Method != null}, inject3: ${inject3Method != null}")
    }

    // ---- MotionEvent builder ----

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
        try { setDisplayIdMethod?.invoke(event, displayId) } catch (_: Exception) {}
        return event
    }

    // ---- Injection methods ----

    private fun injectEvent(displayId: Int, event: MotionEvent): Boolean {
        val im = imGlobalInstance ?: return false

        // Try 3-arg first if we know it works for this display
        if (useInject3 && inject3Method != null) {
            try {
                val r = inject3Method!!.invoke(im, event, 2, workingTargetUid) as? Boolean ?: false
                if (r) return true
            } catch (_: Exception) {}
        }

        // Try 2-arg
        if (inject2Method != null) {
            try {
                val r = inject2Method!!.invoke(im, event, 2) as? Boolean ?: false
                if (r) return true
            } catch (_: Exception) {}
        }

        // If 2-arg fails on external display and we haven't tested 3-arg yet, try all targetUids
        if (!inject3Tested && inject3Method != null && displayId != 0) {
            inject3Tested = true
            for (uid in listOf(-1, 0, 1000, 2000, 10000)) {
                try {
                    val testEvent = buildMouseEvent(displayId, MotionEvent.ACTION_HOVER_MOVE, 100f, 100f)
                    val r = inject3Method!!.invoke(im, testEvent, 2, uid) as? Boolean ?: false
                    testEvent.recycle()
                    if (r) {
                        useInject3 = true
                        workingTargetUid = uid
                        initLog.add("✓ inject3 works with targetUid=$uid on display $displayId")
                        // Re-inject the original event
                        try {
                            val r2 = inject3Method!!.invoke(im, event, 2, uid) as? Boolean ?: false
                            if (r2) return true
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
            initLog.add("✗ inject3 failed for all targetUids on display $displayId")
        }

        return false
    }

    private fun inject(
        displayId: Int,
        action: Int,
        x: Float,
        y: Float,
        buttonState: Int = 0,
        vScroll: Float = 0f
    ): Boolean {
        val event = buildMouseEvent(displayId, action, x, y, buttonState, vScroll)
        val result = injectEvent(displayId, event)
        event.recycle()
        return result
    }

    // ---- Shell fallback ----

    private fun execShell(cmd: String, timeoutMs: Long = 5000): Pair<Int, String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return Pair(-1, "TIMEOUT")
            }
            val stdout = process.inputStream.bufferedReader().readText().trim()
            val stderr = process.errorStream.bufferedReader().readText().trim()
            val output = if (stderr.isNotEmpty()) "$stdout\n$stderr".trim() else stdout
            Pair(process.exitValue(), output)
        } catch (e: Exception) {
            Pair(-1, "Exception: ${e.message}")
        }
    }

    // ---- AIDL implementation ----

    override fun moveCursor(displayId: Int, x: Float, y: Float) {
        val ok = inject(displayId, MotionEvent.ACTION_HOVER_MOVE, x, y)
        if (!ok) {
            // Shell can't do HOVER_MOVE reliably, but try mouse source
            execShell("input -d $displayId mouse motionevent 7 ${x.toInt()} ${y.toInt()}")
        }
    }

    override fun click(displayId: Int, x: Float, y: Float) {
        val downOk = inject(displayId, MotionEvent.ACTION_DOWN, x, y,
            buttonState = MotionEvent.BUTTON_PRIMARY)
        if (downOk) {
            try { Thread.sleep(16) } catch (_: Exception) {}
            inject(displayId, MotionEvent.ACTION_UP, x, y, buttonState = 0)
        } else {
            execShell("input -d $displayId tap ${x.toInt()} ${y.toInt()}")
        }
    }

    override fun scroll(displayId: Int, x: Float, y: Float, vScroll: Float) {
        inject(displayId, MotionEvent.ACTION_SCROLL, x, y, vScroll = vScroll)
    }

    // ===========================================================
    //  DIAGNOSTICS
    // ===========================================================

    override fun diagnose(): String {
        val sb = StringBuilder()
        sb.appendLine("=== PixelTouchpad Diagnostics ===")
        sb.appendLine("Time: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")

        // 1. Device
        sb.appendLine()
        sb.appendLine("-- 1. Device --")
        sb.appendLine("UID:${android.os.Process.myUid()} PID:${android.os.Process.myPid()}")
        sb.appendLine("SDK:${android.os.Build.VERSION.SDK_INT} ${android.os.Build.MODEL}")
        val (_, idOut) = execShell("id")
        sb.appendLine("id: $idOut")

        // 2. Init
        sb.appendLine()
        sb.appendLine("-- 2. Init --")
        initLog.forEach { sb.appendLine(it) }

        // 3. InputManagerGlobal inject tests
        sb.appendLine()
        sb.appendLine("-- 3. API Inject --")

        val img = imGlobalInstance
        if (img != null) {
            // 3a. 2-arg inject per display
            sb.appendLine("3a. 2-arg inject(Event, mode=2):")
            for (d in 0..2) {
                val ev = buildMouseEvent(d, MotionEvent.ACTION_HOVER_MOVE, 100f, 100f)
                val r = try { inject2Method?.invoke(img, ev, 2) } catch (e: Exception) { "ERR:${e.cause?.message}" }
                ev.recycle()
                sb.appendLine("  d=$d HOVER: $r")
            }

            // 3b. 3-arg inject with various targetUids
            if (inject3Method != null) {
                sb.appendLine()
                sb.appendLine("3b. 3-arg inject(Event, mode=2, uid):")
                for (d in 0..1) {
                    for (uid in listOf(-1, 0, 1000, 2000, 10000)) {
                        val ev = buildMouseEvent(d, MotionEvent.ACTION_HOVER_MOVE, 100f, 100f)
                        val r = try {
                            inject3Method!!.invoke(img, ev, 2, uid)
                        } catch (e: Exception) {
                            "ERR:${e.cause?.message?.take(40)}"
                        }
                        ev.recycle()
                        sb.appendLine("  d=$d uid=$uid: $r")
                    }
                }
            }

            // 3c. Try different event sources
            sb.appendLine()
            sb.appendLine("3c. Sources on d=1:")
            val sources = mapOf(
                "MOUSE" to InputDevice.SOURCE_MOUSE,
                "TOUCHSCREEN" to InputDevice.SOURCE_TOUCHSCREEN,
                "TOUCHPAD" to InputDevice.SOURCE_TOUCHPAD,
                "STYLUS" to InputDevice.SOURCE_STYLUS,
            )
            for ((name, src) in sources) {
                val now = SystemClock.uptimeMillis()
                val ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE,
                    100f, 100f, 0)
                ev.source = src
                try { setDisplayIdMethod?.invoke(ev, 1) } catch (_: Exception) {}
                val r = try { inject2Method?.invoke(img, ev, 2) } catch (e: Exception) { "ERR" }
                ev.recycle()
                sb.appendLine("  $name: $r")
            }

            // 3d. inject modes
            sb.appendLine()
            sb.appendLine("3d. Modes on d=1:")
            for (mode in 0..2) {
                val ev = buildMouseEvent(1, MotionEvent.ACTION_HOVER_MOVE, 100f, 100f)
                val r = try { inject2Method?.invoke(img, ev, mode) } catch (e: Exception) { "ERR:${e.cause?.message?.take(30)}" }
                ev.recycle()
                sb.appendLine("  mode=$mode: $r")
            }
        } else {
            sb.appendLine("No InputManager instance")
        }

        // 4. Shell
        sb.appendLine()
        sb.appendLine("-- 4. Shell --")
        val shellTests = listOf(
            "input -d 0 tap 100 100",
            "input -d 1 tap 100 100",
            "input -d 1 motionevent DOWN 100 100",
            "input -d 1 motionevent MOVE 100 200",
            "input -d 1 motionevent UP 100 200",
            "input -d 1 mouse motionevent 7 100 100",
            "input -d 1 mouse motionevent DOWN 100 100",
            "input -d 1 mouse motionevent MOVE 100 200",
            "input -d 1 mouse motionevent UP 100 200",
            "input -d 1 mouse tap 100 100",
        )
        for (cmd in shellTests) {
            val (rc, out) = execShell(cmd)
            val s = if (rc == 0 && out.isEmpty()) "OK" else "rc=$rc ${out.take(50)}"
            sb.appendLine("  [$s] $cmd")
        }

        // 5. /dev/uinput
        sb.appendLine()
        sb.appendLine("-- 5. /dev/uinput --")
        val uf = File("/dev/uinput")
        sb.appendLine("exists:${uf.exists()} canWrite:${uf.canWrite()}")
        try {
            val fd = java.io.FileOutputStream(uf)
            sb.appendLine("open: OK")
            fd.close()
        } catch (e: Exception) {
            sb.appendLine("open: ${e.message}")
        }

        // 6. Displays
        sb.appendLine()
        sb.appendLine("-- 6. Displays --")
        val (_, di) = execShell("dumpsys display | grep -E 'mDisplayId|uniqueId.*local' | head -10 2>&1")
        di.lines().forEach { sb.appendLine("  ${it.trim()}") }

        sb.appendLine()
        sb.appendLine("=== END ===")
        return sb.toString()
    }

    override fun destroy() {}
}
