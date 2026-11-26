package com.zalo.server.net;

import com.zalo.common.Framing;
import com.zalo.common.Packet;
import com.zalo.common.Protocol;
import com.zalo.server.db.UserDao;
import com.zalo.server.util.ServerLog;
import com.zalo.server.service.AuthService;
import com.zalo.server.service.ConversationService;
import com.zalo.server.service.FriendService;
import com.zalo.server.service.MediaService;
import com.zalo.server.service.MessageService;
import com.zalo.server.service.P2pSignalingService;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Một kết nối Client trên Kênh 1 (SSL/TLS).
 *  - Luồng đọc (run): đọc khung -> dispatch.
 *  - Luồng ghi riêng + hàng đợi ArrayBlockingQueue(500): chống Slow Consumer (đặc tả 5.4).
 *  - Socket Binding: chưa AUTH thì chỉ nhận REGISTER/AUTH trong 10s; AUTH xong mới gán userId.
 */
public final class ClientHandler implements Runnable {
    private static final Packet POISON = Packet.of("__POISON__");

    private final Socket socket;
    private final BlockingQueue<Packet> outQ = new ArrayBlockingQueue<>(Protocol.OUT_QUEUE_CAPACITY);
    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private static final AtomicLong PACKETS = new AtomicLong();
    private final long connectedAt = System.currentTimeMillis();
    private volatile long lastActivity = System.currentTimeMillis();
    private DataInputStream in;
    private DataOutputStream out;
    private volatile int userId = -1;
    private volatile int p2pPort;
    private volatile boolean closed;

    public ClientHandler(Socket socket) {
        this.socket = socket;
    }

    // ------------------------------------------------------------------ truy cập
    public static int active() { return ACTIVE.get(); }
    public static long packetsIn() { return PACKETS.get(); }
    public long ageSec() { return (System.currentTimeMillis() - connectedAt) / 1000; }
    public long idleSec() { return (System.currentTimeMillis() - lastActivity) / 1000; }
    public int userId() { return userId; }
    public boolean authenticated() { return userId > 0; }
    public int p2pPort() { return p2pPort; }
    public String ip() { return socket.getInetAddress().getHostAddress(); }

    /** Gán danh tính cho phiên Socket (chỉ AuthService gọi sau khi xác thực thành công). */
    public void bind(int userId, int p2pPort) {
        this.userId = userId;
        this.p2pPort = p2pPort;
    }

    // ------------------------------------------------------------------ gửi
    /** @return false nếu hàng đợi đầy (Client quá chậm) => ngắt kết nối, Client sẽ reconnect + sync lại. */
    public boolean send(Packet p) {
        if (closed) return false;
        if (!outQ.offer(p)) {
            ServerLog.info("slow-consumer", "user " + userId + " đầy hàng đợi -> ngắt");
            close();
            return false;
        }
        return true;
    }

    public void sendAndClose(Packet p) {
        outQ.offer(p);
        outQ.offer(POISON);
    }

    public void sendError(String code, String message, String ref) {
        Packet e = Packet.of(Protocol.ERROR).put("code", code).put("message", message);
        if (ref != null) e.put("ref", ref);
        send(e);
    }

    public void close() {
        closed = true;
        outQ.offer(POISON);
        try { socket.close(); } catch (IOException ignored) { }
    }

