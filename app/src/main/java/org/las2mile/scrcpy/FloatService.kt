package org.las2mile.scrcpy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.app.NotificationCompat
import java.util.Locale

class FloatService : Service(), Scrcpy.ServiceCallbacks {

    private var displayWindow: DisplayWindow? = null
    private var windowManager: WindowManager? = null
    private var lp: WindowManager.LayoutParams? = null

    @Volatile
    private var scrcpy: Scrcpy? = null
    @Volatile
    private var serviceBound = false
    private var surface: Surface? = null

    private var serverAdr: String? = null
    private var serverPort = 7007
    private var screenWidth = 1080
    private var screenHeight = 1920
    private var audioEnabled = false
    private var noControl = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val screenRecorder = ScreenRecorder()
    private var recordTimerRunnable: Runnable? = null
    private var recordStartMs = 0L

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = (binder as Scrcpy.MyServiceBinder).service
            scrcpy = s
            if (s == null) return
            s.setServiceCallbacks(this@FloatService)
            serviceBound = true

            val surf = surface
            if (surf != null && surf.isValid) {
                s.start(surf, serverAdr, serverPort, screenHeight, screenWidth, audioEnabled)

                Thread({
                    var count = 60
                    while (count > 0 && serviceBound && scrcpy != null) {
                        if (scrcpy?.check_socket_connection() == true) break
                        count--
                        try {
                            Thread.sleep(100)
                        } catch (e: InterruptedException) {
                            break
                        }
                    }
                    val finalCount = count
                    mainHandler.post {
                        if (finalCount == 0) {
                            Toast.makeText(this@FloatService, R.string.msg_connection_timeout, Toast.LENGTH_SHORT).show()
                            stopSelf()
                        } else {
                            displayWindow?.let { win ->
                                win.hideHintTip()
                                scrcpy?.let { sc ->
                                    val res = sc.get_remote_device_resolution()
                                    if (res != null && res.size >= 2 && res[0] > 0 && res[1] > 0) {
                                        win.setRemote(res[0], res[1])
                                    }
                                }
                            }
                        }
                    }
                }, "FloatWaitConnect").start()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false
            scrcpy = null
        }
    }

    private val usbDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (UsbManager.ACTION_USB_DEVICE_DETACHED == intent.action) {
                Log.d(TAG, "USB device detached")
                onDisconnected()
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        promoteToForeground()
        setupDisplay()

        val usbFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbDetachReceiver, usbFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbDetachReceiver, usbFilter)
        }
    }

    private fun promoteToForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "scrcpy Floating Window",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Running screen mirroring in floating window"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.btn_start_float))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground with connectedDevice failed, retrying without type: ${e.message}")
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (fatal: Exception) {
                Log.e(TAG, "Failed to start foreground service", fatal)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            return super.onStartCommand(intent, flags, startId)
        }

        serverAdr = intent.getStringExtra("ip")
        serverPort = intent.getIntExtra("port", 7007)
        screenWidth = intent.getIntExtra("width", intent.getIntExtra("w", 1080))
        screenHeight = intent.getIntExtra("height", intent.getIntExtra("h", 1920))
        audioEnabled = intent.getBooleanExtra("audio", false)
        noControl = intent.getBooleanExtra("no_control", false)

        Log.d(TAG, "onStartCommand: server=$serverAdr:$serverPort, size=${screenWidth}x$screenHeight")
        displayWindow?.setRemote(screenWidth, screenHeight)

        val surf = surface
        val adr = serverAdr
        if (!serviceBound && surf != null && surf.isValid && !adr.isNullOrEmpty()) {
            val scrcpyIntent = Intent(this@FloatService, Scrcpy::class.java)
            startService(scrcpyIntent)
            bindService(scrcpyIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        return START_NOT_STICKY
    }

    private fun setupDisplay() {
        val themedContext = ContextThemeWrapper(this, R.style.AppTheme)
        val window = DisplayWindow(themedContext)
        displayWindow = window
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val layoutParams = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            gravity = Gravity.TOP or Gravity.START
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            x = 40
            y = 120
        }
        lp = layoutParams

        wm.addView(window, layoutParams)

        window.setCloseListener {
            Log.d(TAG, "Floating window close clicked")
            stopSelf()
        }

        window.setMoveCallback(object : DisplayWindow.OnMoveCallback {
            override fun onMove(x: Float, y: Float) {
                layoutParams.x += x.toInt()
                layoutParams.y += y.toInt()
                try {
                    windowManager?.updateViewLayout(displayWindow, layoutParams)
                } catch (ignored: Exception) {
                }
            }
        })

        window.setActionCallback(object : DisplayWindow.OnActionCallback {
            override fun onAction(actionType: Int) {
                when (actionType) {
                    DisplayWindow.ACTION_POWER -> scrcpy?.sendKeyevent(KeyEvent.KEYCODE_POWER)
                    DisplayWindow.ACTION_BACK -> scrcpy?.sendKeyevent(KeyEvent.KEYCODE_BACK)
                    DisplayWindow.ACTION_HOME -> scrcpy?.sendKeyevent(KeyEvent.KEYCODE_HOME)
                    DisplayWindow.ACTION_MENU -> scrcpy?.sendKeyevent(KeyEvent.KEYCODE_APP_SWITCH)
                    DisplayWindow.ACTION_VOLUME_DOWN -> scrcpy?.sendKeyevent(KeyEvent.KEYCODE_VOLUME_DOWN)
                    DisplayWindow.ACTION_VOLUME_UP -> scrcpy?.sendKeyevent(KeyEvent.KEYCODE_VOLUME_UP)
                    DisplayWindow.ACTION_ROTATE -> scrcpy?.rotateDevice()
                    DisplayWindow.ACTION_SCREENSHOT -> captureFloatScreenshot()
                    DisplayWindow.ACTION_RECORD_TOGGLE -> toggleFloatRecording()
                }
            }
        })

        window.setOnDisplayTouchListener { _, event ->
            if (noControl || scrcpy == null) false
            else scrcpy?.touchevent(event, window.getSurfaceWidth(), window.getSurfaceHeight()) ?: false
        }

        window.setSurfaceReadyCallback(object : DisplayWindow.SurfaceReadyCallback {
            override fun onSurfaceReady(readySurface: Surface) {
                surface = readySurface
                val adr = serverAdr
                if (!serviceBound && !adr.isNullOrEmpty()) {
                    val scrcpyIntent = Intent(this@FloatService, Scrcpy::class.java)
                    startService(scrcpyIntent)
                    bindService(scrcpyIntent, serviceConnection, Context.BIND_AUTO_CREATE)
                } else if (serviceBound && scrcpy != null) {
                    scrcpy?.setSurface(readySurface)
                }
            }

            override fun onSurfaceDestroyed() {
                surface = null
            }
        })
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "onConfigurationChanged")
        scrcpy?.let { sc ->
            val res = sc.get_remote_device_resolution()
            if (res != null && res.size >= 2 && res[0] > 0 && res[1] > 0) {
                displayWindow?.setRemote(res[0], res[1])
            }
        }
    }

    private fun captureFloatScreenshot() {
        val window = displayWindow ?: return
        val sv = window.getSurfaceView()
        ScreenCaptureUtil.captureFrame(sv, this, object : ScreenCaptureUtil.CaptureCallback {
            override fun onSuccess(filePath: String) {
                Toast.makeText(this@FloatService, R.string.msg_screenshot_saved, Toast.LENGTH_SHORT).show()
            }

            override fun onFailure(reason: String) {
                Toast.makeText(this@FloatService, getString(R.string.msg_screenshot_failed, reason), Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun toggleFloatRecording() {
        if (screenRecorder.isRecording) {
            screenRecorder.stop()
            stopRecordTimer()
        } else {
            val window = displayWindow ?: return
            val sv = window.getSurfaceView()
            screenRecorder.start(sv, sv.width, sv.height, scrcpy, this, object : ScreenRecorder.RecordListener {
                override fun onStarted() {
                    recordStartMs = System.currentTimeMillis()
                    startRecordTimer()
                    Toast.makeText(this@FloatService, R.string.msg_record_started, Toast.LENGTH_SHORT).show()
                }

                override fun onStopped(filePath: String) {
                    stopRecordTimer()
                    Toast.makeText(this@FloatService, R.string.msg_record_saved, Toast.LENGTH_SHORT).show()
                }

                override fun onError(reason: String) {
                    stopRecordTimer()
                    Toast.makeText(this@FloatService, getString(R.string.msg_record_failed, reason), Toast.LENGTH_SHORT).show()
                }

                override fun onTick(elapsedSeconds: Long) {}
            })
        }
    }

    private fun startRecordTimer() {
        recordTimerRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                if (!screenRecorder.isRecording || displayWindow == null) return
                val elapsed = (System.currentTimeMillis() - recordStartMs) / 1000
                val mm = elapsed / 60
                val ss = elapsed % 60
                val text = String.format(Locale.getDefault(), "%02d:%02d", mm, ss)
                displayWindow?.updateRecordTimer(text)
                mainHandler.postDelayed(this, 1000)
            }
        }
        recordTimerRunnable = r
        mainHandler.post(r)
    }

    private fun stopRecordTimer() {
        recordTimerRunnable?.let {
            mainHandler.removeCallbacks(it)
            recordTimerRunnable = null
        }
        displayWindow?.updateRecordTimer(null)
    }

    override fun loadNewRotation() {
        val sc = scrcpy ?: return
        val res = sc.get_remote_device_resolution()
        if (res != null && res.size >= 2 && res[0] > 0 && res[1] > 0) {
            mainHandler.post {
                displayWindow?.setRemote(res[0], res[1])
            }
        }
    }

    override fun onDisconnected() {
        mainHandler.post {
            Toast.makeText(this@FloatService, R.string.msg_device_disconnected, Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "FloatService onDestroy")

        stopRecordTimer()
        if (screenRecorder.isRecording) {
            screenRecorder.stop()
        }

        try {
            unregisterReceiver(usbDetachReceiver)
        } catch (ignored: Exception) {
        }

        if (serviceBound && scrcpy != null) {
            scrcpy?.StopService()
            try {
                unbindService(serviceConnection)
            } catch (ignored: Exception) {
            }
            serviceBound = false
            scrcpy = null
        }

        SendCommands.closeActiveSession()

        displayWindow?.let { win ->
            try {
                windowManager?.removeView(win)
            } catch (ignored: Exception) {
            }
            displayWindow = null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        private const val TAG = "FloatService"
        private const val CHANNEL_ID = "scrcpy_floating_service"
        private const val NOTIFICATION_ID = 1001
    }
}
