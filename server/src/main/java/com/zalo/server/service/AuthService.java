package com.zalo.server.service;

import com.zalo.common.Packet;
import com.zalo.common.Protocol;
import com.zalo.server.db.Db;
import com.zalo.server.db.UserDao;
import com.zalo.server.net.ClientHandler;
import com.zalo.server.net.SessionManager;
import com.zalo.server.util.PasswordHasher;
import com.zalo.server.util.ServerLog;

import java.sql.SQLException;

/** Đăng ký + xác thực (Socket Binding) + Single Session. */
public final class AuthService {
    private AuthService() {}

    public static void register(ClientHandler h, Packet p) throws SQLException {
        String username = str(p, "username");
        String phone = str(p, "phone");
        String password = p.str("password");
        String display = str(p, "displayName");
        Packet r = Packet.of(Protocol.REGISTER_RESULT);
        if (!username.matches("[A-Za-z0-9_.]{3,50}") || !phone.matches("\\d{8,15}")
                || password == null || password.length() < 6 || display.isEmpty() || display.length() > 100) {
            h.send(r.put("ok", false).put("error", Protocol.ERR_BAD_REQUEST)
                    .put("message", "Username 3-50 ký tự, SĐT 8-15 số, mật khẩu >= 6 ký tự, tên hiển thị không rỗng"));
            return;
        }
        try {
            UserDao.insert(username, phone, PasswordHasher.hash(password), display);
            h.send(r.put("ok", true));
        } catch (SQLException e) {
            if (Db.isDuplicate(e)) h.send(r.put("ok", false).put("error", Protocol.ERR_USERNAME_TAKEN));
            else throw e;
        }
    }

    /**
     * Xác thực. Thành công: bind(userId) lên phiên Socket, đá phiên cũ (FORCE_LOGOUT), cập nhật DB
     * rồi mới báo AUTH_RESULT. Từ giờ sender_id của mọi tin LUÔN lấy từ h.userId() (đặc tả 4.1).
     */
    public static void authenticate(ClientHandler h, Packet p) throws SQLException {
        String username = str(p, "username");
        String password = p.str("password");
        int p2pPort = p.intVal("p2pPort", 0);
        UserDao.User u = username.isEmpty() ? null : UserDao.findByUsername(username);
        if (u == null || password == null || !PasswordHasher.verify(password, u.passwordHash())) {
            ServerLog.info("auth", "đăng nhập sai: user='" + username + "' từ " + h.ip());
            h.sendAndClose(Packet.of(Protocol.AUTH_RESULT).put("ok", false).put("error", Protocol.ERR_BAD_CREDENTIALS));
            return;
        }
        h.bind(u.id(), p2pPort);
        SessionManager.register(u.id(), h);                    // 1) đá phiên cũ
        UserDao.setOnline(u.id(), h.ip(), p2pPort);            // 2) cập nhật IP:Port cho P2P
        h.send(Packet.of(Protocol.AUTH_RESULT).put("ok", true).put("userId", u.id())
                .put("displayName", u.displayName()).put("avatarUrl", u.avatarUrl()));
        SessionManager.broadcastPresence(u.id(), true, System.currentTimeMillis());
        ServerLog.info("auth", u.username() + " (id=" + u.id() + ") online từ " + h.ip() + ", p2pPort=" + p2pPort);
    }

    private static String str(Packet p, String k) {
        String s = p.str(k);
        return s == null ? "" : s.trim();
    }
}
