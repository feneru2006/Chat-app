package com.zalo.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Suy ra trạng thái tin nhắn từ mốc của TỪNG thành viên (đặc tả mục 3.3).
 * Không có cột Messages.status: dùng last_delivered_message_id / last_read_message_id.
 */
public final class MessageStateCalc {
    private MessageStateCalc() {}

    public enum State { SENDING, SENT, DELIVERED, READ }

    /** Mốc của một thành viên KHÁC người gửi. */
    public record Member(int userId, long delivered, long read) {}

    /**
     * @param messageId message_id thật của tin (<= 0 nghĩa là chưa được Server cấp => SENDING)
     * @param others    mốc của các thành viên khác người gửi
     */
    public static State compute(long messageId, Collection<Member> others) {
        if (messageId <= 0) return State.SENDING;
        if (others.isEmpty()) return State.SENT;
        boolean allRead = true, allDelivered = true;
        for (Member m : others) {
            if (m.read() < messageId) allRead = false;
            if (Math.max(m.delivered(), m.read()) < messageId) allDelivered = false;
        }
        if (allRead) return State.READ;
        if (allDelivered) return State.DELIVERED;
        return State.SENT;
    }

    /** Danh sách user đã xem tin (để vẽ avatar nhỏ "Đã xem" trong nhóm). */
    public static List<Integer> readers(long messageId, Collection<Member> others) {
        List<Integer> r = new ArrayList<>();
        for (Member m : others) if (m.read() >= messageId) r.add(m.userId());
        return r;
    }
}
