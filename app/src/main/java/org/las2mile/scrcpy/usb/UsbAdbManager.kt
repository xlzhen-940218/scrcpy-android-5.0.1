package org.las2mile.scrcpy.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import org.las2mile.scrcpy.R
import java.io.IOException

class UsbAdbManager private constructor(private val context: Context) {

    interface UsbListener {
        fun onDeviceDiscovered(device: UsbDevice)
        fun onDeviceConnected(device: UsbDevice, bridgePort: Int)
        fun onDeviceDisconnected(device: UsbDevice)
        fun onError(message: String)
    }

    private val usbManager: UsbManager? = context.getSystemService(Context.USB_SERVICE) as? UsbManager
    private var listener: UsbListener? = null

    var connectedDevice: UsbDevice? = null
        private set
    private var activeBridge: UsbAdbBridge? = null
    private var isRegistered = false

    fun setListener(listener: UsbListener?) {
        this.listener = listener
    }

    @Synchronized
    fun register() {
        if (isRegistered) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
        isRegistered = true
        Log.d(TAG, "UsbAdbManager registered broadcast receiver")
    }

    @Synchronized
    fun unregister() {
        if (!isRegistered) return
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (ignored: Exception) {
        }
        isRegistered = false
        Log.d(TAG, "UsbAdbManager unregistered broadcast receiver")
    }

    fun findAdbDevice(): UsbDevice? {
        val manager = usbManager ?: return null
        val deviceList = manager.deviceList ?: return null
        if (deviceList.isEmpty()) return null

        for (device in deviceList.values) {
            if (findAdbInterface(device) != null) {
                return device
            }
        }
        return null
    }

    fun findAdbInterface(device: UsbDevice?): UsbInterface? {
        if (device == null) return null
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            // Standard ADB interface: Class 0xFF (Vendor), Subclass 0x42 (ADB), Protocol 0x01
            if (intf.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                intf.interfaceSubclass == 0x42 &&
                intf.interfaceProtocol == 0x01
            ) {
                return intf
            }
        }
        return null
    }

    fun connect(device: UsbDevice?) {
        if (device == null) {
            listener?.onError(context.getString(R.string.usb_err_no_device))
            return
        }

        val manager = usbManager
        if (manager == null) {
            listener?.onError(context.getString(R.string.usb_err_no_device))
            return
        }

        if (!manager.hasPermission(device)) {
            Log.d(TAG, "Requesting USB permission for ${device.deviceName}")
            val intent = Intent(ACTION_USB_PERMISSION).apply {
                `package` = context.packageName
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(context, 0, intent, flags)
            manager.requestPermission(device, pi)
            return
        }

        openAdbDevice(device)
    }

    @Synchronized
    private fun openAdbDevice(device: UsbDevice) {
        disconnect()

        val adbInterface = findAdbInterface(device)
        if (adbInterface == null) {
            listener?.onError(context.getString(R.string.usb_err_no_adb_mode))
            return
        }

        var epIn: android.hardware.usb.UsbEndpoint? = null
        var epOut: android.hardware.usb.UsbEndpoint? = null
        for (i in 0 until adbInterface.endpointCount) {
            val ep = adbInterface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) {
                    epIn = ep
                } else {
                    epOut = ep
                }
            }
        }

        if (epIn == null || epOut == null) {
            listener?.onError(context.getString(R.string.usb_err_no_endpoints))
            return
        }

        val conn = usbManager?.openDevice(device)
        if (conn == null) {
            listener?.onError(context.getString(R.string.usb_err_open_failed))
            return
        }

        if (!conn.claimInterface(adbInterface, true)) {
            conn.close()
            listener?.onError(context.getString(R.string.usb_err_claim_failed))
            return
        }

        try {
            val bridge = UsbAdbBridge(conn, adbInterface, epIn, epOut)
            val port = bridge.start()
            activeBridge = bridge
            connectedDevice = device
            Log.d(TAG, "USB ADB connected successfully on port $port")
            listener?.onDeviceConnected(device, port)
        } catch (e: IOException) {
            conn.releaseInterface(adbInterface)
            conn.close()
            listener?.onError(context.getString(R.string.usb_err_bridge_start, e.message))
        }
    }

    @Synchronized
    fun disconnect() {
        activeBridge?.let {
            it.close()
            activeBridge = null
        }
        val prev = connectedDevice
        connectedDevice = null
        if (prev != null) {
            listener?.onDeviceDisconnected(prev)
        }
    }

    val isConnected: Boolean
        get() = connectedDevice != null && activeBridge != null

    val bridgePort: Int
        get() = activeBridge?.port ?: -1

    fun getDeviceDisplayName(device: UsbDevice?): String {
        if (device == null) return context.getString(R.string.usb_unknown_device)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val prod = device.productName
            val mfg = device.manufacturerName
            if (!prod.isNullOrEmpty()) {
                if (!mfg.isNullOrEmpty() && !prod.startsWith(mfg)) {
                    return "$mfg $prod"
                }
                return prod
            }
        }
        return context.getString(R.string.usb_device_format, device.vendorId, device.productId)
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED == action) {
                @Suppress("DEPRECATION")
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                Log.d(TAG, "USB device attached: $device")
                if (device != null && findAdbInterface(device) != null) {
                    listener?.onDeviceDiscovered(device)
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED == action) {
                @Suppress("DEPRECATION")
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                Log.d(TAG, "USB device detached: $device")
                if (device != null && connectedDevice != null && device.deviceId == connectedDevice?.deviceId) {
                    disconnect()
                }
            } else if (ACTION_USB_PERMISSION == action) {
                @Suppress("DEPRECATION")
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                Log.d(TAG, "USB permission result: $granted for $device")
                if (granted && device != null) {
                    openAdbDevice(device)
                } else {
                    listener?.onError(context.getString(R.string.usb_err_permission_denied))
                }
            }
        }
    }

    companion object {
        private const val TAG = "UsbAdbManager"
        const val ACTION_USB_PERMISSION = "org.las2mile.scrcpy.USB_PERMISSION"

        @Volatile
        private var instance: UsbAdbManager? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): UsbAdbManager {
            return instance ?: UsbAdbManager(context.applicationContext).also { instance = it }
        }
    }
}
