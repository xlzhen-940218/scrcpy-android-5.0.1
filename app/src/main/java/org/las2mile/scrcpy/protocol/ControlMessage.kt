package org.las2mile.scrcpy.protocol

import android.view.KeyEvent
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

object ControlMessage {
    const val TYPE_INJECT_KEYCODE = 0
    const val TYPE_INJECT_TEXT = 1
    const val TYPE_INJECT_TOUCH_EVENT = 2
    const val TYPE_INJECT_SCROLL_EVENT = 3
    const val TYPE_BACK_OR_SCREEN_ON = 4
    const val TYPE_EXPAND_NOTIFICATION_PANEL = 5
    const val TYPE_EXPAND_SETTINGS_PANEL = 6
    const val TYPE_COLLAPSE_PANELS = 7
    const val TYPE_GET_CLIPBOARD = 8
    const val TYPE_SET_CLIPBOARD = 9
    const val TYPE_SET_DISPLAY_POWER = 10
    const val TYPE_ROTATE_DEVICE = 11
    const val TYPE_UHID_CREATE = 12
    const val TYPE_UHID_INPUT = 13
    const val TYPE_UHID_DESTROY = 14
    const val TYPE_OPEN_HARD_KEYBOARD_SETTINGS = 15
    const val TYPE_START_APP = 16
    const val TYPE_RESET_VIDEO = 17
    const val TYPE_CAMERA_SET_TORCH = 18
    const val TYPE_CAMERA_ZOOM_IN = 19
    const val TYPE_CAMERA_ZOOM_OUT = 20
    const val TYPE_RESIZE_DISPLAY = 21
    const val TYPE_SCAN_FILE = 22

    const val UHID_ID_KEYBOARD = 1
    const val UHID_ID_MOUSE = 2

    // Official scrcpy keyboard HID report descriptor
    val KEYBOARD_REPORT_DESC = byteArrayOf(
        0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
        0x09.toByte(), 0x06.toByte(), // Usage (Keyboard)
        0xA1.toByte(), 0x01.toByte(), // Collection (Application)
        0x05.toByte(), 0x07.toByte(), // Usage Page (Key Codes)
        0x19.toByte(), 0xE0.toByte(), // Usage Minimum (224)
        0x29.toByte(), 0xE7.toByte(), // Usage Maximum (231)
        0x15.toByte(), 0x00.toByte(), // Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(), // Logical Maximum (1)
        0x75.toByte(), 0x01.toByte(), // Report Size (1)
        0x95.toByte(), 0x08.toByte(), // Report Count (8)
        0x81.toByte(), 0x02.toByte(), // Input (Data, Variable, Absolute)
        0x75.toByte(), 0x08.toByte(), // Report Size (8)
        0x95.toByte(), 0x01.toByte(), // Report Count (1)
        0x81.toByte(), 0x01.toByte(), // Input (Constant)
        0x05.toByte(), 0x08.toByte(), // Usage Page (LEDs)
        0x19.toByte(), 0x01.toByte(), // Usage Minimum (1)
        0x29.toByte(), 0x05.toByte(), // Usage Maximum (5)
        0x75.toByte(), 0x01.toByte(), // Report Size (1)
        0x95.toByte(), 0x05.toByte(), // Report Count (5)
        0x91.toByte(), 0x02.toByte(), // Output (Data, Variable, Absolute)
        0x75.toByte(), 0x03.toByte(), // Report Size (3)
        0x95.toByte(), 0x01.toByte(), // Report Count (1)
        0x91.toByte(), 0x01.toByte(), // Output (Constant)
        0x05.toByte(), 0x07.toByte(), // Usage Page (Key Codes)
        0x19.toByte(), 0x00.toByte(), // Usage Minimum (0)
        0x29.toByte(), 0x65.toByte(), // Usage Maximum (101)
        0x15.toByte(), 0x00.toByte(), // Logical Minimum (0)
        0x25.toByte(), 0x65.toByte(), // Logical Maximum (101)
        0x75.toByte(), 0x08.toByte(), // Report Size (8)
        0x95.toByte(), 0x06.toByte(), // Report Count (6)
        0x81.toByte(), 0x00.toByte(), // Input (Data, Array)
        0xC0.toByte()                 // End Collection
    )

