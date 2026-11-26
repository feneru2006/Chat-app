package com.zalo.server.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class UserDao {
    private UserDao() {}

    public record User(int id, String username, String phone, String passwordHash, String displayName,
                       String avatarUrl, boolean allowStranger, boolean showRead, boolean online, long lastSeen) {}

    private static User map(ResultSet rs) throws SQLException {
        return new User(rs.getInt("user_id"), rs.getString("username"), rs.getString("phone"),
                rs.getString("password_hash"), rs.getString("display_name"), rs.getString("avatar_url"),
                rs.getBoolean("allow_stranger_msg"), rs.getBoolean("show_read_status"),
                rs.getBoolean("is_online"), rs.getLong("last_seen"));
    }

    public static User findById(int id) throws SQLException {
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("SELECT * FROM Users WHERE user_id=?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
        }
    }

    public static User findByUsername(String username) throws SQLException {
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("SELECT * FROM Users WHERE username=?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
        }
    }

    /** Ném SQLException trùng khóa (Db.isDuplicate) nếu username/phone đã tồn tại. */
    public static int insert(String username, String phone, String hash, String displayName) throws SQLException {
        String sql = "INSERT INTO Users(username,phone,password_hash,display_name) VALUES(?,?,?,?)";
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, phone);
            ps.setString(3, hash);
            ps.setString(4, displayName);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); return rs.getInt(1); }
        }
    }

    public static void setOnline(int id, String ip, int p2pPort) throws SQLException {
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
                "UPDATE Users SET is_online=TRUE,last_ip=?,p2p_port=? WHERE user_id=?")) {
            ps.setString(1, ip);
            ps.setInt(2, p2pPort);
            ps.setInt(3, id);
            ps.executeUpdate();
        }
    }

    public static void setOffline(int id, long lastSeen) throws SQLException {
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
                "UPDATE Users SET is_online=FALSE,last_seen=? WHERE user_id=?")) {
            ps.setLong(1, lastSeen);
            ps.setInt(2, id);
            ps.executeUpdate();
        }
    }

    /** Server khởi động lại: mọi user phải về Offline (tránh trạng thái ma). */
    public static void resetAllOffline() throws SQLException {
        try (Connection c = Db.get(); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE Users SET is_online=FALSE");
        }
    }

    public static List<User> search(String q, int excludeId, int limit) throws SQLException {
        String sql = "SELECT * FROM Users WHERE user_id<>? AND (username LIKE ? OR phone LIKE ? OR display_name LIKE ?) LIMIT ?";
        List<User> list = new ArrayList<>();
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(sql)) {
            String like = "%" + q.replace("%", "").replace("_", "") + "%";
            ps.setInt(1, excludeId);
            ps.setString(2, like);
            ps.setString(3, like);
            ps.setString(4, like);
            ps.setInt(5, limit);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) list.add(map(rs)); }
        }
        return list;
    }

    /** Những người cần nhận sự kiện Online/Offline: bạn bè + người chung hội thoại. */
    public static List<Integer> contactIds(int userId) throws SQLException {
        String sql = "SELECT DISTINCT cm2.user_id AS uid FROM ConversationMembers cm1 "
                + "JOIN ConversationMembers cm2 ON cm1.conversation_id=cm2.conversation_id AND cm2.user_id<>cm1.user_id "
                + "WHERE cm1.user_id=? "
                + "UNION "
                + "SELECT CASE WHEN user_id_1=? THEN user_id_2 ELSE user_id_1 END AS uid FROM Friendships "
                + "WHERE status='ACCEPTED' AND (user_id_1=? OR user_id_2=?)";
        List<Integer> ids = new ArrayList<>();
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 1; i <= 4; i++) ps.setInt(i, userId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) ids.add(rs.getInt("uid")); }
        }
        return ids;
    }
}
