package org.las2mile.scrcpy.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class UsbAdbBridge(
    private val connection: UsbDeviceConnection?,
    private val usbInterface: UsbInterface?,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint
) : Closeable {

    private var serverSocket: ServerSocket? = null
    var port: Int = -1
        private set
    private val isRunning = AtomicBoolean(false)

    private var acceptThread: Thread? = null
    private var activeTcpSocket: Socket? = null

    @Synchronized
    @Throws(IOException::class)
    fun start(): Int {
        if (isRunning.get()) {
            return port
        }

        val s = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        s.receiveBufferSize = 512 * 1024
        serverSocket = s
        port = s.localPort
        isRunning.set(true)

        val t = Thread({ acceptLoop() }, "UsbAdbBridge-Accept")
        acceptThread = t
        t.start()

        Log.d(TAG, "UsbAdbBridge started on 127.0.0.1:$port")
        return port
    }

    private fun acceptLoop() {
        while (isRunning.get()) {
            try {
                val s = serverSocket ?: break
                val clientSocket = s.accept()
                clientSocket.tcpNoDelay = true
                clientSocket.sendBufferSize = 512 * 1024
                clientSocket.receiveBufferSize = 512 * 1024
                activeTcpSocket = clientSocket
                Log.d(TAG, "Accepted TCP connection from ADB client")

                handleClient(clientSocket)
            } catch (e: IOException) {
                if (!isRunning.get()) break
                Log.e(TAG, "Error accepting client", e)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        val tcpToUsb = Thread({ runTcpToUsb(socket) }, "UsbAdb-TcpToUsb")
        val usbToTcp = Thread({ runUsbToTcp(socket) }, "UsbAdb-UsbToTcp")

        tcpToUsb.start()
        usbToTcp.start()

        try {
            tcpToUsb.join()
        } catch (ignored: InterruptedException) {
        }

        try {
            usbToTcp.join()
        } catch (ignored: InterruptedException) {
        }

        try {
            socket.close()
        } catch (ignored: Exception) {
        }
    }

    private fun runTcpToUsb(socket: Socket) {
        try {
            val inStream = socket.getInputStream()
            val header = ByteArray(24)
            val payloadBuf = ByteArray(262144)

            while (isRunning.get() && !socket.isClosed) {
                val read = readFully(inStream, header, 0, 24)
                if (read < 24) break

                val payloadLength = (header[12].toInt() and 0xFF) or
                        ((header[13].toInt() and 0xFF) shl 8) or
                        ((header[14].toInt() and 0xFF) shl 16) or
                        ((header[15].toInt() and 0xFF) shl 24)

                val sent = connection?.bulkTransfer(endpointOut, header, 24, TIMEOUT_MS) ?: -1
                if (sent < 0) {
                    Log.w(TAG, "Failed to send header via USB: $sent")
                    break
                }

                if (payloadLength in 1 until 16 * 1024 * 1024) {
                    val payload = if (payloadLength <= payloadBuf.size) payloadBuf else ByteArray(payloadLength)
                    val payloadRead = readFully(inStream, payload, 0, payloadLength)
                    if (payloadRead < payloadLength) break

                    val payloadSent = connection?.bulkTransfer(endpointOut, payload, payloadLength, TIMEOUT_MS) ?: -1
                    if (payloadSent < 0) {
                        Log.w(TAG, "Failed to send payload via USB: $payloadSent")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "TcpToUsb stopped: ${e.message}")
        }
    }

    private fun runUsbToTcp(socket: Socket) {
        try {
            val out = socket.getOutputStream()
            val buf = ByteArray(262144)

            while (isRunning.get() && !socket.isClosed) {
                val read = connection?.bulkTransfer(endpointIn, buf, buf.size, 1000) ?: -1
                if (read > 0) {
                    out.write(buf, 0, read)
                    out.flush()
                } else if (read < 0) {
                    if (!isRunning.get()) break
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "UsbToTcp stopped: ${e.message}")
        }
    }

    private fun readFully(inStream: InputStream, b: ByteArray, off: Int, len: Int): Int {
        var total = 0
        while (total < len) {
            val count = inStream.read(b, off + total, len - total)
            if (count < 0) return if (total == 0) -1 else total
            total += count
        }
        return total
    }

    @Synchronized
    override fun close() {
        isRunning.set(false)
        serverSocket?.let {
            try {
                it.close()
            } catch (ignored: Exception) {
            }
            serverSocket = null
        }
        activeTcpSocket?.let {
            try {
                it.close()
            } catch (ignored: Exception) {
            }
            activeTcpSocket = null
        }
        acceptThread?.let {
            it.interrupt()
            acceptThread = null
        }
        if (connection != null && usbInterface != null) {
            try {
                connection.releaseInterface(usbInterface)
            } catch (ignored: Exception) {
            }
            try {
                connection.close()
            } catch (ignored: Exception) {
            }
        }
        Log.d(TAG, "UsbAdbBridge closed")
    }

    companion object {
        private const val TAG = "UsbAdbBridge"
        private const val TIMEOUT_MS = 3000
    }
}

