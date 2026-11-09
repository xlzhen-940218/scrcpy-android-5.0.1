package org.las2mile.scrcpy

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import com.tananaev.adblib.AdbBase64
import com.tananaev.adblib.AdbCrypto
import org.las2mile.scrcpy.adb.AdbKeyManager
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.RSAPublicKeySpec
import java.util.concurrent.CopyOnWriteArrayList

class SendCommands {

    private var thread: Thread? = null
    private var context: Context? = null
    var status: Int = 0
        private set
    var lastError: String? = null
        private set

    fun interface ServerOutputCallback {
        fun onServerLine(line: String)
    }

    private interface AdbSession : AutoCloseable {
        @Throws(Exception::class)
        fun execute(cmd: String): String

        @Throws(Exception::class)
        fun pushFile(data: ByteArray, remotePath: String)

        @Throws(Exception::class)
        fun pushFileStream(
            inputStream: InputStream,
            remotePath: String,
            totalBytes: Long,
            progressListener: ((Long, Long) -> Unit)?
        )

        @Throws(Exception::class)
        fun startServerProcess(cmd: String, callback: ServerOutputCallback?)

        @Throws(Exception::class)
        fun forwardPort(localPort: Int, remotePort: Int)
    }

    private class ModernSession(private val connection: io.github.muntashirakon.adb.AdbConnection) : AdbSession {
        private var serverStream: io.github.muntashirakon.adb.AdbStream? = null
        private var forwardServer: ServerSocket? = null
        private val forwardSockets = CopyOnWriteArrayList<Socket>()
        private val forwardStreams = CopyOnWriteArrayList<io.github.muntashirakon.adb.AdbStream>()

        private fun openExecStream(cmd: String): io.github.muntashirakon.adb.AdbStream {
            val utf8Bytes = cmd.toByteArray(StandardCharsets.UTF_8)
            // Workaround for libadb-android bug:
            // AdbProtocol.generateOpen allocates ByteBuffer with size = destination.length() + 1
            // instead of destination.getBytes(UTF_8).length + 1.
            // If destination contains multi-byte characters (e.g. Chinese, Unicode),
            // byte length > char length, causing java.nio.BufferOverflowException.
            // When multi-byte characters are present, we wrap the command in base64:
            val destination = if (utf8Bytes.size > cmd.length) {
                val b64 = Base64.encodeToString(utf8Bytes, Base64.NO_WRAP)
                "exec:eval \"\$(echo $b64 | (base64 -d 2>/dev/null || toybox base64 -d))\""
            } else {
                "exec:$cmd"
            }
            return connection.open(destination)
        }

        override fun execute(cmd: String): String {
            openExecStream(cmd).use { stream ->
                stream.openInputStream().use { `in` ->
                    val baos = ByteArrayOutputStream()
                    val buf = ByteArray(4096)
                    var r: Int
                    try {
                        while (`in`.read(buf).also { r = it } != -1) {
                            baos.write(buf, 0, r)
                        }
                    } catch (e: IOException) {
                        if (e.message == null || !e.message!!.lowercase().contains("stream closed")) {
                            throw e
                        }
                    }
                    return String(baos.toByteArray(), StandardCharsets.UTF_8)
                }
            }
        }

        override fun pushFile(data: ByteArray, remotePath: String) {
            openExecStream("cat > " + escapeShellArg(remotePath)).use { stream ->
                stream.openOutputStream().use { out ->
                    out.write(data)
                    out.flush()
                }
            }
        }

        override fun pushFileStream(
            inputStream: InputStream,
            remotePath: String,
            totalBytes: Long,
            progressListener: ((Long, Long) -> Unit)?
        ) {
            openExecStream("cat > " + escapeShellArg(remotePath)).use { stream ->
                stream.openOutputStream().use { out ->
                    val buf = ByteArray(65536)
                    var bytesWritten = 0L
                    var r: Int
                    while (inputStream.read(buf).also { r = it } != -1) {
                        out.write(buf, 0, r)
                        bytesWritten += r
                        progressListener?.invoke(bytesWritten, totalBytes)
                    }
                    out.flush()
                }
            }
        }

