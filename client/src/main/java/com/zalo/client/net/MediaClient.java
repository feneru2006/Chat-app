package com.zalo.client.net;

import com.zalo.common.Framing;
import com.zalo.common.Packet;
import com.zalo.common.Protocol;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;

/**
 * Kênh 2 phía Client (Thành viên 3): truyền nhị phân Chunking 8KB. Gọi các hàm này trên LUỒNG NỀN.
 * RAM luôn cố định 8KB dù file 500MB (không Base64, không đọc cả file vào bộ nhớ).
 */
public final class MediaClient {
    private MediaClient() {}

    /** MD5 dạng streaming (cũng 8KB/lần). */
    public static String md5(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] buf = new byte[Protocol.CHUNK_SIZE];
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** Có thể gọi cancel.set(true) từ luồng khác để hủy: ghi dở sẽ dừng & đóng socket (Server xóa file tạm). */
    public static Packet upload(String host, int port, String transferId, File file, DoubleConsumer progress,
                                AtomicBoolean cancel) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 5000);
            s.setSoTimeout(60_000);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), Protocol.CHUNK_SIZE));
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            Framing.writePacket(out, Packet.of(Protocol.UPLOAD_BEGIN).put("transferId", transferId));

            long total = file.length(), sent = 0;
            byte[] buf = new byte[Protocol.CHUNK_SIZE];
            try (InputStream fin = new BufferedInputStream(new FileInputStream(file))) {
                int n;
                while ((n = fin.read(buf)) != -1) {
                    if (cancel.get()) throw new IOException("Đã hủy");
                    out.write(buf, 0, n);
                    sent += n;
                    progress.accept(total == 0 ? 1.0 : (double) sent / total);
                }
            }
            out.flush();
            return Framing.readPacket(in);                           // UPLOAD_RESULT {ok, messageId | error}
        }
    }

    /** Tải về `dest` (ghi file tạm rồi đổi tên). @return dest */
    public static File download(String host, int port, String transferId, File dest, DoubleConsumer progress) throws IOException {
        dest.getParentFile().mkdirs();
        File tmp = new File(dest.getPath() + ".part");
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 5000);
            s.setSoTimeout(60_000);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), Protocol.CHUNK_SIZE));
            Framing.writePacket(out, Packet.of(Protocol.DOWNLOAD_BEGIN).put("transferId", transferId));
            Packet head = Framing.readPacket(in);
            if (!head.boolVal("ok", false)) throw new IOException("Server từ chối: " + head.str("error"));
            long total = head.longVal("fileSize", 0), got = 0;
            byte[] buf = new byte[Protocol.CHUNK_SIZE];
            try (OutputStream fo = new BufferedOutputStream(new FileOutputStream(tmp))) {
                while (got < total) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, total - got));
                    if (n < 0) throw new IOException("Mất kết nối khi tải");
                    fo.write(buf, 0, n);
                    got += n;
                    progress.accept(total == 0 ? 1.0 : (double) got / total);
                }
            }
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        if (dest.exists()) dest.delete();
        if (!tmp.renameTo(dest)) throw new IOException("Không đổi tên được file tạm");
        return dest;
    }
}
