package com.zalo.server.db;

import com.zalo.common.Config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Kết nối MySQL/MariaDB (utf8mb4 để lưu Emoji 4-byte).
 * Đơn giản: mỗi lần gọi mở 1 kết nối. Nếu cần nhanh hơn có thể thay bằng HikariCP (chỉ sửa file này).
 */
public final class Db {
    private static String url, user, pass;

    private Db() {}

    public static void init(Config c) {
        String host = c.get("db.host", "localhost");
        int port = c.getInt("db.port", 3306);
        String name = c.get("db.name", "zalo_network_app");
        user = c.get("db.user", "root");
        pass = c.get("db.password", "");
        url = "jdbc:mysql://" + host + ":" + port + "/" + name
                + "?useUnicode=true&characterEncoding=UTF-8&connectionCollation=utf8mb4_unicode_ci"
                + "&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
    }

    public static Connection get() throws SQLException {
        return DriverManager.getConnection(url, user, pass);
    }

    public static boolean isDuplicate(SQLException e) {
        return e instanceof java.sql.SQLIntegrityConstraintViolationException || e.getErrorCode() == 1062;
    }
}
