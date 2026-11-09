package org.las2mile.scrcpy.protocol

import java.io.DataInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

class DeviceMessage {
    val type: Int
    val text: String?
    var data: ByteArray? = null
        private set

    constructor(type: Int, text: String?) {
        this.type = type
        this.text = text
    }

    constructor(type: Int, data: ByteArray?) {
        this.type = type
        this.text = null
        this.data = data
    }

    companion object {
        const val TYPE_CLIPBOARD = 0
        const val TYPE_ACK_CLIPBOARD = 1
        const val TYPE_UHID_OUTPUT = 2

        @JvmStatic
        @Throws(IOException::class)
        fun parse(dis: DataInputStream): DeviceMessage? {
            val type = dis.readUnsignedByte()
            return when (type) {
                TYPE_CLIPBOARD -> {
                    val len = dis.readInt()
                    val buf = ByteArray(len)
                    dis.readFully(buf)
                    DeviceMessage(type, String(buf, StandardCharsets.UTF_8))
                }
                TYPE_ACK_CLIPBOARD -> {
                    dis.readLong() // sequence
                    DeviceMessage(type, null as String?)
                }
                TYPE_UHID_OUTPUT -> {
                    dis.readUnsignedShort() // id
                    val len = dis.readUnsignedShort()
                    val buf = ByteArray(len)
                    dis.readFully(buf)
                    DeviceMessage(type, buf)
                }
                else -> null
            }
        }
    }
}

