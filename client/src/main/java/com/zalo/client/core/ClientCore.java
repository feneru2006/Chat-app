package com.zalo.client.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zalo.client.net.MediaClient;
import com.zalo.client.net.NetworkService;
import com.zalo.client.net.P2PService;
import com.zalo.common.Config;
import com.zalo.common.MessageStateCalc;
import com.zalo.common.MessageStateCalc.State;
import com.zalo.common.Packet;
import com.zalo.common.Protocol;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * "Bộ não" phía Client: nhận mọi gói tin (Server + P2P), cập nhật mô hình dữ liệu và báo cho UI.
 * TẤT CẢ phương thức public chạy trên luồng JavaFX (gói tin từ luồng mạng được Platform.runLater).
 *
 * Phân công gợi ý: TV5 (mạng + Sender UI), TV2 (P2P, State), TV3 (media), TV4 (History/Time) cùng sửa file này
 * ở các hàm tương ứng — mỗi nhóm hàm được đánh dấu bằng dòng "=====".
 */
public final class ClientCore {

    /** UI cài đặt interface này (xem App.java). */
    public interface Ui {
        void onAuth(boolean ok, String error);
        void onConnState(NetworkService.State s, String detail);
        void onConvListChanged();
        void onConvChanged(int convId);              // tin nhắn / trạng thái của 1 hội thoại đổi
        void onTyping(int convId);
        void onToast(String msg);
        void onForceLogout(String reason);
        void onOpenConversation(int convId);
        void onSocial(Packet p);                     // SEARCH_RESULT, FRIEND_LIST_RESULT, FRIEND_REQUEST_IN, FRIEND_ACCEPTED
    }

    private static final class Upload {
        final MsgModel msg;
        final File file;
        final AtomicBoolean cancel = new AtomicBoolean();
        Upload(MsgModel m, File f) { msg = m; file = f; }
    }

    public final Config cfg;
    private final Ui ui;
    private NetworkService net;
    private P2PService p2p;
    public int myId = -1;
    public String myName = "";
    public final Map<Integer, ConvModel> convs = new LinkedHashMap<>();
    public int currentConv = -1;
    public boolean windowFocused = true;
    private boolean everOnline;
    private final Map<String, Upload> uploads = new HashMap<>();
    private final Map<String, List<Consumer<File>>> downloadWaiters = new HashMap<>();

    public ClientCore(Config cfg, Ui ui) {
        this.cfg = cfg;
        this.ui = ui;
    }

    // ===================================================================== đăng nhập / đăng ký
    public static void registerAsync(Config cfg, String user, String phone, String pass, String display, Consumer<Packet> cb) {
        Thread t = new Thread(() -> {
            Packet res;
            try {
                res = NetworkService.requestOnce(cfg, Packet.of(Protocol.REGISTER).put("username", user)
                        .put("phone", phone).put("password", pass).put("displayName", display));
            } catch (Exception e) {
                res = Packet.of(Protocol.REGISTER_RESULT).put("ok", false).put("error", "Không kết nối được Server: " + e.getMessage());
            }
            Packet r = res;
            Platform.runLater(() -> cb.accept(r));
        }, "register");
        t.setDaemon(true);
        t.start();
    }

    public void login(String user, String pass) throws IOException {
        everOnline = false;
        p2p = new P2PService();
        int port = p2p.start((peer, pk) -> Platform.runLater(() -> onP2P(peer, pk)),
                (peer, up) -> Platform.runLater(() -> onP2PLink(peer, up)));
        net = new NetworkService(cfg);
        net.start(user, pass, port, new NetworkService.Listener() {
            @Override public void onPacket(Packet p) { Platform.runLater(() -> handle(p)); }
            @Override public void onState(NetworkService.State s, String d) { Platform.runLater(() -> ui.onConnState(s, d)); }
        });
    }

    public void shutdown() {
        if (net != null) net.stop();
        if (p2p != null) p2p.stop();
        convs.clear();
        currentConv = -1;
        myId = -1;
    }

    public String host() { return net == null ? cfg.get("server.host", "localhost") : net.host(); }
    public boolean isOnline() { return net != null && net.isOnline(); }
    public boolean p2pConnected(ConvModel c) {
        if (c.isGroup() || p2p == null) return false;
        ConvModel.Member peer = c.peer(myId);
        return peer != null && p2p.isConnected(peer.userId);
    }