    // Official scrcpy mouse HID report descriptor
    val MOUSE_REPORT_DESC = byteArrayOf(
        0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
        0x09.toByte(), 0x02.toByte(), // Usage (Mouse)
        0xA1.toByte(), 0x01.toByte(), // Collection (Application)
        0x09.toByte(), 0x01.toByte(), // Usage (Pointer)
        0xA1.toByte(), 0x00.toByte(), // Collection (Physical)
        0x05.toByte(), 0x09.toByte(), // Usage Page (Buttons)
        0x19.toByte(), 0x01.toByte(), // Usage Minimum (1)
        0x29.toByte(), 0x05.toByte(), // Usage Maximum (5)
        0x15.toByte(), 0x00.toByte(), // Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(), // Logical Maximum (1)
        0x95.toByte(), 0x05.toByte(), // Report Count (5)
        0x75.toByte(), 0x01.toByte(), // Report Size (1)
        0x81.toByte(), 0x02.toByte(), // Input (Data, Variable, Absolute)
        0x95.toByte(), 0x01.toByte(), // Report Count (1)
        0x75.toByte(), 0x03.toByte(), // Report Size (3)
        0x81.toByte(), 0x01.toByte(), // Input (Constant)
        0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
        0x09.toByte(), 0x30.toByte(), // Usage (X)
        0x09.toByte(), 0x31.toByte(), // Usage (Y)
        0x09.toByte(), 0x38.toByte(), // Usage (Wheel)
        0x15.toByte(), 0x81.toByte(), // Logical Minimum (-127)
        0x25.toByte(), 0x7F.toByte(), // Logical Maximum (127)
        0x75.toByte(), 0x08.toByte(), // Report Size (8)
        0x95.toByte(), 0x03.toByte(), // Report Count (3)
        0x81.toByte(), 0x06.toByte(), // Input (Data, Variable, Relative)
        0x05.toByte(), 0x0C.toByte(), // Usage Page (Consumer Page)
        0x0A.toByte(), 0x38.toByte(), 0x02.toByte(), // Usage (AC Pan)
        0x15.toByte(), 0x81.toByte(), // Logical Minimum (-127)
        0x25.toByte(), 0x7F.toByte(), // Logical Maximum (127)
        0x75.toByte(), 0x08.toByte(), // Report Size (8)
        0x95.toByte(), 0x01.toByte(), // Report Count (1)
        0x81.toByte(), 0x06.toByte(), // Input (Data, Variable, Relative)
        0xC0.toByte(),                 // End Collection
        0xC0.toByte()                  // End Collection
    )

    @JvmStatic
    fun createResetVideo(): ByteArray {
        return byteArrayOf(TYPE_RESET_VIDEO.toByte())
    }

    @JvmStatic
    fun createInjectKeycode(action: Int, keycode: Int, repeat: Int, metaState: Int): ByteArray {
        val baos = ByteArrayOutputStream(14)
        val dos = DataOutputStream(baos)
        try {
            dos.writeByte(TYPE_INJECT_KEYCODE)
            dos.writeByte(action)
            dos.writeInt(keycode)
            dos.writeInt(repeat)
            dos.writeInt(metaState)
            dos.flush()
        } catch (ignored: IOException) {
        }
        return baos.toByteArray()
    }

    @JvmStatic
    fun createInjectText(text: String): ByteArray {
        val textBytes = text.toByteArray(StandardCharsets.UTF_8)
        val baos = ByteArrayOutputStream(5 + textBytes.size)
        val dos = DataOutputStream(baos)
        try {
            dos.writeByte(TYPE_INJECT_TEXT)
            dos.writeInt(textBytes.size)
            dos.write(textBytes)
            dos.flush()
        } catch (ignored: IOException) {
        }
        return baos.toByteArray()
    }

