package com.zalo.common.tools;

import com.zalo.common.Framing;
import com.zalo.common.MessageStateCalc;
import com.zalo.common.MessageStateCalc.Member;
import com.zalo.common.MessageStateCalc.State;
import com.zalo.common.Packet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Tự kiểm tra nhanh (không cần JUnit/Mạng):
 *   TC-02: 100 gói liên tiếp, bên nhận chỉ nhận 1-3 byte mỗi lần read() (mô phỏng cắt/dính gói TCP).
 *   Logic suy ra trạng thái tin nhắn theo từng người.
 * Chạy: java -cp common/target/classes:gson.jar com.zalo.common.tools.FramingSelfTest
 */
public final class FramingSelfTest {
    public static void main(String[] args) throws Exception {
        testFraming();
        testState();
        System.out.println("ALL PASS");
    }

    private static void testFraming() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        for (int i = 0; i < 100; i++) {
            Framing.writePacket(out, Packet.of("SEND_MSG").put("n", i).put("content", "Chào nhóm! 🚀 ' \" \\n " + i));
        }
        InputStream slow = new InputStream() {
            private final ByteArrayInputStream src = new ByteArrayInputStream(bos.toByteArray());
            private int step = 0;
            @Override public int read() { return src.read(); }
            @Override public int read(byte[] b, int off, int len) {
                step = step % 3 + 1;                 // luôn trả về tối đa 1..3 byte
                return src.read(b, off, Math.min(len, step));
            }
        };
        DataInputStream in = new DataInputStream(slow);
        for (int i = 0; i < 100; i++) {
            Packet p = Framing.readPacket(in);
            check(p.intVal("n", -1) == i, "thứ tự gói " + i);
            check(p.str("content").contains("🚀"), "emoji gói " + i);
        }
        System.out.println("TC-02 framing: PASS (100/100 gói, đúng thứ tự, emoji nguyên vẹn)");
    }

    private static void testState() {
        List<Member> oneToOne = List.of(new Member(2, 10, 0));
        check(MessageStateCalc.compute(0, oneToOne) == State.SENDING, "SENDING");
        check(MessageStateCalc.compute(10, oneToOne) == State.DELIVERED, "1-1 delivered");
        check(MessageStateCalc.compute(11, oneToOne) == State.SENT, "1-1 sent");
        check(MessageStateCalc.compute(10, List.of(new Member(2, 10, 10))) == State.READ, "1-1 read");
        List<Member> group = List.of(new Member(2, 20, 20), new Member(3, 20, 5), new Member(4, 0, 0));
        check(MessageStateCalc.compute(20, group) == State.SENT, "group chưa đủ người nhận");
        List<Member> group2 = List.of(new Member(2, 20, 20), new Member(3, 20, 5));
        check(MessageStateCalc.compute(20, group2) == State.DELIVERED, "group đủ người nhận");
        check(MessageStateCalc.readers(20, group2).equals(List.of(2)), "readers");
        System.out.println("State calc: PASS");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAIL: " + what);
    }
}