    // ===================================================================== xử lý gói tin từ Server
    private void handle(Packet p) {
        String t = p.type();
        if (t == null) return;
        try {
            switch (t) {
                case Protocol.AUTH_RESULT -> onAuthResult(p);
                case Protocol.FORCE_LOGOUT -> ui.onForceLogout(p.str("reason"));
                case Protocol.CONVERSATIONS -> onConversations(p);
                case Protocol.CONVERSATION_ADDED, Protocol.CONVERSATION_UPDATED -> onConversationUpsert(p, Protocol.CONVERSATION_ADDED.equals(t));
                case Protocol.CONVERSATION_REMOVED -> {
                    convs.remove(p.intVal("conversationId", -1));
                    if (currentConv == p.intVal("conversationId", -1)) currentConv = -1;
                    ui.onConvListChanged();
                }
                case Protocol.NEW_MESSAGE -> onNewMessage(MsgModel.fromJson(p.object("message")));
                case Protocol.MSG_ACK -> onAck(p);
                case Protocol.MSG_STATUS -> onStatus(p);
                case Protocol.SYNC_RESULT -> onSyncResult(p);
                case Protocol.OLDER_RESULT -> onOlderResult(p);
                case Protocol.MSG_RECALLED -> onRecalled(p);
                case Protocol.MSG_DELETED_ME -> onDeletedForMe(p);
                case Protocol.HISTORY_CLEARED -> onHistoryCleared(p);
                case Protocol.MSG_REACTION -> onReaction(p);
                case Protocol.TYPING -> onTyping(p.intVal("conversationId", -1), p.intVal("userId", -1), p.boolVal("typing", true));
                case Protocol.USER_ONLINE, Protocol.USER_OFFLINE -> onPresence(p.intVal("userId", -1), Protocol.USER_ONLINE.equals(t));
                case Protocol.P2P_PEER_INFO -> onPeerInfo(p);
                case Protocol.FILE_READY -> onFileReady(p);
                case Protocol.FILE_DOWNLOAD_READY -> onDownloadReady(p);
                case Protocol.SEARCH_RESULT, Protocol.FRIEND_LIST_RESULT, Protocol.FRIEND_REQUEST_IN, Protocol.FRIEND_ACCEPTED -> ui.onSocial(p);
                case Protocol.ERROR -> ui.onToast(p.str("message") == null ? p.str("code") : p.str("message"));
                default -> { }
            }
        } catch (RuntimeException e) {
            e.printStackTrace();                         // gói lạ không được làm sập UI
        }
    }

    private void onAuthResult(Packet p) {
        if (!p.boolVal("ok", false)) {
            ui.onAuth(false, p.str("error"));
            return;
        }
        myId = p.intVal("userId", -1);
        myName = p.str("displayName");
        p2p.setMyUserId(myId);
        boolean reconnect = everOnline;
        everOnline = true;
        if (!reconnect) ui.onAuth(true, null);
        net.send(Packet.of(Protocol.GET_CONVERSATIONS));
        net.send(Packet.of(Protocol.FRIEND_LIST));
        if (reconnect) resync();                         // TC-03: kéo tin phát sinh lúc mất mạng
    }

    /** Đồng bộ sau khi kết nối lại: {conversationId: last_local_message_id}. */
    private void resync() {
        JsonObject m = new JsonObject();
        for (ConvModel c : convs.values()) if (c.historyLoaded) m.addProperty(String.valueOf(c.id), c.lastMessageId());
        if (m.size() > 0) net.send(Packet.of(Protocol.SYNC).put("conversations", m));
    }

    // ===================================================================== hội thoại
    private void onConversations(Packet p) {
        java.util.Set<Integer> keep = new java.util.HashSet<>();
        for (JsonElement e : p.array("conversations")) {
            JsonObject o = e.getAsJsonObject();
            ConvModel c = convs.computeIfAbsent(o.get("conversationId").getAsInt(), ConvModel::new);
            c.applyJson(o, myId);
            keep.add(c.id);
            if (visible(c)) c.unread = 0;
        }
        convs.keySet().removeIf(id -> !keep.contains(id));
        ui.onConvListChanged();
        p2pLookupAll();
    }

    private void onConversationUpsert(Packet p, boolean added) {
        JsonObject o = p.object("conversation");
        ConvModel c = convs.computeIfAbsent(o.get("conversationId").getAsInt(), ConvModel::new);
        c.applyJson(o, myId);
        c.changeKind = ConvModel.KIND_REFRESH;
        ui.onConvListChanged();
        ui.onConvChanged(c.id);
        if (added && p.boolVal("opened", false)) ui.onOpenConversation(c.id);
        p2pLookupAll();
    }

