package com.zalo.client.net;

import com.zalo.common.Framing;
import com.zalo.common.Packet;
import com.zalo.common.Protocol;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Kênh 3 — P2P (Thành viên 2). Mỗi Client mở một ServerSocket cổng tự do (port 0) và báo cổng lên Server trong gói AUTH.
 * Quy tắc chống mở trùng: userId NHỎ HƠN chủ động connect() tới userId LỚN HƠN (làm ở ClientCore).
 *
 * Giữ nguyên các kết nối P2P khi mất kết nối Server => chat vẫn chạy khi tắt Server (TC-01).
 * Danh tính người gửi trên Kênh 3 được GẮN theo socket (qua P2P_HELLO), người nhận không tin trường senderId trong gói.
 *
 * Gói trên Kênh 3 (cùng Framing 4-byte): P2P_HELLO, SEND_MSG, P2P_ACK, TYPING.
 */
public final class P2PService {
    /** Callback (peerUserId, packet) — gọi trên luồng mạng, người dùng tự runLater. */
    private BiConsumer<Integer, Packet> onPacket;
    private BiConsumer<Integer, Boolean> onLink;     // (peerUserId, connected)
    private ServerSocket listener;
    private volatile int myUserId = -1;
    private final ConcurrentHashMap<Integer, Peer> peers = new ConcurrentHashMap<>();

    private static final class Peer {
        final int userId;
        final Socket socket;
        final DataOutputStream out;
        final DataInputStream in;

        Peer(int userId, Socket s) throws IOException {
            this(userId, s, new DataInputStream(new BufferedInputStream(s.getInputStream())));
        }

        /** Dùng lại luồng đọc đã có (tránh mất byte đã nằm trong bộ đệm sau khi đọc P2P_HELLO). */
        Peer(int userId, Socket s, DataInputStream in) throws IOException {
            this.userId = userId;
            this.socket = s;
            this.in = in;
            this.out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
        }

        synchronized void write(Packet p) throws IOException { Framing.writePacket(out, p); }
    }

    /** Mở cổng lắng nghe. @return cổng thật do hệ điều hành cấp (gửi kèm AUTH). */
    public int start(BiConsumer<Integer, Packet> onPacket, BiConsumer<Integer, Boolean> onLink) throws IOException {
        this.onPacket = onPacket;
        this.onLink = onLink;
        listener = new ServerSocket(0);
        Thread t = new Thread(() -> {
            while (!listener.isClosed()) {
                try {
                    Socket s = listener.accept();
                    Thread h = new Thread(() -> serveIncoming(s), "p2p-in");
                    h.setDaemon(true);
                    h.start();
                } catch (IOException e) {
                    return;
                }
            }
        }, "p2p-acceptor");
        t.setDaemon(true);
        t.start();
        return listener.getLocalPort();
    }

    public void setMyUserId(int id) { this.myUserId = id; }
    public int port() { return listener == null ? 0 : listener.getLocalPort(); }
    public boolean isConnected(int userId) { return peers.containsKey(userId); }

    public void stop() {
        try { if (listener != null) listener.close(); } catch (IOException ignored) { }
        for (Peer p : peers.values()) try { p.socket.close(); } catch (IOException ignored) { }
        peers.clear();
    }

    /** Chủ động gọi tới đối phương (chạy trên luồng nền, timeout 3s). Bỏ qua nếu đã có kết nối. */
    public void connect(int peerId, String ip, int port) {
        if (peers.containsKey(peerId)) return;
        Thread t = new Thread(() -> {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(ip, port), 3000);
                s.setTcpNoDelay(true);
                Peer p = new Peer(peerId, s);
                p.write(Packet.of(Protocol.P2P_HELLO).put("userId", myUserId));
                register(p);
                readLoop(p);
            } catch (IOException e) {
                // không nối được (khác mạng/NAT/tường lửa) => ClientCore tự fallback qua Server
            }
        }, "p2p-connect-" + peerId);
        t.setDaemon(true);
        t.start();
    }

    private void serveIncoming(Socket s) {
        try {
            s.setTcpNoDelay(true);
            s.setSoTimeout(5000);                               // phải HELLO trong 5s
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            Packet hello = Framing.readPacket(in);
            int uid = hello.intVal("userId", -1);
            if (!Protocol.P2P_HELLO.equals(hello.type()) || uid <= 0) { s.close(); return; }
            s.setSoTimeout(0);
            Peer bound = new Peer(uid, s, in);
            register(bound);
            readLoop(bound);
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private void register(Peer p) {
        Peer old = peers.put(p.userId, p);
        if (old != null && old != p) try { old.socket.close(); } catch (IOException ignored) { }
        onLink.accept(p.userId, true);
    }

    private void readLoop(Peer p) {
        try {
            while (true) onPacket.accept(p.userId, Framing.readPacket(p.in));
        } catch (IOException e) {
            // ngắt
        } finally {
            if (peers.remove(p.userId, p)) onLink.accept(p.userId, false);
            try { p.socket.close(); } catch (IOException ignored) { }
        }
    }

    /** @return true nếu đã ghi thành công qua P2P. */
    public boolean send(int peerId, Packet pkt) {
        Peer p = peers.get(peerId);
        if (p == null) return false;
        try {
            p.write(pkt);
            return true;
        } catch (IOException e) {
            try { p.socket.close(); } catch (IOException ignored) { }
            return false;
        }
    }
}
