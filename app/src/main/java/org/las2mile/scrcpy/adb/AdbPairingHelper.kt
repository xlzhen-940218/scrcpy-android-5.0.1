package org.las2mile.scrcpy.adb

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.muntashirakon.adb.PairingConnectionCtx
import java.nio.charset.StandardCharsets

object AdbPairingHelper {
    private const val TAG = "AdbPairingHelper"

    interface PairingCallback {
        fun onSuccess()
        fun onFailure(error: String)
    }

    @JvmStatic
    fun pair(context: Context, host: String?, port: Int, pairingCode: String?, callback: PairingCallback) {
        Thread({
            val mainHandler = Handler(Looper.getMainLooper())
            try {
                require(!host.isNullOrBlank()) { "Host IP address cannot be empty" }
                require(port in 1..65535) { "Invalid pairing port: $port" }
                require(!pairingCode.isNullOrBlank()) { "Pairing code cannot be empty" }

                val cleanCode = pairingCode.trim()
                var cleanHost = host.trim()
                if (cleanHost.contains(":")) {
                    cleanHost = cleanHost.substring(0, cleanHost.indexOf(':')).trim()
                }

                Log.d(TAG, "Starting pairing with $cleanHost:$port")
                val keyPair = AdbKeyManager.getKeyPair(context)

                PairingConnectionCtx(
                    cleanHost,
                    port,
                    cleanCode.toByteArray(StandardCharsets.UTF_8),
                    keyPair.privateKey,
                    keyPair.certificate,
                    "scrcpy-android"
                ).use { ctx ->
                    ctx.start()
                    Log.d(TAG, "Pairing successful with $cleanHost:$port")
                    mainHandler.post { callback.onSuccess() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Pairing failed", e)
                var msg = e.message
                if (msg.isNullOrEmpty()) {
                    msg = e.toString()
                }
                if (msg.contains("Connection refused") || msg.contains("ECONNREFUSED")) {
                    msg = "Connection refused on port $port. Ensure 'Pair device with pairing code' dialog is open on target phone."
                } else if (msg.contains("closed with errors") || msg.contains("bad_record_mac") || msg.contains("decryption failed")) {
                    msg = "Pairing failed: Incorrect pairing code or pairing expired. Check code on target phone."
                }
                val finalMsg = msg
                mainHandler.post { callback.onFailure(finalMsg) }
            }
        }, "AdbPairingThread").start()
    }
}