    public void openConversation(int convId) {
        currentConv = convId;
        ConvModel c = convs.get(convId);
        if (c == null) return;
        if (!c.historyLoaded && !c.loadingOlder) loadOlder(c);           // lần đầu: 30 tin mới nhất
        markReadIfVisible(c);
        if (!c.isGroup()) {
            ConvModel.Member peer = c.peer(myId);
            if (peer != null && !p2p.isConnected(peer.userId)) net.send(Packet.of(Protocol.P2P_LOOKUP).put("targetUserId", peer.userId));
        }
        ui.onConvListChanged();
    }

    public void onFocusChanged(boolean focused) {
        windowFocused = focused;
        ConvModel c = convs.get(currentConv);
        if (focused && c != null) markReadIfVisible(c);
    }

    private boolean visible(ConvModel c) { return currentConv == c.id && windowFocused; }

    // ===================================================================== P2P (TV2)
    private void p2pLookupAll() {
        for (ConvModel c : convs.values()) {
            if (c.isGroup()) continue;
            ConvModel.Member peer = c.peer(myId);
            // Quy tắc: userId NHỎ gọi tới userId LỚN. Phía lớn đợi (hoặc dùng Server nếu chưa nối được).
            if (peer != null && peer.online && myId < peer.userId && !p2p.isConnected(peer.userId)) {
                net.send(Packet.of(Protocol.P2P_LOOKUP).put("targetUserId", peer.userId));
            }
        }
    }

    private void onPeerInfo(Packet p) {
        int target = p.intVal("targetUserId", -1);
        if (p.boolVal("online", false) && myId < target) p2p.connect(target, p.str("ip"), p.intVal("port", 0));
    }

    private void onP2PLink(int peerId, boolean up) {
        for (ConvModel c : convs.values()) {
            if (!c.isGroup() && c.peer(myId) != null && c.peer(myId).userId == peerId) {
                c.changeKind = ConvModel.KIND_REFRESH;
                ui.onConvChanged(c.id);
            }
        }
    }

    private ConvModel privateWith(int peerId) {
        for (ConvModel c : convs.values()) {
            if (!c.isGroup() && c.peer(myId) != null && c.peer(myId).userId == peerId) return c;
        }
        return null;
    }

    /** Gói từ Kênh 3. peerId do socket P2P_HELLO gắn, KHÔNG lấy từ nội dung gói. */
    private void onP2P(int peerId, Packet p) {
        ConvModel c = privateWith(peerId);
        if (c == null || p.type() == null) return;
        switch (p.type()) {
            case Protocol.SEND_MSG -> {
                String cid = p.str("clientMsgId");
                if (cid == null) return;
                p2p.send(peerId, Packet.of(Protocol.P2P_ACK).put("clientMsgId", cid));
                for (MsgModel x : c.messages) if (cid.equals(x.clientMsgId)) return;     // bản Server đã tới trước
                MsgModel m = new MsgModel();
                m.clientMsgId = cid;
                m.conv = c.id;
                m.senderId = peerId;
                m.senderName = c.peer(myId).displayName;
                m.content = p.str("content");
                m.createdAt = p.longVal("createdAt", System.currentTimeMillis());
                m.viaP2P = true;
                c.messages.add(m);
                c.addedCount = 1;
                c.changeKind = ConvModel.KIND_APPEND;
                c.preview = m.preview();
                c.updatedAt = Math.max(c.updatedAt, m.createdAt);
                if (!visible(c)) c.unread++;
                ui.onConvChanged(c.id);
                ui.onConvListChanged();
            }
            case Protocol.P2P_ACK -> {
                for (MsgModel x : c.messages) {
                    if (p.str("clientMsgId") != null && p.str("clientMsgId").equals(x.clientMsgId)) x.p2pDelivered = true;
                }
                c.changeKind = ConvModel.KIND_REFRESH;
                ui.onConvChanged(c.id);
            }
            case Protocol.TYPING -> onTyping(c.id, peerId, p.boolVal("typing", true));
            default -> { }
        }
    }

    private void onPresence(int userId, boolean online) {
        for (ConvModel c : convs.values()) {
            ConvModel.Member m = c.member(userId);
            if (m != null) m.online = online;
        }
        ui.onConvListChanged();
        if (online && userId > myId) return;                    // phía lớn đợi phía nhỏ gọi
        if (online) net.send(Packet.of(Protocol.P2P_LOOKUP).put("targetUserId", userId));
    }