        override fun startServerProcess(cmd: String, callback: ServerOutputCallback?) {
            val stream = openExecStream(cmd)
            serverStream = stream
            Thread({
                try {
                    BufferedReader(InputStreamReader(stream.openInputStream(), StandardCharsets.UTF_8)).use { reader ->
                        var line: String?
                        while (true) {
                            try {
                                line = reader.readLine()
                            } catch (e: IOException) {
                                if (e.message != null && e.message!!.lowercase().contains("stream closed")) {
                                    break
                                }
                                throw e
                            }
                            if (line == null) break
                            Log.i(TAG, "[server] $line")
                            callback?.onServerLine(line)
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Server stream closed: ${e.message}")
                }
            }, "ModernServerStream").start()
        }

        override fun forwardPort(localPort: Int, remotePort: Int) {
            forwardServer?.let {
                try { it.close() } catch (ignored: Exception) {}
            }
            val srv = ServerSocket(localPort, 50, InetAddress.getByName("127.0.0.1"))
            forwardServer = srv
            Thread({
                while (!srv.isClosed) {
                    try {
                        val client = srv.accept()
                        client.tcpNoDelay = true
                        client.sendBufferSize = 1024 * 1024
                        client.receiveBufferSize = 1024 * 1024
                        forwardSockets.add(client)
                        val stream = connection.open("tcp:$remotePort")
                        forwardStreams.add(stream)

                        Thread({
                            try {
                                client.getInputStream().use { `in` ->
                                    stream.openOutputStream().use { out ->
                                        val buf = ByteArray(131072)
                                        var r: Int
                                        while (`in`.read(buf).also { r = it } != -1) {
                                            out.write(buf, 0, r)
                                            out.flush()
                                        }
                                    }
                                }
                            } catch (ignored: Exception) {}
                        }).start()

                        Thread({
                            try {
                                stream.openInputStream().use { `in` ->
                                    client.getOutputStream().use { out ->
                                        val buf = ByteArray(262144)
                                        var r: Int
                                        while (`in`.read(buf).also { r = it } != -1) {
                                            out.write(buf, 0, r)
                                            out.flush()
                                        }
                                    }
                                }
                            } catch (ignored: Exception) {}
                        }).start()
                    } catch (e: Exception) {
                        if (srv.isClosed) break
                    }
                }
            }, "ModernPortForward-$localPort").start()
        }

        override fun close() {
            serverStream?.let {
                try { it.close() } catch (ignored: Exception) {}
                serverStream = null
            }
            forwardServer?.let {
                try { it.close() } catch (ignored: Exception) {}
                forwardServer = null
            }
            for (s in forwardSockets) {
                try { s.close() } catch (ignored: Exception) {}
            }
            forwardSockets.clear()
            for (s in forwardStreams) {
                try { s.close() } catch (ignored: Exception) {}
            }
            forwardStreams.clear()
            try { connection.close() } catch (ignored: Exception) {}
        }
    }

    private class LegacySession(
        private val socket: Socket,
        private val connection: com.tananaev.adblib.AdbConnection
    ) : AdbSession {
        private var serverStream: com.tananaev.adblib.AdbStream? = null
        private var forwardServer: ServerSocket? = null
        private val forwardSockets = CopyOnWriteArrayList<Socket>()
        private val forwardStreams = CopyOnWriteArrayList<com.tananaev.adblib.AdbStream>()

        override fun execute(cmd: String): String {
            connection.open("exec:$cmd").use { stream ->
                val baos = ByteArrayOutputStream()
                while (!stream.isClosed) {
                    try {
                        val r = stream.read()
                        if (r != null && r.isNotEmpty()) {
                            baos.write(r)
                        }
                    } catch (e: IOException) {
                        if (e.message == null || !e.message!!.lowercase().contains("stream closed")) {
                            throw e
                        }
                        break
                    } catch (e: Exception) {
                        break
                    }
                }
                return String(baos.toByteArray(), StandardCharsets.UTF_8)
            }
        }

        override fun pushFile(data: ByteArray, remotePath: String) {
            connection.open("exec:cat > " + escapeShellArg(remotePath)).use { stream ->
                var offset = 0
                val chunkSize = 4096
                while (offset < data.size) {
                    val len = Math.min(chunkSize, data.size - offset)
                    val chunk = ByteArray(len)
                    System.arraycopy(data, offset, chunk, 0, len)
                    stream.write(chunk)
                    offset += len
                }
            }
        }

        override fun pushFileStream(
            inputStream: InputStream,
            remotePath: String,
            totalBytes: Long,
            progressListener: ((Long, Long) -> Unit)?
        ) {
            connection.open("exec:cat > " + escapeShellArg(remotePath)).use { stream ->
                val buf = ByteArray(4096)
                var bytesWritten = 0L
                var r: Int
                while (inputStream.read(buf).also { r = it } != -1) {
                    val chunk = if (r == buf.size) buf else buf.copyOf(r)
                    stream.write(chunk)
                    bytesWritten += r
                    progressListener?.invoke(bytesWritten, totalBytes)
                }
            }
        }

        override fun startServerProcess(cmd: String, callback: ServerOutputCallback?) {
            val stream = connection.open("exec:$cmd")
            serverStream = stream
            Thread({
                try {
                    while (!stream.isClosed) {
                        val r: ByteArray? = try {
                            stream.read()
                        } catch (e: IOException) {
                            if (e.message != null && e.message!!.lowercase().contains("stream closed")) {
                                break
                            }
                            throw e
                        }
                        if (r != null && r.isNotEmpty()) {
                            val s = String(r, StandardCharsets.UTF_8)
                            Log.i(TAG, "[server] ${s.trim()}")
                            callback?.onServerLine(s)
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Legacy server stream closed: ${e.message}")
                }
            }, "LegacyServerStream").start()
        }

        override fun forwardPort(localPort: Int, remotePort: Int) {
            forwardServer?.let {
                try { it.close() } catch (ignored: Exception) {}
            }
            val srv = ServerSocket(localPort, 50, InetAddress.getByName("127.0.0.1"))
            forwardServer = srv
            Thread({
                while (!srv.isClosed) {
                    try {
                        val client = srv.accept()
                        client.tcpNoDelay = true
                        client.sendBufferSize = 512 * 1024
                        client.receiveBufferSize = 512 * 1024
                        forwardSockets.add(client)
                        val stream = connection.open("tcp:$remotePort")
                        forwardStreams.add(stream)

                        Thread({
                            try {
                                val `in` = client.getInputStream()
                                val buf = ByteArray(65536)
                                var r: Int
                                while (`in`.read(buf).also { r = it } != -1 && !stream.isClosed) {
                                    val chunk = ByteArray(r)
                                    System.arraycopy(buf, 0, chunk, 0, r)
                                    stream.write(chunk)
                                }
                            } catch (ignored: Exception) {}
                        }).start()

                        Thread({
                            try {
                                val out = client.getOutputStream()
                                while (!stream.isClosed && !client.isClosed) {
                                    val data = stream.read()
                                    if (data != null && data.isNotEmpty()) {
                                        out.write(data)
                                    }
                                }
                            } catch (ignored: Exception) {}
                        }).start()
                    } catch (e: Exception) {
                        if (srv.isClosed) break
                    }
                }
            }, "LegacyPortForward-$localPort").start()
        }

        override fun close() {
            serverStream?.let {
                try { it.close() } catch (ignored: Exception) {}
                serverStream = null
            }
            forwardServer?.let {
                try { it.close() } catch (ignored: Exception) {}
                forwardServer = null
            }
            for (s in forwardSockets) {
                try { s.close() } catch (ignored: Exception) {}
            }
            forwardSockets.clear()
            for (s in forwardStreams) {
                try { s.close() } catch (ignored: Exception) {}
            }
            forwardStreams.clear()
            try { socket.close() } catch (ignored: Exception) {}
        }
    }

    private fun ensureLegacyCrypto(context: Context): AdbCrypto {
        val privFile = context.getFileStreamPath("priv.key")
        val pubFile = context.getFileStreamPath("pub.key")

        val kp = AdbKeyManager.getKeyPair(context)

        FileOutputStream(privFile).use { fos ->
            fos.write(kp.privateKey.encoded)
        }
        val privCrt = kp.privateKey as RSAPrivateCrtKey
        val pubSpec = RSAPublicKeySpec(privCrt.modulus, privCrt.publicExponent)
        val kf = KeyFactory.getInstance("RSA")
        val pubKey = kf.generatePublic(pubSpec)
        val pubBase64 = Base64.encodeToString(pubKey.encoded, Base64.NO_WRAP)
        FileOutputStream(pubFile).use { fos ->
            fos.write(("$pubBase64 scrcpy-android\u0000").toByteArray(StandardCharsets.UTF_8))
        }

        return AdbCrypto.loadAdbKeyPair(getBase64Impl(), privFile, pubFile)
    }

    fun SendAdbCommands(
        context: Context?,
        fileBytes: ByteArray?,
        ip: String?,
        port: Int,
        bitrate: Int,
        size: Int,
        maxFps: Int,
        videoCodec: String?,
        audio: Boolean,
        audioCodec: String?,
        control: Boolean,
        stayAwake: Boolean
    ): Int {
        this.context = context
        status = 1
        lastError = null

        val command = StringBuilder().apply {
            append("CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server 5.0.1")
            append(" port=").append(port)
            append(" tunnel_forward=true")
            append(" video_bit_rate=").append(bitrate)
            if (size > 0) {
                append(" max_size=").append(size)
            }
            if (maxFps > 0) {
                append(" max_fps=").append(maxFps)
            }
            append(" video_codec=").append(videoCodec ?: "h264")
            append(" audio=").append(audio)
            if (audio) {
                append(" audio_codec=").append(audioCodec ?: "opus")
            }
            append(" control=").append(control)
            append(" stay_awake=").append(stayAwake)
            append(" cleanup=false")
            append(" send_device_meta=true")
            append(" send_frame_meta=true")
            append(" send_stream_meta=true")
            append(" send_dummy_byte=true")
        }

        val t = Thread({
            try {
                adbWrite(ip, fileBytes, command.toString())
            } catch (e: Exception) {
                Log.e(TAG, "adbWrite unexpected exception", e)
                status = 2
                if (lastError == null) {
                    lastError = context?.getString(R.string.err_execution_exception, e.message)
                        ?: ("Execution exception: " + e.message)
                }
            }
        })
        thread = t
        t.start()

        var count = 0
        while (status == 1 && count < 300) {
            try {
                Thread.sleep(100)
                count++
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }
        if (count == 300) {
            status = 2
            if (lastError == null) {
                lastError = context?.getString(R.string.err_deploy_timeout)
                    ?: "Connection or server deployment timed out (30s)"
            }
        }
        return status
    }

    fun SendAdbCommands(
        context: Context?,
        fileBytes: ByteArray?,
        ip: String?,
        localip: String?,
        bitrate: Int,
        size: Int
    ): Int {
        return SendAdbCommands(context, fileBytes, ip, 7007, bitrate, size, 0, "h264", false, "raw", true, false)
    }

    private fun adbWrite(ip: String?, fileData: ByteArray?, command: String) {
        var targetHost = ip ?: "127.0.0.1"
        var targetAdbPort = 5555
        if (ip != null) {
            val colonIndex = ip.indexOf(':')
            if (colonIndex != -1) {
                targetHost = ip.substring(0, colonIndex).trim()
                try {
                    targetAdbPort = ip.substring(colonIndex + 1).trim().toInt()
                } catch (ignored: NumberFormatException) {
                }
            }
        }

        closeActiveSession()
        var session: AdbSession? = null
        val isUsbLocal = "127.0.0.1" == targetHost
        val apiLevel = Math.max(31, Build.VERSION.SDK_INT)
        val ctx = context ?: throw IOException("Context is null")

        // 1. USB local connection
        if (isUsbLocal) {
            try {
                Log.d(TAG, "Attempting high-speed Modern ADB connection for USB OTG to 127.0.0.1:$targetAdbPort")
                val kp = AdbKeyManager.getKeyPair(ctx)
                val modernConn = io.github.muntashirakon.adb.AdbConnection.create(
                    targetHost, targetAdbPort, kp.privateKey, kp.certificate, apiLevel
                )
                modernConn.connect()
                session = ModernSession(modernConn)
                Log.d(TAG, "High-speed Modern ADB session established for USB (maxData=${modernConn.maxData})")
            } catch (e: Exception) {
                Log.w(TAG, "Modern ADB failed for USB (${e.message}), falling back to legacy...", e)
            }
        }

        // 2. Wi-Fi: If target port is not 5555, try Modern TLS first
        if (!isUsbLocal && targetAdbPort != 5555) {
            try {
                Log.d(TAG, "Attempting TLS connection to $targetHost:$targetAdbPort")
                val kp = AdbKeyManager.getKeyPair(ctx)
                val modernConn = io.github.muntashirakon.adb.AdbConnection.create(
                    targetHost, targetAdbPort, kp.privateKey, kp.certificate, apiLevel
                )
                modernConn.connect()
                session = ModernSession(modernConn)
                Log.d(TAG, "TLS ADB session established with $targetHost:$targetAdbPort")
            } catch (e: io.github.muntashirakon.adb.AdbPairingRequiredException) {
                status = 2
                lastError = context?.getString(R.string.err_not_paired) ?: "Device not paired! Please pair first."
                return
            } catch (e: Exception) {
                Log.w(TAG, "TLS connection failed (${e.message}), trying legacy ADB...", e)
            }
        }

        // 3. Legacy unencrypted connection
        if (session == null && status == 1) {
            try {
                Log.d(TAG, "Attempting Legacy ADB connection to $targetHost:$targetAdbPort")
                val crypto = ensureLegacyCrypto(ctx)
                val sock = Socket(targetHost, targetAdbPort)
                val adb = com.tananaev.adblib.AdbConnection.create(sock, crypto)
                adb.connect()
                session = LegacySession(sock, adb)
                Log.d(TAG, "Legacy ADB session established with $targetHost:$targetAdbPort")
            } catch (e: ConnectException) {
                status = 2
                lastError = context?.getString(R.string.err_connection_refused, targetHost, targetAdbPort)
                    ?: "Connection to $targetHost:$targetAdbPort refused."
                return
            } catch (e: Exception) {
                if (targetAdbPort == 5555) {
                    try {
                        Log.d(TAG, "Trying TLS on 5555 as fallback")
                        val kp = AdbKeyManager.getKeyPair(ctx)
                        val modernConn = io.github.muntashirakon.adb.AdbConnection.create(
                            targetHost, targetAdbPort, kp.privateKey, kp.certificate, apiLevel
                        )
                        modernConn.connect()
                        session = ModernSession(modernConn)
                    } catch (pe: io.github.muntashirakon.adb.AdbPairingRequiredException) {
                        status = 2
                        lastError = context?.getString(R.string.err_not_paired) ?: "Device not paired! Please pair first."
                        return
                    } catch (te: Exception) {
                        status = 2
                        lastError = context?.getString(R.string.err_cannot_connect, targetHost, targetAdbPort, te.message)
                            ?: "Cannot connect to $targetHost:$targetAdbPort: ${te.message}"
                        return
                    }
                } else {
                    status = 2
                    lastError = context?.getString(R.string.err_cannot_connect, targetHost, targetAdbPort, e.message)
                        ?: "Cannot connect to $targetHost:$targetAdbPort: ${e.message}"
                    return
                }
            }
        }

        if (session != null && status == 1) {
            try {
                deployServer(session, fileData, command)
                if ("127.0.0.1" == targetHost) {
                    Log.d(TAG, "Setting up local port forwarding for USB session on port 7007")
                    session.forwardPort(7007, 7007)
                }
                activeSession = session
                status = 0
            } catch (e: Exception) {
                Log.e(TAG, "Deploying server failed", e)
                status = 2
                lastError = context?.getString(R.string.err_deploy_failed, e.message)
                    ?: "Failed to deploy server: ${e.message}"
                try {
                    session.close()
                } catch (ignored: Exception) {}
            }
        }
    }

    private fun deployServer(session: AdbSession, fileData: ByteArray?, command: String) {
        var rawBytes = fileData
        if (fileData != null && fileData.size > 4) {
            if (fileData[0] != 0x50.toByte() || fileData[1] != 0x4b.toByte()) {
                try {
                    rawBytes = Base64.decode(fileData, Base64.DEFAULT)
                } catch (ignored: Exception) {
                }
            }
        }

        if (rawBytes == null || rawBytes.isEmpty()) {
            throw IOException("Server jar data is empty!")
        }

        val remotePath = "/data/local/tmp/scrcpy-server.jar"

        // 1. Check if server already exists
        var needPush = true
        val lsOutput = session.execute("ls -l $remotePath 2>/dev/null")
        if (lsOutput.contains(" ${rawBytes.size} ")) {
            Log.d(TAG, "scrcpy-server.jar is already up-to-date (${rawBytes.size} bytes), skipping upload!")
            needPush = false
        }

        // 2. Direct binary push
        if (needPush) {
            Log.d(TAG, "Pushing scrcpy-server.jar (${rawBytes.size} bytes) via binary exec:cat stream...")
            session.pushFile(rawBytes, remotePath)
            session.execute("chmod 755 $remotePath")

            val verifyLs = session.execute("ls -l $remotePath 2>/dev/null")
            Log.d(TAG, "Verify upload: ${verifyLs.trim()}")
            if (!verifyLs.contains(" ${rawBytes.size} ")) {
                throw IOException("Pushed file size verification failed: ${verifyLs.trim()}")
            }
        }

        // 3. Terminate previous server instance
        try {
            session.execute("pkill -f com.genymobile.scrcpy.Server 2>/dev/null")
        } catch (ignored: Exception) {}

        // 4. Start scrcpy server
        Log.d(TAG, "Starting scrcpy server process: $command")
        val serverStarted = booleanArrayOf(false)
        val startupError = arrayOfNulls<String>(1)

        session.startServerProcess(command) { line ->
            if (line.contains("[server] INFO:") || line.contains("Device:")) {
                serverStarted[0] = true
            }
            if (line.contains("Exception") || line.contains("Error") || line.contains("ERROR:")) {
                startupError[0] = line
            }
        }

        // Wait up to 3 seconds for server banner
        for (i in 0 until 30) {
            if (serverStarted[0]) {
                Log.d(TAG, "scrcpy server confirmed running!")
                break
            }
            if (startupError[0] != null) {
                throw IOException("Server failed on startup: ${startupError[0]}")
            }
            Thread.sleep(100)
        }
    }

    companion object {
        private const val TAG = "SendCommands"

        @Volatile
        private var activeSession: AdbSession? = null

        @JvmStatic
        @Synchronized
        fun closeActiveSession() {
            activeSession?.let {
                try {
                    it.close()
                } catch (ignored: Exception) {
                }
                activeSession = null
            }
        }

        @JvmStatic
        fun getBase64Impl(): AdbBase64 {
            return AdbBase64 { arg0 -> Base64.encodeToString(arg0, Base64.NO_WRAP) }
        }

        @JvmStatic
        fun hasActiveSession(): Boolean {
            return activeSession != null
        }

        @JvmStatic
        fun escapeShellArg(arg: String): String {
            return "'" + arg.replace("'", "'\\''") + "'"
        }

        @JvmStatic
        fun pushRemoteFile(
            inputStream: InputStream,
            remotePath: String,
            totalBytes: Long,
            progressListener: ((Long, Long) -> Unit)? = null
        ): Boolean {
            val session = activeSession ?: run {
                Log.e(TAG, "pushRemoteFile: No active ADB session")
                return false
            }
            val dir = remotePath.substringBeforeLast('/', "")
            val tempPath = "/sdcard/Scrcpy/.tmp_${System.currentTimeMillis()}_${(1000..9999).random()}.tmp"

            // 1. Ensure parent /sdcard/Scrcpy directory exists
            try {
                session.execute("mkdir -p '/sdcard/Scrcpy'")
            } catch (ignored: Exception) {}

            // 2. Stream to safe ASCII temporary path first
            try {
                session.pushFileStream(inputStream, tempPath, totalBytes, progressListener)
            } catch (e: Exception) {
                Log.e(TAG, "pushFileStream failed for $tempPath", e)
                try { session.execute("rm -f " + escapeShellArg(tempPath)) } catch (ignored: Exception) {}
                return false
            }

            // 3. Ensure final target directory exists
            if (dir.isNotEmpty()) {
                try {
                    val mkdirCmd = "mkdir -p " + escapeShellArg(dir)
                    val res = session.execute(mkdirCmd)
                    if (res.isNotBlank()) {
                        Log.d(TAG, "mkdir result: $res")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "mkdir failed: ${e.message}")
                }
            }

            // 4. Move temporary file to final remotePath
            try {
                val mvCmd = "mv -f " + escapeShellArg(tempPath) + " " + escapeShellArg(remotePath)
                val mvRes = session.execute(mvCmd)
                if (mvRes.isNotBlank()) {
                    Log.d(TAG, "mv result: $mvRes")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to move $tempPath to $remotePath", e)
                try { session.execute("rm -f " + escapeShellArg(tempPath)) } catch (ignored: Exception) {}
                return false
            }

            // 5. Update permissions
            try {
                session.execute("chmod 644 " + escapeShellArg(remotePath))
            } catch (ignored: Exception) {}

            return true
        }

        @JvmStatic
        fun executeRemoteCommand(cmd: String): String? {
            return try {
                activeSession?.execute(cmd)
            } catch (e: Exception) {
                null
            }
        }
    }
}

