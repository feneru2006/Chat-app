package com.zalo.server.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zalo.common.Packet;
import com.zalo.common.Protocol;
import com.zalo.server.db.ConversationDao;
import com.zalo.server.db.Db;
import com.zalo.server.db.MessageDao;
import com.zalo.server.db.MessageDao.Att;
import com.zalo.server.db.MessageDao.Msg;
import com.zalo.server.db.UserDao;
import com.zalo.server.net.ClientHandler;
import com.zalo.server.net.SessionManager;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lõi tin nhắn: gửi, Idempotency, State (Đã nhận/Đã xem theo từng người), History, Thu hồi/Xóa, Reaction.
 * Quy tắc vàng: sender_id = h.userId(), created_at = đồng hồ SERVER (nguồn chân lý cho Time).
 */
public final class MessageService {
    private static final Set<String> REACTIONS = Set.of("LIKE", "HEART", "HAHA", "SAD", "ANGRY");

    private MessageService() {}

    // ================================================================== JSON
    public static JsonObject toJson(Msg m, Map<Integer, UserDao.User> cache) throws SQLException {
        UserDao.User u = cache.get(m.senderId());
        if (u == null) {
            u = UserDao.findById(m.senderId());
            cache.put(m.senderId(), u);
        }
        JsonObject o = new JsonObject();
        o.addProperty("messageId", m.id());
        o.addProperty("clientMsgId", m.clientMsgId());
        o.addProperty("conversationId", m.conv());
        o.addProperty("senderId", m.senderId());
        o.addProperty("senderName", u == null ? "?" : u.displayName());
        o.addProperty("senderAvatar", u == null ? null : u.avatarUrl());
        o.addProperty("msgType", m.type());
        o.addProperty("content", m.recalled() ? null : m.content());
        if (m.replyTo() != null) o.addProperty("replyToMsgId", m.replyTo());
        o.addProperty("recalled", m.recalled());
        o.addProperty("createdAt", m.createdAt());
        if (!m.recalled() && !Protocol.MSG_TEXT.equals(m.type()) && !Protocol.MSG_SYSTEM.equals(m.type())) {
            Att a = MessageDao.attachmentOf(m.id());
            if (a != null) {
                JsonObject at = new JsonObject();
                at.addProperty("attachmentId", a.id());
                at.addProperty("fileName", a.fileName());
                at.addProperty("fileSize", a.fileSize());
                at.addProperty("mimeType", a.mime());
                if (a.durationSec() != null) at.addProperty("durationSec", a.durationSec());
                at.addProperty("hasThumb", a.thumbPath() != null);
                o.add("attachment", at);
            }
        }
        Map<Integer, String> reactions = MessageDao.reactionsOf(m.id());
        if (!reactions.isEmpty()) {
            JsonArray arr = new JsonArray();
            reactions.forEach((uid, r) -> {
                JsonObject x = new JsonObject();
                x.addProperty("userId", uid);
                x.addProperty("reaction", r);
                arr.add(x);
            });
            o.add("reactions", arr);
        }
        return o;
    }

    private static Packet ack(Msg m, JsonObject withMessage) {
        Packet p = Packet.of(Protocol.MSG_ACK).put("clientMsgId", m.clientMsgId()).put("conversationId", m.conv())
                .put("messageId", m.id()).put("status", Protocol.ST_SENT).put("createdAt", m.createdAt());
        if (withMessage != null) p.put("message", withMessage);
        return p;
    }

    // ================================================================== GỬI
    /** SEND_MSG (TEXT/Emoji) — qua Server, hoặc bản sao của tin P2P để lưu History (Client dedupe theo clientMsgId). */
    public static void send(ClientHandler h, Packet p) throws SQLException {
        int me = h.userId();
        int conv = p.intVal("conversationId", -1);
        String cid = p.str("clientMsgId");
        String content = p.str("content");
        if (cid == null || cid.isEmpty() || cid.length() > 64 || content == null || content.isEmpty() || content.length() > 8000) {
            h.sendError(Protocol.ERR_BAD_REQUEST, "SEND_MSG thiếu clientMsgId/content hoặc quá dài", cid);
            return;
        }
        if (!ConversationDao.isMember(conv, me)) {
            h.sendError(Protocol.ERR_NOT_MEMBER, "Bạn không thuộc hội thoại này", cid);
            return;
        }
        Long reply = p.has("replyToMsgId") ? p.longVal("replyToMsgId", 0) : null;
        long now = System.currentTimeMillis();                          // Time: đồng hồ Server
        Msg m;
        try {
            m = MessageDao.insert(conv, me, cid, reply, Protocol.MSG_TEXT, content, now);
        } catch (SQLException e) {
            if (Db.isDuplicate(e)) {                                    // Idempotency: gửi lại do rớt mạng
                Msg old = MessageDao.findByClientMsgId(conv, cid);
                if (old != null) {
                    h.send(ack(old, null));                             // chỉ trả MSG_ACK cũ, KHÔNG đẩy lại cho người nhận
                    return;
                }
            }
            throw e;
        }
        ConversationDao.touch(conv, m.id(), m.createdAt());
        finishSend(m);
    }

