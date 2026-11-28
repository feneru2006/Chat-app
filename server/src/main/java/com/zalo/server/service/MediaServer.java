package com.zalo.server.service;

import com.zalo.common.Framing;
import com.zalo.common.Packet;
import com.zalo.common.Protocol;
import com.zalo.server.db.ConversationDao;
import com.zalo.server.db.Db;
import com.zalo.server.db.MessageDao;
import com.zalo.server.db.MessageDao.Att;
import com.zalo.server.db.MessageDao.Msg;
import com.zalo.server.service.MediaService.Dir;
import com.zalo.server.service.MediaService.Transfer;
import com.zalo.server.util.Thumbnailer;

import javax.net.ServerSocketFactory;
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
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Kênh 2 — cổng nhị phân phụ (Chunking 8KB). Độc lập hoàn toàn với Kênh 1 nên gửi file lớn không làm trễ chat.
 *
 * Giao thức một kết nối:
 *   Client -> khung JSON {"type":"UPLOAD_BEGIN"|"DOWNLOAD_BEGIN","transferId":"..."}
 *   UPLOAD  : Client ghi đúng fileSize byte thô -> Server kiểm MD5 -> khung JSON UPLOAD_RESULT {ok, messageId?}
 *   DOWNLOAD: Server trả khung JSON {"ok":true,"fileSize":N} rồi ghi N byte thô.
 *
 * Mặc định là TCP thường (đặc tả: Kênh 2 = Binary TCP Socket). Có thể bọc SSL giống Kênh 1 nếu muốn (xem ServerMain).
 * TODO (Thành viên 3): ghép thêm tính năng "resume" nếu còn thời gian (không bắt buộc).
 */
public final class MediaServer {
    private static volatile int port;
    private static Path storage;
    private static final ExecutorService POOL = Executors.newCachedThreadPool();

    private MediaServer() {}

    public static int port() { return port; }