    // ===================================================================== gửi tin
    /** Gửi TEXT/Emoji. 1-1: ưu tiên P2P (nếu đã nối) + luôn gửi 1 bản qua Server để lưu History / fallback. */
    public void sendText(int convId, String text) {
        ConvModel c = convs.get(convId);
        if (c == null || text == null || text.isBlank()) return;
        String cid = UUID.randomUUID().toString();
        MsgModel m = new MsgModel();
        m.clientMsgId = cid;
        m.conv = convId;
        m.senderId = myId;
        m.senderName = myName;
        m.content = text;
        m.createdAt = System.currentTimeMillis();                // tạm, Server sẽ ghi đè bằng giờ chuẩn khi MSG_ACK
        ConvModel.Member peer = c.isGroup() ? null : c.peer(myId);
        if (peer != null) {
            m.viaP2P = p2p.send(peer.userId, Packet.of(Protocol.SEND_MSG).put("clientMsgId", cid)
                    .put("conversationId", convId).put("content", text).put("createdAt", m.createdAt));
        }
        c.messages.add(m);                                       // Optimistic UI: hiện ngay, trạng thái SENDING
        c.addedCount = 1;
        c.changeKind = ConvModel.KIND_APPEND;
        c.preview = "Bạn: " + text;
        c.updatedAt = Math.max(c.updatedAt, m.createdAt);
        net.send(Packet.of(Protocol.SEND_MSG).put("clientMsgId", cid).put("conversationId", convId)
                .put("msgType", Protocol.MSG_TEXT).put("content", text));
        ui.onConvChanged(convId);
        ui.onConvListChanged();
    }

    private void onAck(Packet p) {
        ConvModel c = convs.get(p.intVal("conversationId", -1));
        if (c == null) return;
        String cid = p.str("clientMsgId");
        MsgModel target = null;
        for (MsgModel x : c.messages) if (cid != null && cid.equals(x.clientMsgId)) { target = x; break; }
        long id = p.longVal("messageId", 0), at = p.longVal("createdAt", 0);
        if (target != null) {
            target.id = id;
            target.createdAt = at;                               // Time: giờ Server là chuẩn
            target.failed = false;
            target.progress = -1;
            if (p.object("message") != null) target.mergeFromServer(MsgModel.fromJson(p.object("message")));
        } else if (p.object("message") != null) {
            c.messages.add(MsgModel.fromJson(p.object("message")));
        }
        c.changeKind = ConvModel.KIND_REFRESH;
        ui.onConvChanged(c.id);
        ui.onConvListChanged();
    }

    // ===================================================================== nhận tin (State + History + Time)
    private void onNewMessage(MsgModel in) {
        ConvModel c = convs.get(in.conv);
        if (c == null) {
            net.send(Packet.of(Protocol.GET_CONVERSATIONS));
            return;
        }
        MsgModel ex = upsert(c, in);
        if (ex == null) {
            c.messages.add(in);
            ex = in;
            c.addedCount = 1;
            c.changeKind = ConvModel.KIND_APPEND;
            if (in.senderId != myId && !"SYSTEM".equals(in.type) && !visible(c)) c.unread++;
        } else {
            c.changeKind = ConvModel.KIND_REFRESH;
        }
        c.preview = ("SYSTEM".equals(ex.type) || ex.senderId != myId ? "" : "Bạn: ") + ex.preview();
        c.updatedAt = Math.max(c.updatedAt, ex.createdAt);
        if (ex.senderId != myId && ex.id > 0) {
            ackDelivered(c, ex.id);                              // => Server cập nhật last_delivered_message_id
            markReadIfVisible(c);
        }
        ui.onConvChanged(c.id);
        ui.onConvListChanged();
    }

    /** Tìm tin đã có (theo clientMsgId hoặc message_id) và gộp dữ liệu Server vào. @return null nếu là tin mới. */
    private MsgModel upsert(ConvModel c, MsgModel in) {
        for (MsgModel x : c.messages) {
            boolean same = (in.clientMsgId != null && in.clientMsgId.equals(x.clientMsgId)) || (in.id > 0 && x.id == in.id);
            if (same) {
                x.mergeFromServer(in);
                return x;
            }
        }
        return null;
    }

    private void ackDelivered(ConvModel c, long upTo) {
        if (upTo <= c.sentDeliveredUpTo) return;
        c.sentDeliveredUpTo = upTo;
        net.send(Packet.of(Protocol.ACK_DELIVERED).put("conversationId", c.id).put("upToMessageId", upTo));
    }

