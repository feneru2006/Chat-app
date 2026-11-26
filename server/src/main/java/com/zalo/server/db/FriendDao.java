package com.zalo.server.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Bảng Friendships: khóa (user_id_1 < user_id_2); action_user_id = người gửi lời mời. */
public final class FriendDao {
    private FriendDao() {}

    public record Row(String status, int actionUserId) {}

    private static int lo(int a, int b) { return Math.min(a, b); }
    private static int hi(int a, int b) { return Math.max(a, b); }

    public static Row find(int a, int b) throws SQLException {
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
                "SELECT status,action_user_id FROM Friendships WHERE user_id_1=? AND user_id_2=?")) {
            ps.setInt(1, lo(a, b));
            ps.setInt(2, hi(a, b));
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? new Row(rs.getString(1), rs.getInt(2)) : null; }
        }
    }

    public static void insertPending(int from, int to) throws SQLException {
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO Friendships(user_id_1,user_id_2,status,action_user_id) VALUES(?,?,'PENDING',?)")) {
            ps.setInt(1, lo(from, to));
            ps.setInt(2, hi(from, to));
            ps.setInt(3, from);
            ps.executeUpdate();
        }
    }

    public static void accept(Connection c, int a, int b) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE Friendships SET status='ACCEPTED' WHERE user_id_1=? AND user_id_2=?")) {
            ps.setInt(1, lo(a, b));
            ps.setInt(2, hi(a, b));
            ps.executeUpdate();
        }
    }

    /** @return các user_id đã là bạn. */
    public static List<Integer> friends(int user) throws SQLException {
        return others(user, "status='ACCEPTED'");
    }

    /** @return các user_id đã gửi lời mời tới tôi và đang chờ. */
    public static List<Integer> pendingIncoming(int user) throws SQLException {
        return others(user, "status='PENDING' AND action_user_id<>" + user);
    }

    private static List<Integer> others(int user, String cond) throws SQLException {
        String sql = "SELECT CASE WHEN user_id_1=? THEN user_id_2 ELSE user_id_1 END FROM Friendships WHERE (user_id_1=? OR user_id_2=?) AND " + cond;
        List<Integer> ids = new ArrayList<>();
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, user);
            ps.setInt(2, user);
            ps.setInt(3, user);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) ids.add(rs.getInt(1)); }
        }
        return ids;
    }
}
