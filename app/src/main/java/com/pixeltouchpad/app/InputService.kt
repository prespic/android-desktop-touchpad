package com.pixeltouchpad.app

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Shizuku UserService - runs with shell (ADB) privileges.
 *
 * Tries multiple strategies to move the system cursor:
 * 1. /dev/uhid virtual HID mouse
 * 2. sendevent shell commands to the uhid event device
 * 3. uinput virtual mouse (alternative kernel API)
 */
class InputService : IInputService.Stub() {

    private val initLog = mutableListOf<String>()

    // ---- Strategy selection ----
    private enum class Strategy { UHID, SENDEVENT, NONE }
    private var activeStrategy = Strategy.NONE

    // ---- UHID virtual mouse ----
    private var uhidFd: FileOutputStream? = null
    private var uhidReady = false
    private var uhidEventDev: String? = null  // e.g. "/dev/input/event9"

    // ---- Counters for debugging ----
    @Volatile private var moveCallCount = 0L
    @Volatile private var reportSentCount = 0L
    @Volatile private var lastSendError: String? = null

    // Track previous absolute position to compute deltas
    @Volatile private var prevX = Float.NaN
    @Volatile private var prevY = Float.NaN

    companion object {
        private const val UHID_CREATE2 = 11
        private const val UHID_INPUT2 = 12
        private const val UHID_DESTROY = 1
        private const val BUS_VIRTUAL: Short = 0x06
        private const val CREATE2_REQ_SIZE = 4372
        private const val UHID_EVENT_HEADER = 4

        // HID Report Descriptor: 3-button relative mouse with wheel
        private val HID_MOUSE_DESCRIPTOR = byteArrayOf(
            0x05, 0x01,                   // Usage Page (Generic Desktop)
            0x09, 0x02,                   // Usage (Mouse)
            0xA1.toByte(), 0x01,          // Collection (Application)
            0x09, 0x01,                   //   Usage (Pointer)
            0xA1.toByte(), 0x00,          //   Collection (Physical)
            0x05, 0x09,                   //     Usage Page (Button)
            0x19, 0x01,                   //     Usage Minimum (1)
            0x29, 0x03,                   //     Usage Maximum (3)
            0x15, 0x00,                   //     Logical Minimum (0)
            0x25, 0x01,                   //     Logical Maximum (1)
            0x95.toByte(), 0x03,          //     Report Count (3)
            0x75, 0x01,                   //     Report Size (1)
            0x81.toByte(), 0x02,          //     Input (Data,Var,Abs)
            0x95.toByte(), 0x01,          //     Report Count (1)
            0x75, 0x05,                   //     Report Size (5)
            0x81.toByte(), 0x01,          //     Input (Cnst)
            0x05, 0x01,                   //     Usage Page (Generic Desktop)
            0x09, 0x30,                   //     Usage (X)
            0x09, 0x31,                   //     Usage (Y)
            0x09, 0x38,                   //     Usage (Wheel)
            0x15, 0x81.toByte(),          //     Logical Minimum (-127)
            0x25, 0x7F,                   //     Logical Maximum (127)
            0x75, 0x08,                   //     Report Size (8)
            0x95.toByte(), 0x03,          //     Report Count (3)
            0x81.toByte(), 0x06,          //     Input (Data,Var,Rel)
            0xC0.toByte(),                //   End Collection
            0xC0.toByte()                 // End Collection
        )

        // Linux input event types/codes for sendevent
        private const val EV_REL = 2
        private const val EV_KEY = 1
        private const val EV_SYN = 0
        private const val REL_X = 0
        private const val REL_Y = 1
        private const val REL_WHEEL = 8
        private const val BTN_LEFT = 272
        private const val SYN_REPORT = 0
    }

    init {
        try {
            val uhidFile = File("/dev/uhid")
            if (uhidFile.exists() && uhidFile.canWrite()) {
                uhidFd = FileOutputStream(uhidFile)
                createUhidDevice()
                initLog.add("UHID: device created, uhidReady=$uhidReady")

                // Find our event device
                uhidEventDev = findUhidEventDevice()
                initLog.add("UHID: eventDev=$uhidEventDev")

                if (uhidReady) {
                    activeStrategy = Strategy.UHID
                    initLog.add("Strategy: UHID (direct fd write)")
                }
            } else {
                initLog.add("UHID: /dev/uhid not writable")
            }

            // If UHID created a device but we found its event path, try sendevent as fallback
            if (activeStrategy == Strategy.NONE && uhidEventDev != null) {
                activeStrategy = Strategy.SENDEVENT
                initLog.add("Strategy: SENDEVENT (shell, dev=$uhidEventDev)")
            }

            if (activeStrategy == Strategy.NONE) {
                initLog.add("Strategy: NONE - no working input method found")
            }
        } catch (e: Exception) {
            initLog.add("Init error: ${e.message}")
        }
    }

