package com.zalo.server.tools;

import com.zalo.common.Framing;
import com.zalo.common.Packet;
import com.zalo.common.Protocol;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyStore;

/**
 * Client dòng lệnh (không cần JavaFX) để chứng minh TC-04 / TC-07 và heartbeat.
 *
 *   java -cp server.jar:common.jar:gson.jar com.zalo.server.tools.TestClient <host> <port> <user> <pass> [tls|plain] [truststore] [--no-ping]
 *
 * Lệnh gõ vào: ping | typing <conversationId> | raw <json> | quit
 * --no-ping: không gửi PING => Server phải ngắt sau 45 giây (kiểm chứng Heartbeat Timeout).
 */
public final class TestClient {
    public static void main(String[] a) throws Exception {
        if (a.length < 4) {
            System.out.println("Dùng: TestClient <host> <port> <user> <pass> [tls|plain] [truststore.jks] [--no-ping]");
            return;
        }
        boolean tls = a.length < 5 || !"plain".equals(a[4]);
        String trust = a.length > 5 && !a[5].startsWith("--") ? a[5] : "client-trust.jks";
        boolean ping = true;
        for (String s : a) if ("--no-ping".equals(s)) ping = false;

        Socket s;
        if (tls) {
            KeyStore ts = KeyStore.getInstance("JKS");
            try (FileInputStream f = new FileInputStream(trust)) { ts.load(f, "changeit".toCharArray()); }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ts);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            SSLSocket ss = (SSLSocket) ctx.getSocketFactory().createSocket();
            ss.connect(new InetSocketAddress(a[0], Integer.parseInt(a[1])), 5000);
            ss.startHandshake();
            System.out.println("TLS OK: " + ss.getSession().getProtocol() + " / " + ss.getSession().getCipherSuite());
            s = ss;
        } else {
            s = new Socket();
            s.connect(new InetSocketAddress(a[0], Integer.parseInt(a[1])), 5000);
        }
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
        Framing.writePacket(out, Packet.of(Protocol.AUTH).put("username", a[2]).put("password", a[3]).put("p2pPort", 0));

        Thread reader = new Thread(() -> {
            try {
                while (true) System.out.println("<< " + Framing.read(in));
            } catch (Exception e) {
                System.out.println("[Kết nối đã bị đóng: " + e.getClass().getSimpleName() + "]");
                System.exit(0);
            }
        });
        reader.setDaemon(true);
        reader.start();

        if (ping) {
            Thread hb = new Thread(() -> {
                try {
                    while (true) {
                        Thread.sleep(Protocol.PING_INTERVAL_MS);
                        synchronized (out) { Framing.writePacket(out, Packet.of(Protocol.PING)); }
                    }
                } catch (Exception ignored) { }
            });
            hb.setDaemon(true);
            hb.start();
        }
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = stdin.readLine()) != null) {
            String[] p = line.trim().split("\\s+", 2);
            synchronized (out) {
                switch (p[0]) {
                    case "ping" -> Framing.writePacket(out, Packet.of(Protocol.PING));
                    case "typing" -> Framing.writePacket(out, Packet.of(Protocol.TYPING).put("conversationId", Integer.parseInt(p[1])).put("typing", true));
                    case "raw" -> Framing.write(out, p[1]);
                    case "quit" -> { s.close(); return; }
                    default -> System.out.println("lệnh: ping | typing <conv> | raw <json> | quit");
                }
            }
        }
        Thread.sleep(Long.MAX_VALUE);                                 // stdin đóng (chạy nền) vẫn giữ kết nối
    }
}