    /** Mở đúng khung chat (và cửa sổ đang được focus) => MARK_AS_READ => Server cập nhật last_read_message_id. */
    public void markReadIfVisible(ConvModel c) {
        if (!visible(c)) return;
        c.unread = 0;
        long last = c.lastMessageId();
        if (last > c.sentReadUpTo) {
            c.sentReadUpTo = last;
            c.sentDeliveredUpTo = Math.max(c.sentDeliveredUpTo, last);
            net.send(Packet.of(Protocol.MARK_AS_READ).put("conversationId", c.id).put("upToMessageId", last));
        }
        ui.onConvListChanged();
    }

    private void onStatus(Packet p) {
        ConvModel c = convs.get(p.intVal("conversationId", -1));
        if (c == null) return;
        long[] st = c.states.computeIfAbsent(p.intVal("userId", -1), k -> new long[2]);
        long up = p.longVal("upToMessageId", 0);
        st[0] = Math.max(st[0], up);
        if (Protocol.ST_READ.equals(p.str("status"))) st[1] = Math.max(st[1], up);
        c.changeKind = ConvModel.KIND_REFRESH;
        ui.onConvChanged(c.id);
    }

    /**
     * Trạng thái hiển thị của tin NHẰM của mình (SENDING/SENT/DELIVERED/READ) — suy ra từ mốc từng thành viên (đặc tả 3.3).
     */
    public State stateOf(ConvModel c, MsgModel m) {
        if (m.id <= 0) return m.p2pDelivered ? State.DELIVERED : State.SENDING;
        List<MessageStateCalc.Member> others = new ArrayList<>();
        for (ConvModel.Member mem : c.members) {
            if (mem.userId == myId) continue;
            long[] s = c.states.getOrDefault(mem.userId, new long[2]);
            others.add(new MessageStateCalc.Member(mem.userId, s[0], s[1]));
        }
        return MessageStateCalc.compute(m.id, others);
    }

    /** Tên những người đã xem tin m (hiển thị "Đã xem bởi ..." ở tin cuối của mình). */
    public List<String> readersOf(ConvModel c, MsgModel m) {
        List<String> names = new ArrayList<>();
        for (ConvModel.Member mem : c.members) {
            if (mem.userId == myId) continue;
            long[] s = c.states.getOrDefault(mem.userId, new long[2]);
            if (m.id > 0 && s[1] >= m.id) names.add(mem.displayName);
        }
        return names;
    }

    // ===================================================================== History (TV4)
    public void loadOlder(ConvModel c) {
        if (c.loadingOlder || !c.hasMore) return;
        c.loadingOlder = true;
        net.send(Packet.of(Protocol.LOAD_OLDER_MESSAGES).put("conversationId", c.id).put("beforeMessageId", c.oldestMessageId()));
    }

    private void onOlderResult(Packet p) {
        ConvModel c = convs.get(p.intVal("conversationId", -1));
        if (c == null) return;
        boolean initial = !c.historyLoaded;
        c.loadingOlder = false;
        c.historyLoaded = true;
        c.hasMore = p.boolVal("hasMore", false);
        List<MsgModel> list = new ArrayList<>();
        for (JsonElement e : p.array("messages")) list.add(MsgModel.fromJson(e.getAsJsonObject()));
        int added = 0;
        for (int i = list.size() - 1; i >= 0; i--) {             // chèn từ tin gần nhất lùi dần vào đầu danh sách
            MsgModel m = list.get(i);
            if (upsert(c, m) == null) {
                c.messages.add(0, m);
                added++;
            }
        }
        c.addedCount = added;
        c.changeKind = initial ? ConvModel.KIND_INITIAL : ConvModel.KIND_PREPEND;
        if (initial) {
            long maxOther = 0;
            for (MsgModel m : c.messages) if (m.senderId != myId) maxOther = Math.max(maxOther, m.id);
            if (maxOther > 0) ackDelivered(c, maxOther);
            markReadIfVisible(c);
        }
        ui.onConvChanged(c.id);
    }