    private fun createUhidDevice() {
        val fd = uhidFd ?: return
        val totalSize = UHID_EVENT_HEADER + CREATE2_REQ_SIZE
        val buf = ByteBuffer.allocate(totalSize)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(UHID_CREATE2)

        val name = "PixelTouchpad Mouse".toByteArray(Charsets.UTF_8)
        buf.put(name, 0, minOf(name.size, 127))
        buf.position(UHID_EVENT_HEADER + 128)       // skip name
        buf.position(UHID_EVENT_HEADER + 128 + 64)  // skip phys
        buf.position(UHID_EVENT_HEADER + 128 + 64 + 64) // skip uniq
        buf.putShort(HID_MOUSE_DESCRIPTOR.size.toShort()) // rd_size
        buf.putShort(BUS_VIRTUAL)  // bus
        buf.putInt(0x1234)         // vendor
        buf.putInt(0x5678)         // product
        buf.putInt(1)              // version
        buf.putInt(0)              // country
        buf.put(HID_MOUSE_DESCRIPTOR) // rd_data

        fd.write(buf.array())
        Thread.sleep(300) // give kernel time to register

        uhidReady = true
    }

    /**
     * Find the /dev/input/eventN device created by our UHID device
     * by scanning /sys/class/input/
     */
    private fun findUhidEventDevice(): String? {
        try {
            val inputDir = File("/sys/class/input")
            if (!inputDir.exists()) return null

            for (entry in inputDir.listFiles() ?: emptyArray()) {
                if (!entry.name.startsWith("event")) continue
                val nameFile = File(entry, "device/name")
                if (nameFile.exists()) {
                    val devName = nameFile.readText().trim()
                    if (devName == "PixelTouchpad Mouse") {
                        return "/dev/input/${entry.name}"
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback: try shell
        val (rc, out) = execShell("for d in /sys/class/input/event*; do " +
                "name=\$(cat \$d/device/name 2>/dev/null); " +
                "if [ \"\$name\" = 'PixelTouchpad Mouse' ]; then " +
                "echo /dev/input/\$(basename \$d); fi; done")
        if (rc == 0 && out.startsWith("/dev/input/event")) {
            return out.lines().first().trim()
        }
        return null
    }

    // ---- UHID report writing ----

    @Synchronized
    private fun sendMouseReport(buttons: Int, dx: Int, dy: Int, wheel: Int = 0) {
        val fd = uhidFd ?: return
        if (!uhidReady) return

        val buf = ByteBuffer.allocate(10)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(UHID_INPUT2)
        buf.putShort(4)
        buf.put(buttons.toByte())
        buf.put(dx.coerceIn(-127, 127).toByte())
        buf.put(dy.coerceIn(-127, 127).toByte())
        buf.put(wheel.coerceIn(-127, 127).toByte())

        fd.write(buf.array())
        reportSentCount++
    }

    private fun uhidMove(dx: Float, dy: Float) {
        var rx = dx
        var ry = dy
        while (kotlin.math.abs(rx) > 0.5f || kotlin.math.abs(ry) > 0.5f) {
            val sx = rx.coerceIn(-127f, 127f).toInt()
            val sy = ry.coerceIn(-127f, 127f).toInt()
            sendMouseReport(0, sx, sy)
            rx -= sx
            ry -= sy
        }
    }

    // ---- sendevent strategy ----

    private fun sendeventMove(dx: Int, dy: Int) {
        val dev = uhidEventDev ?: return
        val cdx = dx.coerceIn(-127, 127)
        val cdy = dy.coerceIn(-127, 127)
        // sendevent expects type code value as decimal
        val cmd = "sendevent $dev $EV_REL $REL_X $cdx && " +
                "sendevent $dev $EV_REL $REL_Y $cdy && " +
                "sendevent $dev $EV_SYN $SYN_REPORT 0"
        execShell(cmd, 1000)
        reportSentCount++
    }

    private fun sendeventClick(button: Int) {
        val dev = uhidEventDev ?: return
        execShell("sendevent $dev $EV_KEY $button 1 && sendevent $dev $EV_SYN $SYN_REPORT 0", 1000)
        Thread.sleep(16)
        execShell("sendevent $dev $EV_KEY $button 0 && sendevent $dev $EV_SYN $SYN_REPORT 0", 1000)
    }

    private fun sendeventScroll(amount: Int) {
        val dev = uhidEventDev ?: return
        execShell("sendevent $dev $EV_REL $REL_WHEEL $amount && " +
                "sendevent $dev $EV_SYN $SYN_REPORT 0", 1000)
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

    // ---- AIDL implementation ----

    override fun moveCursor(displayId: Int, x: Float, y: Float) {
        try {
            moveCallCount++
            val px = prevX
            val py = prevY
            prevX = x
            prevY = y

            if (px.isNaN()) return // first call, just set position

            val dx = x - px
            val dy = y - py
            if (kotlin.math.abs(dx) < 0.1f && kotlin.math.abs(dy) < 0.1f) return

            when (activeStrategy) {
                Strategy.UHID -> uhidMove(dx, dy)
                Strategy.SENDEVENT -> sendeventMove(dx.toInt(), dy.toInt())
                Strategy.NONE -> {}
            }
        } catch (e: Exception) {
            lastSendError = "move: ${e.message}"
        }
    }

    override fun click(displayId: Int, x: Float, y: Float) {
        try {
            when (activeStrategy) {
                Strategy.UHID -> {
                    sendMouseReport(1, 0, 0)
                    Thread.sleep(16)
                    sendMouseReport(0, 0, 0)
                }
                Strategy.SENDEVENT -> sendeventClick(BTN_LEFT)
                Strategy.NONE -> execShell("input -d $displayId tap ${x.toInt()} ${y.toInt()}")
            }
        } catch (e: Exception) {
            lastSendError = "click: ${e.message}"
        }
    }

    override fun scroll(displayId: Int, x: Float, y: Float, vScroll: Float) {
        try {
            val amount = vScroll.toInt().coerceIn(-10, 10)
            when (activeStrategy) {
                Strategy.UHID -> sendMouseReport(0, 0, 0, amount)
                Strategy.SENDEVENT -> sendeventScroll(amount)
                Strategy.NONE -> {}
            }
        } catch (e: Exception) {
            lastSendError = "scroll: ${e.message}"
        }
    }

    // ---- DIAGNOSTICS ----

    override fun diagnose(externalDisplayId: Int): String {
        val sb = StringBuilder()
        sb.appendLine("=== Diagnostics ===")
        sb.appendLine("SDK:${android.os.Build.VERSION.SDK_INT} ${android.os.Build.MODEL}")
        sb.appendLine("UID:${android.os.Process.myUid()} PID:${android.os.Process.myPid()}")
        sb.appendLine("External displayId: $externalDisplayId")
        sb.appendLine("Active strategy: $activeStrategy")
        sb.appendLine()

        // Counters
        sb.appendLine("-- Counters --")
        sb.appendLine("moveCursor calls: $moveCallCount")
        sb.appendLine("reports sent: $reportSentCount")
        sb.appendLine("prevX: $prevX prevY: $prevY")
        sb.appendLine("lastSendError: $lastSendError")
        sb.appendLine()

        // Init log
        sb.appendLine("-- Init --")
        initLog.forEach { sb.appendLine(it) }
        sb.appendLine()

        // UHID status
        sb.appendLine("-- UHID Status --")
        sb.appendLine("uhidFd: ${if (uhidFd != null) "open" else "null"}")
        sb.appendLine("uhidReady: $uhidReady")
        sb.appendLine("uhidEventDev: $uhidEventDev")
        sb.appendLine()

        // Find our device via /sys
        sb.appendLine("-- Device search via /sys --")
        val (_, sysSearch) = execShell(
            "for d in /sys/class/input/event*; do " +
            "echo \"\$(basename \$d): \$(cat \$d/device/name 2>/dev/null)\"; done"
        )
        sysSearch.lines().forEach { sb.appendLine("  $it") }
        sb.appendLine()

        // getevent device list
        sb.appendLine("-- getevent -p (our device) --")
        val devPath = uhidEventDev
        if (devPath != null) {
            val (_, geInfo) = execShell("getevent -p $devPath 2>&1")
            geInfo.lines().take(20).forEach { sb.appendLine("  $it") }
        } else {
            sb.appendLine("  No event device found")
        }
        sb.appendLine()

        // Test 1: UHID direct write + getevent capture
        sb.appendLine("-- Test 1: UHID write + getevent verify --")
        if (uhidReady && devPath != null) {
            try {
                // Start getevent in background, send report, capture output
                val (rc, captured) = execShell(
                    "timeout 1 getevent -c 4 $devPath 2>&1 &" +
                    "GE_PID=\$!; sleep 0.1; " +
                    // We'll send the report from Kotlin, so just wait and read
                    "wait \$GE_PID 2>/dev/null; echo EXIT:\$?", 3000
                )
                // Send a uhid report while getevent might be listening
                sendMouseReport(0, 50, 0)
                Thread.sleep(100)
                sendMouseReport(0, 0, 50)
                sb.appendLine("  UHID write: OK")
                sb.appendLine("  getevent capture: $captured")
            } catch (e: Exception) {
                sb.appendLine("  Error: ${e.message}")
            }
        } else {
            sb.appendLine("  Skip (uhidReady=$uhidReady, devPath=$devPath)")
        }
        sb.appendLine()

        // Test 2: sendevent
        sb.appendLine("-- Test 2: sendevent --")
        if (devPath != null) {
            // Send EV_REL X=50
            val (rc1, o1) = execShell("sendevent $devPath $EV_REL $REL_X 50 2>&1")
            sb.appendLine("  REL_X 50: rc=$rc1 $o1")
            val (rc2, o2) = execShell("sendevent $devPath $EV_REL $REL_Y 50 2>&1")
            sb.appendLine("  REL_Y 50: rc=$rc2 $o2")
            val (rc3, o3) = execShell("sendevent $devPath $EV_SYN $SYN_REPORT 0 2>&1")
            sb.appendLine("  SYN_REPORT: rc=$rc3 $o3")
        } else {
            sb.appendLine("  Skip (no event device)")
        }
        sb.appendLine()

        // Test 3: getevent after shell sendevent
        sb.appendLine("-- Test 3: getevent read after sendevent --")
        if (devPath != null) {
            val (_, ge) = execShell(
                "(sendevent $devPath $EV_REL $REL_X 30 && " +
                "sendevent $devPath $EV_REL $REL_Y 30 && " +
                "sendevent $devPath $EV_SYN $SYN_REPORT 0) && " +
                "timeout 0.5 getevent -c 3 -l $devPath 2>&1 || echo 'timeout/no events'", 3000
            )
            ge.lines().forEach { sb.appendLine("  $it") }
        }
        sb.appendLine()

        // Test 4: shell input commands
        sb.appendLine("-- Test 4: shell input commands --")
        for (cmd in listOf(
            "input mouse move 50 0",
            "input -d $externalDisplayId mouse move 50 0",
            "input touchscreen swipe 500 500 550 500 50"
        )) {
            val (rc, out) = execShell(cmd + " 2>&1", 3000)
            sb.appendLine("  `$cmd` → rc=$rc ${out.take(100)}")
        }
        sb.appendLine()

        // Test 5: permissions check
        sb.appendLine("-- Test 5: permissions --")
        if (devPath != null) {
            val (_, ls) = execShell("ls -la $devPath 2>&1")
            sb.appendLine("  $ls")
            val (_, lsz) = execShell("ls -Z $devPath 2>&1")
            sb.appendLine("  context: $lsz")
            // Can we open it for write?
            val evFile = File(devPath)
            sb.appendLine("  Java canRead: ${evFile.canRead()} canWrite: ${evFile.canWrite()}")
        }
        sb.appendLine()

        // Test 6: UHID write with bigger buffer
        sb.appendLine("-- Test 6: UHID write (full buffer) --")
        if (uhidReady) {
            try {
                val fd = uhidFd!!
                // Write UHID_INPUT2 with full-size buffer (type + input2_req)
                // input2_req = size(2) + data(4096) = 4098
                val fullBuf = ByteBuffer.allocate(4 + 4098)
                fullBuf.order(ByteOrder.LITTLE_ENDIAN)
                fullBuf.putInt(UHID_INPUT2)
                fullBuf.putShort(4)  // report size
                fullBuf.put(0)       // buttons
                fullBuf.put(80.toByte()) // X
                fullBuf.put(0)       // Y
                fullBuf.put(0)       // wheel
                // rest is zero (already allocated as zeros)
                synchronized(this) {
                    fd.write(fullBuf.array())
                }
                sb.appendLine("  Full buffer write: OK")
                Thread.sleep(100)
                // And another one
                fullBuf.clear()
                fullBuf.order(ByteOrder.LITTLE_ENDIAN)
                fullBuf.putInt(UHID_INPUT2)
                fullBuf.putShort(4)
                fullBuf.put(0)
                fullBuf.put(0)
                fullBuf.put(80.toByte()) // Y move
                fullBuf.put(0)
                synchronized(this) {
                    fd.write(fullBuf.array())
                }
                sb.appendLine("  Full buffer write Y: OK")
            } catch (e: Exception) {
                sb.appendLine("  Error: ${e.message}")
            }
        }
        sb.appendLine()

        // All input devices listing
        sb.appendLine("-- Input devices (ls) --")
        val (_, devs) = execShell("ls -la /dev/input/ 2>&1")
        devs.lines().forEach { sb.appendLine("  $it") }

        sb.appendLine()
        sb.appendLine("=== END ===")
        return sb.toString()
    }

    override fun destroy() {
        try {
            if (uhidReady) {
                val buf = ByteBuffer.allocate(4)
                buf.order(ByteOrder.LITTLE_ENDIAN)
                buf.putInt(UHID_DESTROY)
                uhidFd?.write(buf.array())
            }
            uhidFd?.close()
        } catch (_: Exception) {}
        uhidFd = null
        uhidReady = false
    }
}