    // ------------------------------------------------------------------ vòng đời
    @Override
    public void run() {
        try {
            ACTIVE.incrementAndGet();
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);                       // thêm 1 lớp phát hiện kết nối chết ở tầng TCP
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            Thread w = new Thread(this::writerLoop, "writer-" + socket.getRemoteSocketAddress());
            w.setDaemon(true);
            w.start();

            while (!closed) {
                if (!authenticated()) {
                    int remain = (int) (Protocol.AUTH_TIMEOUT_MS - (System.currentTimeMillis() - connectedAt));
                    if (remain <= 0) throw new SocketTimeoutException("AUTH timeout");
                    socket.setSoTimeout(remain);
                }
                Packet p = Framing.readPacket(in);
                lastActivity = System.currentTimeMillis();
                PACKETS.incrementAndGet();
                boolean wasAuth = authenticated();
                handle(p);
                if (!wasAuth && authenticated()) socket.setSoTimeout(Protocol.HEARTBEAT_TIMEOUT_MS);
            }
        } catch (SocketTimeoutException e) {
            ServerLog.info("net", authenticated()
                    ? "user " + userId + " im lặng quá " + (Protocol.HEARTBEAT_TIMEOUT_MS / 1000) + "s (Heartbeat Timeout) -> ngắt, coi là Offline"
                    : ip() + " không AUTH trong " + (Protocol.AUTH_TIMEOUT_MS / 1000) + "s -> ngắt");
        } catch (SSLException e) {
            ServerLog.info("tls", "bắt tay TLS lỗi từ " + ip() + ": " + e.getMessage() + " (Client dùng TCP thường hoặc chưa tin chứng chỉ?)");
        } catch (IOException e) {
            // Client ngắt kết nối / gói lỗi: bình thường
        } catch (RuntimeException e) {
            e.printStackTrace();
        } finally {
            cleanup();
        }
    }

    private void writerLoop() {
        try {
            while (true) {
                Packet p = outQ.take();
                if (p == POISON) break;
                Framing.writePacket(out, p);
            }
        } catch (Exception ignored) {
        } finally {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private void cleanup() {
        ACTIVE.decrementAndGet();
        closed = true;
        outQ.offer(POISON);
        try { socket.close(); } catch (IOException ignored) { }
        int uid = userId;
        if (uid > 0 && SessionManager.unregister(uid, this)) {
            long now = System.currentTimeMillis();
            try {
                UserDao.setOffline(uid, now);
            } catch (SQLException e) {
                ServerLog.info("db", "setOffline lỗi: " + e.getMessage());
            }
            SessionManager.broadcastPresence(uid, false, now);
            ServerLog.info("net", "user " + uid + " OFFLINE (phiên " + ageSec() + "s)");
        }
    }

    // ------------------------------------------------------------------ dispatch
    private void handle(Packet p) {
        String t = p.type();
        if (t == null) {
            sendError(Protocol.ERR_BAD_REQUEST, "Thiếu trường type", null);
            return;
        }
        try {
            if (!authenticated()) {
                switch (t) {
                    case Protocol.REGISTER -> AuthService.register(this, p);
                    case Protocol.AUTH -> AuthService.authenticate(this, p);
                    default -> sendError(Protocol.ERR_UNAUTHENTICATED, "Cần AUTH trước", null);
                }
                return;
            }
            switch (t) {
                case Protocol.PING -> send(Packet.of(Protocol.PONG));

                // --- tin nhắn & trạng thái (State, History, Time, Sender)
                case Protocol.SEND_MSG -> MessageService.send(this, p);
                case Protocol.ACK_DELIVERED -> MessageService.delivered(this, p);
                case Protocol.MARK_AS_READ -> MessageService.read(this, p);
                case Protocol.SYNC -> MessageService.sync(this, p);
                case Protocol.LOAD_OLDER_MESSAGES -> MessageService.older(this, p);
                case Protocol.TYPING -> MessageService.typing(this, p);
                case Protocol.RECALL_MSG -> MessageService.recall(this, p);
                case Protocol.DELETE_FOR_ME -> MessageService.deleteForMe(this, p);
                case Protocol.CLEAR_HISTORY -> MessageService.clearHistory(this, p);
                case Protocol.REACT -> MessageService.react(this, p);

                // --- hội thoại & nhóm
                case Protocol.GET_CONVERSATIONS -> ConversationService.list(this);
                case Protocol.OPEN_PRIVATE -> ConversationService.openPrivate(this, p);
                case Protocol.CREATE_GROUP -> ConversationService.createGroup(this, p);
                case Protocol.GROUP_ADD_MEMBER -> ConversationService.addMember(this, p);
                case Protocol.GROUP_REMOVE_MEMBER -> ConversationService.removeMember(this, p);
                case Protocol.GROUP_RENAME -> ConversationService.rename(this, p);
                case Protocol.GROUP_LEAVE -> ConversationService.leave(this, p);

                // --- danh bạ
                case Protocol.SEARCH_USER -> FriendService.search(this, p);
                case Protocol.FRIEND_REQUEST -> FriendService.request(this, p);
                case Protocol.FRIEND_ACCEPT -> FriendService.accept(this, p);
                case Protocol.FRIEND_LIST -> FriendService.list(this);

                // --- P2P signaling
                case Protocol.P2P_LOOKUP -> P2pSignalingService.lookup(this, p);

                // --- media (Kênh 1: điều khiển; dữ liệu đi Kênh 2)
                case Protocol.FILE_INIT -> MediaService.fileInit(this, p);
                case Protocol.FILE_CANCEL -> MediaService.fileCancel(this, p);
                case Protocol.FILE_DOWNLOAD_REQ -> MediaService.downloadRequest(this, p);

                default -> sendError(Protocol.ERR_UNKNOWN_TYPE, "Không hỗ trợ: " + t, null);
            }
        } catch (SQLException e) {
            ServerLog.info("db", t + ": " + e.getMessage());
            sendError(Protocol.ERR_SERVER, "Lỗi cơ sở dữ liệu", p.str("clientMsgId"));
        } catch (RuntimeException e) {          // JSON thiếu trường / sai kiểu
            sendError(Protocol.ERR_BAD_REQUEST, "Gói tin không hợp lệ: " + e.getMessage(), p.str("clientMsgId"));
        }
    }
}
