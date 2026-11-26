package com.zalo.client.net;

import com.zalo.common.Config;
import com.zalo.common.Framing;
import com.zalo.common.Packet;
import com.zalo.common.Protocol;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lõi mạng Kênh 1 phía Client (Thành viên 5). KHÔNG đụng tới UI — mọi sự kiện đi qua Listener
 * (nơi gọi tự chuyển sang luồng UI bằng Platform.runLater).
 *
 *  - Hàng đợi OutboxQueue (LinkedBlockingDeque): send() chỉ đưa vào hàng đợi, luồng Writer mới ghi ra Socket.
 *  - Gặp IOException: gói đang ghi dở được trả lại ĐẦU hàng đợi, chạy Exponential Backoff 3s -> 5s -> 10s,
 *    kết nối lại + AUTH lại, rồi đẩy nốt Outbox (Server chống trùng bằng client_msg_id) — TC-03.
 *  - Heartbeat: PING mỗi 20s; Client cũng timeout 60s nếu không nhận được gì (phát hiện Server chết / mất Wi-Fi).
 */
public final class NetworkService {
    public enum State { CONNECTING, ONLINE, RECONNECTING, AUTH_FAILED, STOPPED }

    public interface Listener {
        void onPacket(Packet p);                 // gọi trên luồng mạng
        void onState(State s, String detail);    // gọi trên luồng mạng
    }

    private final Config cfg;
    private final LinkedBlockingDeque<Packet> outbox = new LinkedBlockingDeque<>();
    private volatile boolean running;
    private volatile boolean online;
    private volatile Socket socket;
    private volatile int attempt;
    private String user, pass;
    private int p2pPort;
    private Listener listener;

    public NetworkService(Config cfg) {
        this.cfg = cfg;
    }

    public String host() { return cfg.get("server.host", "localhost"); }
    public boolean isOnline() { return online; }

    public void start(String user, String pass, int p2pPort, Listener l) {
        this.user = user;
        this.pass = pass;
        this.p2pPort = p2pPort;
        this.listener = l;
        this.running = true;
        Thread t = new Thread(this::loop, "net-loop");
        t.setDaemon(true);
        t.start();
    }

    /** Đưa gói tin vào Outbox (không chặn). Mất mạng thì gói nằm chờ, tự gửi sau khi kết nối lại. */
    public void send(Packet p) {
        outbox.offer(p);
    }

    public void stop() {
        running = false;
        online = false;
        closeQuietly(socket);
    }

    // ================================================================== vòng đời kết nối
    private void loop() {
        while (running) {
            listener.onState(attempt == 0 ? State.CONNECTING : State.RECONNECTING, "");
            try {
                serveOnce();
            } catch (IOException | RuntimeException e) {
                // rơi xuống backoff
            } catch (Exception e) {
                // lỗi TLS/keystore: cũng thử lại
            }
            online = false;
            if (!running) break;
            int[] bo = Protocol.RECONNECT_BACKOFF_SEC;
            int sec = bo[Math.min(attempt, bo.length - 1)];
            attempt++;
            listener.onState(State.RECONNECTING, "Đang kết nối lại sau " + sec + "s...");
            try {
                Thread.sleep(sec * 1000L);
            } catch (InterruptedException e) {
                return;
            }
        }
        listener.onState(State.STOPPED, "");
    }

    private void serveOnce() throws Exception {
        Socket s = openSocket(cfg);
        socket = s;
        AtomicBoolean alive = new AtomicBoolean(true);
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));

            // 1) AUTH trong 10s đầu (ghi thẳng, không qua Outbox)
            Framing.writePacket(out, Packet.of(Protocol.AUTH).put("username", user).put("password", pass).put("p2pPort", p2pPort));
            Packet res = Framing.readPacket(in);
            if (!Protocol.AUTH_RESULT.equals(res.type()) || !res.boolVal("ok", false)) {
                running = false;                                   // sai mật khẩu: không thử lại vô ích
                listener.onPacket(res);
                listener.onState(State.AUTH_FAILED, res.str("error"));
                return;
            }
            attempt = 0;
            online = true;
            listener.onState(State.ONLINE, "");
            listener.onPacket(res);

            // 2) Writer + Heartbeat
            Thread writer = new Thread(() -> writerLoop(out, s, alive), "net-writer");
            writer.setDaemon(true);
            writer.start();
            Thread hb = new Thread(() -> heartbeatLoop(alive), "net-heartbeat");
            hb.setDaemon(true);
            hb.start();

            // 3) Reader (luồng này)
            while (running) {
                Packet p = Framing.readPacket(in);
                if (Protocol.FORCE_LOGOUT.equals(p.type())) {      // TC-04
                    running = false;
                    listener.onPacket(p);
                    return;
                }
                if (!Protocol.PONG.equals(p.type())) listener.onPacket(p);
            }
        } finally {
            alive.set(false);
            closeQuietly(s);
        }
    }

    private void writerLoop(DataOutputStream out, Socket s, AtomicBoolean alive) {
        try {
            while (alive.get()) {
                Packet p = outbox.poll(500, TimeUnit.MILLISECONDS);
                if (p == null) continue;
                try {
                    Framing.writePacket(out, p);
                } catch (IOException e) {
                    outbox.addFirst(p);                            // giữ lại gói chưa gửi được
                    throw e;
                }
            }
        } catch (Exception e) {
            closeQuietly(s);                                       // đánh thức Reader => vào backoff
        }
    }

    private void heartbeatLoop(AtomicBoolean alive) {
        try {
            while (alive.get()) {
                Thread.sleep(Protocol.PING_INTERVAL_MS);
                if (alive.get()) outbox.addFirst(Packet.of(Protocol.PING));
            }
        } catch (InterruptedException ignored) { }
    }

    // ================================================================== tiện ích dùng chung
    /** Mở socket tới Server: SSL/TLS nếu tls.enabled=true (tin cậy chứng chỉ tự ký trong client-trust.jks). */
    public static Socket openSocket(Config cfg) throws Exception {
        String host = cfg.get("server.host", "localhost");
        int port = cfg.getInt("server.port", Protocol.DEFAULT_TLS_PORT);
        Socket s;
        if (Boolean.parseBoolean(cfg.get("tls.enabled", "true"))) {
            KeyStore ts = KeyStore.getInstance("JKS");
            char[] pw = cfg.get("tls.truststorePassword", "changeit").toCharArray();
            try (FileInputStream f = new FileInputStream(cfg.get("tls.truststore", "client-trust.jks"))) { ts.load(f, pw); }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ts);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            SSLSocket ss = (SSLSocket) ctx.getSocketFactory().createSocket();
            ss.connect(new InetSocketAddress(host, port), 5000);
            ss.startHandshake();
            s = ss;
        } else {
            s = new Socket();
            s.connect(new InetSocketAddress(host, port), 5000);
        }
        s.setTcpNoDelay(true);
        s.setSoTimeout(60_000);                                    // 60s không nhận gì (kể cả PONG) => coi như đứt
        return s;
    }

    /** Gửi 1 yêu cầu rồi đọc 1 phản hồi (dùng cho REGISTER trước khi đăng nhập). Gọi trên luồng nền. */
    public static Packet requestOnce(Config cfg, Packet request) throws Exception {
        try (Socket s = openSocket(cfg)) {
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            Framing.writePacket(out, request);
            return Framing.readPacket(in);
        }
    }

    private static void closeQuietly(Socket s) {
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }
}
