package org.las2mile.scrcpy

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.IOException

class ScrcpyHost : Scrcpy.ServiceCallbacks {

    fun interface ConnectCallBack {
        fun onConnect(w: Float, h: Float)
    }

    private var context: Context? = null
    @Volatile
    private var scrcpy: Scrcpy? = null

    private var videoBitrate = 0
    private var serverAdr: String? = null
    private var surface: Surface? = null

    private var fileBase64: ByteArray? = null
    private var sendCommands: SendCommands? = null
    private var connectCallBack: ConnectCallBack? = null

    fun setConnectCallBack(connectCallBack: ConnectCallBack?) {
        this.connectCallBack = connectCallBack
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
            val s = (iBinder as Scrcpy.MyServiceBinder).service
            scrcpy = s
            if (s == null) return
            s.setServiceCallbacks(this@ScrcpyHost)
            serviceBound = true

            if (first_time) {
                s.start(surface, serverAdr, 7007, screenHeight, screenWidth, false)
                var count = 100
                while (count != 0) {
                    val activeScrcpy = scrcpy
                    if (activeScrcpy == null || !serviceBound) break
                    if (activeScrcpy.check_socket_connection()) break
                    count--
                    try {
                        Thread.sleep(100)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
                if (count == 0) {
                    if (serviceBound && scrcpy != null) {
                        scrcpy?.StopService()
                        try {
                            context?.unbindService(this)
                        } catch (ignored: Exception) {}
                        serviceBound = false
                        scrcpy = null
                    }
                    context?.let {
                        Toast.makeText(it, "Connection Timed out", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    scrcpy?.let { active ->
                        val remRes = active.get_remote_device_resolution()
                        if (remRes != null && remRes.size >= 2) {
                            remote_device_width = remRes[0].toFloat()
                            remote_device_height = remRes[1].toFloat()
                        }
                    }
                    first_time = false
                    Log.d(TAG, "onServiceConnected: ${remote_device_width}x$remote_device_height")
                    connectCallBack?.onConnect(
                        Math.min(remote_device_width, remote_device_height),
                        Math.max(remote_device_width, remote_device_height)
                    )
                }
            } else {
                scrcpy?.setParms(surface, screenWidth, screenHeight)
            }
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            serviceBound = false
            scrcpy = null
        }
    }

    private fun exectJar() {
        val ctx = context ?: return
        val assetManager = ctx.assets
        try {
            assetManager.open("scrcpy-server.jar").use { inputStream ->
                val baos = ByteArrayOutputStream()
                val buffer = ByteArray(16384)
                var read: Int
                while (inputStream.read(buffer).also { read = it } != -1) {
                    baos.write(buffer, 0, read)
                }
                val rawBytes = baos.toByteArray()
                fileBase64 = Base64.encode(rawBytes, Base64.NO_WRAP)
                Log.d(TAG, "Loaded scrcpy-server.jar: ${rawBytes.size} bytes")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Asset Manager error: ${e.message}")
        }
    }

    fun connect(context: Context, clientIp: String, width: Int, height: Int, bitrate: Int, display: Surface?) {
        this.context = context
        screenWidth = width
        screenHeight = height
        videoBitrate = bitrate
        surface = display
        serverAdr = clientIp

        exectJar()
        val sender = SendCommands()
        sendCommands = sender

        val adr = serverAdr
        if (!adr.isNullOrEmpty()) {
            if (sender.SendAdbCommands(
                    context, fileBase64, adr, 7007, videoBitrate,
                    Math.max(screenHeight, screenWidth), 0, "h264", false, "opus", true, false
                ) == 0
            ) {
                start_screen_copy_magic()
            } else {
                val err = sender.lastError
                val msg = if (!err.isNullOrEmpty()) err else "Network OR ADB connection failed"
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(context, "Server Address Empty", Toast.LENGTH_SHORT).show()
        }
    }

    private fun start_screen_copy_magic() {
        val ctx = context ?: return
        val intent = Intent(ctx, Scrcpy::class.java)
        ctx.startService(intent)
        ctx.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun touch(motionEvent: MotionEvent, surfaceW: Int, surfaceH: Int): Boolean {
        return scrcpy?.touchevent(motionEvent, surfaceW, surfaceH) ?: false
    }

    fun keyEvent(keyCode: Int) {
        scrcpy?.sendKeyevent(keyCode)
    }

    override fun loadNewRotation() {
        scrcpy?.let { s ->
            val remRes = s.get_remote_device_resolution()
            if (remRes != null && remRes.size >= 2) {
                remote_device_width = remRes[0].toFloat()
                remote_device_height = remRes[1].toFloat()
                first_time = false
                connectCallBack?.onConnect(
                    Math.min(remote_device_width, remote_device_height),
                    Math.max(remote_device_width, remote_device_height)
                )
            }
        }
    }

    override fun onDisconnected() {
    }

    fun destroy() {
        if (serviceBound) {
            scrcpy?.StopService()
            context?.let { ctx ->
                try {
                    ctx.unbindService(serviceConnection)
                } catch (ignored: Exception) {}
                val intent = Intent(ctx, Scrcpy::class.java)
                ctx.stopService(intent)
            }
            serviceBound = false
        }
    }

    companion object {
        private const val TAG = "ScrcpyHost"

        @Volatile
        private var serviceBound = false
        private var first_time = true

        private var screenWidth = 0
        private var screenHeight = 0
        private var remote_device_width = 0f
        private var remote_device_height = 0f
    }
}

