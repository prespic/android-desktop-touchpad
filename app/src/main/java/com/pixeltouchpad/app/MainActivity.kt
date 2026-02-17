package com.pixeltouchpad.app

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.IBinder
import android.view.Display
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    companion object {
        private const val SHIZUKU_PERMISSION_CODE = 1001
    }

    // Shizuku service
    private var inputService: IInputService? = null
    private var isServiceBound = false

    // Display info
    private var externalDisplayId: Int = -1
    private var externalWidth = 1920f
    private var externalHeight = 1080f

    // Views
    private lateinit var touchpadView: TouchpadView
    private lateinit var statusText: TextView
    private lateinit var btnConnect: Button

    // ---- Shizuku UserService connection ----

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

    // ---- Shizuku permission listener ----

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

    // ---- Display listener ----

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

    // ---- Lifecycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        touchpadView = findViewById(R.id.touchpadView)
        statusText = findViewById(R.id.statusText)
        btnConnect = findViewById(R.id.btnConnect)

        touchpadView.visibility = View.GONE

        btnConnect.setOnClickListener { startSetup() }

        // Register listeners
        Shizuku.addRequestPermissionResultListener(permissionResultListener)

        val dm = getSystemService(DisplayManager::class.java)
        dm.registerDisplayListener(displayListener, null)

        // Wire touchpad callbacks
        touchpadView.onCursorMove = { x, y ->
            try {
                inputService?.moveCursor(externalDisplayId, x, y)
            } catch (_: Exception) {
            }
        }

        touchpadView.onClick = { x, y ->
            try {
                inputService?.click(externalDisplayId, x, y)
            } catch (_: Exception) {
            }
        }

        touchpadView.onScroll = { x, y, vScroll ->
            try {
                inputService?.scroll(externalDisplayId, x, y, vScroll)
            } catch (_: Exception) {
            }
        }

        // Auto-start if Shizuku is already running
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
            } catch (_: Exception) {
            }
        }
    }

    // ---- Setup flow ----

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
        updateStatus("Služba připojena. Hledám externí displej...")
        detectExternalDisplay()
    }

    // ---- Display detection ----

    private fun detectExternalDisplay() {
        val dm = getSystemService(DisplayManager::class.java)
        val displays = dm.displays

        val external = displays.firstOrNull { display ->
            display.displayId != Display.DEFAULT_DISPLAY
        }

        if (external != null) {
            externalDisplayId = external.displayId

            // Get display size
            val mode = external.mode
            externalWidth = mode.physicalWidth.toFloat()
            externalHeight = mode.physicalHeight.toFloat()

            // Configure touchpad
            touchpadView.displayWidth = externalWidth
            touchpadView.displayHeight = externalHeight
            touchpadView.resetCursor()

            // Show touchpad
            touchpadView.visibility = View.VISIBLE
            btnConnect.visibility = View.GONE
            updateStatus(
                "Displej #$externalDisplayId (${externalWidth.toInt()}×${externalHeight.toInt()})\n" +
                        "Touchpad aktivní"
            )
        } else {
            externalDisplayId = -1
            touchpadView.visibility = View.GONE
            btnConnect.visibility = View.VISIBLE
            updateStatus(
                "Služba běží, ale žádný externí displej nenalezen.\n\n" +
                        "Připoj monitor přes USB-C kabel."
            )
        }
    }

    // ---- UI helpers ----

    private fun updateStatus(text: String) {
        statusText.text = text
    }
}