    /** MSG_ACK cho người gửi (=> SENT) + NEW_MESSAGE cho thành viên Online khác. Dùng chung cho TEXT và media. */
    public static void finishSend(Msg m) throws SQLException {
        Map<Integer, UserDao.User> cache = new HashMap<>();
        JsonObject mj = toJson(m, cache);
        boolean media = !Protocol.MSG_TEXT.equals(m.type());
        SessionManager.sendTo(m.senderId(), ack(m, media ? mj : null));
        broadcastNew(m, mj, false);
    }

    private static void broadcastNew(Msg m, JsonObject mj, boolean includeSender) throws SQLException {
        Packet np = Packet.of(Protocol.NEW_MESSAGE).put("message", mj);
        for (int uid : ConversationDao.memberIds(m.conv())) {
            if (uid != m.senderId() || includeSender) SessionManager.sendTo(uid, np);
        }
    }

    /** Tin hệ thống (SYSTEM) khi có biến động thành viên nhóm. */
    public static void postSystem(int conv, int actorId, String text) throws SQLException {
        Msg m = MessageDao.insert(conv, actorId, "sys-" + UUID.randomUUID(), null, Protocol.MSG_SYSTEM, text, System.currentTimeMillis());
        ConversationDao.touch(conv, m.id(), m.createdAt());
        broadcastNew(m, toJson(m, new HashMap<>()), true);
    }

    // ================================================================== STATE: Đã nhận / Đã xem
    public static void delivered(ClientHandler h, Packet p) throws SQLException {
        int me = h.userId();
        int conv = p.intVal("conversationId", -1);
        if (!ConversationDao.isMember(conv, me)) return;
        long upTo = Math.min(p.longVal("upToMessageId", 0), MessageDao.maxId(conv));
        if (upTo <= 0) return;
        ConversationDao.markDelivered(conv, me, upTo);                  // chỉ tăng, không lùi
        broadcastStatus(conv, me, Protocol.ST_DELIVERED, upTo);
    }

    public static void read(ClientHandler h, Packet p) throws SQLException {
        int me = h.userId();
        int conv = p.intVal("conversationId", -1);
        if (!ConversationDao.isMember(conv, me)) return;
        long upTo = Math.min(p.longVal("upToMessageId", 0), MessageDao.maxId(conv));
        if (upTo <= 0) return;
        ConversationDao.markRead(conv, me, upTo);                       // luôn cập nhật (đếm tin chưa đọc)
        UserDao.User u = UserDao.findById(me);
        // Người dùng tắt show_read_status => không phát READ cho người khác, chỉ phát DELIVERED.
        broadcastStatus(conv, me, u != null && !u.showRead() ? Protocol.ST_DELIVERED : Protocol.ST_READ, upTo);
    }

    private static void broadcastStatus(int conv, int userId, String status, long upTo) throws SQLException {
        Packet st = Packet.of(Protocol.MSG_STATUS).put("conversationId", conv).put("userId", userId)
                .put("status", status).put("upToMessageId", upTo);
        SessionManager.sendToMany(ConversationDao.memberIds(conv), st, userId);
    }

    // ================================================================== HISTORY
    /**
     * SYNC: {"conversations": {"5": 1040, "7": 0}} = {conversation_id: last_local_message_id}.
     * Trả về tối đa 50 tin mới hơn mốc / hội thoại + mốc Đã nhận/Đã xem của các thành viên.
     * hasMore=true => Client gửi SYNC lại với mốc mới.
     */
    public static void sync(ClientHandler h, Packet p) throws SQLException {
        int me = h.userId();
        JsonObject convs = p.object("conversations");
        if (convs == null) {
            h.sendError(Protocol.ERR_BAD_REQUEST, "SYNC thiếu conversations", null);
            return;
        }
        Map<Integer, UserDao.User> cache = new HashMap<>();
        for (Map.Entry<String, com.google.gson.JsonElement> e : convs.entrySet()) {
            int conv = Integer.parseInt(e.getKey());
            long last = e.getValue().getAsLong();
            if (!ConversationDao.isMember(conv, me)) continue;
            List<Msg> list = MessageDao.newer(me, conv, last, Protocol.SYNC_LIMIT + 1);
            boolean more = list.size() > Protocol.SYNC_LIMIT;
            if (more) list = list.subList(0, Protocol.SYNC_LIMIT);
            JsonArray arr = new JsonArray();
            for (Msg m : list) arr.add(toJson(m, cache));
            h.send(Packet.of(Protocol.SYNC_RESULT).put("conversationId", conv).put("messages", arr)
                    .put("hasMore", more).put("memberStates", memberStates(conv)));
        }
    }

