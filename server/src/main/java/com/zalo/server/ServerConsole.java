package com.zalo.server;

import com.zalo.server.net.ClientHandler;
import com.zalo.server.net.SessionManager;
import com.zalo.server.util.ServerLog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Lệnh quản trị gõ trực tiếp vào cửa sổ Server: help | list | kick <userId> | stats | quit. */
public final class ServerConsole implements Runnable {
    @Override
    public void run() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            System.out.println("[console] gõ 'help' để xem lệnh quản trị");
            String line;
            while ((line = r.readLine()) != null) handle(line.trim());
        } catch (IOException ignored) {
        }
    }

    private void handle(String line) {
        if (line.isEmpty()) return;
        String[] a = line.split("\\s+");
        switch (a[0].toLowerCase()) {
            case "help" -> System.out.println("list | kick <userId> | stats | quit");
            case "list" -> {
                System.out.printf("%d user online:%n", SessionManager.count());
                for (ClientHandler h : SessionManager.all()) {
                    System.out.printf("  user=%d  ip=%s  p2pPort=%d  online %ds  im lặng %ds%n",
                            h.userId(), h.ip(), h.p2pPort(), h.ageSec(), h.idleSec());
                }
            }
            case "kick" -> {
                try {
                    int id = Integer.parseInt(a[1]);
                    System.out.println(SessionManager.kick(id, "Bạn đã bị quản trị viên ngắt kết nối.") ? "đã kick " + id : "user " + id + " không online");
                } catch (RuntimeException e) {
                    System.out.println("cú pháp: kick <userId>");
                }
            }
            case "stats" -> System.out.printf("uptime=%ds  kết nối đang mở=%d  user online=%d  gói đã nhận=%d%n",
                    (System.currentTimeMillis() - ServerMain.START) / 1000, ClientHandler.active(), SessionManager.count(), ClientHandler.packetsIn());
            case "quit", "exit" -> {
                ServerLog.info("server", "Quản trị viên yêu cầu tắt server");
                System.exit(0);
            }
            default -> System.out.println("lệnh không hợp lệ, gõ 'help'");
        }
    }
}
