package com.zalo.common;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** Đọc file .properties; có thể ghi đè bằng -Dkey=value. Thiếu file thì dùng giá trị mặc định. */
public final class Config {
    private final Properties props = new Properties();

    public static Config load(String path) {
        Config c = new Config();
        try (InputStreamReader r = new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8)) {
            c.props.load(r);
        } catch (IOException e) {
            System.err.println("[config] Không đọc được " + path + " -> dùng mặc định / -D");
        }
        return c;
    }

    public String get(String key, String def) {
        String s = System.getProperty(key);
        return s != null ? s : props.getProperty(key, def);
    }

    public int getInt(String key, int def) {
        try {
            return Integer.parseInt(get(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
