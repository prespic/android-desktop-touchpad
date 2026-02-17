package com.pixeltouchpad.app

import android.view.MotionEvent
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Shizuku UserService - runs with shell (ADB) privileges.
 *
 * Uses /dev/uhid to create a virtual HID mouse device.
 * This is the only reliable way to move the system cursor
 * in Android desktop mode — injected MotionEvents only
 * deliver to windows but don't move the pointer.
 */
class InputService : IInputService.Stub() {

    private val initLog = mutableListOf<String>()

    // ---- UHID virtual mouse ----

    private var uhidFd: FileOutputStream? = null
    private var uhidReady = false

    // Track previous absolute position to compute deltas
    private var prevX = Float.NaN
    private var prevY = Float.NaN

    companion object {
        // UHID event types
        private const val UHID_CREATE2 = 11
        private const val UHID_INPUT2 = 12
        private const val UHID_DESTROY = 1

        private const val BUS_VIRTUAL: Short = 0x06

        // Total size of uhid_create2_req struct
        // name(128) + phys(64) + uniq(64) + rd_size(2) + bus(2) + vendor(4)
        // + product(4) + version(4) + country(4) + rd_data(4096) = 4372
        private const val CREATE2_REQ_SIZE = 4372
        private const val UHID_EVENT_HEADER = 4  // uint32 type

        // HID Report Descriptor: 3-button relative mouse with wheel
        // Report format: [buttons(1)] [X(1)] [Y(1)] [wheel(1)] = 4 bytes
        private val HID_MOUSE_DESCRIPTOR = byteArrayOf(
            0x05, 0x01,                   // Usage Page (Generic Desktop)
            0x09, 0x02,                   // Usage (Mouse)
            0xA1.toByte(), 0x01,          // Collection (Application)
            0x09, 0x01,                   //   Usage (Pointer)
            0xA1.toByte(), 0x00,          //   Collection (Physical)
            // Buttons
            0x05, 0x09,                   //     Usage Page (Button)
            0x19, 0x01,                   //     Usage Minimum (1)
            0x29, 0x03,                   //     Usage Maximum (3)
            0x15, 0x00,                   //     Logical Minimum (0)
            0x25, 0x01,                   //     Logical Maximum (1)
            0x95.toByte(), 0x03,          //     Report Count (3)
            0x75, 0x01,                   //     Report Size (1)
            0x81.toByte(), 0x02,          //     Input (Data,Var,Abs)
            // Padding (5 bits)
            0x95.toByte(), 0x01,          //     Report Count (1)
            0x75, 0x05,                   //     Report Size (5)
            0x81.toByte(), 0x01,          //     Input (Cnst)
            // X, Y, Wheel (relative)
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
    }

    init {
        // Try to create UHID mouse
        try {
            val uhidFile = File("/dev/uhid")
            if (uhidFile.exists() && uhidFile.canWrite()) {
                uhidFd = FileOutputStream(uhidFile)
                createUhidDevice()
                initLog.add("✓ /dev/uhid opened")
            } else {
                initLog.add("✗ /dev/uhid not writable")
                // Try /dev/uinput as alternative check
                val uinputFile = File("/dev/uinput")
                initLog.add("  /dev/uinput exists:${uinputFile.exists()} canWrite:${uinputFile.canWrite()}")
            }
        } catch (e: Exception) {
            initLog.add("✗ uhid init: ${e.message}")
        }
    }

    private fun createUhidDevice() {
        val fd = uhidFd ?: return

        // Build UHID_CREATE2 event
        val totalSize = UHID_EVENT_HEADER + CREATE2_REQ_SIZE
        val buf = ByteBuffer.allocate(totalSize)
        buf.order(ByteOrder.LITTLE_ENDIAN)

        // Event type
        buf.putInt(UHID_CREATE2)

        // name (128 bytes, null-padded)
        val name = "PixelTouchpad Mouse".toByteArray(Charsets.UTF_8)
        buf.put(name, 0, minOf(name.size, 127))
        buf.position(UHID_EVENT_HEADER + 128)

        // phys (64 bytes) - skip
        buf.position(UHID_EVENT_HEADER + 128 + 64)

        // uniq (64 bytes) - skip
        buf.position(UHID_EVENT_HEADER + 128 + 64 + 64)

        // rd_size (uint16)
        buf.putShort(HID_MOUSE_DESCRIPTOR.size.toShort())

        // bus (uint16)
        buf.putShort(BUS_VIRTUAL)

        // vendor (uint32)
        buf.putInt(0x1234)

        // product (uint32)
        buf.putInt(0x5678)

        // version (uint32)
        buf.putInt(1)

        // country (uint32)
        buf.putInt(0)

        // rd_data (4096 bytes, copy descriptor + zero padding)
        buf.put(HID_MOUSE_DESCRIPTOR)
        // Rest is already zeroed by ByteBuffer.allocate

        fd.write(buf.array())

        // Give kernel time to register the device
        Thread.sleep(200)

        uhidReady = true
        initLog.add("✓ UHID mouse device created")
    }

    @Synchronized
    private fun sendMouseReport(buttons: Int, dx: Int, dy: Int, wheel: Int = 0) {
        val fd = uhidFd ?: return
        if (!uhidReady) return

        // UHID_INPUT2: type(4) + size(2) + data(4 bytes for mouse)
        val buf = ByteBuffer.allocate(10)
        buf.order(ByteOrder.LITTLE_ENDIAN)

        buf.putInt(UHID_INPUT2)
        buf.putShort(4) // report size
        buf.put(buttons.toByte())
        buf.put(dx.coerceIn(-127, 127).toByte())
        buf.put(dy.coerceIn(-127, 127).toByte())
        buf.put(wheel.coerceIn(-127, 127).toByte())

        fd.write(buf.array())
    }

    /**
     * Move cursor by relative amount. Handles deltas > 127 by sending multiple reports.
     */
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

    private fun uhidClick(button: Int = 1) {
        sendMouseReport(button, 0, 0)
        try { Thread.sleep(16) } catch (_: Exception) {}
        sendMouseReport(0, 0, 0)
    }

    private fun uhidScroll(amount: Int) {
        sendMouseReport(0, 0, 0, amount)
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

    @Synchronized
    override fun moveCursor(displayId: Int, x: Float, y: Float) {
        if (uhidReady) {
            if (!prevX.isNaN()) {
                val dx = x - prevX
                val dy = y - prevY
                if (kotlin.math.abs(dx) > 0.1f || kotlin.math.abs(dy) > 0.1f) {
                    uhidMove(dx, dy)
                }
            }
            prevX = x
            prevY = y
        }
    }

    @Synchronized
    override fun click(displayId: Int, x: Float, y: Float) {
        if (uhidReady) {
            uhidClick(1) // left button
        } else {
            execShell("input -d $displayId tap ${x.toInt()} ${y.toInt()}")
        }
    }

    @Synchronized
    override fun scroll(displayId: Int, x: Float, y: Float, vScroll: Float) {
        if (uhidReady) {
            uhidScroll(vScroll.toInt().coerceIn(-10, 10))
        }
    }

    // ---- DIAGNOSTICS ----

    override fun diagnose(externalDisplayId: Int): String {
        val sb = StringBuilder()
        sb.appendLine("=== Diagnostics ===")
        sb.appendLine("SDK:${android.os.Build.VERSION.SDK_INT} ${android.os.Build.MODEL}")
        sb.appendLine("UID:${android.os.Process.myUid()} PID:${android.os.Process.myPid()}")
        sb.appendLine("External displayId: $externalDisplayId")
        sb.appendLine()

        sb.appendLine("-- Init --")
        initLog.forEach { sb.appendLine(it) }
        sb.appendLine()

        sb.appendLine("-- UHID Status --")
        sb.appendLine("uhidFd: ${if (uhidFd != null) "open" else "null"}")
        sb.appendLine("uhidReady: $uhidReady")
        sb.appendLine("prevX: $prevX prevY: $prevY")

        // Check /dev/uhid
        sb.appendLine()
        sb.appendLine("-- /dev/uhid --")
        val uhidFile = File("/dev/uhid")
        sb.appendLine("exists: ${uhidFile.exists()}")
        sb.appendLine("canRead: ${uhidFile.canRead()}")
        sb.appendLine("canWrite: ${uhidFile.canWrite()}")
        val (_, lsUhid) = execShell("ls -la /dev/uhid 2>&1")
        sb.appendLine(lsUhid)
        val (_, lsZUhid) = execShell("ls -Z /dev/uhid 2>&1")
        sb.appendLine("context: $lsZUhid")

        // Check /dev/uinput too
        sb.appendLine()
        sb.appendLine("-- /dev/uinput --")
        val uinputFile = File("/dev/uinput")
        sb.appendLine("exists: ${uinputFile.exists()} canWrite: ${uinputFile.canWrite()}")

        // Check if our device appeared
        sb.appendLine()
        sb.appendLine("-- Input devices --")
        val (_, devs) = execShell("ls -la /dev/input/ 2>&1")
        devs.lines().forEach { sb.appendLine("  $it") }

        // Check for our device in /proc/bus/input/devices
        sb.appendLine()
        sb.appendLine("-- Registered HID devices --")
        val (_, hidDevs) = execShell("cat /proc/bus/input/devices 2>&1 | grep -A3 'PixelTouchpad'")
        if (hidDevs.isNotEmpty()) {
            sb.appendLine(hidDevs)
        } else {
            sb.appendLine("  PixelTouchpad device NOT found in /proc/bus/input/devices")
            // Show last few entries for debug
            val (_, lastDevs) = execShell("cat /proc/bus/input/devices 2>&1 | tail -20")
            sb.appendLine("  (last entries:)")
            lastDevs.lines().forEach { sb.appendLine("  $it") }
        }

        // Test live mouse report
        sb.appendLine()
        sb.appendLine("-- Live test --")
        if (uhidReady) {
            try {
                sendMouseReport(0, 10, 0)  // small move right
                sb.appendLine("Sent move(10,0): OK")
                Thread.sleep(50)
                sendMouseReport(0, 0, 10)  // small move down
                sb.appendLine("Sent move(0,10): OK")
            } catch (e: Exception) {
                sb.appendLine("Send failed: ${e.message}")
            }
        } else {
            sb.appendLine("UHID not ready, skipping live test")
        }

        sb.appendLine()
        sb.appendLine("=== END ===")
        return sb.toString()
    }

    @Synchronized
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
