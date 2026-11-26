package com.zalo.server.net;

import com.zalo.common.Packet;
import com.zalo.common.Protocol;
import com.zalo.server.db.UserDao;

import java.sql.SQLException;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/** Bảng phiên Online: userId -> ClientHandler (Single Session Policy + định tuyến gói tin). */
public final class SessionManager {
    private static final ConcurrentHashMap<Integer, ClientHandler> ONLINE = new ConcurrentHashMap<>();

    private SessionManager() {}

    /** Đăng ký phiên mới; nếu đã có phiên cũ thì đá ra bằng FORCE_LOGOUT (TC-04). */
    public static void register(int userId, ClientHandler h) {
        ClientHandler old = ONLINE.put(userId, h);
        if (old != null && old != h) {
            old.sendAndClose(Packet.of(Protocol.FORCE_LOGOUT)
                    .put("reason", "Tài khoản của bạn đã đăng nhập trên một thiết bị khác."));
        }
    }

    /** @return true nếu handler này vẫn là phiên hiện hành (và vừa bị gỡ). */
    public static boolean unregister(int userId, ClientHandler h) {
        return ONLINE.remove(userId, h);
    }

    public static ClientHandler get(int userId) { return ONLINE.get(userId); }

    public static boolean isOnline(int userId) { return ONLINE.containsKey(userId); }

    public static void sendTo(int userId, Packet p) {
        ClientHandler h = ONLINE.get(userId);
        if (h != null) h.send(p);
    }

    public static void sendToMany(Collection<Integer> userIds, Packet p, int exceptUserId) {
        for (int id : userIds) if (id != exceptUserId) sendTo(id, p);
    }

    /** Phát USER_ONLINE / USER_OFFLINE cho bạn bè và người chung hội thoại. */
    public static void broadcastPresence(int userId, boolean online, long lastSeen) {
        try {
            Packet p = Packet.of(online ? Protocol.USER_ONLINE : Protocol.USER_OFFLINE)
                    .put("userId", userId).put("lastSeen", lastSeen);
            sendToMany(UserDao.contactIds(userId), p, userId);
        } catch (SQLException e) {
            System.err.println("[presence] " + e.getMessage());
        }
    }
}