    @JvmStatic
    fun createInjectTouchEvent(
        action: Int,
        pointerId: Long,
        position: Position,
        pressure: Float,
        actionButton: Int,
        buttons: Int
    ): ByteArray {
        val baos = ByteArrayOutputStream(33)
        val dos = DataOutputStream(baos)
        try {
            dos.writeByte(TYPE_INJECT_TOUCH_EVENT)
            dos.writeByte(action)
            dos.writeLong(pointerId)
            dos.writeInt(position.x)
            dos.writeInt(position.y)
            dos.writeShort(position.screenWidth)
            dos.writeShort(position.screenHeight)
            val u16Pressure = (pressure.coerceIn(0f, 1f) * 0xFFFF).toInt()
            dos.writeShort(u16Pressure)
            dos.writeInt(actionButton)
            dos.writeInt(buttons)
            dos.flush()
        } catch (ignored: IOException) {
        }
        return baos.toByteArray()
    }

    @JvmStatic
    fun createInjectScrollEvent(position: Position, hScroll: Float, vScroll: Float, buttons: Int): ByteArray {
        val baos = ByteArrayOutputStream(22)
        val dos = DataOutputStream(baos)
        try {
            dos.writeByte(TYPE_INJECT_SCROLL_EVENT)
            dos.writeInt(position.x)
            dos.writeInt(position.y)
            dos.writeShort(position.screenWidth)
            dos.writeShort(position.screenHeight)
            val sHScroll = ((hScroll / 16.0f) * 0x7FFF).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            val sVScroll = ((vScroll / 16.0f) * 0x7FFF).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            dos.writeShort(sHScroll.toInt())
            dos.writeShort(sVScroll.toInt())
            dos.writeInt(buttons)
            dos.flush()
        } catch (ignored: IOException) {
        }
        return baos.toByteArray()
    }

    @JvmStatic
    fun createBackOrScreenOn(action: Int): ByteArray {
        return byteArrayOf(TYPE_BACK_OR_SCREEN_ON.toByte(), action.toByte())
    }

    @JvmStatic
    fun createEmpty(type: Int): ByteArray {
        return byteArrayOf(type.toByte())
    }

    @JvmStatic
    fun createSetDisplayPower(on: Boolean): ByteArray {
        return byteArrayOf(TYPE_SET_DISPLAY_POWER.toByte(), if (on) 1.toByte() else 0.toByte())
    }

    @JvmStatic
    fun createGetClipboard(): ByteArray {
        return byteArrayOf(TYPE_GET_CLIPBOARD.toByte(), 0.toByte())
    }

    @JvmStatic
    fun createSetClipboard(text: String, paste: Boolean): ByteArray {
        val textBytes = text.toByteArray(StandardCharsets.UTF_8)
        val baos = ByteArrayOutputStream(14 + textBytes.size)
        val dos = DataOutputStream(baos)
        try {
            dos.writeByte(TYPE_SET_CLIPBOARD)
            dos.writeLong(0) // sequence
            dos.writeByte(if (paste) 1 else 0)
            dos.writeInt(textBytes.size)
            dos.write(textBytes)
            dos.flush()
        } catch (ignored: IOException) {
        }
        return baos.toByteArray()
    }

    @JvmStatic
    fun createScanFile(path: String): ByteArray {
        val pathBytes = path.toByteArray(StandardCharsets.UTF_8)
        val baos = ByteArrayOutputStream(5 + pathBytes.size)
        val dos = DataOutputStream(baos)
        try {
            dos.writeByte(TYPE_SCAN_FILE)
            dos.writeInt(pathBytes.size)
            dos.write(pathBytes)
            dos.flush()
        } catch (ignored: IOException) {
        }
        return baos.toByteArray()
    }

