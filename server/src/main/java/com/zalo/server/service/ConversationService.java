package com.zalo.server.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zalo.common.Packet;
import com.zalo.common.Protocol;
import com.zalo.server.db.ConversationDao;
import com.zalo.server.db.ConversationDao.Conv;
import com.zalo.server.db.ConversationDao.MemberInfo;
import com.zalo.server.db.MessageDao;
import com.zalo.server.db.UserDao;
import com.zalo.server.net.ClientHandler;
import com.zalo.server.net.SessionManager;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Danh sách hội thoại, chat 1-1 (PRIVATE), quản trị nhóm (chỉ ADMIN thêm/xóa/đổi tên). */
public final class ConversationService {
    private ConversationService() {}

    /** JSON một hội thoại dành riêng cho người xem `viewer` (có unread, lastMessage, danh sách thành viên). */
    public static JsonObject toJson(int convId, int viewer) throws SQLException {
        Conv c = ConversationDao.find(convId);
        JsonObject o = new JsonObject();
        o.addProperty("conversationId", c.id());
        o.addProperty("type", c.type());
        o.addProperty("updatedAt", c.updatedAt());
        o.addProperty("unread", ConversationDao.unread(convId, viewer));
        JsonArray ms = new JsonArray();
        for (MemberInfo m : ConversationDao.members(convId)) {
            JsonObject x = new JsonObject();
            x.addProperty("userId", m.userId());
            x.addProperty("displayName", m.displayName());
            x.addProperty("avatarUrl", m.avatarUrl());
            x.addProperty("role", m.role());
            x.addProperty("online", SessionManager.isOnline(m.userId()));
            x.addProperty("lastSeen", m.lastSeen());
            ms.add(x);
        }
        o.add("members", ms);
        if ("GROUP".equals(c.type())) {
            o.addProperty("groupName", c.groupName());
        }
        MessageDao.Msg last = MessageDao.latestVisible(viewer, convId);
        if (last != null) o.add("lastMessage", MessageService.toJson(last, new HashMap<>()));
        o.add("memberStates", MessageService.memberStates(convId));
        return o;
    }

    public static void list(ClientHandler h) throws SQLException {
        JsonArray arr = new JsonArray();
        for (int id : ConversationDao.conversationIdsOf(h.userId())) arr.add(toJson(id, h.userId()));
        h.send(Packet.of(Protocol.CONVERSATIONS).put("conversations", arr));
    }

    /** OPEN_PRIVATE {targetUserId}: mở/tạo hội thoại 1-1. Người lạ: kiểm tra allow_stranger_msg. */
    public static void openPrivate(ClientHandler h, Packet p) throws SQLException {
        int me = h.userId();
        int target = p.intVal("targetUserId", -1);
        UserDao.User t = target == me ? null : UserDao.findById(target);
        if (t == null) {
            h.sendError(Protocol.ERR_NOT_FOUND, "Không tìm thấy người dùng", null);
            return;
        }
        Integer id = ConversationDao.findPrivate(me, target);
        boolean created = false;
        if (id == null) {
            var fr = com.zalo.server.db.FriendDao.find(me, target);
            boolean friend = fr != null && "ACCEPTED".equals(fr.status());
            if (!friend && !t.allowStranger()) {
                h.sendError(Protocol.ERR_STRANGER_BLOCKED, "Người này không nhận tin nhắn từ người lạ", null);
                return;
            }
            id = ConversationDao.createPrivate(me, target, System.currentTimeMillis());
            created = true;
        }
        JsonObject conv = toJson(id, me);
        h.send(Packet.of(Protocol.CONVERSATION_ADDED).put("conversation", conv).put("opened", true));
        if (created) SessionManager.sendTo(target, Packet.of(Protocol.CONVERSATION_ADDED).put("conversation", toJson(id, target)));
    }

    /** CREATE_GROUP {name, memberIds:[...]} */
    public static void createGroup(ClientHandler h, Packet p) throws SQLException {
        String name = p.str("name") == null ? "" : p.str("name").trim();
        JsonArray arr = p.array("memberIds");
        Set<Integer> ids = new LinkedHashSet<>();
        if (arr != null) for (JsonElement e : arr) ids.add(e.getAsInt());
        ids.remove(h.userId());
        if (name.isEmpty() || name.length() > 120 || ids.isEmpty()) {
            h.sendError(Protocol.ERR_BAD_REQUEST, "Nhóm cần tên và ít nhất 1 thành viên khác", null);
            return;
        }
        for (int id : ids) {
            if (UserDao.findById(id) == null) {
                h.sendError(Protocol.ERR_NOT_FOUND, "Thành viên " + id + " không tồn tại", null);
                return;
            }
        }
        int conv = ConversationDao.createGroup(name, h.userId(), ids, System.currentTimeMillis());
        MessageService.postSystem(conv, h.userId(), displayOf(h.userId()) + " đã tạo nhóm \"" + name + "\"");
        pushToMembers(conv, Protocol.CONVERSATION_ADDED);
    }

