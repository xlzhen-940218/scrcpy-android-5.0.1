package org.las2mile.scrcpy.protocol;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class DeviceMessage {

    public static final int TYPE_CLIPBOARD = 0;
    public static final int TYPE_ACK_CLIPBOARD = 1;
    public static final int TYPE_UHID_OUTPUT = 2;

    private final int type;
    private final String text;
    private byte[] data;

    public DeviceMessage(int type, String text) {
        this.type = type;
        this.text = text;
    }

    public DeviceMessage(int type, byte[] data) {
        this.type = type;
        this.text = null;
        this.data = data;
    }

    public int getType() {
        return type;
    }

    public String getText() {
        return text;
    }

    public byte[] getData() {
        return data;
    }

    public static DeviceMessage parse(DataInputStream dis) throws IOException {
        int type = dis.readUnsignedByte();
        if (type == TYPE_CLIPBOARD) {
            int len = dis.readInt();
            byte[] buf = new byte[len];
            dis.readFully(buf);
            return new DeviceMessage(type, new String(buf, StandardCharsets.UTF_8));
        } else if (type == TYPE_ACK_CLIPBOARD) {
            dis.readLong(); // sequence
            return new DeviceMessage(type, (String) null);
        } else if (type == TYPE_UHID_OUTPUT) {
            int id = dis.readUnsignedShort();
            int len = dis.readUnsignedShort();
            byte[] buf = new byte[len];
            dis.readFully(buf);
            return new DeviceMessage(type, buf);
        }
        return null;
    }
}