    private void onSyncResult(Packet p) {
        ConvModel c = convs.get(p.intVal("conversationId", -1));
        if (c == null) return;
        if (p.array("memberStates") != null) c.applyStates(p.array("memberStates"));
        long maxOther = 0;
        int added = 0;
        for (JsonElement e : p.array("messages")) {
            MsgModel m = MsgModel.fromJson(e.getAsJsonObject());
            if (upsert(c, m) == null) {
                c.messages.add(m);
                added++;
                if (m.senderId != myId && !"SYSTEM".equals(m.type) && !visible(c)) c.unread++;
            }
            if (m.senderId != myId) maxOther = Math.max(maxOther, m.id);
            c.preview = ("SYSTEM".equals(m.type) || m.senderId != myId ? "" : "Bạn: ") + m.preview();
            c.updatedAt = Math.max(c.updatedAt, m.createdAt);
        }
        c.addedCount = added;
        c.changeKind = ConvModel.KIND_APPEND;
        if (maxOther > 0) ackDelivered(c, maxOther);
        markReadIfVisible(c);
        if (p.boolVal("hasMore", false)) {                       // còn tin: hỏi tiếp từ mốc mới
            JsonObject m = new JsonObject();
            m.addProperty(String.valueOf(c.id), c.lastMessageId());
            net.send(Packet.of(Protocol.SYNC).put("conversations", m));
        }
        ui.onConvChanged(c.id);
        ui.onConvListChanged();
    }

    // ===================================================================== thu hồi / xóa / cảm xúc
    public void recall(MsgModel m) { if (m.id > 0) net.send(Packet.of(Protocol.RECALL_MSG).put("messageId", m.id)); }
    public void deleteForMe(MsgModel m) { if (m.id > 0) net.send(Packet.of(Protocol.DELETE_FOR_ME).put("messageId", m.id)); }
    public void clearHistory(int convId) { net.send(Packet.of(Protocol.CLEAR_HISTORY).put("conversationId", convId)); }
    public void react(MsgModel m, String reaction) {
        if (m.id <= 0) return;
        Packet p = Packet.of(Protocol.REACT).put("messageId", m.id);
        if (reaction != null) p.put("reaction", reaction);
        net.send(p);
    }

    private MsgModel byId(ConvModel c, long id) {
        for (MsgModel m : c.messages) if (m.id == id) return m;
        return null;
    }

    private void onRecalled(Packet p) {
        ConvModel c = convs.get(p.intVal("conversationId", -1));
        if (c == null) return;
        MsgModel m = byId(c, p.longVal("messageId", -1));
        if (m != null) {
            m.recalled = true;
            if (m == c.messages.get(c.messages.size() - 1)) c.preview = m.preview();
        }
        c.changeKind = ConvModel.KIND_REFRESH;
        ui.onConvChanged(c.id);
        ui.onConvListChanged();
    }

    private void onDeletedForMe(Packet p) {
        ConvModel c = convs.get(p.intVal("conversationId", -1));
        if (c == null) return;
        MsgModel m = byId(c, p.longVal("messageId", -1));
        if (m != null) c.messages.remove(m);
        c.changeKind = ConvModel.KIND_REFRESH;
        ui.onConvChanged(c.id);
    }

    private void onHistoryCleared(Packet p) {
        ConvModel c = convs.get(p.intVal("conversationId", -1));
        if (c == null) return;
        long up = p.longVal("upToMessageId", 0);
        c.messages.removeIf(m -> m.id > 0 && m.id <= up);
        c.preview = "";
        c.hasMore = false;
        c.changeKind = ConvModel.KIND_REFRESH;
        ui.onConvChanged(c.id);
        ui.onConvListChanged();
    }

    private void onReaction(Packet p) {
        ConvModel c = convs.get(p.intVal("conversationId", -1));
        if (c == null) return;
        MsgModel m = byId(c, p.longVal("messageId", -1));
        if (m == null) return;
        String r = p.str("reaction");
        if (r == null) m.reactions.remove(p.intVal("userId", -1)); else m.reactions.put(p.intVal("userId", -1), r);
        c.changeKind = ConvModel.KIND_REFRESH;
        ui.onConvChanged(c.id);
    }

    // ===================================================================== đang soạn tin
    public void sendTyping(int convId, boolean typing) {
        ConvModel c = convs.get(convId);
        if (c == null) return;
        ConvModel.Member peer = c.isGroup() ? null : c.peer(myId);
        Packet p = Packet.of(Protocol.TYPING).put("conversationId", convId).put("typing", typing);
        if (peer != null && p2p.send(peer.userId, p)) return;
        net.send(Packet.of(Protocol.TYPING).put("conversationId", convId).put("typing", typing));
    }

