package org.las2mile.scrcpy

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import org.las2mile.scrcpy.protocol.ControlMessage
import java.util.Locale

class ScreenMirrorActivity : Activity(),
    Scrcpy.ServiceCallbacks, SensorEventListener, SurfaceHolder.Callback {

    companion object {
        private const val TAG = "ScreenMirrorActivity"
        private const val REQUEST_CODE_PICK_FILE = 1001

        const val EXTRA_SERVER_ADR = "server_adr"
        const val EXTRA_SERVER_PORT = "server_port"
        const val EXTRA_SCREEN_WIDTH = "screen_width"
        const val EXTRA_SCREEN_HEIGHT = "screen_height"
        const val EXTRA_AUDIO_ENABLED = "audio_enabled"
        const val EXTRA_NAV = "nav"
        const val EXTRA_NO_CONTROL = "no_control"
        const val EXTRA_SCREEN_OFF = "screen_off"
        const val EXTRA_SYNC_ROTATION = "sync_rotation"
        const val EXTRA_UHID = "extra_uhid"
    }

    private var serverAdr: String? = null
    private var serverPort = 7007
    private var screenWidth = 0
    private var screenHeight = 0
    private var audioEnabled = false
    private var nav = true
    private var noControl = false
    private var screenOff = false
    private var syncRotation = true
    private var uhidEnabled = false

    private var surfaceView: SurfaceView? = null
    private var surface: Surface? = null
    private var containerLayout: ConstraintLayout? = null
    private var remoteInputView: RemoteInputView? = null
    private var isSoftKeyboardShowing = false
    private val pressedHidKeys = LinkedHashSet<Int>()
    private var lastMouseX = 0f
    private var lastMouseY = 0f
    private var hasLastMousePos = false
    @Volatile
    private var scrcpy: Scrcpy? = null
    @Volatile
    private var serviceBound = false
    private var isFirstBind = true
    private var backTimestamp: Long = 0

    private var remoteDeviceWidth = 0f
    private var remoteDeviceHeight = 0f
    @Volatile
    private var landscape = false

    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null

    // Screenshot & Recording
    private val screenRecorder = ScreenRecorder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recordTimerRunnable: Runnable? = null
    private var recordStartMs: Long = 0

    // Floating Tool Button & Radial Menu
    private var radialMenuLayout: RadialMenuLayout? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            scrcpy = (binder as? Scrcpy.MyServiceBinder)?.service
            val s = scrcpy ?: return
            s.setServiceCallbacks(this@ScreenMirrorActivity)
            serviceBound = true

            if (isFirstBind) {
                s.start(surface, serverAdr, serverPort, screenHeight, screenWidth, audioEnabled)

                Thread({
                    var count = 60
                    while (count > 0) {
                        val currentScrcpy = scrcpy
                        if (currentScrcpy == null || !serviceBound) break
                        if (currentScrcpy.check_socket_connection()) break
                        count--
                        try {
                            Thread.sleep(100)
                        } catch (e: InterruptedException) {
                            break
                        }
                    }
                    val finalCount = count
                    runOnUiThread {
                        if (finalCount == 0) {
                            stopScrcpyService()
                            Toast.makeText(this@ScreenMirrorActivity, getString(R.string.msg_connection_timeout), Toast.LENGTH_SHORT).show()
                            finish()
                        } else {
                            scrcpy?.let { activeScrcpy ->
                                val res = activeScrcpy.get_remote_device_resolution()
                                if (res.size >= 2) {
                                    remoteDeviceWidth = res[0].toFloat()
                                    remoteDeviceHeight = res[1].toFloat()
                                }
                                isFirstBind = false
                                if (uhidEnabled) {
                                    activeScrcpy.initUhid()
                                }
                                if (screenOff) {
                                    activeScrcpy.setDisplayPower(false)
                                }
                            }
                            adjustSurfaceLayout()
                        }
                    }
                }, "WaitSocketConnect").start()
            } else {
                s.setParms(surface, screenWidth, screenHeight)
                adjustSurfaceLayout()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            scrcpy = null
        }
    }

    private val usbDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (UsbManager.ACTION_USB_DEVICE_DETACHED == intent?.action) {
                Log.d(TAG, "USB device detached")
                onDisconnected()
            }
        }
    }

    private var orientationEventListener: OrientationEventListener? = null
    private var lastPhysicalLandscape: Boolean? = null
    private var lastRotationSyncTime: Long = 0

    @Synchronized
    private fun checkAndSyncRotation(targetLandscape: Boolean) {
        if (!syncRotation || scrcpy == null) return
        val now = SystemClock.uptimeMillis()
        if (now - lastRotationSyncTime < 1000) return
        if (remoteDeviceWidth <= 0 || remoteDeviceHeight <= 0) return
        val remoteLandscape = remoteDeviceWidth > remoteDeviceHeight
        if (targetLandscape != remoteLandscape) {
            lastRotationSyncTime = now
            Log.d(TAG, "Syncing remote rotation: targetLandscape=$targetLandscape, remoteLandscape=$remoteLandscape")
            scrcpy?.rotateDevice()
        }
    }

    @SuppressLint("SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val i = intent
        serverAdr = i.getStringExtra(EXTRA_SERVER_ADR)
        serverPort = i.getIntExtra(EXTRA_SERVER_PORT, 7007)
        screenWidth = i.getIntExtra(EXTRA_SCREEN_WIDTH, 1280)
        screenHeight = i.getIntExtra(EXTRA_SCREEN_HEIGHT, 720)
        audioEnabled = i.getBooleanExtra(EXTRA_AUDIO_ENABLED, false)
        nav = i.getBooleanExtra(EXTRA_NAV, true)
        noControl = i.getBooleanExtra(EXTRA_NO_CONTROL, false)
        screenOff = i.getBooleanExtra(EXTRA_SCREEN_OFF, false)
        syncRotation = i.getBooleanExtra(EXTRA_SYNC_ROTATION, true)
        uhidEnabled = i.getBooleanExtra(EXTRA_UHID, false)

        setContentView(R.layout.surface)
        applyFullscreen()

        surfaceView = findViewById(R.id.decoder_surface)
        containerLayout = findViewById(R.id.container1)
        radialMenuLayout = findViewById(R.id.radial_menu_layout)
        remoteInputView = findViewById(R.id.remote_input_view)

        setupRemoteInputView()

        radialMenuLayout?.let { menu ->
            menu.visibility = if (nav || !noControl) View.VISIBLE else View.GONE
            menu.setOnMenuActionListener { actionId ->
                when (actionId) {
                    RadialMenuLayout.ACTION_SCREENSHOT -> captureFullscreenScreenshot()
                    RadialMenuLayout.ACTION_RECORD -> handleRecordPullTab()
                    RadialMenuLayout.ACTION_FILE_TRANSFER -> pickFilesToTransfer()
                    RadialMenuLayout.ACTION_KEYBOARD -> handleKeyboardAction()
                    RadialMenuLayout.ACTION_POWER -> scrcpy?.sendKeyevent(KeyEvent.KEYCODE_POWER)
                    RadialMenuLayout.ACTION_VOL_UP -> scrcpy?.sendKeyevent(KeyEvent.KEYCODE_VOLUME_UP)
                    RadialMenuLayout.ACTION_VOL_DOWN -> scrcpy?.sendKeyevent(KeyEvent.KEYCODE_VOLUME_DOWN)
                    RadialMenuLayout.ACTION_ROTATE -> scrcpy?.rotateDevice()
                    RadialMenuLayout.ACTION_APPS -> scrcpy?.sendKeyevent(KeyEvent.KEYCODE_APP_SWITCH)
                    RadialMenuLayout.ACTION_HOME -> scrcpy?.sendKeyevent(KeyEvent.KEYCODE_HOME)
                    RadialMenuLayout.ACTION_BACK -> scrcpy?.sendKeyevent(KeyEvent.KEYCODE_BACK)
                }
            }
        }

        surfaceView?.holder?.addCallback(this)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        if (syncRotation) {
            orientationEventListener = object : OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
                override fun onOrientationChanged(orientation: Int) {
                    if (orientation == ORIENTATION_UNKNOWN) return
                    val isLandscape = (orientation in 60..120) || (orientation in 240..300)
                    val isPortrait = (orientation >= 330 || orientation <= 30) || (orientation in 150..210)

                    if (isLandscape && lastPhysicalLandscape != true) {
                        lastPhysicalLandscape = true
                        checkAndSyncRotation(true)
                    } else if (isPortrait && lastPhysicalLandscape != false) {
                        lastPhysicalLandscape = false
                        checkAndSyncRotation(false)
                    }
                }
            }
            if (orientationEventListener?.canDetectOrientation() == true) {
                orientationEventListener?.enable()
            }
        }

        val usbFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbDetachReceiver, usbFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbDetachReceiver, usbFilter)
        }
    }

    override fun onResume() {
        super.onResume()
        applyFullscreen()
        if (orientationEventListener?.canDetectOrientation() == true) {
            orientationEventListener?.enable()
        }
        if (!isFirstBind && serviceBound && scrcpy != null) {
            scrcpy?.resume()
            scrcpy?.resetVideo()
            registerProximity()
        }
    }

    override fun onPause() {
        super.onPause()
        orientationEventListener?.disable()
        sensorManager?.unregisterListener(this)
        if (isFinishing && serviceBound && scrcpy != null) {
            scrcpy?.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        orientationEventListener?.disable()
        orientationEventListener = null
        sensorManager?.unregisterListener(this)
        try {
            unregisterReceiver(usbDetachReceiver)
        } catch (ignored: Exception) {}
        if (uhidEnabled) {
            try {
                scrcpy?.destroyUhid()
            } catch (ignored: Exception) {}
        }
        stopScrcpyService()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val currentLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        checkAndSyncRotation(currentLandscape)
        containerLayout?.post { adjustSurfaceLayout() }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
        val intent = Intent(this, Scrcpy::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surface = holder.surface
        if (scrcpy != null && surface != null && surface!!.isValid) {
            scrcpy?.setSurface(surface)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surface = null
    }

    override fun loadNewRotation() {
        val s = scrcpy ?: return
        val res = s.get_remote_device_resolution()
        if (res.size < 2) return
        remoteDeviceWidth = res[0].toFloat()
        remoteDeviceHeight = res[1].toFloat()
        landscape = remoteDeviceWidth > remoteDeviceHeight

        containerLayout?.post { adjustSurfaceLayout() }
    }

    override fun onDisconnected() {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                Toast.makeText(this@ScreenMirrorActivity, R.string.msg_device_disconnected, Toast.LENGTH_SHORT).show()
                stopScrcpyService()
                finish()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchAndNav() {
        if (!noControl && surfaceView != null) {
            surfaceView?.setOnTouchListener { _, event ->
                val isMouse = event.isFromSource(InputDevice.SOURCE_MOUSE) ||
                        event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
                if (uhidEnabled && isMouse) {
                    handleUhidMouseEvent(event)
                    return@setOnTouchListener true
                }
                val sv = surfaceView ?: return@setOnTouchListener false
                scrcpy?.touchevent(event, sv.width, sv.height) ?: false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                surfaceView?.setOnCapturedPointerListener { _, event ->
                    if (uhidEnabled && handleUhidMouseEvent(event)) {
                        true
                    } else false
                }
            }
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val isMouse = event.isFromSource(InputDevice.SOURCE_MOUSE) ||
                event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
        if (uhidEnabled && isMouse) {
            if (handleUhidMouseEvent(event)) {
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (uhidEnabled && event != null && scrcpy != null && serviceBound) {
            val isFromKeyboard = (event.source and InputDevice.SOURCE_KEYBOARD) != 0 ||
                    (event.deviceId != 0 && (event.flags and KeyEvent.FLAG_FROM_SYSTEM) == 0)
            if (isFromKeyboard && handleUhidKeyEvent(keyCode, event, isDown = true)) {
                return true
            }
        }
        if (scrcpy != null && serviceBound) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DEL,
                KeyEvent.KEYCODE_FORWARD_DEL,
                KeyEvent.KEYCODE_TAB,
                KeyEvent.KEYCODE_ESCAPE,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    scrcpy?.sendKeyevent(keyCode)
                    return true
                }
                else -> {
                    if (event != null && event.unicodeChar > 0 && !Character.isISOControl(event.unicodeChar)) {
                        scrcpy?.sendText(event.unicodeChar.toChar().toString())
                        return true
                    }
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (uhidEnabled && event != null && scrcpy != null && serviceBound) {
            val isFromKeyboard = (event.source and InputDevice.SOURCE_KEYBOARD) != 0 ||
                    (event.deviceId != 0 && (event.flags and KeyEvent.FLAG_FROM_SYSTEM) == 0)
            if (isFromKeyboard && handleUhidKeyEvent(keyCode, event, isDown = false)) {
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isSoftKeyboardShowing) {
            toggleSoftKeyboard()
            return
        }
        if (radialMenuLayout?.isExpanded == true) {
            radialMenuLayout?.collapse()
            return
        }
        val now = SystemClock.uptimeMillis()
        if (backTimestamp == 0L || now > backTimestamp + 2000) {
            backTimestamp = now
            Toast.makeText(this, getString(R.string.msg_press_again_exit), Toast.LENGTH_SHORT).show()
        } else {
            backTimestamp = 0
            stopScrcpyService()
            finish()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_PROXIMITY) {
            val s = scrcpy
            if (serviceBound && s != null) {
                try {
                    s.setDisplayPower(event.values[0] != 0f)
                } catch (ignored: Exception) {}
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun applyFullscreen() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    private fun adjustSurfaceLayout() {
        setupTouchAndNav()

        radialMenuLayout?.let { menu ->
            menu.visibility = if (nav || !noControl) View.VISIBLE else View.GONE
            menu.bringToFront()
        }

        val cl = containerLayout ?: return
        if (remoteDeviceWidth <= 0 || remoteDeviceHeight <= 0) return

        val ratio = if (remoteDeviceWidth >= remoteDeviceHeight) {
            "H," + remoteDeviceWidth.toInt() + ":" + remoteDeviceHeight.toInt()
        } else {
            "W," + remoteDeviceWidth.toInt() + ":" + remoteDeviceHeight.toInt()
        }

        val cs = ConstraintSet()
        cs.clone(cl)
        cs.setDimensionRatio(R.id.decoder_surface, ratio)
        cs.applyTo(cl)

        radialMenuLayout?.bringToFront()
    }

    private fun registerProximity() {
        if (sensorManager != null && proximitySensor != null) {
            sensorManager?.unregisterListener(this)
            sensorManager?.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun restoreRemoteDisplayPower() {
        if (scrcpy != null && serviceBound) {
            try {
                scrcpy?.setDisplayPower(true)
                scrcpy?.sendKeyevent(KeyEvent.KEYCODE_WAKEUP)
            } catch (ignored: Exception) {}
        }
    }

    private fun stopScrcpyService() {
        stopFullscreenRecordTimer()
        if (screenRecorder.isRecording) {
            screenRecorder.stop()
        }

        // Restore remote device display power before tearing down connection
        restoreRemoteDisplayPower()

        val activeScrcpy = scrcpy
        val connection = serviceConnection
        if (serviceBound && activeScrcpy != null) {
            serviceBound = false
            scrcpy = null
            if (uhidEnabled) {
                try {
                    activeScrcpy.destroyUhid()
                } catch (ignored: Exception) {}
            }
            Thread {
                try {
                    Thread.sleep(200)
                } catch (ignored: Exception) {}
                activeScrcpy.StopService()
                SendCommands.closeActiveSession()
            }.start()
            try {
                unbindService(connection)
            } catch (ignored: Exception) {}
        } else {
            SendCommands.closeActiveSession()
        }
    }

    private fun captureFullscreenScreenshot() {
        val sv = surfaceView ?: return
        ScreenCaptureUtil.captureFrame(sv, this, object : ScreenCaptureUtil.CaptureCallback {
            override fun onSuccess(filePath: String) {
                Toast.makeText(this@ScreenMirrorActivity, R.string.msg_screenshot_saved, Toast.LENGTH_SHORT).show()
            }

            override fun onFailure(reason: String) {
                Toast.makeText(this@ScreenMirrorActivity, getString(R.string.msg_screenshot_failed, reason), Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun handleRecordPullTab() {
        if (isFinishing || isDestroyed) return
        if (screenRecorder.isRecording) {
            val msg = getString(R.string.dialog_record_stop_msg) + "\n\n" + getString(R.string.dialog_record_hint_long_press)
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_record_stop_title)
                .setMessage(msg)
                .setPositiveButton(R.string.dialog_btn_stop_record) { _, _ -> stopFullscreenRecording() }
                .setNegativeButton(R.string.dialog_btn_continue_record, null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_record_start_title)
                .setMessage(R.string.dialog_record_start_msg)
                .setPositiveButton(R.string.dialog_btn_start_record) { _, _ -> startFullscreenRecording() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun startFullscreenRecording() {
        val sv = surfaceView ?: return
        screenRecorder.start(sv, sv.width, sv.height, scrcpy, this, object : ScreenRecorder.RecordListener {
            override fun onStarted() {
                recordStartMs = System.currentTimeMillis()
                startFullscreenRecordTimer()
                Toast.makeText(this@ScreenMirrorActivity, R.string.msg_record_started, Toast.LENGTH_SHORT).show()
            }

            override fun onStopped(filePath: String) {
                stopFullscreenRecordTimer()
                Toast.makeText(this@ScreenMirrorActivity, R.string.msg_record_saved, Toast.LENGTH_SHORT).show()
            }

            override fun onError(reason: String) {
                stopFullscreenRecordTimer()
                Toast.makeText(this@ScreenMirrorActivity, getString(R.string.msg_record_failed, reason), Toast.LENGTH_SHORT).show()
            }

            override fun onTick(elapsedSeconds: Long) {}
        })
    }

    private fun stopFullscreenRecording() {
        screenRecorder.stop()
        stopFullscreenRecordTimer()
    }

    private fun startFullscreenRecordTimer() {
        recordTimerRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                if (!screenRecorder.isRecording) return
                val elapsed = (System.currentTimeMillis() - recordStartMs) / 1000
                val mm = elapsed / 60
                val ss = elapsed % 60
                val text = String.format(Locale.getDefault(), "%02d:%02d", mm, ss)
                updateRecordTabUI(text)
                mainHandler.postDelayed(this, 1000)
            }
        }
        recordTimerRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun stopFullscreenRecordTimer() {
        recordTimerRunnable?.let {
            mainHandler.removeCallbacks(it)
            recordTimerRunnable = null
        }
        updateRecordTabUI(null)
    }

    private fun updateRecordTabUI(timerText: String?) {
        val recording = !timerText.isNullOrEmpty()
        radialMenuLayout?.setRecordingState(recording, timerText)
    }

    // ────────────────────────────────────────────────────────────────────────
    // File Transfer (Scrcpy / ADB Channel)
    // ────────────────────────────────────────────────────────────────────────

    private fun pickFilesToTransfer() {
        if (isFinishing || isDestroyed) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(intent, REQUEST_CODE_PICK_FILE)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开系统文件选择器: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PICK_FILE) {
            scrcpy?.resetVideo()
            if (resultCode == RESULT_OK && data != null) {
                val uris = mutableListOf<Uri>()
                data.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) {
                        clip.getItemAt(i).uri?.let { uris.add(it) }
                    }
                } ?: data.data?.let { uris.add(it) }

                if (uris.isNotEmpty()) {
                    startTransferFiles(uris)
                }
            }
        }
    }

    private fun startTransferFiles(uris: List<Uri>) {
        if (!SendCommands.hasActiveSession()) {
            Toast.makeText(this, "通道未就绪，无法传输文件", Toast.LENGTH_SHORT).show()
            return
        }

        val progressDialog = AlertDialog.Builder(this)
            .setTitle(R.string.menu_file_transfer)
            .setMessage(getString(R.string.msg_transferring_file, ""))
            .setCancelable(false)
            .create()
        progressDialog.show()

        Thread {
            var successCount = 0
            var failCount = 0
            var lastRemotePath = ""

            for (uri in uris) {
                var fileName = "file_${System.currentTimeMillis()}"
                var fileSize = 0L

                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIdx != -1) fileName = cursor.getString(nameIdx) ?: fileName
                        if (sizeIdx != -1) fileSize = cursor.getLong(sizeIdx)
                    }
                }

                val category = getCategoryFolder(fileName)
                val remotePath = "/sdcard/Scrcpy/$category/$fileName"
                lastRemotePath = remotePath

                runOnUiThread {
                    progressDialog.setMessage(getString(R.string.msg_transferring_file, fileName))
                }

                try {
                    val inputStream = contentResolver.openInputStream(uri)
                    if (inputStream != null) {
                        inputStream.use { input ->
                            val success = SendCommands.pushRemoteFile(input, remotePath, fileSize)
                            if (success) {
                                scrcpy?.scanFile(remotePath)
                                successCount++
                            } else {
                                failCount++
                            }
                        }
                    } else {
                        failCount++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "File transfer failed for $fileName", e)
                    failCount++
                }
            }

            runOnUiThread {
                progressDialog.dismiss()
                scrcpy?.resetVideo()
                if (successCount > 0) {
                    val msg = if (uris.size == 1) {
                        getString(R.string.msg_file_transfer_success, lastRemotePath)
                    } else {
                        "成功传输 $successCount 个文件至 /sdcard/Scrcpy/"
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
                if (failCount > 0) {
                    Toast.makeText(this, "部分文件传输失败 ($failCount 个)", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun getCategoryFolder(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "heif", "raw" -> "Pictures"
            "mp4", "mkv", "avi", "mov", "flv", "wmv", "3gp", "ts", "webm", "m4v" -> "Videos"
            "mp3", "wav", "flac", "aac", "ogg", "m4a", "opus", "wma", "mid" -> "Music"
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "epub", "csv", "json" -> "Documents"
            "apk", "apks", "xapk", "aab" -> "APKs"
            else -> "Others"
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // UHID Keyboard & Mouse & Soft Keyboard
    // ────────────────────────────────────────────────────────────────────────

    private fun setupRemoteInputView() {
        remoteInputView?.let { inputView ->
            inputView.onCommitTextListener = { text ->
                if (text.isNotEmpty()) {
                    scrcpy?.sendText(text)
                }
            }
            inputView.onDeleteListener = {
                if (uhidEnabled) {
                    scrcpy?.sendControlMessage(ControlMessage.createUhidKeyboardReport(0, intArrayOf(0x2A)))
                    scrcpy?.sendControlMessage(ControlMessage.createUhidKeyboardReport(0, intArrayOf()))
                } else {
                    scrcpy?.sendKeyevent(KeyEvent.KEYCODE_DEL)
                }
            }
            inputView.onKeyEventListener = { event ->
                if (uhidEnabled) {
                    handleUhidKeyEvent(event.keyCode, event, event.action == KeyEvent.ACTION_DOWN)
                } else {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        scrcpy?.sendKeyevent(event.keyCode)
                        true
                    } else false
                }
            }
        }
    }

    private fun handleKeyboardAction() {
        if (hasPhysicalKeyboard()) {
            Toast.makeText(this, R.string.msg_physical_keyboard_connected, Toast.LENGTH_SHORT).show()
        }
        toggleSoftKeyboard()
    }

    private fun toggleSoftKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val view = remoteInputView ?: return
        view.requestFocus()
        if (isSoftKeyboardShowing) {
            imm.hideSoftInputFromWindow(view.windowToken, 0)
            isSoftKeyboardShowing = false
        } else {
            @Suppress("DEPRECATION")
            imm.showSoftInput(view, InputMethodManager.SHOW_FORCED)
            isSoftKeyboardShowing = true
        }
    }

    private fun hasPhysicalKeyboard(): Boolean {
        val config = resources.configuration
        if (config.keyboard == Configuration.KEYBOARD_QWERTY) {
            return true
        }
        for (id in InputDevice.getDeviceIds()) {
            val dev = InputDevice.getDevice(id) ?: continue
            if (dev.isVirtual) continue
            val sources = dev.sources
            if ((sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD &&
                dev.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
            ) {
                return true
            }
        }
        return false
    }

    private fun handleUhidKeyEvent(keyCode: Int, event: KeyEvent, isDown: Boolean): Boolean {
        val s = scrcpy ?: return false

        if (keyCode == KeyEvent.KEYCODE_BACK && (event.flags and KeyEvent.FLAG_FROM_SYSTEM) != 0) {
            return false
        }

        if (ControlMessage.isModifierKey(keyCode)) {
            val mask = ControlMessage.getModifierMask(keyCode)
            var mods = ControlMessage.getModifiersFromMetaState(event.metaState)
            mods = if (isDown) (mods or mask) else (mods and mask.inv())
            s.sendControlMessage(ControlMessage.createUhidKeyboardReport(mods, pressedHidKeys.toIntArray()))
            return true
        }

        val scancode = ControlMessage.keyCodeToHidScancode(keyCode)
        if (scancode != 0) {
            if (isDown) {
                if (event.repeatCount == 0) {
                    pressedHidKeys.add(scancode)
                }
            } else {
                pressedHidKeys.remove(scancode)
            }
            val mods = ControlMessage.getModifiersFromMetaState(event.metaState)
            s.sendControlMessage(ControlMessage.createUhidKeyboardReport(mods, pressedHidKeys.toIntArray()))
            return true
        }
        return false
    }

    private fun handleUhidMouseEvent(event: MotionEvent): Boolean {
        val s = scrcpy ?: return false
        val action = event.actionMasked

        if (action == MotionEvent.ACTION_SCROLL) {
            val vScroll = (event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 1.0f).toInt().coerceIn(-127, 127)
            val hScroll = (event.getAxisValue(MotionEvent.AXIS_HSCROLL) * 1.0f).toInt().coerceIn(-127, 127)
            if (vScroll != 0 || hScroll != 0) {
                val buttons = getHidMouseButtons(event.buttonState)
                s.sendControlMessage(ControlMessage.createUhidMouseReport(buttons, 0, 0, vScroll, hScroll))
                return true
            }
        }

        var dx = 0
        var dy = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && surfaceView?.hasPointerCapture() == true) {
            dx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X).toInt().coerceIn(-127, 127)
            dy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y).toInt().coerceIn(-127, 127)
        } else {
            if (hasLastMousePos && (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_HOVER_MOVE)) {
                dx = (event.x - lastMouseX).toInt().coerceIn(-127, 127)
                dy = (event.y - lastMouseY).toInt().coerceIn(-127, 127)
            }
            lastMouseX = event.x
            lastMouseY = event.y
            hasLastMousePos = (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL)
        }

        val buttons = getHidMouseButtons(event.buttonState)

        if (action == MotionEvent.ACTION_DOWN && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (surfaceView?.hasPointerCapture() == false) {
                surfaceView?.requestPointerCapture()
            }
        }

        s.sendControlMessage(ControlMessage.createUhidMouseReport(buttons, dx, dy, 0, 0))
        return true
    }

    private fun getHidMouseButtons(buttonState: Int): Int {
        var buttons = 0
        if ((buttonState and MotionEvent.BUTTON_PRIMARY) != 0) buttons = buttons or (1 shl 0)
        if ((buttonState and MotionEvent.BUTTON_SECONDARY) != 0) buttons = buttons or (1 shl 1)
        if ((buttonState and MotionEvent.BUTTON_TERTIARY) != 0) buttons = buttons or (1 shl 2)
        if ((buttonState and MotionEvent.BUTTON_BACK) != 0) buttons = buttons or (1 shl 3)
        if ((buttonState and MotionEvent.BUTTON_FORWARD) != 0) buttons = buttons or (1 shl 4)
        return buttons
    }
}
