package com.zalo.client.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/** Một tin nhắn trong bộ nhớ Client. id <= 0 nghĩa là chưa được Server cấp message_id (trạng thái SENDING). */
public final class MsgModel {
    public long id;
    public String clientMsgId;
    public int conv, senderId;
    public String senderName = "";
    public String type = "TEXT";
    public String content;
    public boolean recalled;
    public long createdAt;                 // Server Unix ms (nguồn chân lý); tin chờ ACK tạm dùng giờ máy
    public JsonObject attachment;          // {attachmentId,fileName,fileSize,mimeType,durationSec,hasThumb}
    public final Map<Integer, String> reactions = new LinkedHashMap<>();

    // --- chỉ có ở phía Client
    public boolean viaP2P;                 // gửi/nhận qua kênh P2P (hiển thị nhãn "P2P" / "Server")
    public boolean p2pDelivered;           // đối phương đã P2P_ACK
    public boolean failed;                 // upload lỗi / bị hủy
    public File localFile;                 // file gốc của tin do mình gửi
    public double progress = -1;           // 0..1 khi đang upload/download; -1 = không có

    public boolean isMine(int myId) { return senderId == myId; }
    public boolean isMedia() { return !"TEXT".equals(type) && !"SYSTEM".equals(type); }

    // ------------------------------------------------------------------ attachment
    public long attId() { return attachment == null ? -1 : attachment.get("attachmentId").getAsLong(); }
    public String fileName() { return attachment == null ? "" : str(attachment, "fileName"); }
    public long fileSize() { return attachment == null ? 0 : attachment.get("fileSize").getAsLong(); }
    public int durationSec() { return attachment != null && attachment.has("durationSec") ? attachment.get("durationSec").getAsInt() : 0; }
    public boolean hasThumb() { return attachment != null && attachment.has("hasThumb") && attachment.get("hasThumb").getAsBoolean(); }

    // ------------------------------------------------------------------ JSON
    static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    public static MsgModel fromJson(JsonObject o) {
        MsgModel m = new MsgModel();
        m.id = o.get("messageId").getAsLong();
        m.clientMsgId = str(o, "clientMsgId");
        m.conv = o.get("conversationId").getAsInt();
        m.senderId = o.get("senderId").getAsInt();
        String sn = str(o, "senderName");
        m.senderName = sn == null ? "" : sn;
        m.type = str(o, "msgType");
        m.content = str(o, "content");
        m.recalled = o.has("recalled") && o.get("recalled").getAsBoolean();
        m.createdAt = o.get("createdAt").getAsLong();
        if (o.has("attachment") && o.get("attachment").isJsonObject()) m.attachment = o.getAsJsonObject("attachment");
        if (o.has("reactions") && o.get("reactions").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("reactions")) {
                JsonObject r = e.getAsJsonObject();
                m.reactions.put(r.get("userId").getAsInt(), r.get("reaction").getAsString());
            }
        }
        return m;
    }

    /** Gộp thông tin từ bản của Server vào bản local (cùng clientMsgId). */
    public void mergeFromServer(MsgModel s) {
        if (s.id > 0) id = s.id;
        if (s.createdAt > 0) createdAt = s.createdAt;          // Time: lấy giờ Server
        if (s.attachment != null) attachment = s.attachment;
        recalled = recalled || s.recalled;
        if (!s.reactions.isEmpty()) { reactions.clear(); reactions.putAll(s.reactions); }
        if (s.senderName != null && !s.senderName.isEmpty()) senderName = s.senderName;
    }

    public String preview() {
        if (recalled) return "Tin nhắn đã bị thu hồi";
        return switch (type) {
            case "IMAGE" -> "[Hình ảnh]";
            case "VIDEO" -> "[Video]";
            case "SOUND" -> "[Tin nhắn thoại]";
            case "FILE" -> "[Tệp] " + fileName();
            default -> content == null ? "" : content;
        };
    }
}