    @JvmStatic
    fun createUhidCreate(
        id: Int,
        vendorId: Int = 0,
        productId: Int = 0,
        name: String = "",
        reportDesc: ByteArray
    ): ByteArray {
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        val nameLen = Math.min(nameBytes.size, 127)
        val baos = ByteArrayOutputStream(1 + 2 + 2 + 2 + 1 + nameLen + 2 + reportDesc.size)
        val dos = DataOutputStream(baos)
        try {
            dos.writeByte(TYPE_UHID_CREATE)
            dos.writeShort(id)
            dos.writeShort(vendorId)
            dos.writeShort(productId)
            dos.writeByte(nameLen)
            dos.write(nameBytes, 0, nameLen)
            dos.writeShort(reportDesc.size)
            dos.write(reportDesc)
            dos.flush()
        } catch (ignored: IOException) {
        }
        return baos.toByteArray()
    }

    @JvmStatic
    fun createUhidInput(id: Int, data: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream(1 + 2 + 2 + data.size)
        val dos = DataOutputStream(baos)
        try {
            dos.writeByte(TYPE_UHID_INPUT)
            dos.writeShort(id)
            dos.writeShort(data.size)
            dos.write(data)
            dos.flush()
        } catch (ignored: IOException) {
        }
        return baos.toByteArray()
    }

    @JvmStatic
    fun createUhidDestroy(id: Int): ByteArray {
        val baos = ByteArrayOutputStream(3)
        val dos = DataOutputStream(baos)
        try {
            dos.writeByte(TYPE_UHID_DESTROY)
            dos.writeShort(id)
            dos.flush()
        } catch (ignored: IOException) {
        }
        return baos.toByteArray()
    }

    @JvmStatic
    fun createUhidKeyboardReport(modifiers: Int, keys: IntArray): ByteArray {
        val report = ByteArray(8)
        report[0] = (modifiers and 0xFF).toByte()
        report[1] = 0 // reserved
        val count = Math.min(keys.size, 6)
        for (i in 0 until count) {
            report[2 + i] = (keys[i] and 0xFF).toByte()
        }
        return createUhidInput(UHID_ID_KEYBOARD, report)
    }

    @JvmStatic
    fun createUhidMouseReport(
        buttons: Int,
        relX: Int,
        relY: Int,
        vScroll: Int,
        hScroll: Int
    ): ByteArray {
        val report = ByteArray(5)
        report[0] = (buttons and 0x1F).toByte()
        report[1] = relX.coerceIn(-127, 127).toByte()
        report[2] = relY.coerceIn(-127, 127).toByte()
        report[3] = vScroll.coerceIn(-127, 127).toByte()
        report[4] = hScroll.coerceIn(-127, 127).toByte()
        return createUhidInput(UHID_ID_MOUSE, report)
    }

