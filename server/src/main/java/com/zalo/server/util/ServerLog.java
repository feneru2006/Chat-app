package com.zalo.server.util;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Log có giờ, in ra console và (nếu cấu hình) ghi thêm vào file. Dùng làm minh chứng khi demo / báo cáo. */
public final class ServerLog {
    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
    private static PrintWriter file;

    private ServerLog() {}

    public static synchronized void init(String path) {
        if (path == null || path.isBlank()) return;
        try {
            file = new PrintWriter(new OutputStreamWriter(new FileOutputStream(path, true), StandardCharsets.UTF_8), true);
        } catch (IOException e) {
            System.err.println("[log] Không mở được " + path + ": " + e.getMessage());
        }
    }

    public static synchronized void info(String tag, String msg) {
        String line = F.format(Instant.now()) + " [" + tag + "] " + msg;
        System.out.println(line);
        if (file != null) file.println(line);
    }
}
