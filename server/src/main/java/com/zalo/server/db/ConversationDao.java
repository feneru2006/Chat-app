package com.zalo.server.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class ConversationDao {
    private ConversationDao() {}

    public record Conv(int id, String type, String groupName, String groupAvatar, Long lastMessageId, long updatedAt) {}
    public record MemberInfo(int userId, String displayName, String avatarUrl, String role, boolean online) {}
    /** Hai mốc của một thành viên: dùng để suy ra Đã nhận / Đã xem (xem MessageStateCalc). */
    public record MemberState(int userId, long delivered, long read) {}

    // ------------------------------------------------------------------ truy vấn cơ bản
    public static Conv find(int conv) throws SQLException {
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("SELECT * FROM Conversations WHERE conversation_id=?")) {
            ps.setInt(1, conv);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                long last = rs.getLong("last_message_id");
                Long lastId = rs.wasNull() ? null : last;
                return new Conv(rs.getInt("conversation_id"), rs.getString("type"), rs.getString("group_name"),
                        rs.getString("group_avatar"), lastId, rs.getLong("updated_at"));
            }
        }
    }

    public static List<Integer> memberIds(int conv) throws SQLException {
        List<Integer> ids = new ArrayList<>();
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("SELECT user_id FROM ConversationMembers WHERE conversation_id=?")) {
            ps.setInt(1, conv);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) ids.add(rs.getInt(1)); }
        }
        return ids;
    }

    /** @return ADMIN / MEMBER, hoặc null nếu không phải thành viên. */
    public static String role(int conv, int user) throws SQLException {
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
                "SELECT role FROM ConversationMembers WHERE conversation_id=? AND user_id=?")) {
            ps.setInt(1, conv);
            ps.setInt(2, user);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    public static boolean isMember(int conv, int user) throws SQLException {
        return role(conv, user) != null;
    }

    public static List<MemberInfo> members(int conv) throws SQLException {
        String sql = "SELECT u.user_id,u.display_name,u.avatar_url,cm.role,u.is_online FROM ConversationMembers cm "
                + "JOIN Users u ON u.user_id=cm.user_id WHERE cm.conversation_id=? ORDER BY cm.joined_at";
        List<MemberInfo> list = new ArrayList<>();
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, conv);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(new MemberInfo(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5)));
            }
        }
        return list;
    }

    public static List<Integer> conversationIdsOf(int user) throws SQLException {
        String sql = "SELECT c.conversation_id FROM Conversations c JOIN ConversationMembers m ON m.conversation_id=c.conversation_id "
                + "WHERE m.user_id=? ORDER BY c.updated_at DESC";
        List<Integer> ids = new ArrayList<>();
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, user);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) ids.add(rs.getInt(1)); }
        }
        return ids;
    }

    public static Integer findPrivate(int a, int b) throws SQLException {
        String sql = "SELECT c.conversation_id FROM Conversations c "
                + "JOIN ConversationMembers x ON x.conversation_id=c.conversation_id AND x.user_id=? "
                + "JOIN ConversationMembers y ON y.conversation_id=c.conversation_id AND y.user_id=? "
                + "WHERE c.type='PRIVATE' LIMIT 1";
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, a);
            ps.setInt(2, b);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : null; }
        }
    }

    public static Integer peerOf(int conv, int me) throws SQLException {
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
                "SELECT user_id FROM ConversationMembers WHERE conversation_id=? AND user_id<>? LIMIT 1")) {
            ps.setInt(1, conv);
            ps.setInt(2, me);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : null; }
        }
    }

    // ------------------------------------------------------------------ tạo / sửa
    /** Dùng chung Connection để gộp vào 1 Transaction (vd. kết bạn + tạo hội thoại). */
    public static int createPrivate(Connection c, int a, int b, long now) throws SQLException {
        int id;
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO Conversations(type,updated_at) VALUES('PRIVATE',?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); id = rs.getInt(1); }
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO ConversationMembers(conversation_id,user_id,role) VALUES(?,?, 'MEMBER')")) {
            for (int u : new int[]{a, b}) {
                ps.setInt(1, id);
                ps.setInt(2, u);
                ps.executeUpdate();
            }
        }
        return id;
    }

    public static int createPrivate(int a, int b, long now) throws SQLException {
        try (Connection c = Db.get()) {
            c.setAutoCommit(false);
            try {
                int id = createPrivate(c, a, b, now);
                c.commit();
                return id;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    public static int createGroup(String name, int adminId, Collection<Integer> others, long now) throws SQLException {
        try (Connection c = Db.get()) {
            c.setAutoCommit(false);
            try {
                int id;
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO Conversations(type,group_name,updated_at) VALUES('GROUP',?,?)", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, name);
                    ps.setLong(2, now);
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); id = rs.getInt(1); }
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO ConversationMembers(conversation_id,user_id,role) VALUES(?,?,?)")) {
                    ps.setInt(1, id);
                    ps.setInt(2, adminId);
                    ps.setString(3, "ADMIN");
                    ps.executeUpdate();
                    for (int u : others) {
                        if (u == adminId) continue;
                        ps.setInt(2, u);
                        ps.setString(3, "MEMBER");
                        ps.executeUpdate();
                    }
                }
                c.commit();
                return id;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    public static void touch(int conv, long lastMessageId, long ts) throws SQLException {
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
                "UPDATE Conversations SET last_message_id=?,updated_at=? WHERE conversation_id=?")) {
            ps.setLong(1, lastMessageId);
            ps.setLong(2, ts);
            ps.setInt(3, conv);
            ps.executeUpdate();
        }
    }

    public static void addMember(int conv, int user) throws SQLException {
        // Thành viên mới không thấy lịch sử cũ: đặt mốc xóa = tin mới nhất hiện tại.
        long max = MessageDao.maxId(conv);
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO ConversationMembers(conversation_id,user_id,role,cleared_before_msg_id,last_delivered_message_id,last_read_message_id) VALUES(?,?, 'MEMBER',?,?,?)")) {
            ps.setInt(1, conv);
            ps.setInt(2, user);
            ps.setLong(3, max);
            ps.setLong(4, max);
            ps.setLong(5, max);
            ps.executeUpdate();
        }
    }

    public static void removeMember(int conv, int user) throws SQLException {
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM ConversationMembers WHERE conversation_id=? AND user_id=?")) {
            ps.setInt(1, conv);
            ps.setInt(2, user);
            ps.executeUpdate();
        }
    }

    public static void rename(int conv, String name) throws SQLException {
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("UPDATE Conversations SET group_name=? WHERE conversation_id=?")) {
            ps.setString(1, name);
            ps.setInt(2, conv);
            ps.executeUpdate();
        }
    }

    public static void setRole(int conv, int user, String role) throws SQLException {
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
                "UPDATE ConversationMembers SET role=? WHERE conversation_id=? AND user_id=?")) {
            ps.setString(1, role);
            ps.setInt(2, conv);
            ps.setInt(3, user);
            ps.executeUpdate();
        }
    }

    public static int countAdmins(int conv) throws SQLException {
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM ConversationMembers WHERE conversation_id=? AND role='ADMIN'")) {
            ps.setInt(1, conv);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    /** Thành viên vào nhóm sớm nhất (trừ một người) — dùng để chuyển quyền ADMIN. */
    public static Integer oldestMemberExcluding(int conv, int excluded) throws SQLException {
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
                "SELECT user_id FROM ConversationMembers WHERE conversation_id=? AND user_id<>? ORDER BY joined_at,user_id LIMIT 1")) {
            ps.setInt(1, conv);
            ps.setInt(2, excluded);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : null; }
        }
    }

    public static void delete(int conv) throws SQLException {
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("DELETE FROM Conversations WHERE conversation_id=?")) {
            ps.setInt(1, conv);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ State: hai mốc của từng thành viên
    public static void markDelivered(int conv, int user, long upTo) throws SQLException {
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
                "UPDATE ConversationMembers SET last_delivered_message_id=GREATEST(last_delivered_message_id,?) "
                        + "WHERE conversation_id=? AND user_id=?")) {
            ps.setLong(1, upTo);
            ps.setInt(2, conv);
            ps.setInt(3, user);
            ps.executeUpdate();
        }
    }

    /** Đã xem thì chắc chắn đã nhận => nâng cả hai mốc. */
    public static void markRead(int conv, int user, long upTo) throws SQLException {
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
                "UPDATE ConversationMembers SET last_read_message_id=GREATEST(last_read_message_id,?),"
                        + "last_delivered_message_id=GREATEST(last_delivered_message_id,?) WHERE conversation_id=? AND user_id=?")) {
            ps.setLong(1, upTo);
            ps.setLong(2, upTo);
            ps.setInt(3, conv);
            ps.setInt(4, user);
            ps.executeUpdate();
        }
    }

    public static List<MemberState> memberStates(int conv) throws SQLException {
        List<MemberState> list = new ArrayList<>();
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
                "SELECT user_id,last_delivered_message_id,last_read_message_id FROM ConversationMembers WHERE conversation_id=?")) {
            ps.setInt(1, conv);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(new MemberState(rs.getInt(1), rs.getLong(2), rs.getLong(3)));
            }
        }
        return list;
    }

    public static int unread(int conv, int user) throws SQLException {
        String sql = "SELECT COUNT(*) FROM Messages m JOIN ConversationMembers cm ON cm.conversation_id=m.conversation_id AND cm.user_id=? "
                + "WHERE m.conversation_id=? AND m.message_id>cm.last_read_message_id AND m.message_id>cm.cleared_before_msg_id AND m.sender_id<>?";
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, user);
            ps.setInt(2, conv);
            ps.setInt(3, user);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    /** Xóa lịch sử phía tôi: ghi mốc cleared_before_msg_id. */
    public static void clearHistory(int conv, int user, long upTo) throws SQLException {
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
                "UPDATE ConversationMembers SET cleared_before_msg_id=GREATEST(cleared_before_msg_id,?) WHERE conversation_id=? AND user_id=?")) {
            ps.setLong(1, upTo);
            ps.setInt(2, conv);
            ps.setInt(3, user);
            ps.executeUpdate();
        }
    }
}