    public static void addMember(ClientHandler h, Packet p) throws SQLException {
        int conv = p.intVal("conversationId", -1);
        int target = p.intVal("userId", -1);
        if (!requireAdmin(h, conv)) return;
        if (UserDao.findById(target) == null) {
            h.sendError(Protocol.ERR_NOT_FOUND, "Người dùng không tồn tại", null);
            return;
        }
        if (ConversationDao.isMember(conv, target)) return;
        ConversationDao.addMember(conv, target);
        MessageService.postSystem(conv, h.userId(), displayOf(h.userId()) + " đã thêm " + displayOf(target) + " vào nhóm");
        SessionManager.sendTo(target, Packet.of(Protocol.CONVERSATION_ADDED).put("conversation", toJson(conv, target)));
        pushToMembers(conv, Protocol.CONVERSATION_UPDATED);
    }

    public static void removeMember(ClientHandler h, Packet p) throws SQLException {
        int conv = p.intVal("conversationId", -1);
        int target = p.intVal("userId", -1);
        if (!requireAdmin(h, conv)) return;
        if (target == h.userId() || !ConversationDao.isMember(conv, target)) return;
        ConversationDao.removeMember(conv, target);
        MessageService.postSystem(conv, h.userId(), displayOf(h.userId()) + " đã xóa " + displayOf(target) + " khỏi nhóm");
        SessionManager.sendTo(target, Packet.of(Protocol.CONVERSATION_REMOVED).put("conversationId", conv));
        pushToMembers(conv, Protocol.CONVERSATION_UPDATED);
    }

    public static void rename(ClientHandler h, Packet p) throws SQLException {
        int conv = p.intVal("conversationId", -1);
        String name = p.str("name") == null ? "" : p.str("name").trim();
        if (!requireAdmin(h, conv)) return;
        if (name.isEmpty() || name.length() > 120) {
            h.sendError(Protocol.ERR_BAD_REQUEST, "Tên nhóm không hợp lệ", null);
            return;
        }
        ConversationDao.rename(conv, name);
        MessageService.postSystem(conv, h.userId(), displayOf(h.userId()) + " đã đổi tên nhóm thành \"" + name + "\"");
        pushToMembers(conv, Protocol.CONVERSATION_UPDATED);
    }

    /** Rời nhóm. Nếu là ADMIN cuối cùng thì tự chuyển ADMIN cho thành viên khác; nhóm trống thì xóa. */
    public static void leave(ClientHandler h, Packet p) throws SQLException {
        int conv = p.intVal("conversationId", -1);
        int me = h.userId();
        Conv c = ConversationDao.find(conv);
        String role = ConversationDao.role(conv, me);
        if (c == null || !"GROUP".equals(c.type()) || role == null) {
            h.sendError(Protocol.ERR_BAD_REQUEST, "Không thể rời hội thoại này", null);
            return;
        }
        String myName = displayOf(me);
        if ("ADMIN".equals(role) && ConversationDao.countAdmins(conv) == 1) {
            Integer heir = ConversationDao.oldestMemberExcluding(conv, me);
            if (heir == null) {                                         // chỉ còn mình tôi: xóa nhóm
                ConversationDao.delete(conv);
                h.send(Packet.of(Protocol.CONVERSATION_REMOVED).put("conversationId", conv));
                return;
            }
            ConversationDao.setRole(conv, heir, "ADMIN");
        }
        ConversationDao.removeMember(conv, me);
        MessageService.postSystem(conv, me, myName + " đã rời nhóm");
        h.send(Packet.of(Protocol.CONVERSATION_REMOVED).put("conversationId", conv));
        pushToMembers(conv, Protocol.CONVERSATION_UPDATED);
    }

    // ------------------------------------------------------------------ helper
    private static boolean requireAdmin(ClientHandler h, int conv) throws SQLException {
        Conv c = ConversationDao.find(conv);
        if (c == null || !"GROUP".equals(c.type())) {
            h.sendError(Protocol.ERR_NOT_FOUND, "Không tìm thấy nhóm", null);
            return false;
        }
        if (!"ADMIN".equals(ConversationDao.role(conv, h.userId()))) {
            h.sendError(Protocol.ERR_NOT_ADMIN, "Chỉ ADMIN mới có quyền này", null);
            return false;
        }
        return true;
    }

    private static void pushToMembers(int conv, String type) throws SQLException {
        List<Integer> ids = ConversationDao.memberIds(conv);
        for (int uid : ids) SessionManager.sendTo(uid, Packet.of(type).put("conversation", toJson(conv, uid)));
    }

    private static String displayOf(int userId) throws SQLException {
        UserDao.User u = UserDao.findById(userId);
        return u == null ? "?" : u.displayName();
    }
}
