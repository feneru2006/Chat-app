package com.zalo.server.service;

import com.zalo.common.Packet;
import com.zalo.common.Protocol;
import com.zalo.server.db.ConversationDao;
import com.zalo.server.db.MessageDao;
import com.zalo.server.db.MessageDao.Att;
import com.zalo.server.net.ClientHandler;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kênh 1 phần điều khiển cho File/Image/Video/Sound (FILE_INIT / FILE_CANCEL / FILE_DOWNLOAD_REQ).
 * Dữ liệu nhị phân đi qua Kênh 2 (MediaServer). Hai bên gặp nhau qua `transferId` (token dùng một lần).
 *
 * TODO (Thành viên 3): hoàn thiện kiểm tra loại file theo msgType (IMAGE: jpg/png, VIDEO: mp4, SOUND: wav).
 */
public final class MediaService {
    private MediaService() {}

    public enum Dir { UPLOAD, DOWNLOAD }

    /** Một lượt truyền đã được cấp phép trên Kênh 1, đang chờ Kênh 2 kết nối tới. */
    public static final class Transfer {
        public final String id = UUID.randomUUID().toString();
        public final Dir dir;
        public final int userId, conv;
        public final String clientMsgId, msgType, fileName, md5;
        public final long fileSize;
        public final Integer durationSec;
        public final Long replyTo;
        public final Att download;                    // chỉ dùng cho DOWNLOAD
        public final boolean thumb;
        public volatile boolean cancelled;
        public volatile java.net.Socket activeSocket;

        Transfer(Dir dir, int userId, int conv, String clientMsgId, String msgType, String fileName,
                 long fileSize, String md5, Integer durationSec, Long replyTo, Att download, boolean thumb) {
            this.dir = dir; this.userId = userId; this.conv = conv; this.clientMsgId = clientMsgId;
            this.msgType = msgType; this.fileName = fileName; this.fileSize = fileSize; this.md5 = md5;
            this.durationSec = durationSec; this.replyTo = replyTo; this.download = download; this.thumb = thumb;
        }
    }

    private static final Map<String, Transfer> PENDING = new ConcurrentHashMap<>();   // transferId -> Transfer
    private static final Map<String, Transfer> BY_CLIENT_MSG = new ConcurrentHashMap<>(); // userId:clientMsgId -> Transfer

    public static Transfer take(String transferId) { return PENDING.remove(transferId); }
    public static void forget(Transfer t) {
        if (t.clientMsgId != null) BY_CLIENT_MSG.remove(t.userId + ":" + t.clientMsgId);
    }

    // ------------------------------------------------------------------ upload
    /** FILE_INIT {clientMsgId, conversationId, msgType, fileName, fileSize, md5, durationSec?, replyToMsgId?} */
    public static void fileInit(ClientHandler h, Packet p) throws java.sql.SQLException {
        int conv = p.intVal("conversationId", -1);
        String cid = p.str("clientMsgId");
        String type = p.str("msgType");
        String name = p.str("fileName");
        long size = p.longVal("fileSize", -1);
        String md5 = p.str("md5");
        if (cid == null || name == null || md5 == null || size <= 0 || type == null
                || !(Protocol.MSG_FILE.equals(type) || Protocol.MSG_IMAGE.equals(type)
                || Protocol.MSG_VIDEO.equals(type) || Protocol.MSG_SOUND.equals(type))) {
            h.sendError(Protocol.ERR_BAD_REQUEST, "FILE_INIT thiếu/ sai trường", cid);
            return;
        }
        if (size > Protocol.MAX_FILE_BYTES) {
            h.sendError(Protocol.ERR_TOO_LARGE, "File vượt quá giới hạn", cid);
            return;
        }
        if (!ConversationDao.isMember(conv, h.userId())) {
            h.sendError(Protocol.ERR_NOT_MEMBER, "Bạn không thuộc hội thoại này", cid);
            return;
        }
        Integer dur = p.has("durationSec") ? p.intVal("durationSec", 0) : null;
        Long reply = p.has("replyToMsgId") ? p.longVal("replyToMsgId", 0) : null;
        Transfer t = new Transfer(Dir.UPLOAD, h.userId(), conv, cid, type, name, size, md5.toLowerCase(), dur, reply, null, false);
        PENDING.put(t.id, t);
        BY_CLIENT_MSG.put(h.userId() + ":" + cid, t);
        h.send(Packet.of(Protocol.FILE_READY).put("clientMsgId", cid).put("transferId", t.id)
                .put("mediaPort", MediaServer.port()));
    }

    /** FILE_CANCEL {clientMsgId}: đóng luồng đang ghi, MediaServer sẽ xóa file tạm. */
    public static void fileCancel(ClientHandler h, Packet p) {
        Transfer t = BY_CLIENT_MSG.remove(h.userId() + ":" + p.str("clientMsgId"));
        if (t == null) return;
        t.cancelled = true;
        PENDING.remove(t.id);
        java.net.Socket s = t.activeSocket;
        if (s != null) try { s.close(); } catch (java.io.IOException ignored) { }
    }

    // ------------------------------------------------------------------ download
    /** FILE_DOWNLOAD_REQ {messageId, thumb:true|false} => FILE_DOWNLOAD_READY {transferId, mediaPort, fileSize} */
    public static void downloadRequest(ClientHandler h, Packet p) throws java.sql.SQLException {
        long messageId = p.longVal("messageId", -1);
        MessageDao.Msg m = MessageDao.findById(messageId);
        Att a = m == null ? null : MessageDao.attachmentOf(messageId);
        if (m == null || a == null || !ConversationDao.isMember(m.conv(), h.userId()) || m.recalled()) {
            h.sendError(Protocol.ERR_NOT_FOUND, "Không có tệp đính kèm", null);
            return;
        }
        boolean thumb = p.boolVal("thumb", false) && a.thumbPath() != null;
        Transfer t = new Transfer(Dir.DOWNLOAD, h.userId(), m.conv(), null, m.type(), a.fileName(),
                a.fileSize(), null, a.durationSec(), null, a, thumb);
        PENDING.put(t.id, t);
        h.send(Packet.of(Protocol.FILE_DOWNLOAD_READY).put("messageId", messageId).put("transferId", t.id)
                .put("mediaPort", MediaServer.port()).put("thumb", thumb));
    }
}