    public static void start(int listenPort, String storageDir, ServerSocketFactory factory) throws IOException {
        storage = Paths.get(storageDir);
        Files.createDirectories(storage.resolve("tmp"));
        ServerSocket ss = factory.createServerSocket(listenPort);
        port = ss.getLocalPort();
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Socket s = ss.accept();
                    POOL.submit(() -> handle(s));
                } catch (IOException e) {
                    System.err.println("[media] accept: " + e.getMessage());
                    return;
                }
            }
        }, "media-acceptor");
        t.setDaemon(true);
        t.start();
        System.out.println("[media] lắng nghe Kênh 2 tại cổng " + port + ", lưu tại " + storage.toAbsolutePath());
    }

    private static void handle(Socket s) {
        Transfer t = null;
        try (s) {
            s.setSoTimeout(30_000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            Packet hello = Framing.readPacket(in);
            t = MediaService.take(hello.str("transferId") == null ? "" : hello.str("transferId"));
            if (t == null) {
                Framing.writePacket(out, Packet.of(Protocol.UPLOAD_RESULT).put("ok", false).put("error", Protocol.ERR_NOT_FOUND));
                return;
            }
            t.activeSocket = s;
            if (t.dir == Dir.UPLOAD) upload(t, in, out); else download(t, out);
        } catch (Exception e) {
            if (t != null && !t.cancelled) System.err.println("[media] " + e.getMessage());
        } finally {
            if (t != null) MediaService.forget(t);
        }
    }

    // ================================================================== UPLOAD
    private static void upload(Transfer t, DataInputStream in, DataOutputStream out) throws Exception {
        Path tmp = storage.resolve("tmp").resolve(t.id + ".part");
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] buf = new byte[Protocol.CHUNK_SIZE];                     // RAM cố định 8KB dù file 500MB
        long remaining = t.fileSize;
        boolean complete = false;
        try (OutputStream fo = new BufferedOutputStream(new FileOutputStream(tmp.toFile()))) {
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) break;                                       // bị ngắt / hủy
                fo.write(buf, 0, n);
                md.update(buf, 0, n);
                remaining -= n;
            }
            complete = remaining == 0;
        } catch (IOException e) {
            complete = false;
        }
        if (!complete || t.cancelled) {
            Files.deleteIfExists(tmp);                                  // hủy giữa chừng => xóa file tạm
            return;
        }
        String got = HexFormat.of().formatHex(md.digest());
        if (!got.equalsIgnoreCase(t.md5)) {
            Files.deleteIfExists(tmp);
            Framing.writePacket(out, Packet.of(Protocol.UPLOAD_RESULT).put("ok", false).put("error", Protocol.ERR_MD5_MISMATCH)
                    .put("clientMsgId", t.clientMsgId));
            return;
        }
        // Lưu file chính thức: storage/<convId>/<uuid>_<tên an toàn>
        String safe = t.fileName.replaceAll("[^A-Za-z0-9._-]", "_");
        Path dir = storage.resolve(String.valueOf(t.conv));
        Files.createDirectories(dir);
        String base = java.util.UUID.randomUUID() + "_" + safe;
        Path dest = dir.resolve(base);
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);

        String thumbRel = null;
        if (Protocol.MSG_IMAGE.equals(t.msgType)) {
            Path th = dir.resolve("thumb_" + base + ".jpg");
            if (Thumbnailer.create(dest, th)) thumbRel = storage.relativize(th).toString();
        } else if (Protocol.MSG_VIDEO.equals(t.msgType)) {
            Path th = dir.resolve("thumb_" + base + ".jpg");
            if (Thumbnailer.createFromVideo(dest, th)) thumbRel = storage.relativize(th).toString();
        }

        long now = System.currentTimeMillis();
        Msg m;
        try (Connection c = Db.get()) {
            c.setAutoCommit(false);
            try {
                m = MessageDao.insert(c, t.conv, t.userId, t.clientMsgId, t.replyTo, t.msgType, t.fileName, now);
                MessageDao.insertAttachment(c, m.id(), t.fileName, t.fileSize, mime(t), t.durationSec,
                        storage.relativize(dest).toString(), thumbRel);
                c.commit();
            } catch (java.sql.SQLException e) {
                c.rollback();
                if (Db.isDuplicate(e)) {                                // client gửi lại cùng clientMsgId
                    Msg old = MessageDao.findByClientMsgId(t.conv, t.clientMsgId);
                    Files.deleteIfExists(dest);
                    Framing.writePacket(out, Packet.of(Protocol.UPLOAD_RESULT).put("ok", true).put("messageId", old.id()));
                    return;
                }
                throw e;
            }
        }
        ConversationDao.touch(t.conv, m.id(), m.createdAt());
        Framing.writePacket(out, Packet.of(Protocol.UPLOAD_RESULT).put("ok", true).put("messageId", m.id())
                .put("clientMsgId", t.clientMsgId));
        MessageService.finishSend(m);                                   // MSG_ACK(kèm message) + NEW_MESSAGE
    }

    // ================================================================== DOWNLOAD
    private static void download(Transfer t, DataOutputStream out) throws Exception {
        Att a = t.download;
        Path file = storage.resolve(t.thumb ? a.thumbPath() : a.filePath()).normalize();
        if (!file.startsWith(storage.normalize()) || !Files.isRegularFile(file)) {   // chống ../
            Framing.writePacket(out, Packet.of(Protocol.UPLOAD_RESULT).put("ok", false).put("error", Protocol.ERR_NOT_FOUND));
            return;
        }
        long size = Files.size(file);
        Framing.writePacket(out, Packet.of(Protocol.UPLOAD_RESULT).put("ok", true).put("fileSize", size));
        byte[] buf = new byte[Protocol.CHUNK_SIZE];
        try (InputStream fi = new BufferedInputStream(new FileInputStream(file.toFile()))) {
            int n;
            while ((n = fi.read(buf)) != -1) out.write(buf, 0, n);
        }
        out.flush();
    }

    private static String mime(Transfer t) {
        String n = t.fileName.toLowerCase();
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".wav")) return "audio/wav";
        return "application/octet-stream";
    }
}