    private void onTyping(int convId, int userId, boolean typing) {
        ConvModel c = convs.get(convId);
        if (c == null || userId == myId) return;
        if (typing) c.typing.put(userId, System.currentTimeMillis() + 4000); else c.typing.remove(userId);
        ui.onTyping(convId);
        if (typing) {
            PauseTransition t = new PauseTransition(Duration.millis(4100));
            t.setOnFinished(e -> ui.onTyping(convId));
            t.play();
        }
    }

    // ===================================================================== danh bạ / nhóm (pass-through)
    public void searchUsers(String q) { net.send(Packet.of(Protocol.SEARCH_USER).put("query", q)); }
    public void friendRequest(int userId) { net.send(Packet.of(Protocol.FRIEND_REQUEST).put("targetUserId", userId)); }
    public void friendAccept(int fromUserId) { net.send(Packet.of(Protocol.FRIEND_ACCEPT).put("fromUserId", fromUserId)); }
    public void friendList() { net.send(Packet.of(Protocol.FRIEND_LIST)); }
    public void openPrivate(int userId) { net.send(Packet.of(Protocol.OPEN_PRIVATE).put("targetUserId", userId)); }

    public void createGroup(String name, List<Integer> memberIds) {
        JsonArray arr = new JsonArray();
        for (int id : memberIds) arr.add(id);
        net.send(Packet.of(Protocol.CREATE_GROUP).put("name", name).put("memberIds", arr));
    }

    public void groupAdd(int conv, int user) { net.send(Packet.of(Protocol.GROUP_ADD_MEMBER).put("conversationId", conv).put("userId", user)); }
    public void groupRemove(int conv, int user) { net.send(Packet.of(Protocol.GROUP_REMOVE_MEMBER).put("conversationId", conv).put("userId", user)); }
    public void groupRename(int conv, String name) { net.send(Packet.of(Protocol.GROUP_RENAME).put("conversationId", conv).put("name", name)); }
    public void groupLeave(int conv) { net.send(Packet.of(Protocol.GROUP_LEAVE).put("conversationId", conv)); }

    // ===================================================================== File / Image / Video / Sound (TV3)
    public static String typeOf(File f) {
        String n = f.getName().toLowerCase();
        if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")) return Protocol.MSG_IMAGE;
        if (n.endsWith(".mp4")) return Protocol.MSG_VIDEO;
        if (n.endsWith(".wav")) return Protocol.MSG_SOUND;
        return Protocol.MSG_FILE;
    }

    public void sendMedia(int convId, File f, String msgType, int durationSec) {
        ConvModel c = convs.get(convId);
        if (c == null) return;
        if (f.length() <= 0 || f.length() > Protocol.MAX_FILE_BYTES) {
            ui.onToast("File rỗng hoặc vượt quá " + (Protocol.MAX_FILE_BYTES >> 20) + "MB");
            return;
        }
        MsgModel m = new MsgModel();
        m.clientMsgId = UUID.randomUUID().toString();
        m.conv = convId;
        m.senderId = myId;
        m.senderName = myName;
        m.type = msgType;
        m.content = f.getName();
        m.createdAt = System.currentTimeMillis();
        m.localFile = f;
        m.progress = 0;
        JsonObject att = new JsonObject();
        att.addProperty("fileName", f.getName());
        att.addProperty("fileSize", f.length());
        if (durationSec > 0) att.addProperty("durationSec", durationSec);
        m.attachment = att;
        c.messages.add(m);
        c.addedCount = 1;
        c.changeKind = ConvModel.KIND_APPEND;
        c.preview = "Bạn: " + m.preview();
        Upload up = new Upload(m, f);
        uploads.put(m.clientMsgId, up);
        ui.onConvChanged(convId);
        ui.onConvListChanged();

        Thread t = new Thread(() -> {
            try {
                String md5 = MediaClient.md5(f);                 // streaming 8KB, không nạp cả file vào RAM
                Platform.runLater(() -> {
                    if (up.cancel.get()) return;
                    Packet init = Packet.of(Protocol.FILE_INIT).put("clientMsgId", m.clientMsgId).put("conversationId", convId)
                            .put("msgType", msgType).put("fileName", f.getName()).put("fileSize", f.length()).put("md5", md5);
                    if (durationSec > 0) init.put("durationSec", durationSec);
                    net.send(init);
                });
            } catch (Exception e) {
                Platform.runLater(() -> failUpload(c, m, "Không đọc được file"));
            }
        }, "md5");
        t.setDaemon(true);
        t.start();
    }

