package com.pixeltouchpad.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.IBinder
import android.view.Display
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    companion object {
        private const val SHIZUKU_PERMISSION_CODE = 1001
    }

    private var inputService: IInputService? = null
    private var isServiceBound = false

    private var externalDisplayId: Int = -1
    private var externalWidth = 1920f
    private var externalHeight = 1080f

    // Event counters for debugging
    private var moveCount = 0L
    private var clickCount = 0L
    private var scrollCount = 0L
    private var lastError: String? = null

    private lateinit var touchpadView: TouchpadView
    private lateinit var statusText: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnDiagnose: Button
    private lateinit var btnCopy: Button
    private lateinit var btnShare: Button
    private var lastDiagnosticOutput: String? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            inputService = IInputService.Stub.asInterface(binder)
            isServiceBound = true
            runOnUiThread { onServiceReady() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            inputService = null
            isServiceBound = false
            runOnUiThread { updateStatus("Služba odpojena") }
        }
    }

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(packageName, InputService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("input")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    bindInputService()
                } else {
                    updateStatus("Shizuku oprávnění zamítnuto")
                }
            }
        }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            runOnUiThread { detectExternalDisplay() }
        }

        override fun onDisplayRemoved(displayId: Int) {
            runOnUiThread {
                if (displayId == externalDisplayId) {
                    externalDisplayId = -1
                    updateStatus("Externí displej odpojen")
                    touchpadView.visibility = View.GONE
                    btnConnect.visibility = View.VISIBLE
                }
            }
        }

        override fun onDisplayChanged(displayId: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        touchpadView = findViewById(R.id.touchpadView)
        statusText = findViewById(R.id.statusText)
        btnConnect = findViewById(R.id.btnConnect)
        btnDiagnose = findViewById(R.id.btnDiagnose)
        btnCopy = findViewById(R.id.btnCopy)
        btnShare = findViewById(R.id.btnShare)

        touchpadView.visibility = View.GONE
        btnDiagnose.visibility = View.GONE
        btnCopy.visibility = View.GONE
        btnShare.visibility = View.GONE

        btnConnect.setOnClickListener { startSetup() }
        btnDiagnose.setOnClickListener { runDiagnose() }
        btnCopy.setOnClickListener { copyDiagnostics() }
        btnShare.setOnClickListener { shareDiagnostics() }

        Shizuku.addRequestPermissionResultListener(permissionResultListener)

        val dm = getSystemService(DisplayManager::class.java)
        dm.registerDisplayListener(displayListener, null)

        // Touchpad callbacks with event counting
        touchpadView.onCursorMove = { x, y ->
            moveCount++
            try {
                inputService?.moveCursor(externalDisplayId, x, y)
            } catch (e: Exception) {
                lastError = "Move: ${e.message}"
            }
            if (moveCount % 50 == 0L) {
                runOnUiThread { updateEventCounter() }
            }
        }

        touchpadView.onClick = { x, y ->
            clickCount++
            try {
                inputService?.click(externalDisplayId, x, y)
            } catch (e: Exception) {
                lastError = "Click: ${e.message}"
            }
            runOnUiThread { updateEventCounter() }
        }

        touchpadView.onScroll = { x, y, vScroll ->
            scrollCount++
            try {
                inputService?.scroll(externalDisplayId, x, y, vScroll)
            } catch (e: Exception) {
                lastError = "Scroll: ${e.message}"
            }
            if (scrollCount % 10 == 0L) {
                runOnUiThread { updateEventCounter() }
            }
        }

        if (Shizuku.pingBinder()) {
            startSetup()
        } else {
            updateStatus("Spusť Shizuku a klikni na Připojit")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        getSystemService(DisplayManager::class.java)
            .unregisterDisplayListener(displayListener)

        if (isServiceBound) {
            try {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
            } catch (_: Exception) {}
        }
    }

    private fun startSetup() {
        if (!Shizuku.pingBinder()) {
            updateStatus("Shizuku není spuštěno!\n\nOtevři Shizuku app a spusť službu.")
            return
        }

        if (checkShizukuPermission()) {
            bindInputService()
        } else {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE)
            updateStatus("Čekám na povolení Shizuku...")
        }
    }

    private fun checkShizukuPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    private fun bindInputService() {
        updateStatus("Připojuji se k Shizuku službě...")
        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        } catch (e: Exception) {
            updateStatus("Chyba při připojení: ${e.message}")
        }
    }

    private fun onServiceReady() {
        btnDiagnose.visibility = View.VISIBLE
        updateStatus("Služba připojena. Hledám externí displej...")
        detectExternalDisplay()
    }

    private fun detectExternalDisplay() {
        val dm = getSystemService(DisplayManager::class.java)
        val displays = dm.displays

        // List all displays for debugging
        val displayInfo = displays.joinToString("\n") { d ->
            val mode = d.mode
            "  #${d.displayId}: ${d.name} (${mode.physicalWidth}×${mode.physicalHeight})"
        }

        val external = displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }

        if (external != null) {
            externalDisplayId = external.displayId
            val mode = external.mode
            externalWidth = mode.physicalWidth.toFloat()
            externalHeight = mode.physicalHeight.toFloat()

            touchpadView.displayWidth = externalWidth
            touchpadView.displayHeight = externalHeight
            touchpadView.resetCursor()

            touchpadView.visibility = View.VISIBLE
            btnConnect.visibility = View.GONE
            updateStatus(
                "Displej #$externalDisplayId (${externalWidth.toInt()}×${externalHeight.toInt()})\n" +
                "Touchpad aktivní\n\n" +
                "Pokud kurzor nereaguje, klikni Diagnostika\n\n" +
                "Nalezené displeje:\n$displayInfo"
            )
        } else {
            touchpadView.visibility = View.GONE
            btnConnect.visibility = View.VISIBLE
            updateStatus(
                "Žádný externí displej nenalezen.\n" +
                "Připoj monitor přes USB-C.\n\n" +
                "Nalezené displeje:\n$displayInfo"
            )
        }
    }

    private fun runDiagnose() {
        updateStatus("Spouštím diagnostiku...")
        Thread {
            try {
                val result = inputService?.diagnose() ?: "Služba není připojena"
                lastDiagnosticOutput = result
                runOnUiThread {
                    updateStatus(result)
                    btnCopy.visibility = View.VISIBLE
                    btnShare.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                val error = "Diagnostika selhala: ${e.message}"
                lastDiagnosticOutput = error
                runOnUiThread {
                    updateStatus(error)
                    btnCopy.visibility = View.VISIBLE
                    btnShare.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun copyDiagnostics() {
        val text = lastDiagnosticOutput ?: return
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("PixelTouchpad Diagnostics", text))
        Toast.makeText(this, "Zkopírováno do schránky", Toast.LENGTH_SHORT).show()
    }

    private fun shareDiagnostics() {
        val text = lastDiagnosticOutput ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "PixelTouchpad Diagnostics")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Sdílet diagnostiku"))
    }

    private fun updateEventCounter() {
        val err = if (lastError != null) "\nErr: $lastError" else ""
        val svc = if (inputService != null) "connected" else "NULL"
        statusText.text = "Move: $moveCount | Click: $clickCount | Scroll: $scrollCount\n" +
            "Display: #$externalDisplayId | Service: $svc$err"
    }

    private fun updateStatus(text: String) {
        statusText.text = text
    }
}
