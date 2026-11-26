package com.zalo.client.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Một hội thoại (PRIVATE hoặc GROUP) trong bộ nhớ Client. */
public final class ConvModel {
    public static final int KIND_APPEND = 0, KIND_PREPEND = 1, KIND_REFRESH = 2, KIND_INITIAL = 3;

    public static final class Member {
        public int userId;
        public String displayName, role;
        public boolean online;
        public long lastSeen;
    }

    public final int id;
    public String type;                    // PRIVATE | GROUP
    public String groupName;
    public long updatedAt;
    public int unread;
    public String preview = "";
    public final List<Member> members = new ArrayList<>();
    public final ObservableList<MsgModel> messages = FXCollections.observableArrayList();
    /** userId -> {delivered, read}: hai mốc của từng thành viên (đặc tả 3.3). */
    public final Map<Integer, long[]> states = new HashMap<>();
    public final Map<Integer, Long> typing = new HashMap<>();   // userId -> hết hạn (ms)

    public boolean hasMore = true, loadingOlder, historyLoaded;
    public long sentDeliveredUpTo, sentReadUpTo;                 // mốc Client đã báo Server
    public int changeKind = KIND_REFRESH, addedCount;

    public ConvModel(int id) {
        this.id = id;
    }

    public boolean isGroup() { return "GROUP".equals(type); }

    public Member member(int userId) {
        for (Member m : members) if (m.userId == userId) return m;
        return null;
    }

    public Member peer(int myId) {
        for (Member m : members) if (m.userId != myId) return m;
        return null;
    }

    public String title(int myId) {
        if (isGroup()) return groupName == null ? "Nhóm" : groupName;
        Member p = peer(myId);
        return p == null ? "?" : p.displayName;
    }

    public long lastMessageId() {
        for (int i = messages.size() - 1; i >= 0; i--) if (messages.get(i).id > 0) return messages.get(i).id;
        return 0;
    }

    public long oldestMessageId() {
        for (MsgModel m : messages) if (m.id > 0) return m.id;
        return 0;
    }

    /** Cập nhật từ JSON Server (giữ nguyên danh sách tin đã có). */
    public void applyJson(JsonObject o, int myId) {
        type = MsgModel.str(o, "type");
        groupName = MsgModel.str(o, "groupName");
        updatedAt = o.get("updatedAt").getAsLong();
        unread = o.get("unread").getAsInt();
        members.clear();
        for (JsonElement e : o.getAsJsonArray("members")) {
            JsonObject x = e.getAsJsonObject();
            Member m = new Member();
            m.userId = x.get("userId").getAsInt();
            m.displayName = MsgModel.str(x, "displayName");
            m.role = MsgModel.str(x, "role");
            m.online = x.get("online").getAsBoolean();
            m.lastSeen = x.has("lastSeen") ? x.get("lastSeen").getAsLong() : 0;
            members.add(m);
        }
        if (o.has("lastMessage") && o.get("lastMessage").isJsonObject()) {
            MsgModel last = MsgModel.fromJson(o.getAsJsonObject("lastMessage"));
            preview = ("SYSTEM".equals(last.type) || last.senderId != myId ? "" : "Bạn: ") + last.preview();
            updatedAt = Math.max(updatedAt, last.createdAt);
        }
        if (o.has("memberStates")) applyStates(o.getAsJsonArray("memberStates"));
    }

    public void applyStates(Iterable<JsonElement> arr) {
        for (JsonElement e : arr) {
            JsonObject s = e.getAsJsonObject();
            long[] cur = states.computeIfAbsent(s.get("userId").getAsInt(), k -> new long[2]);
            cur[0] = Math.max(cur[0], s.get("delivered").getAsLong());
            cur[1] = Math.max(cur[1], s.get("read").getAsLong());
        }
    }
}