    private void onFileReady(Packet p) {
        Upload up = uploads.get(p.str("clientMsgId"));
        if (up == null || up.cancel.get()) return;
        ConvModel c = convs.get(up.msg.conv);
        String transferId = p.str("transferId");
        int port = p.intVal("mediaPort", Protocol.DEFAULT_MEDIA_PORT);
        Thread t = new Thread(() -> {
            try {
                Packet res = MediaClient.upload(net.host(), port, transferId, up.file, v -> Platform.runLater(() -> {
                    up.msg.progress = v;
                    ui.onConvChanged(up.msg.conv);
                }), up.cancel);
                Platform.runLater(() -> {
                    uploads.remove(up.msg.clientMsgId);
                    if (!res.boolVal("ok", false)) failUpload(c, up.msg, "Server từ chối: " + res.str("error"));
                });
            } catch (IOException e) {
                Platform.runLater(() -> {
                    if (!up.cancel.get()) failUpload(c, up.msg, "Gửi file thất bại: " + e.getMessage());
                });
            }
        }, "upload");
        t.setDaemon(true);
        t.start();
    }

    private void failUpload(ConvModel c, MsgModel m, String why) {
        m.failed = true;
        m.progress = -1;
        uploads.remove(m.clientMsgId);
        ui.onToast(why);
        if (c != null) ui.onConvChanged(c.id);
    }

    /** Hủy truyền giữa chừng: FILE_CANCEL qua Kênh 1, đóng Kênh 2, Server xóa file tạm. */
    public void cancelUpload(MsgModel m) {
        Upload up = uploads.remove(m.clientMsgId);
        if (up == null) return;
        up.cancel.set(true);
        net.send(Packet.of(Protocol.FILE_CANCEL).put("clientMsgId", m.clientMsgId));
        ConvModel c = convs.get(m.conv);
        if (c != null) {
            c.messages.remove(m);
            ui.onConvChanged(c.id);
        }
    }

    // ------------------------------------------------------------------ tải về (thumbnail / bản gốc)
    public File cacheFile(MsgModel m, boolean thumb) {
        String base = thumb ? m.attId() + "_thumb.jpg" : m.attId() + "_" + m.fileName().replaceAll("[^A-Za-z0-9._-]", "_");
        return new File(new File(cfg.get("download.dir", "downloads"), "cache"), base);
    }

    /** Lấy file (từ file gốc đã gửi / cache / Server). cb chạy trên luồng UI, nhận null nếu lỗi. */
    public void fetch(MsgModel m, boolean thumb, Consumer<File> cb) {
        if (!thumb && m.localFile != null && m.localFile.exists()) { cb.accept(m.localFile); return; }
        if (m.id <= 0 || m.attId() <= 0) { cb.accept(null); return; }
        File cached = cacheFile(m, thumb);
        if (cached.exists()) { cb.accept(cached); return; }
        String key = m.id + ":" + thumb;
        List<Consumer<File>> waiters = downloadWaiters.get(key);
        if (waiters != null) { waiters.add(cb); return; }
        downloadWaiters.put(key, new ArrayList<>(List.of(cb)));
        net.send(Packet.of(Protocol.FILE_DOWNLOAD_REQ).put("messageId", m.id).put("thumb", thumb));
    }

    private void onDownloadReady(Packet p) {
        long messageId = p.longVal("messageId", -1);
        boolean thumb = p.boolVal("thumb", false);
        String key = messageId + ":" + thumb;
        MsgModel m = null;
        for (ConvModel c : convs.values()) if ((m = byId(c, messageId)) != null) break;
        if (m == null) { downloadWaiters.remove(key); return; }
        MsgModel msg = m;
        File dest = cacheFile(msg, thumb);
        String transferId = p.str("transferId");
        int port = p.intVal("mediaPort", Protocol.DEFAULT_MEDIA_PORT);
        Thread t = new Thread(() -> {
            File ok = null;
            try {
                ok = MediaClient.download(net.host(), port, transferId, dest, v -> {
                    if (!thumb) Platform.runLater(() -> { msg.progress = v >= 1 ? -1 : v; ui.onConvChanged(msg.conv); });
                });
            } catch (IOException e) {
                Platform.runLater(() -> ui.onToast("Tải thất bại: " + e.getMessage()));
            }
            File result = ok;
            Platform.runLater(() -> {
                msg.progress = -1;
                List<Consumer<File>> ws = downloadWaiters.remove(key);
                if (ws != null) ws.forEach(w -> w.accept(result));
                ui.onConvChanged(msg.conv);
            });
        }, "download");
        t.setDaemon(true);
        t.start();
    }
}
