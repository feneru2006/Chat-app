package com.zalo.server.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zalo.common.Packet;
import com.zalo.common.Protocol;
import com.zalo.server.db.ConversationDao;
import com.zalo.server.db.Db;
import com.zalo.server.db.FriendDao;
import com.zalo.server.db.UserDao;
import com.zalo.server.net.ClientHandler;
import com.zalo.server.net.SessionManager;

import java.sql.Connection;
import java.sql.SQLException;

/** Tìm người, kết bạn (PENDING -> ACCEPTED). Không có Block (đặc tả v4.1). */
public final class FriendService {
    private FriendService() {}

    private static JsonObject userJson(UserDao.User u) {
        JsonObject o = new JsonObject();
        o.addProperty("userId", u.id());
        o.addProperty("username", u.username());
        o.addProperty("displayName", u.displayName());
        o.addProperty("avatarUrl", u.avatarUrl());
        o.addProperty("online", SessionManager.isOnline(u.id()));
        o.addProperty("lastSeen", u.lastSeen());
        return o;
    }

    public static void search(ClientHandler h, Packet p) throws SQLException {
        String q = p.str("query") == null ? "" : p.str("query").trim();
        JsonArray arr = new JsonArray();
        if (q.length() >= 2) {
            for (UserDao.User u : UserDao.search(q, h.userId(), 20)) {
                JsonObject o = userJson(u);
                FriendDao.Row r = FriendDao.find(h.userId(), u.id());
                o.addProperty("friendStatus", r == null ? "NONE" : r.status());
                arr.add(o);
            }
        }
        h.send(Packet.of(Protocol.SEARCH_RESULT).put("users", arr));
    }

    public static void request(ClientHandler h, Packet p) throws SQLException {
        int me = h.userId();
        int target = p.intVal("targetUserId", -1);
        if (target == me || UserDao.findById(target) == null) {
            h.sendError(Protocol.ERR_NOT_FOUND, "Không tìm thấy người dùng", null);
            return;
        }
        if (FriendDao.find(me, target) != null) return;                 // đã gửi / đã là bạn
        FriendDao.insertPending(me, target);
        UserDao.User u = UserDao.findById(me);
        SessionManager.sendTo(target, Packet.of(Protocol.FRIEND_REQUEST_IN).put("from", userJson(u)));
    }

    /** Người nhận chấp nhận: 1 Transaction = đổi status + tạo hội thoại PRIVATE nếu chưa có. */
    public static void accept(ClientHandler h, Packet p) throws SQLException {
        int me = h.userId();
        int from = p.intVal("fromUserId", -1);
        FriendDao.Row r = FriendDao.find(me, from);
        if (r == null || !"PENDING".equals(r.status()) || r.actionUserId() == me) {
            h.sendError(Protocol.ERR_BAD_REQUEST, "Không có lời mời nào để chấp nhận", null);
            return;
        }
        Integer conv = ConversationDao.findPrivate(me, from);
        try (Connection c = Db.get()) {
            c.setAutoCommit(false);
            try {
                FriendDao.accept(c, me, from);
                if (conv == null) conv = ConversationDao.createPrivate(c, me, from, System.currentTimeMillis());
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
        UserDao.User meU = UserDao.findById(me);
        SessionManager.sendTo(from, Packet.of(Protocol.FRIEND_ACCEPTED).put("user", userJson(meU)));
        h.send(Packet.of(Protocol.FRIEND_ACCEPTED).put("user", userJson(UserDao.findById(from))));
        SessionManager.sendTo(from, Packet.of(Protocol.CONVERSATION_ADDED).put("conversation", ConversationService.toJson(conv, from)));
        h.send(Packet.of(Protocol.CONVERSATION_ADDED).put("conversation", ConversationService.toJson(conv, me)));
    }

    public static void list(ClientHandler h) throws SQLException {
        JsonArray friends = new JsonArray();
        for (int id : FriendDao.friends(h.userId())) {
            UserDao.User u = UserDao.findById(id);
            if (u != null) friends.add(userJson(u));
        }
        JsonArray pending = new JsonArray();
        for (int id : FriendDao.pendingIncoming(h.userId())) {
            UserDao.User u = UserDao.findById(id);
            if (u != null) pending.add(userJson(u));
        }
        h.send(Packet.of(Protocol.FRIEND_LIST_RESULT).put("friends", friends).put("pending", pending));
    }
}
