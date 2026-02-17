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
 */
class InputService : IInputService.Stub() {

    private val initLog = mutableListOf<String>()

    // ---- Reflection handles ----
    private var imGlobalInstance: Any? = null
    private var inject2Method: Method? = null  // (InputEvent, int mode)
    private var inject3Method: Method? = null  // (InputEvent, int mode, int targetUid)
    private var setDisplayIdMethod: Method? = null

    // Discovered working configuration
    private var workingMode = 0  // 0=WAIT_FOR_RESULT (works!), 2=ASYNC
    private var workingUid = -1  // for 3-arg inject
    private var useInject3 = false
    private var configDiscovered = false

    init {
        try {
            setDisplayIdMethod = InputEvent::class.java.getDeclaredMethod(
                "setDisplayId", Int::class.javaPrimitiveType
            )
            initLog.add("✓ setDisplayId")
        } catch (e: Exception) {
            initLog.add("✗ setDisplayId: ${e.message}")
        }

        // InputManagerGlobal
        try {
            val imgClass = Class.forName("android.hardware.input.InputManagerGlobal")
            imGlobalInstance = imgClass.getDeclaredMethod("getInstance").invoke(null)
            initLog.add("✓ InputManagerGlobal")

            try {
                inject2Method = imgClass.getDeclaredMethod(
                    "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType
                )
                initLog.add("✓ inject2")
            } catch (_: Exception) {}

            try {
                inject3Method = imgClass.getDeclaredMethod(
                    "injectInputEvent", InputEvent::class.java,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                )
                initLog.add("✓ inject3")
            } catch (_: Exception) {}
        } catch (e: Exception) {
            initLog.add("✗ InputManagerGlobal: ${e.cause?.message ?: e.message}")
            // Legacy fallback
            try {
                imGlobalInstance = InputManager::class.java
                    .getDeclaredMethod("getInstance").invoke(null)
                inject2Method = InputManager::class.java.getDeclaredMethod(
                    "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType
                )
                initLog.add("✓ InputManager legacy")
            } catch (e2: Exception) {
                initLog.add("✗ legacy: ${e2.cause?.message ?: e2.message}")
            }
        }
    }

    // ---- MotionEvent builder ----

