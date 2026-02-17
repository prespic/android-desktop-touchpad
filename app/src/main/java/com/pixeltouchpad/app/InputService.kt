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
 * Comprehensive input injection with multiple fallback strategies.
 */
class InputService : IInputService.Stub() {

    private var useShellFallback = false
    private val initLog = mutableListOf<String>()

    // ---- InputManager reflection ----

    private var imInstance: Any? = null
    private var injectMethod: Method? = null
    private var setDisplayIdMethod: Method? = null

    init {
        try {
            imInstance = InputManager::class.java
                .getDeclaredMethod("getInstance")
                .invoke(null)
            initLog.add("✓ InputManager.getInstance() OK")
        } catch (e: Exception) {
            initLog.add("✗ InputManager.getInstance(): ${e.cause?.message ?: e.message}")
            useShellFallback = true
        }

        try {
            injectMethod = InputManager::class.java.getDeclaredMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            initLog.add("✓ injectInputEvent method found")
        } catch (e: Exception) {
            initLog.add("✗ injectInputEvent: ${e.message}")
            useShellFallback = true
        }

        try {
            setDisplayIdMethod = InputEvent::class.java.getDeclaredMethod(
                "setDisplayId",
                Int::class.javaPrimitiveType
            )
            initLog.add("✓ setDisplayId method found")
        } catch (e: Exception) {
            initLog.add("✗ setDisplayId: ${e.message}")
        }

        initLog.add(if (useShellFallback) "→ Using SHELL fallback" else "→ Using InputManager API")
    }

    // ---- Strategy: InputManager.injectInputEvent (reflection) ----

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

