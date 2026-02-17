package com.pixeltouchpad.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.IBinder
import android.view.Display
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    companion object {
        private const val SHIZUKU_PERMISSION_CODE = 1001
        private const val PREFS_NAME = "touchpad_prefs"
        private const val KEY_CURSOR_SENSITIVITY = "cursor_sensitivity"
        private const val KEY_SCROLL_SENSITIVITY = "scroll_sensitivity"
        private const val DEFAULT_CURSOR_SENS = 1.5f
        private const val DEFAULT_SCROLL_SENS = 0.08f
    }

    private var inputService: IInputService? = null
    private var isServiceBound = false

    private var externalDisplayId: Int = -1
    private var externalWidth = 1920f
    private var externalHeight = 1080f

    private var moveCount = 0L
    private var clickCount = 0L
    private var scrollCount = 0L
    private var lastError: String? = null

    private lateinit var prefs: SharedPreferences
    private lateinit var touchpadView: TouchpadView
    private lateinit var statusText: TextView
    private lateinit var setupPanel: View
    private lateinit var btnConnect: Button
    private lateinit var btnSettings: ImageButton

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            inputService = IInputService.Stub.asInterface(binder)
            isServiceBound = true
            runOnUiThread { onServiceReady() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            inputService = null
            isServiceBound = false
            runOnUiThread {
                updateStatus("Služba odpojena")
                showSetupPanel()
            }
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
                    showSetupPanel()
                }
            }
        }

        override fun onDisplayChanged(displayId: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        touchpadView = findViewById(R.id.touchpadView)
        statusText = findViewById(R.id.statusText)
        setupPanel = findViewById(R.id.setupPanel)
        btnConnect = findViewById(R.id.btnConnect)
        btnSettings = findViewById(R.id.btnSettings)

        touchpadView.visibility = View.GONE
        btnSettings.visibility = View.GONE

        // Load persisted sensitivity
        touchpadView.sensitivity = prefs.getFloat(KEY_CURSOR_SENSITIVITY, DEFAULT_CURSOR_SENS)
        touchpadView.scrollSensitivity = prefs.getFloat(KEY_SCROLL_SENSITIVITY, DEFAULT_SCROLL_SENS)

        btnConnect.setOnClickListener { startSetup() }
        btnSettings.setOnClickListener { openSettings() }

        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        val dm = getSystemService(DisplayManager::class.java)
        dm.registerDisplayListener(displayListener, null)

        // --- Touchpad callbacks ---

        touchpadView.onCursorMove = { x, y ->
            moveCount++
            try { inputService?.moveCursor(externalDisplayId, x, y) }
            catch (e: Exception) { lastError = "Move: ${e.message}" }
            if (moveCount % 50 == 0L) runOnUiThread { updateEventCounter() }
        }

        touchpadView.onClick = { x, y ->
            clickCount++
            try { inputService?.click(externalDisplayId, x, y) }
            catch (e: Exception) { lastError = "Click: ${e.message}" }
            runOnUiThread { updateEventCounter() }
        }

        touchpadView.onRightClick = { x, y ->
            try { inputService?.rightClick(externalDisplayId, x, y) }
            catch (e: Exception) { lastError = "RClick: ${e.message}" }
        }

        touchpadView.onScroll = { x, y, vScroll ->
            scrollCount++
            try { inputService?.scroll(externalDisplayId, x, y, vScroll) }
            catch (e: Exception) { lastError = "Scroll: ${e.message}" }
            if (scrollCount % 10 == 0L) runOnUiThread { updateEventCounter() }
        }

        touchpadView.onPinchZoom = { zoomDelta ->
            // Convert pinch to scroll events (zoom = Ctrl+scroll in most apps)
            // Positive delta = fingers apart = zoom in = scroll up
            val scrollAmount = if (zoomDelta > 0) 1f else -1f
            try { inputService?.scroll(externalDisplayId, 0f, 0f, scrollAmount) }
            catch (e: Exception) { lastError = "Zoom: ${e.message}" }
        }

        touchpadView.onDragStart = {
            try { inputService?.startDrag(externalDisplayId) }
            catch (e: Exception) { lastError = "DragStart: ${e.message}" }
        }

        touchpadView.onDragEnd = {
            try { inputService?.endDrag(externalDisplayId) }
            catch (e: Exception) { lastError = "DragEnd: ${e.message}" }
        }

        touchpadView.onThreeFingerSwipe = { direction ->
            try {
                when (direction) {
                    TouchpadView.SwipeDirection.LEFT ->
                        inputService?.sendKeyEvent(externalDisplayId, 4)     // KEYCODE_BACK
                    TouchpadView.SwipeDirection.RIGHT ->
                        inputService?.sendKeyEvent(externalDisplayId, 187)   // KEYCODE_APP_SWITCH
                    TouchpadView.SwipeDirection.UP ->
                        inputService?.sendKeyEvent(externalDisplayId, 284)   // KEYCODE_ALL_APPS
                    TouchpadView.SwipeDirection.DOWN ->
                        inputService?.sendShellCommand(externalDisplayId, "cmd statusbar expand-notifications")
                }
            } catch (e: Exception) {
                lastError = "Swipe: ${e.message}"
            }
        }

        if (Shizuku.pingBinder()) {
            startSetup()
        } else {
            updateStatus("Spusť Shizuku a klikni na Připojit")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && touchpadView.visibility == View.VISIBLE) {
            enableFullscreenMode()
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

    private fun enableFullscreenMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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
        } catch (_: Exception) { false }
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

    private fun detectExternalDisplay() {
        val dm = getSystemService(DisplayManager::class.java)
        val displays = dm.displays

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

            showTouchpad()
        } else {
            showSetupPanel()
            updateStatus(
                "Žádný externí displej nenalezen.\n" +
                "Připoj monitor přes USB-C.\n\n" +
                "Nalezené displeje:\n$displayInfo"
            )
        }
    }

    private fun showTouchpad() {
        touchpadView.visibility = View.VISIBLE
        setupPanel.visibility = View.GONE
        btnSettings.visibility = View.VISIBLE
        enableFullscreenMode()
    }

    private fun showSetupPanel() {
        touchpadView.visibility = View.GONE
        setupPanel.visibility = View.VISIBLE
        btnSettings.visibility = View.GONE
    }

    private fun openSettings() {
        val sheet = SettingsBottomSheet.newInstance(
            touchpadView.sensitivity,
            touchpadView.scrollSensitivity
        )

        sheet.onSensitivityChanged = { value ->
            touchpadView.sensitivity = value
            prefs.edit().putFloat(KEY_CURSOR_SENSITIVITY, value).apply()
        }

        sheet.onScrollSensitivityChanged = { value ->
            touchpadView.scrollSensitivity = value
            prefs.edit().putFloat(KEY_SCROLL_SENSITIVITY, value).apply()
        }

        sheet.onDiagnoseClicked = {
            sheet.dismiss()
            runDiagnose()
        }

        sheet.onDisconnectClicked = {
            showSetupPanel()
        }

        sheet.show(supportFragmentManager, "settings")
    }

    private fun runDiagnose() {
        // Show setup panel for diagnostic output
        showSetupPanel()
        updateStatus("Spouštím diagnostiku...")
        Thread {
            try {
                val result = inputService?.diagnose(externalDisplayId) ?: "Služba není připojena"
                runOnUiThread { updateStatus(result) }
            } catch (e: Exception) {
                runOnUiThread { updateStatus("Diagnostika selhala: ${e.message}") }
            }
        }.start()
    }

    private fun updateEventCounter() {
        // Only show in setup panel (if visible)
    }

    private fun updateStatus(text: String) {
        statusText.text = text
    }
}
