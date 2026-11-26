package com.zalo.client.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** Tiện ích giao diện dùng chung (Time + Sender). */
public final class UiUtil {
    private UiUtil() {}

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DMY_HM = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    /** Time: Server Unix ms (UTC) -> giờ Việt Nam. Hôm nay chỉ hiện HH:mm, ngày khác hiện kèm ngày/tháng. */
    public static String time(long ms) {
        ZonedDateTime z = Instant.ofEpochMilli(ms).atZone(VN);
        return z.toLocalDate().equals(LocalDate.now(VN)) ? HM.format(z) : DMY_HM.format(z);
    }

    /** Sender: màu tên ổn định theo user_id. */
    public static Color userColor(int userId) {
        return Color.hsb((userId * 47) % 360, 0.6, 0.7);
    }

    public static String css(Color c) {
        return String.format("#%02x%02x%02x", (int) (c.getRed() * 255), (int) (c.getGreen() * 255), (int) (c.getBlue() * 255));
    }

    /** Avatar tròn chữ cái đầu, tô màu theo user_id. */
    public static Node avatar(String name, int userId, double radius) {
        Circle c = new Circle(radius, userColor(userId));
        String ch = name == null || name.isBlank() ? "?" : name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase();
        Label l = new Label(ch);
        l.setStyle("-fx-text-fill:white;-fx-font-weight:bold;-fx-font-size:" + (int) (radius * 0.9) + "px;");
        StackPane sp = new StackPane(c, l);
        sp.setAlignment(Pos.CENTER);
        sp.setMinSize(radius * 2, radius * 2);
        sp.setMaxSize(radius * 2, radius * 2);
        return sp;
    }

    public static String size(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.1f MB", b / 1048576.0);
        return String.format("%.2f GB", b / 1073741824.0);
    }

    public static String duration(int sec) {
        return (sec / 60) + ":" + String.format("%02d", sec % 60);
    }
}
