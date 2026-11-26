package com.zalo.server;

import com.zalo.common.Config;
import com.zalo.common.Protocol;
import com.zalo.server.db.Db;
import com.zalo.server.db.UserDao;
import com.zalo.server.net.ClientHandler;
import com.zalo.server.service.MediaServer;

import javax.net.ServerSocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import java.io.FileInputStream;
import java.net.Socket;
import java.security.KeyStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Điểm khởi chạy Server: Acceptor Thread + Thread Pool, Kênh 1 bọc SSL/TLS, Kênh 2 media.
 * Chạy:  mvn -q -pl server exec:java -Dexec.args="server.properties"
 */
public final class ServerMain {
    public static void main(String[] args) throws Exception {
        Config cfg = Config.load(args.length > 0 ? args[0] : "server.properties");
        Db.init(cfg);
        try {
            UserDao.resetAllOffline();
        } catch (Exception e) {
            System.err.println("[db] Không kết nối được MySQL: " + e.getMessage());
            System.err.println("     Hãy chạy db/schema.sql và kiểm tra db.* trong server.properties");
            return;
        }

        int port = cfg.getInt("server.port", Protocol.DEFAULT_TLS_PORT);
        boolean tls = Boolean.parseBoolean(cfg.get("tls.enabled", "true"));

        ServerSocketFactory tlsFactory = ServerSocketFactory.getDefault();
        if (tls) {
            KeyStore ks = KeyStore.getInstance("JKS");
            char[] pw = cfg.get("tls.keystorePassword", "changeit").toCharArray();
            try (FileInputStream f = new FileInputStream(cfg.get("tls.keystore", "server.jks"))) { ks.load(f, pw); }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, pw);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            tlsFactory = ctx.getServerSocketFactory();
        }

        MediaServer.start(cfg.getInt("media.port", Protocol.DEFAULT_MEDIA_PORT), cfg.get("storage.dir", "storage"),
                ServerSocketFactory.getDefault());                      // Kênh 2: TCP thường theo đặc tả

        SSLServerSocketHolder.print(tls, port);
        java.net.ServerSocket ss = tlsFactory.createServerSocket(port);
        if (ss instanceof SSLServerSocket sss) sss.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});

        ExecutorService pool = Executors.newCachedThreadPool();
        while (true) {
            Socket s = ss.accept();
            pool.submit(new ClientHandler(s));
        }
    }

    private static final class SSLServerSocketHolder {
        static void print(boolean tls, int port) {
            System.out.println("[server] Kênh 1 " + (tls ? "SSL/TLS" : "TCP (KHÔNG mã hóa — chỉ để debug)") + " tại cổng " + port);
        }
    }
}
