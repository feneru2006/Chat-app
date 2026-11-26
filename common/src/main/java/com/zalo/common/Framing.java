package com.zalo.common;

import com.google.gson.JsonParseException;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Đóng khung gói tin TCP: [4 byte độ dài (big-endian)] + [JSON UTF-8].
 * readFully() chờ đủ N byte nên không bị "dính gói" / "cắt gói" (TC-02).
 *
 * LƯU Ý: mỗi Socket chỉ nên có MỘT luồng ghi (writer thread lấy từ hàng đợi),
 * nếu không các khung từ nhiều luồng sẽ chen vào nhau.
 */
public final class Framing {
    private Framing() {}

    public static void write(DataOutputStream out, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        if (b.length > Protocol.MAX_FRAME_BYTES) throw new IOException("Frame too large: " + b.length);
        out.writeInt(b.length);
        out.write(b);
        out.flush();
    }

    public static String read(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len <= 0 || len > Protocol.MAX_FRAME_BYTES) throw new IOException("Invalid frame length: " + len);
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    public static void writePacket(DataOutputStream out, Packet p) throws IOException {
        write(out, p.toJson());
    }

    public static Packet readPacket(DataInputStream in) throws IOException {
        try {
            return Packet.parse(read(in));
        } catch (JsonParseException | IllegalArgumentException e) {
            throw new IOException("Malformed JSON frame", e);
        }
    }
}
