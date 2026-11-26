package com.zalo.server;

import com.zalo.common.Config;
import com.zalo.common.Protocol;
import com.zalo.server.db.Db;
import com.zalo.server.db.UserDao;
import com.zalo.server.net.ClientHandler;
import com.zalo.server.net.SessionManager;
import com.zalo.server.service.MediaServer;
import com.zalo.server.util.ServerLog;

import javax.net.ServerSocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Điểm khởi chạy Server (Thành viên 1): Acceptor Thread + Thread Pool, Kênh 1 bọc SSL/TLS, Kênh 2 media.
 *   mvn -q -pl server exec:java -Dexec.args="server.properties"
 * Lệnh quản trị gõ trong cửa sổ này: help | list | kick <id> | stats | quit (xem ServerConsole).
 */
public final class ServerMain {
    public static final long START = System.currentTimeMillis();

    public static void main(String[] args) throws Exception {
        // Ưu tiên IPv4 để địa chỉ ghi vào Users.last_ip (dùng cho P2P) là IPv4 trong LAN, không phải IPv6 "0:0:0:0:0:0:0:1".
        System.setProperty("java.net.preferIPv4Stack", "true");
        Config cfg = Config.load(args.length > 0 ? args[0] : "server.properties");
        ServerLog.init(cfg.get("log.file", "server.log"));
        Db.init(cfg);
        try {
            UserDao.resetAllOffline();                          // khởi động lại: không để lại "user ma" đang Online
        } catch (Exception e) {
            ServerLog.info("db", "Không kết nối được MySQL: " + e.getMessage());
            ServerLog.info("db", "Hãy chạy db/schema.sql và kiểm tra db.* trong server.properties");
            return;
        }

        int port = cfg.getInt("server.port", Protocol.DEFAULT_TLS_PORT);
        int maxConn = cfg.getInt("max.connections", 500);
        boolean tls = Boolean.parseBoolean(cfg.get("tls.enabled", "true"));

        ServerSocketFactory factory = ServerSocketFactory.getDefault();
        if (tls) {
            factory = tlsFactory(cfg);
            if (factory == null) return;
        }
        MediaServer.start(cfg.getInt("media.port", Protocol.DEFAULT_MEDIA_PORT), cfg.get("storage.dir", "storage"),
                ServerSocketFactory.getDefault());              // Kênh 2: TCP thường theo đặc tả

        ServerSocket ss = factory.createServerSocket(port, 100);
        if (ss instanceof SSLServerSocket sss) sss.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
        ServerLog.info("server", "Kênh 1 " + (tls ? "SSL/TLS" : "TCP (KHÔNG mã hóa — chỉ để debug)") + " tại cổng " + port
                + " | tối đa " + maxConn + " kết nối | IP máy: " + localIp());

        Runtime.getRuntime().addShutdownHook(new Thread(ServerMain::shutdown, "shutdown"));
        Thread console = new Thread(new ServerConsole(), "console");
        console.setDaemon(true);
        console.start();

        ExecutorService pool = Executors.newCachedThreadPool();
        while (true) {
            Socket s;
            try {
                s = ss.accept();                                // bắt tay TLS diễn ra ở luồng ClientHandler, không chặn Acceptor
            } catch (IOException e) {
                ServerLog.info("server", "accept lỗi: " + e.getMessage());
                continue;
            }
            if (ClientHandler.active() >= maxConn) {
                ServerLog.info("server", "quá " + maxConn + " kết nối, từ chối " + s.getInetAddress().getHostAddress());
                try { s.close(); } catch (IOException ignored) { }
                continue;
            }
            pool.submit(new ClientHandler(s));
        }
    }

    /** @return null (và in hướng dẫn) nếu chưa có keystore. */
    private static ServerSocketFactory tlsFactory(Config cfg) throws Exception {
        String path = cfg.get("tls.keystore", "server.jks");
        if (!new File(path).isFile()) {
            ServerLog.info("tls", "Không thấy keystore '" + path + "'. Tạo chứng chỉ tự ký bằng: bash scripts/gen-cert.sh");
            ServerLog.info("tls", "(Windows: scripts\\gen-cert.bat). Hoặc tạm đặt tls.enabled=false ở CẢ server và client để debug.");
            return null;
        }
        char[] pw = cfg.get("tls.keystorePassword", "changeit").toCharArray();
        KeyStore ks = KeyStore.getInstance("JKS");
        try (FileInputStream f = new FileInputStream(path)) { ks.load(f, pw); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pw);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx.getServerSocketFactory();
    }

    private static void shutdown() {
        ServerLog.info("server", "Đang tắt: đóng " + SessionManager.count() + " phiên, đặt mọi user về Offline");
        SessionManager.closeAll();
        try { UserDao.resetAllOffline(); } catch (Exception ignored) { }
    }

    private static String localIp() {
        try { return InetAddress.getLocalHost().getHostAddress(); } catch (Exception e) { return "?"; }
    }
}