    fun isModifierKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> true
            else -> false
        }
    }

    fun getModifierMask(keyCode: Int): Int {
        return when (keyCode) {
            KeyEvent.KEYCODE_CTRL_LEFT -> 1 shl 0
            KeyEvent.KEYCODE_SHIFT_LEFT -> 1 shl 1
            KeyEvent.KEYCODE_ALT_LEFT -> 1 shl 2
            KeyEvent.KEYCODE_META_LEFT -> 1 shl 3
            KeyEvent.KEYCODE_CTRL_RIGHT -> 1 shl 4
            KeyEvent.KEYCODE_SHIFT_RIGHT -> 1 shl 5
            KeyEvent.KEYCODE_ALT_RIGHT -> 1 shl 6
            KeyEvent.KEYCODE_META_RIGHT -> 1 shl 7
            else -> 0
        }
    }

    fun getModifiersFromMetaState(metaState: Int): Int {
        var mods = 0
        if ((metaState and KeyEvent.META_CTRL_LEFT_ON) != 0 || (metaState and KeyEvent.META_CTRL_ON) != 0) mods = mods or (1 shl 0)
        if ((metaState and KeyEvent.META_SHIFT_LEFT_ON) != 0 || (metaState and KeyEvent.META_SHIFT_ON) != 0) mods = mods or (1 shl 1)
        if ((metaState and KeyEvent.META_ALT_LEFT_ON) != 0 || (metaState and KeyEvent.META_ALT_ON) != 0) mods = mods or (1 shl 2)
        if ((metaState and KeyEvent.META_META_LEFT_ON) != 0 || (metaState and KeyEvent.META_META_ON) != 0) mods = mods or (1 shl 3)
        if ((metaState and KeyEvent.META_CTRL_RIGHT_ON) != 0) mods = mods or (1 shl 4)
        if ((metaState and KeyEvent.META_SHIFT_RIGHT_ON) != 0) mods = mods or (1 shl 5)
        if ((metaState and KeyEvent.META_ALT_RIGHT_ON) != 0) mods = mods or (1 shl 6)
        if ((metaState and KeyEvent.META_META_RIGHT_ON) != 0) mods = mods or (1 shl 7)
        return mods
    }

    fun keyCodeToHidScancode(keyCode: Int): Int {
        return when (keyCode) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 0x04 + (keyCode - KeyEvent.KEYCODE_A)
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> 0x1E + (keyCode - KeyEvent.KEYCODE_1)
            KeyEvent.KEYCODE_0 -> 0x27
            KeyEvent.KEYCODE_ENTER -> 0x28
            KeyEvent.KEYCODE_ESCAPE -> 0x29
            KeyEvent.KEYCODE_DEL -> 0x2A // Backspace
            KeyEvent.KEYCODE_TAB -> 0x2B
            KeyEvent.KEYCODE_SPACE -> 0x2C
            KeyEvent.KEYCODE_MINUS -> 0x2D
            KeyEvent.KEYCODE_EQUALS -> 0x2E
            KeyEvent.KEYCODE_LEFT_BRACKET -> 0x2F
            KeyEvent.KEYCODE_RIGHT_BRACKET -> 0x30
            KeyEvent.KEYCODE_BACKSLASH -> 0x31
            KeyEvent.KEYCODE_SEMICOLON -> 0x33
            KeyEvent.KEYCODE_APOSTROPHE -> 0x34
            KeyEvent.KEYCODE_GRAVE -> 0x35
            KeyEvent.KEYCODE_COMMA -> 0x36
            KeyEvent.KEYCODE_PERIOD -> 0x37
            KeyEvent.KEYCODE_SLASH -> 0x38
            KeyEvent.KEYCODE_CAPS_LOCK -> 0x39
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> 0x3A + (keyCode - KeyEvent.KEYCODE_F1)
            KeyEvent.KEYCODE_SYSRQ -> 0x46 // PrintScreen
            KeyEvent.KEYCODE_SCROLL_LOCK -> 0x47
            KeyEvent.KEYCODE_BREAK -> 0x48 // Pause
            KeyEvent.KEYCODE_INSERT -> 0x49
            KeyEvent.KEYCODE_MOVE_HOME -> 0x4A
            KeyEvent.KEYCODE_PAGE_UP -> 0x4B
            KeyEvent.KEYCODE_FORWARD_DEL -> 0x4C
            KeyEvent.KEYCODE_MOVE_END -> 0x4D
            KeyEvent.KEYCODE_PAGE_DOWN -> 0x4E
            KeyEvent.KEYCODE_DPAD_RIGHT -> 0x4F
            KeyEvent.KEYCODE_DPAD_LEFT -> 0x50
            KeyEvent.KEYCODE_DPAD_DOWN -> 0x51
            KeyEvent.KEYCODE_DPAD_UP -> 0x52
            KeyEvent.KEYCODE_NUM_LOCK -> 0x53
            KeyEvent.KEYCODE_NUMPAD_DIVIDE -> 0x54
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> 0x55
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> 0x56
            KeyEvent.KEYCODE_NUMPAD_ADD -> 0x57
            KeyEvent.KEYCODE_NUMPAD_ENTER -> 0x58
            in KeyEvent.KEYCODE_NUMPAD_1..KeyEvent.KEYCODE_NUMPAD_9 -> 0x59 + (keyCode - KeyEvent.KEYCODE_NUMPAD_1)
            KeyEvent.KEYCODE_NUMPAD_0 -> 0x62
            KeyEvent.KEYCODE_NUMPAD_DOT -> 0x63
            KeyEvent.KEYCODE_MENU -> 0x65
            else -> 0
        }
    }
}

