package com.zalo.client;

/** Lớp main "thường" (không extends Application) để chạy được từ fat-jar / IDE không cấu hình module. */
public final class Launcher {
    public static void main(String[] args) {
        App.main(args);
    }
}