    /** LOAD_OLDER_MESSAGES: {conversationId, beforeMessageId} => 30 tin cũ hơn (keyset pagination). */
    public static void older(ClientHandler h, Packet p) throws SQLException {
        int me = h.userId();
        int conv = p.intVal("conversationId", -1);
        if (!ConversationDao.isMember(conv, me)) {
            h.sendError(Protocol.ERR_NOT_MEMBER, "Không thuộc hội thoại", null);
            return;
        }
        long before = p.longVal("beforeMessageId", 0);
        if (before <= 0) before = Long.MAX_VALUE;
        List<Msg> list = MessageDao.older(me, conv, before, Protocol.OLDER_PAGE + 1);
        boolean more = list.size() > Protocol.OLDER_PAGE;
        if (more) list = list.subList(1, list.size());                 // bỏ tin cũ nhất (phần dư để dò hasMore)
        Map<Integer, UserDao.User> cache = new HashMap<>();
        JsonArray arr = new JsonArray();
        for (Msg m : list) arr.add(toJson(m, cache));
        h.send(Packet.of(Protocol.OLDER_RESULT).put("conversationId", conv).put("messages", arr).put("hasMore", more));
    }

    public static JsonArray memberStates(int conv) throws SQLException {
        JsonArray arr = new JsonArray();
        for (ConversationDao.MemberState s : ConversationDao.memberStates(conv)) {
            JsonObject o = new JsonObject();
            o.addProperty("userId", s.userId());
            o.addProperty("delivered", s.delivered());
            o.addProperty("read", s.read());
            arr.add(o);
        }
        return arr;
    }

    // ================================================================== Đang soạn tin
    public static void typing(ClientHandler h, Packet p) throws SQLException {
        int me = h.userId();
        int conv = p.intVal("conversationId", -1);
        if (!ConversationDao.isMember(conv, me)) return;
        Packet t = Packet.of(Protocol.TYPING).put("conversationId", conv).put("userId", me)
                .put("typing", p.boolVal("typing", true));
        SessionManager.sendToMany(ConversationDao.memberIds(conv), t, me);
    }

    // ================================================================== Thu hồi / Xóa / Reaction
    /** Thu hồi (Recall): chỉ người gửi; phát MSG_RECALLED cho mọi thành viên Online. */
    public static void recall(ClientHandler h, Packet p) throws SQLException {
        long id = p.longVal("messageId", -1);
        Msg m = MessageDao.findById(id);
        if (m == null || !ConversationDao.isMember(m.conv(), h.userId())) {
            h.sendError(Protocol.ERR_NOT_FOUND, "Không tìm thấy tin nhắn", null);
            return;
        }
        if (m.senderId() != h.userId()) {
            h.sendError(Protocol.ERR_FORBIDDEN, "Chỉ người gửi mới thu hồi được", null);
            return;
        }
        if (MessageDao.recall(id, h.userId())) {
            Packet r = Packet.of(Protocol.MSG_RECALLED).put("conversationId", m.conv()).put("messageId", id);
            SessionManager.sendToMany(ConversationDao.memberIds(m.conv()), r, -1);
        }
    }

    /** Xóa chỉ ở phía tôi (Delete for Me): ghi MessageDeletions, không phát cho người khác. */
    public static void deleteForMe(ClientHandler h, Packet p) throws SQLException {
        long id = p.longVal("messageId", -1);
        Msg m = MessageDao.findById(id);
        if (m == null || !ConversationDao.isMember(m.conv(), h.userId())) {
            h.sendError(Protocol.ERR_NOT_FOUND, "Không tìm thấy tin nhắn", null);
            return;
        }
        MessageDao.deleteForMe(id, h.userId());
        h.send(Packet.of(Protocol.MSG_DELETED_ME).put("conversationId", m.conv()).put("messageId", id));
    }

    /** Xóa lịch sử trò chuyện: cleared_before_msg_id = ID tin mới nhất hiện tại. */
    public static void clearHistory(ClientHandler h, Packet p) throws SQLException {
        int conv = p.intVal("conversationId", -1);
        if (!ConversationDao.isMember(conv, h.userId())) {
            h.sendError(Protocol.ERR_NOT_MEMBER, "Không thuộc hội thoại", null);
            return;
        }
        long max = MessageDao.maxId(conv);
        ConversationDao.clearHistory(conv, h.userId(), max);
        h.send(Packet.of(Protocol.HISTORY_CLEARED).put("conversationId", conv).put("upToMessageId", max));
    }

    /** Thả cảm xúc: {messageId, reaction: LIKE|HEART|HAHA|SAD|ANGRY | null (gỡ)}. */
    public static void react(ClientHandler h, Packet p) throws SQLException {
        long id = p.longVal("messageId", -1);
        String reaction = p.str("reaction");
        if (reaction != null && reaction.isEmpty()) reaction = null;
        if (reaction != null && !REACTIONS.contains(reaction)) {
            h.sendError(Protocol.ERR_BAD_REQUEST, "reaction không hợp lệ", null);
            return;
        }
        Msg m = MessageDao.findById(id);
        if (m == null || !ConversationDao.isMember(m.conv(), h.userId())) {
            h.sendError(Protocol.ERR_NOT_FOUND, "Không tìm thấy tin nhắn", null);
            return;
        }
        MessageDao.setReaction(id, h.userId(), reaction);
        Packet r = Packet.of(Protocol.MSG_REACTION).put("conversationId", m.conv()).put("messageId", id)
                .put("userId", h.userId()).put("reaction", reaction);
        SessionManager.sendToMany(ConversationDao.memberIds(m.conv()), r, -1);
    }
}