        return try {
            val result = inject.invoke(im, event, 2)
            event.recycle()
            result as? Boolean ?: false
        } catch (e: Exception) {
            event.recycle()
            false
        }
    }

    // ---- Strategy: Shell commands ----

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

    private fun shellMove(displayId: Int, x: Float, y: Float) {
        execShell("input -d $displayId motionevent HOVER_MOVE ${x.toInt()} ${y.toInt()}")
    }

    private fun shellTap(displayId: Int, x: Float, y: Float) {
        execShell("input -d $displayId tap ${x.toInt()} ${y.toInt()}")
    }

    // ---- AIDL implementation ----

    override fun moveCursor(displayId: Int, x: Float, y: Float) {
        if (!useShellFallback) {
            val ok = injectViaInputManager(displayId, MotionEvent.ACTION_HOVER_MOVE, x, y)
            if (!ok) {
                useShellFallback = true
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
    }

    // ===========================================================
    //  COMPREHENSIVE DIAGNOSTICS
    // ===========================================================

    override fun diagnose(): String {
        val sb = StringBuilder()
        sb.appendLine("=== PixelTouchpad Full Diagnostics ===")
        sb.appendLine("Timestamp: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        sb.appendLine()

        // ---- 1. Process info ----
        sb.appendLine("--- 1. Process Info ---")
        sb.appendLine("UID: ${android.os.Process.myUid()}")
        sb.appendLine("PID: ${android.os.Process.myPid()}")
        val (_, idOut) = execShell("id")
        sb.appendLine("id: $idOut")
        val (_, whoami) = execShell("whoami")
        sb.appendLine("whoami: $whoami")
        sb.appendLine("Build.VERSION.SDK_INT: ${android.os.Build.VERSION.SDK_INT}")
        sb.appendLine("Build.VERSION.RELEASE: ${android.os.Build.VERSION.RELEASE}")
        sb.appendLine("Build.DEVICE: ${android.os.Build.DEVICE}")
        sb.appendLine("Build.MODEL: ${android.os.Build.MODEL}")
        sb.appendLine()

        // ---- 2. Init log ----
        sb.appendLine("--- 2. Init Log ---")
        initLog.forEach { sb.appendLine(it) }
        sb.appendLine()

        // ---- 3. InputManager strategies ----
        sb.appendLine("--- 3. InputManager API Tests ---")

        // 3a. getInstance (already tried in init)
        sb.appendLine("3a. InputManager.getInstance(): ${if (imInstance != null) "OK (${imInstance!!::class.java.name})" else "FAILED"}")

        // 3b. Try alternative: InputManager via hidden InputManagerGlobal
        sb.appendLine()
        sb.appendLine("3b. InputManagerGlobal:")
        try {
            val imgClass = Class.forName("android.hardware.input.InputManagerGlobal")
            val getInstanceMethod = imgClass.getDeclaredMethod("getInstance")
            val imgInstance = getInstanceMethod.invoke(null)
            sb.appendLine("  getInstance(): OK (${imgInstance::class.java.name})")

            // Try to find inject method on this class
            val methods = imgClass.declaredMethods.filter { it.name.contains("inject", ignoreCase = true) }
            sb.appendLine("  inject methods: ${methods.map { "${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }})" }}")

            // Also list all public methods for discovery
            val allMethods = imgClass.declaredMethods.map { it.name }.distinct().sorted()
            sb.appendLine("  all methods: $allMethods")
        } catch (e: Exception) {
            sb.appendLine("  FAILED: ${e.message}")
        }

        // 3c. Try Instrumentation
        sb.appendLine()
        sb.appendLine("3c. Instrumentation:")
        try {
            val instrClass = Class.forName("android.app.Instrumentation")
            val instr = instrClass.getDeclaredConstructor().newInstance()
            sb.appendLine("  Created Instrumentation instance: OK")

            // Try sendPointerSync
            val sendMethod = instrClass.getDeclaredMethod("sendPointerSync", MotionEvent::class.java)
            sb.appendLine("  sendPointerSync method: found")

            val now = SystemClock.uptimeMillis()
            val testEvent = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE,
                100f, 100f, 0)
            try {
                sendMethod.invoke(instr, testEvent)
                sb.appendLine("  sendPointerSync(HOVER_MOVE 100,100): OK (no exception)")
            } catch (e: Exception) {
                sb.appendLine("  sendPointerSync(HOVER_MOVE 100,100): ${e.cause?.message ?: e.message}")
            }
            testEvent.recycle()
        } catch (e: Exception) {
            sb.appendLine("  FAILED: ${e.message}")
        }

        // 3d. inject on display 0
        sb.appendLine()
        sb.appendLine("3d. injectInputEvent tests:")
        for (displayId in listOf(0, 1, 2)) {
            val result = injectViaInputManager(displayId, MotionEvent.ACTION_HOVER_MOVE, 100f, 100f)
            sb.appendLine("  display=$displayId HOVER_MOVE(100,100): $result")
        }

        // 3e. inject with different modes (0=WAIT_FOR_RESULT, 1=WAIT_FOR_FINISH, 2=ASYNC)
        sb.appendLine()
        sb.appendLine("3e. inject modes (display 0):")
        val im = imInstance
        val inject = injectMethod
        if (im != null && inject != null) {
            for (mode in 0..2) {
                try {
                    val now = SystemClock.uptimeMillis()
                    val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE,
                        100f, 100f, 0)
                    event.source = InputDevice.SOURCE_MOUSE
                    try { setDisplayIdMethod?.invoke(event, 0) } catch (_: Exception) {}
                    val result = inject.invoke(im, event, mode)
                    event.recycle()
                    sb.appendLine("  mode=$mode: $result")
                } catch (e: Exception) {
                    sb.appendLine("  mode=$mode: EXCEPTION ${e.cause?.message ?: e.message}")
                }
            }
        } else {
            sb.appendLine("  SKIPPED (no InputManager)")
        }

        sb.appendLine()

        // ---- 4. Shell command tests ----
        sb.appendLine("--- 4. Shell Command Tests ---")

        // 4a. basic input command help
        val (rc0, helpOut) = execShell("input --help 2>&1 | head -20")
        sb.appendLine("4a. input --help (rc=$rc0):")
        helpOut.lines().take(10).forEach { sb.appendLine("  $it") }

        // 4b. input tap on display 0
        sb.appendLine()
        sb.appendLine("4b. Shell input commands:")

        val shellTests = listOf(
            "input tap 100 100",
            "input -d 0 tap 100 100",
            "input -d 1 tap 100 100",
            "input -d 2 tap 100 100",
            "input motionevent DOWN 100 100",
            "input motionevent HOVER_MOVE 100 100",
            "input -d 0 motionevent HOVER_MOVE 100 100",
            "input -d 1 motionevent HOVER_MOVE 100 100",
            "input mouse tap 100 100",
            "input -d 0 mouse tap 100 100",
            "input -d 1 mouse tap 100 100",
        )

        for (cmd in shellTests) {
            val (rc, out) = execShell(cmd)
            val status = if (rc == 0 && out.isEmpty()) "OK" else "rc=$rc ${out.take(80)}"
            sb.appendLine("  [$status] $cmd")
        }

        sb.appendLine()

        // ---- 5. /dev/uinput access ----
        sb.appendLine("--- 5. /dev/uinput Access ---")

        val uinputPath = "/dev/uinput"
        val uinputFile = File(uinputPath)
        sb.appendLine("exists: ${uinputFile.exists()}")
        sb.appendLine("canRead: ${uinputFile.canRead()}")
        sb.appendLine("canWrite: ${uinputFile.canWrite()}")

        val (_, lsUinput) = execShell("ls -la /dev/uinput 2>&1")
        sb.appendLine("ls -la: $lsUinput")

        val (_, statUinput) = execShell("stat /dev/uinput 2>&1")
        sb.appendLine("stat: ${statUinput.take(200)}")

        // Try to actually open it
        sb.appendLine()
        sb.appendLine("Open test:")
        try {
            val fd = java.io.FileOutputStream(uinputFile)
            sb.appendLine("  FileOutputStream: OK (opened for write)")
            fd.close()
            sb.appendLine("  Closed OK")
        } catch (e: Exception) {
            sb.appendLine("  FileOutputStream: FAILED - ${e.message}")
        }

        // Check /dev/input/ devices
        sb.appendLine()
        val (_, inputDevices) = execShell("ls -la /dev/input/ 2>&1")
        sb.appendLine("Input devices (/dev/input/):")
        inputDevices.lines().forEach { sb.appendLine("  $it") }

        sb.appendLine()

        // ---- 6. SELinux ----
        sb.appendLine("--- 6. SELinux ---")
        val (_, getenforce) = execShell("getenforce 2>&1")
        sb.appendLine("getenforce: $getenforce")

        val (_, secontext) = execShell("cat /proc/self/attr/current 2>&1")
        sb.appendLine("SE context: $secontext")

        val (_, seUinput) = execShell("ls -Z /dev/uinput 2>&1")
        sb.appendLine("uinput context: $seUinput")

        sb.appendLine()

        // ---- 7. Available permissions / capabilities ----
        sb.appendLine("--- 7. Capabilities ---")
        val (_, capOut) = execShell("cat /proc/self/status | grep -i cap 2>&1")
        sb.appendLine(capOut)

        sb.appendLine()

        // ---- 8. dumpsys input (truncated) ----
        sb.appendLine("--- 8. Input Devices (dumpsys) ---")
        val (_, dumpsys) = execShell("dumpsys input | head -60 2>&1")
        dumpsys.lines().take(40).forEach { sb.appendLine("  $it") }

        sb.appendLine()

        // ---- 9. Display info via dumpsys ----
        sb.appendLine("--- 9. Display Info ---")
        val (_, displayDumpsys) = execShell("dumpsys display | grep -E 'mDisplayId|mBaseDisplayInfo|DisplayDeviceInfo' | head -20 2>&1")
        displayDumpsys.lines().forEach { sb.appendLine("  $it") }

        sb.appendLine()

        // ---- 10. wm commands ----
        sb.appendLine("--- 10. Window Manager ---")
        val (_, wmSize) = execShell("wm size 2>&1")
        sb.appendLine("wm size: $wmSize")
        val (_, wmDensity) = execShell("wm density 2>&1")
        sb.appendLine("wm density: $wmDensity")

        sb.appendLine()
        sb.appendLine("=== END ===")
        return sb.toString()
    }

    override fun destroy() {}
}
