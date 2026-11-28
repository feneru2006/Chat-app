package com.zalo.server.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MessageDao {
    private MessageDao() {}

    public record Msg(long id, String clientMsgId, int conv, int senderId, Long replyTo, String type,
                      String content, boolean recalled, long createdAt) {}

    public record Att(long id, long messageId, String fileName, long fileSize, String mime,
                      Integer durationSec, String filePath, String thumbPath) {}

    private static Msg map(ResultSet rs) throws SQLException {
        long r = rs.getLong("reply_to_msg_id");
        Long reply = rs.wasNull() ? null : r;
        return new Msg(rs.getLong("message_id"), rs.getString("client_msg_id"), rs.getInt("conversation_id"),
                rs.getInt("sender_id"), reply, rs.getString("msg_type"), rs.getString("content"),
                rs.getBoolean("is_recalled"), rs.getLong("created_at"));
    }

    /** Chèn tin nhắn. Trùng (conversation_id, client_msg_id) => SQLException, kiểm tra bằng Db.isDuplicate(). */
    public static Msg insert(Connection c, int conv, int sender, String clientMsgId, Long replyTo,
                             String type, String content, long now) throws SQLException {
        String sql = "INSERT INTO Messages(client_msg_id,conversation_id,sender_id,reply_to_msg_id,msg_type,content,created_at) VALUES(?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, clientMsgId);
            ps.setInt(2, conv);
            ps.setInt(3, sender);
            if (replyTo == null) ps.setNull(4, Types.BIGINT); else ps.setLong(4, replyTo);
            ps.setString(5, type);
            ps.setString(6, content);
            ps.setLong(7, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return new Msg(rs.getLong(1), clientMsgId, conv, sender, replyTo, type, content, false, now);
            }
        }
    }

    public static Msg insert(int conv, int sender, String clientMsgId, Long replyTo, String type, String content, long now) throws SQLException {
        try (Connection c = Db.get()) { return insert(c, conv, sender, clientMsgId, replyTo, type, content, now); }
    }

    public static long insertAttachment(Connection c, long messageId, String fileName, long size, String mime,
                                        Integer durationSec, String filePath, String thumbPath) throws SQLException {
        String sql = "INSERT INTO Attachments(message_id,file_name,file_size,mime_type,duration_sec,file_path,thumbnail_path) VALUES(?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, messageId);
            ps.setString(2, fileName);
            ps.setLong(3, size);
            ps.setString(4, mime);
            if (durationSec == null) ps.setNull(5, Types.INTEGER); else ps.setInt(5, durationSec);
            ps.setString(6, filePath);
            ps.setString(7, thumbPath);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); return rs.getLong(1); }
        }
    }

    public static Msg findByClientMsgId(int conv, String clientMsgId) throws SQLException {
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM Messages WHERE conversation_id=? AND client_msg_id=?")) {
            ps.setInt(1, conv);
            ps.setString(2, clientMsgId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
        }
    }

    public static Msg findById(long id) throws SQLException {
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("SELECT * FROM Messages WHERE message_id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
        }
    }

    /** Tin mới nhất mà một thành viên còn được xem, tôn trọng xóa riêng và xóa lịch sử. */
    public static Msg latestVisible(int user, int conv) throws SQLException {
        String sql = "SELECT m.*" + VISIBLE + "ORDER BY m.message_id DESC LIMIT 1";
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, user);
            ps.setInt(2, conv);
            ps.setInt(3, user);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
        }
    }

    public static long maxId(int conv) throws SQLException {
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(MAX(message_id),0) FROM Messages WHERE conversation_id=?")) {
            ps.setInt(1, conv);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }

    private static final String VISIBLE =
            " FROM Messages m JOIN ConversationMembers cm ON cm.conversation_id=m.conversation_id AND cm.user_id=? "
                    + "WHERE m.conversation_id=? AND m.message_id>cm.cleared_before_msg_id "
                    + "AND NOT EXISTS (SELECT 1 FROM MessageDeletions d WHERE d.message_id=m.message_id AND d.user_id=?) ";

    /** Đồng bộ Offline: tin mới hơn mốc lastId (tăng dần). Tôn trọng "xóa phía tôi" và "xóa lịch sử". */
    public static List<Msg> newer(int user, int conv, long lastId, int limit) throws SQLException {
        String sql = "SELECT m.*" + VISIBLE + "AND m.message_id>? ORDER BY m.message_id ASC LIMIT ?";
        List<Msg> list = new ArrayList<>();
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, user);
            ps.setInt(2, conv);
            ps.setInt(3, user);
            ps.setLong(4, lastId);
            ps.setInt(5, limit);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) list.add(map(rs)); }
        }
        return list;
    }

    /** Phân trang lịch sử: tin cũ hơn beforeId (keyset pagination), trả về theo thứ tự tăng dần. */
    public static List<Msg> older(int user, int conv, long beforeId, int limit) throws SQLException {
        String sql = "SELECT m.*" + VISIBLE + "AND m.message_id<? ORDER BY m.message_id DESC LIMIT ?";
        List<Msg> list = new ArrayList<>();
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, user);
            ps.setInt(2, conv);
            ps.setInt(3, user);
            ps.setLong(4, beforeId);
            ps.setInt(5, limit);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) list.add(map(rs)); }
        }
        Collections.reverse(list);
        return list;
    }

    // ------------------------------------------------------------------ Thu hồi / Xóa
    public static boolean recall(long messageId, int senderId) throws SQLException {
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
                "UPDATE Messages SET is_recalled=TRUE WHERE message_id=? AND sender_id=? AND is_recalled=FALSE")) {
            ps.setLong(1, messageId);
            ps.setInt(2, senderId);
            return ps.executeUpdate() > 0;
        }
    }

    public static void deleteForMe(long messageId, int userId) throws SQLException {
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
                "INSERT IGNORE INTO MessageDeletions(message_id,user_id) VALUES(?,?)")) {
            ps.setLong(1, messageId);
            ps.setInt(2, userId);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ Reaction
    public static void setReaction(long messageId, int userId, String reaction) throws SQLException {
        try (Connection c = Db.get()) {
            if (reaction == null) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM MessageReactions WHERE message_id=? AND user_id=?")) {
                    ps.setLong(1, messageId);
                    ps.setInt(2, userId);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO MessageReactions(message_id,user_id,reaction_type) VALUES(?,?,?) "
                                + "ON DUPLICATE KEY UPDATE reaction_type=VALUES(reaction_type)")) {
                    ps.setLong(1, messageId);
                    ps.setInt(2, userId);
                    ps.setString(3, reaction);
                    ps.executeUpdate();
                }
            }
        }
    }

    /** @return userId -> reaction */
    public static Map<Integer, String> reactionsOf(long messageId) throws SQLException {
        Map<Integer, String> m = new LinkedHashMap<>();
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
                "SELECT user_id,reaction_type FROM MessageReactions WHERE message_id=?")) {
            ps.setLong(1, messageId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) m.put(rs.getInt(1), rs.getString(2)); }
        }
        return m;
    }

    // ------------------------------------------------------------------ Attachments
    private static Att mapAtt(ResultSet rs) throws SQLException {
        int d = rs.getInt("duration_sec");
        Integer dur = rs.wasNull() ? null : d;
        return new Att(rs.getLong("attachment_id"), rs.getLong("message_id"), rs.getString("file_name"),
                rs.getLong("file_size"), rs.getString("mime_type"), dur, rs.getString("file_path"), rs.getString("thumbnail_path"));
    }

    public static Att attachmentOf(long messageId) throws SQLException {
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("SELECT * FROM Attachments WHERE message_id=? LIMIT 1")) {
            ps.setLong(1, messageId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? mapAtt(rs) : null; }
        }
    }

    public static Att attachmentById(long attachmentId) throws SQLException {
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("SELECT * FROM Attachments WHERE attachment_id=?")) {
            ps.setLong(1, attachmentId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? mapAtt(rs) : null; }
        }
    }
}
