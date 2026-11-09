package org.las2mile.scrcpy

import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import org.las2mile.scrcpy.audio.AudioPlayer
import org.las2mile.scrcpy.decoder.VideoDecoder
import org.las2mile.scrcpy.protocol.ControlMessage
import org.las2mile.scrcpy.protocol.DeviceMessage
import org.las2mile.scrcpy.protocol.Position
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class Scrcpy : Service() {
    companion object {
        private const val TAG = "Scrcpy"
    }

    private var serverAdr: String? = null
    private var serverPort = 7007
    var isAudioEnabled = false
        private set
    private var surface: Surface? = null
    private var screenWidth = 0
    private var screenHeight = 0

    private var videoDecoder: VideoDecoder? = null
    private var audioPlayer: AudioPlayer? = null
    @Volatile
    private var audioPcmListener: AudioPlayer.AudioPcmListener? = null

    fun setAudioPcmListener(listener: AudioPlayer.AudioPcmListener?) {
        this.audioPcmListener = listener
        audioPlayer?.setPcmListener(listener)
    }

    val isAudioActive: Boolean
        get() = isAudioEnabled && audioPlayer != null

    private val mBinder: IBinder = MyServiceBinder()
    private val isRunning = AtomicBoolean(false)
    private var serviceCallbacks: ServiceCallbacks? = null
    private val remoteDevResolution = IntArray(2)
    @Volatile
    private var socketStatus = false
    var deviceName: String = ""
        private set

    private var videoSocket: Socket? = null
    private var audioSocket: Socket? = null
    private var controlSocket: Socket? = null

    private val controlQueue: BlockingQueue<ByteArray> = LinkedBlockingQueue()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder {
        return mBinder
    }

    fun setServiceCallbacks(callbacks: ServiceCallbacks?) {
        this.serviceCallbacks = callbacks
    }

    fun setParms(newSurface: Surface?, newWidth: Int, newHeight: Int) {
        this.screenWidth = newWidth
        this.screenHeight = newHeight
        this.surface = newSurface
        if (videoDecoder != null && surface != null && surface!!.isValid) {
            videoDecoder?.configure(surface, screenWidth, screenHeight)
        }
    }

    fun setSurface(newSurface: Surface?) {
        this.surface = newSurface
        if (videoDecoder != null) {
            val w = if (remoteDevResolution[0] > 0) remoteDevResolution[0] else screenWidth
            val h = if (remoteDevResolution[1] > 0) remoteDevResolution[1] else screenHeight
            if (newSurface != null && newSurface.isValid) {
                videoDecoder?.setSurface(newSurface, w, h)
                resetVideo()
            }
        }
    }

    fun resetVideo() {
        sendControlMessage(ControlMessage.createResetVideo())
    }

    fun start(surface: Surface?, serverAdr: String?, screenHeight: Int, screenWidth: Int) {
        start(surface, serverAdr, 7007, screenHeight, screenWidth, false)
    }

    fun start(surface: Surface?, serverAdr: String?, serverPort: Int, screenHeight: Int, screenWidth: Int, audio: Boolean) {
        this.surface = surface
        if (serverAdr != null && serverAdr.contains(":")) {
            this.serverAdr = serverAdr.substring(0, serverAdr.indexOf(':')).trim()
        } else {
            this.serverAdr = serverAdr
        }
        this.serverPort = serverPort
        this.screenHeight = screenHeight
        this.screenWidth = screenWidth
        this.isAudioEnabled = audio

        this.videoDecoder = VideoDecoder()
        this.videoDecoder?.start()

        isRunning.set(true)
        Thread({ connectionThread() }, "Scrcpy-Connect").start()
    }

    fun pause() {
        videoDecoder?.stop()
    }

    fun resume() {
        videoDecoder?.let { decoder ->
            decoder.start()
            val w = if (remoteDevResolution[0] > 0) remoteDevResolution[0] else screenWidth
            val h = if (remoteDevResolution[1] > 0) remoteDevResolution[1] else screenHeight
            if (surface != null && surface!!.isValid && w > 0 && h > 0) {
                decoder.configure(surface, w, h)
            }
            resetVideo()
        }
    }

    fun initUhid() {
        sendControlMessage(
            ControlMessage.createUhidCreate(
                id = ControlMessage.UHID_ID_KEYBOARD,
                name = "scrcpy-keyboard",
                reportDesc = ControlMessage.KEYBOARD_REPORT_DESC
            )
        )
        sendControlMessage(
            ControlMessage.createUhidCreate(
                id = ControlMessage.UHID_ID_MOUSE,
                name = "scrcpy-mouse",
                reportDesc = ControlMessage.MOUSE_REPORT_DESC
            )
        )
    }

    fun destroyUhid() {
        sendControlMessage(ControlMessage.createUhidDestroy(ControlMessage.UHID_ID_KEYBOARD))
        sendControlMessage(ControlMessage.createUhidDestroy(ControlMessage.UHID_ID_MOUSE))
    }

    fun StopService() {
        val wasRunning = isRunning.getAndSet(false)
        closeSockets()
        videoDecoder?.stop()
        audioPlayer?.stop()
        if (wasRunning && serviceCallbacks != null) {
            mainHandler.post {
                serviceCallbacks?.onDisconnected()
            }
        }
        stopSelf()
    }

    fun get_remote_device_resolution(): IntArray {
        return remoteDevResolution
    }

    fun check_socket_connection(): Boolean {
        return socketStatus
    }

    fun sendControlMessage(msg: ByteArray?) {
        if (msg != null && isRunning.get()) {
            controlQueue.offer(msg)
        }
    }

    fun sendKeyevent(keycode: Int) {
        sendControlMessage(ControlMessage.createInjectKeycode(0, keycode, 0, 0))
        sendControlMessage(ControlMessage.createInjectKeycode(1, keycode, 0, 0))
    }

    fun setDisplayPower(on: Boolean) {
        sendControlMessage(ControlMessage.createSetDisplayPower(on))
    }

    fun setClipboard(text: String, paste: Boolean) {
        sendControlMessage(ControlMessage.createSetClipboard(text, paste))
    }

    fun rotateDevice() {
        sendControlMessage(ControlMessage.createEmpty(ControlMessage.TYPE_ROTATE_DEVICE))
    }

    fun sendText(text: String, paste: Boolean = true) {
        if (text.isEmpty()) return
        val isSimpleAscii = text.all { it.code in 32..126 }
        if (isSimpleAscii && text.length == 1) {
            sendControlMessage(ControlMessage.createInjectText(text))
        } else {
            setClipboard(text, paste)
        }
    }

    fun scanFile(path: String) {
        sendControlMessage(ControlMessage.createScanFile(path))
    }

    fun touchevent(touch_event: MotionEvent, displayW: Int, displayH: Int): Boolean {
        if (remoteDevResolution[0] == 0 || remoteDevResolution[1] == 0 || displayW <= 0 || displayH <= 0) {
            return false
        }

        val actionMasked = touch_event.actionMasked
        val actionIndex = touch_event.actionIndex
        val action: Int = when (actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> 0
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> 1
            MotionEvent.ACTION_MOVE -> 2
            MotionEvent.ACTION_CANCEL -> 3
            else -> return false
        }

        val remoteW = remoteDevResolution[0]
        val remoteH = remoteDevResolution[1]

        if (action == 2) {
            for (i in 0 until touch_event.pointerCount) {
                val pointerId = touch_event.getPointerId(i).toLong()
                val x = touch_event.getX(i)
                val y = touch_event.getY(i)
                val posX = Math.max(0, Math.min(remoteW, (x * remoteW / displayW).toInt()))
                val posY = Math.max(0, Math.min(remoteH, (y * remoteH / displayH).toInt()))
                val pressure = touch_event.getPressure(i)
                val msg = ControlMessage.createInjectTouchEvent(
                    action, pointerId,
                    Position(posX, posY, remoteW, remoteH), pressure, 0, 1
                )
                sendControlMessage(msg)
            }
        } else {
            val pointerId = touch_event.getPointerId(actionIndex).toLong()
            val x = touch_event.getX(actionIndex)
            val y = touch_event.getY(actionIndex)
            val posX = Math.max(0, Math.min(remoteW, (x * remoteW / displayW).toInt()))
            val posY = Math.max(0, Math.min(remoteH, (y * remoteH / displayH).toInt()))
            val pressure = touch_event.getPressure(actionIndex)
            val msg = ControlMessage.createInjectTouchEvent(
                action, pointerId,
                Position(posX, posY, remoteW, remoteH), pressure, 0, 1
            )
            sendControlMessage(msg)
        }
        return true
    }

    private fun connectionThread() {
        var attempts = 50
        while (attempts > 0 && isRunning.get()) {
            try {
                Log.d(TAG, "Connecting to server at $serverAdr:$serverPort")

                val vSocket = Socket()
                vSocket.receiveBufferSize = 1024 * 1024
                vSocket.sendBufferSize = 256 * 1024
                vSocket.connect(InetSocketAddress(serverAdr, serverPort), 3000)
                vSocket.tcpNoDelay = true
                videoSocket = vSocket

                if (isAudioEnabled) {
                    val aSocket = Socket()
                    aSocket.receiveBufferSize = 256 * 1024
                    aSocket.connect(InetSocketAddress(serverAdr, serverPort), 3000)
                    aSocket.tcpNoDelay = true
                    audioSocket = aSocket
                }

                val cSocket = Socket()
                cSocket.sendBufferSize = 64 * 1024
                cSocket.connect(InetSocketAddress(serverAdr, serverPort), 3000)
                cSocket.tcpNoDelay = true
                controlSocket = cSocket

                Log.d(TAG, "All sockets connected successfully")
                socketStatus = true
                break
            } catch (e: IOException) {
                attempts--
                closeSockets()
                try {
                    Thread.sleep(100)
                } catch (ignored: InterruptedException) {
                }
            }
        }

        if (!socketStatus || !isRunning.get()) {
            socketStatus = false
            StopService()
            return
        }

        Thread({ controlSendThread() }, "Scrcpy-Control-Send").start()
        Thread({ controlRecvThread() }, "Scrcpy-Control-Recv").start()

        if (isAudioEnabled && audioSocket != null) {
            Thread({ audioThread() }, "Scrcpy-Audio").start()
        }

        Thread({ videoThread() }, "Scrcpy-Video").start()
    }

    private fun videoThread() {
        try {
            val socket = videoSocket ?: return
            val dis = DataInputStream(BufferedInputStream(socket.getInputStream(), 1 shl 18))

            val dummy = dis.readByte().toInt()
            Log.d(TAG, "Dummy byte read: $dummy")

            val devMeta = ByteArray(64)
            dis.readFully(devMeta)
            deviceName = String(devMeta, StandardCharsets.UTF_8).trim()
            Log.d(TAG, "Device metadata: $deviceName")

            val codecId = dis.readInt()
            Log.d(TAG, String.format("Video Codec ID: 0x%08X", codecId))
            videoDecoder?.setCodec(codecId)

            while (isRunning.get()) {
                val firstInt = dis.readInt()
                if ((firstInt and 0x80000000.toInt()) != 0) {
                    val width = dis.readInt()
                    val height = dis.readInt()
                    Log.d(TAG, "Session packet received: ${width}x$height")

                    remoteDevResolution[0] = width
                    remoteDevResolution[1] = height

                    if (surface != null && surface!!.isValid) {
                        videoDecoder?.configure(surface, width, height)
                    }

                    if (serviceCallbacks != null) {
                        mainHandler.post {
                            serviceCallbacks?.loadNewRotation()
                        }
                    }
                } else {
                    val isConfig = (firstInt and 0x40000000) != 0
                    val isKeyFrame = (firstInt and 0x20000000) != 0
                    val secondInt = dis.readInt()
                    val pts = ((firstInt.toLong() and 0x1FFFFFFFL) shl 32) or (secondInt.toLong() and 0xFFFFFFFFL)

                    val packetSize = dis.readInt()
                    if (packetSize in 1 until (1 shl 22)) {
                        val payload = ByteArray(packetSize)
                        dis.readFully(payload)

                        var flags = 0
                        if (isConfig) {
                            flags = flags or 2
                        }
                        if (isKeyFrame) {
                            flags = flags or 1
                        }

                        videoDecoder?.decodeSample(payload, 0, packetSize, pts, flags)
                    }
                }
            }
        } catch (e: IOException) {
            Log.d(TAG, "Video stream ended: ${e.message}")
        } finally {
            StopService()
        }
    }

    private fun audioThread() {
        try {
            val socket = audioSocket ?: return
            val dis = DataInputStream(BufferedInputStream(socket.getInputStream(), 1 shl 16))
            val codecId = dis.readInt()
            Log.d(TAG, String.format("Audio Codec ID: 0x%08X", codecId))

            if (codecId == 0) {
                Log.w(TAG, "Audio capture disabled by device")
                return
            }

            val player = AudioPlayer(codecId)
            audioPlayer = player
            audioPcmListener?.let { player.setPcmListener(it) }
            player.start()

            while (isRunning.get()) {
                val firstInt = dis.readInt()
                val isConfig = (firstInt and 0x40000000) != 0
                val secondInt = dis.readInt()
                val pts = ((firstInt.toLong() and 0x1FFFFFFFL) shl 32) or (secondInt.toLong() and 0xFFFFFFFFL)

                val packetSize = dis.readInt()
                if (packetSize in 1 until (1 shl 18)) {
                    val payload = ByteArray(packetSize)
                    dis.readFully(payload)
                    player.playSample(payload, pts, isConfig)
                }
            }
        } catch (e: IOException) {
            Log.d(TAG, "Audio stream ended: ${e.message}")
        } finally {
            audioPlayer?.stop()
        }
    }

    private fun controlSendThread() {
        try {
            val socket = controlSocket ?: return
            val dos = DataOutputStream(socket.getOutputStream())
            while (isRunning.get()) {
                val msg = controlQueue.take()
                dos.write(msg)
                dos.flush()
            }
        } catch (ignored: Exception) {
        }
    }

    private fun controlRecvThread() {
        try {
            val socket = controlSocket ?: return
            val dis = DataInputStream(socket.getInputStream())
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

            while (isRunning.get()) {
                val msg = DeviceMessage.parse(dis)
                if (msg != null && msg.type == DeviceMessage.TYPE_CLIPBOARD && msg.text != null) {
                    mainHandler.post {
                        cm?.setPrimaryClip(ClipData.newPlainText("scrcpy", msg.text))
                    }
                }
            }
        } catch (ignored: IOException) {
        }
    }

    private fun closeSockets() {
        socketStatus = false
        try {
            videoSocket?.close()
        } catch (ignored: IOException) {}
        videoSocket = null

        try {
            audioSocket?.close()
        } catch (ignored: IOException) {}
        audioSocket = null

        try {
            controlSocket?.close()
        } catch (ignored: IOException) {}
        controlSocket = null
    }

    interface ServiceCallbacks {
        fun loadNewRotation()
        fun onDisconnected()
    }

    inner class MyServiceBinder : Binder() {
        val service: Scrcpy
            get() = this@Scrcpy
    }
}