    private fun buildMouseEvent(
        displayId: Int, action: Int, x: Float, y: Float,
        buttonState: Int = 0, vScroll: Float = 0f
    ): MotionEvent {
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y
            pressure = if (action == MotionEvent.ACTION_HOVER_MOVE ||
                action == MotionEvent.ACTION_SCROLL) 0f else 1f
            size = 1f
            if (vScroll != 0f) setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
        })
        val event = MotionEvent.obtain(now, now, action, 1, props, coords,
            0, buttonState, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        try { setDisplayIdMethod?.invoke(event, displayId) } catch (_: Exception) {}
        return event
    }

    // ---- Injection ----

    private fun tryInject(event: MotionEvent, mode: Int): Boolean {
        val im = imGlobalInstance ?: return false
        return try {
            val r = inject2Method?.invoke(im, event, mode) as? Boolean ?: false
            r
        } catch (_: Exception) { false }
    }

    private fun tryInject3(event: MotionEvent, mode: Int, uid: Int): Boolean {
        val im = imGlobalInstance ?: return false
        return try {
            val r = inject3Method?.invoke(im, event, mode, uid) as? Boolean ?: false
            r
        } catch (_: Exception) { false }
    }

    /**
     * Auto-discover working inject configuration on first call to external display.
     */
    private fun discoverConfig(displayId: Int) {
        if (configDiscovered) return
        configDiscovered = true

        // Try 2-arg with different modes
        for (mode in listOf(0, 2, 1)) {
            val ev = buildMouseEvent(displayId, MotionEvent.ACTION_HOVER_MOVE, 100f, 100f)
            val ok = tryInject(ev, mode)
            ev.recycle()
            if (ok) {
                workingMode = mode
                useInject3 = false
                initLog.add("✓ FOUND: inject2 mode=$mode on d=$displayId")
                return
            }
        }

        // Try 3-arg with different modes and UIDs
        if (inject3Method != null) {
            for (mode in listOf(0, 2)) {
                for (uid in listOf(-1, 0, 1000, 2000)) {
                    val ev = buildMouseEvent(displayId, MotionEvent.ACTION_HOVER_MOVE, 100f, 100f)
                    val ok = tryInject3(ev, mode, uid)
                    ev.recycle()
                    if (ok) {
                        workingMode = mode
                        workingUid = uid
                        useInject3 = true
                        initLog.add("✓ FOUND: inject3 mode=$mode uid=$uid on d=$displayId")
                        return
                    }
                }
            }
        }

        initLog.add("✗ No working inject config for d=$displayId")
    }

    private fun inject(
        displayId: Int, action: Int, x: Float, y: Float,
        buttonState: Int = 0, vScroll: Float = 0f
    ): Boolean {
        discoverConfig(displayId)
        val event = buildMouseEvent(displayId, action, x, y, buttonState, vScroll)
        val ok = if (useInject3) {
            tryInject3(event, workingMode, workingUid)
        } else {
            tryInject(event, workingMode)
        }
        event.recycle()
        return ok
    }

    // ---- Shell fallback ----

    private fun execShell(cmd: String, timeoutMs: Long = 5000): Pair<Int, String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) { process.destroyForcibly(); return Pair(-1, "TIMEOUT") }
            val out = process.inputStream.bufferedReader().readText().trim()
            val err = process.errorStream.bufferedReader().readText().trim()
            Pair(process.exitValue(), if (err.isNotEmpty()) "$out\n$err".trim() else out)
        } catch (e: Exception) { Pair(-1, "Exception: ${e.message}") }
    }

    // ---- AIDL ----

    override fun moveCursor(displayId: Int, x: Float, y: Float) {
        inject(displayId, MotionEvent.ACTION_HOVER_MOVE, x, y)
    }

    override fun click(displayId: Int, x: Float, y: Float) {
        val ok = inject(displayId, MotionEvent.ACTION_DOWN, x, y,
            buttonState = MotionEvent.BUTTON_PRIMARY)
        if (ok) {
            try { Thread.sleep(16) } catch (_: Exception) {}
            inject(displayId, MotionEvent.ACTION_UP, x, y, buttonState = 0)
        } else {
            execShell("input -d $displayId tap ${x.toInt()} ${y.toInt()}")
        }
    }

    override fun scroll(displayId: Int, x: Float, y: Float, vScroll: Float) {
        inject(displayId, MotionEvent.ACTION_SCROLL, x, y, vScroll = vScroll)
    }

    // ---- DIAGNOSTICS ----

    override fun diagnose(externalDisplayId: Int): String {
        val sb = StringBuilder()
        sb.appendLine("=== Diagnostics ===")
        sb.appendLine("SDK:${android.os.Build.VERSION.SDK_INT} ${android.os.Build.MODEL}")
        sb.appendLine("UID:${android.os.Process.myUid()} PID:${android.os.Process.myPid()}")
        sb.appendLine("External displayId: $externalDisplayId")
        sb.appendLine()

        // Init
        sb.appendLine("-- Init --")
        initLog.forEach { sb.appendLine(it) }
        sb.appendLine()

        // Test actual display IDs
        val testDisplays = listOf(0, externalDisplayId)
        sb.appendLine("-- 2-arg inject per display & mode --")
        for (d in testDisplays) {
            for (mode in 0..2) {
                val ev = buildMouseEvent(d, MotionEvent.ACTION_HOVER_MOVE, 100f, 100f)
                val r = tryInject(ev, mode)
                ev.recycle()
                sb.appendLine("  d=$d mode=$mode HOVER: $r")
            }
        }

        sb.appendLine()
        sb.appendLine("-- 3-arg inject (d=$externalDisplayId) --")
        if (inject3Method != null) {
            for (mode in listOf(0, 2)) {
                for (uid in listOf(-1, 0, 1000, 2000, 10000)) {
                    val ev = buildMouseEvent(externalDisplayId, MotionEvent.ACTION_HOVER_MOVE, 100f, 100f)
                    val r = try {
                        inject3Method!!.invoke(imGlobalInstance, ev, mode, uid)
                    } catch (e: Exception) { "ERR:${e.cause?.message?.take(30)}" }
                    ev.recycle()
                    sb.appendLine("  mode=$mode uid=$uid: $r")
                }
            }
        } else {
            sb.appendLine("  inject3 not available")
        }

        // Test DOWN too
        sb.appendLine()
        sb.appendLine("-- DOWN/UP on d=$externalDisplayId --")
        for (mode in 0..2) {
            val down = buildMouseEvent(externalDisplayId, MotionEvent.ACTION_DOWN, 500f, 500f,
                buttonState = MotionEvent.BUTTON_PRIMARY)
            val r = tryInject(down, mode)
            down.recycle()
            sb.appendLine("  mode=$mode DOWN: $r")
            if (r) {
                val up = buildMouseEvent(externalDisplayId, MotionEvent.ACTION_UP, 500f, 500f)
                tryInject(up, mode)
                up.recycle()
            }
        }

        // Shell on actual display
        sb.appendLine()
        sb.appendLine("-- Shell (d=$externalDisplayId) --")
        val cmds = listOf(
            "input -d $externalDisplayId tap 100 100",
            "input -d $externalDisplayId motionevent DOWN 100 100",
            "input -d $externalDisplayId motionevent MOVE 200 200",
            "input -d $externalDisplayId motionevent UP 200 200",
        )
        for (cmd in cmds) {
            val (rc, out) = execShell(cmd)
            val s = if (rc == 0 && out.isEmpty()) "OK" else "rc=$rc ${out.take(50)}"
            sb.appendLine("  [$s] $cmd")
        }

        // /dev/uinput
        sb.appendLine()
        sb.appendLine("-- /dev/uinput --")
        val uf = File("/dev/uinput")
        sb.appendLine("exists:${uf.exists()} canWrite:${uf.canWrite()}")

        // Current config
        sb.appendLine()
        sb.appendLine("-- Active config --")
        sb.appendLine("configDiscovered: $configDiscovered")
        sb.appendLine("useInject3: $useInject3")
        sb.appendLine("workingMode: $workingMode")
        sb.appendLine("workingUid: $workingUid")

        sb.appendLine()
        sb.appendLine("=== END ===")
        return sb.toString()
    }

    override fun destroy() {}
}
