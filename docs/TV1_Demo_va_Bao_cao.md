# Thành viên 1 — Server Core, TLS, State (Presence): hướng dẫn demo, vấn đáp, báo cáo

**Phụ trách rubric:** State (Online / Offline / Typing), bảo mật TLS, SessionManager.
**File:** `server/ServerMain`, `ServerConsole`, `net/ClientHandler`, `net/SessionManager`, `service/AuthService`,
`util/ServerLog`, `tools/TestClient`, `scripts/gen-cert.sh|.bat`.

## 1. Các thành phần đã làm
| Thành phần | Cách hoạt động |
|---|---|
| Acceptor + Thread Pool | `ServerMain` chỉ `accept()`; mỗi kết nối chạy `ClientHandler` trong `CachedThreadPool`. Bắt tay TLS diễn ra ở luồng của Handler nên 1 Client chậm không chặn việc nhận kết nối mới. Giới hạn `max.connections`. |
| TLS (Kênh 1) | `SSLServerSocket` + chứng chỉ tự ký (`server.jks`); Client tin chứng chỉ qua `client-trust.jks`. Chỉ bật TLS 1.3 / 1.2. Thiếu keystore → in hướng dẫn rõ ràng thay vì lỗi khó hiểu. |
| Socket Binding | Phiên mới là UNAUTHENTICATED, chỉ nhận REGISTER/AUTH, **10s** không AUTH thì ngắt. AUTH đúng mới gán `userId`; `sender_id` luôn lấy từ phiên. |
| Single Session | `SessionManager.register` đá phiên cũ bằng `FORCE_LOGOUT` rồi đóng (TC-04). |
| Heartbeat | Client PING mỗi 20s; Server `setSoTimeout(45s)` — im lặng 45s ném `SocketTimeoutException` → dọn phiên, `is_online=false`, ghi `last_seen`, phát `USER_OFFLINE` (TC-07). Thêm `SO_KEEPALIVE` làm lớp phụ. |
| Presence | `USER_ONLINE/OFFLINE` gửi cho bạn bè + người chung hội thoại (có `lastSeen` → UI hiển thị "Hoạt động 5 phút trước"). `TYPING` định tuyến qua Server cho nhóm. |
| Slow Consumer | Mỗi Handler có `ArrayBlockingQueue(500)` + luồng ghi riêng; đầy hàng đợi thì ngắt Client đó, không ảnh hưởng người khác. |
| Vận hành | `server.log` có giờ; lệnh `list`, `kick <id>`, `stats`, `quit`; tắt server êm (đóng phiên, đặt mọi user Offline); khởi động lại cũng reset Offline. |

## 2. Kịch bản demo (đã có công cụ)
Chạy Server, tạo 2 tài khoản bằng giao diện Client. Công cụ dòng lệnh (không cần JavaFX):
```bash
java -cp server/target/classes:common/target/classes:<gson.jar> com.zalo.server.tools.TestClient \
     localhost 9000 <user> <pass> tls client-trust.jks [--no-ping]
```
- **TC-04 (đá phiên):** mở Client A (giao diện), rồi chạy `TestClient` cùng tài khoản A → Client giao diện nhận `FORCE_LOGOUT` và về màn hình đăng nhập; log: `đăng nhập thiết bị mới -> FORCE_LOGOUT phiên cũ`.
- **TC-07 (Heartbeat):** (a) Client B giao diện đang xem A trong danh sách; chạy `TestClient` tài khoản A, rồi **kill -9 / Task Manager** → trong ≤ 45s B thấy A chuyển Offline. (b) Hoặc chạy `TestClient ... --no-ping` (không gửi PING) → sau đúng 45s log: `im lặng quá 45s (Heartbeat Timeout)`.
- **TC-07 (Wireshark):** bắt trên card mạng, filter `tcp.port == 9000`. Với `tls.enabled=true` chỉ thấy `Client Hello`, `Server Hello`, `Application Data` (không đọc được nội dung). Bật `tls.enabled=false` (cả 2 phía) rồi bắt lại: thấy JSON rõ ràng → làm **ảnh đối chứng** cho báo cáo. Cổng 9001 (Kênh 2) và P2P là TCP thường nên sẽ thấy dữ liệu thô — nên nói trước.
- **Quản trị:** lệnh `list`/`stats` trong cửa sổ Server để cho thầy xem số phiên đang mở.
- **Kiểm tra TLS nhanh:** `TestClient` in `TLS OK: TLSv1.3 / TLS_AES_256_GCM_SHA384`. Client TCP thường nối vào cổng TLS sẽ bị log: `bắt tay TLS lỗi ... Unsupported or unrecognized SSL message`.

## 3. Câu hỏi vấn đáp thường gặp
1. **TLS bắt tay gồm những bước nào?** ClientHello (phiên bản, cipher) → ServerHello + chứng chỉ → Client kiểm tra chứng chỉ (ở đây: có trong truststore) → trao đổi khóa (ECDHE ở TLS 1.3) → hai bên dẫn xuất khóa phiên → dữ liệu mã hóa đối xứng (AES-GCM).
2. **Vì sao chứng chỉ tự ký vẫn dùng được?** Client được cấu hình tin đúng chứng chỉ này (`client-trust.jks`); ngoài đời phải do CA ký. Không tắt kiểm tra chứng chỉ, chỉ thêm vào danh sách tin cậy.
3. **Vì sao TCP không tự biết Client đã chết?** Rút dây/tắt ngang không gửi FIN; Server chỉ biết khi ghi/đọc lỗi hoặc keepalive hệ điều hành (mặc định ~2 giờ). Nên cần Heartbeat tầng ứng dụng (PING 20s, timeout 45s) để dọn "kết nối ma".
4. **Vì sao timeout 45s mà PING 20s?** Cho phép mất tối đa 1 gói PING (20s × 2 + dư) trước khi coi là chết, tránh Offline nhầm khi mạng chập chờn.
5. **Vì sao `sender_id` không lấy từ gói JSON?** Client có thể tự điền giả; Server dùng `userId` đã gắn vào Socket sau AUTH.
6. **Hai máy cùng đăng nhập 1 tài khoản?** Single Session: phiên cũ bị `FORCE_LOGOUT`. Xử lý bằng `ConcurrentHashMap.put` trả về phiên cũ (nguyên tử).
7. **Hạn chế?** Mật khẩu gửi trong gói AUTH (an toàn nhờ TLS, nên không dùng được khi tắt TLS); Kênh 2 và P2P chưa mã hóa; chưa giới hạn tốc độ gói tin sau khi đăng nhập.

## 4. Gợi ý nội dung Báo cáo cá nhân (mục 7.1 đặc tả)
1. **Tổng quan:** vai trò "trạm điều phối" — nhận kết nối, xác thực, giữ bảng định vị `{userId: IP + port P2P}`, giám sát Online/Offline.
2. **Lý thuyết & luồng:** TLS handshake và `SSLServerSocket`; vòng đời một phiên (UNAUTHENTICATED → AUTH ≤ 10s → ACTIVE → cleanup); Single Session; Heartbeat 20s/45s; Slow Consumer; luồng dọn dẹp khi ngắt (`unregister` → `setOffline` → `USER_OFFLINE`).
3. **Khó khăn & bài học:** "kết nối ma" khi tắt ngang bằng Task Manager → cần Keep-Alive tầng ứng dụng; chống race khi 2 thiết bị đăng nhập cùng lúc (`unregister(userId, handler)` chỉ gỡ khi đúng phiên); lỗi TLS khó đoán khi Client dùng TCP thường → bổ sung log chẩn đoán; chứng chỉ phải khớp IP/hostname nếu bật kiểm tra hostname.
4. **Minh chứng:** ảnh Wireshark (có/không TLS), trích `server.log` các sự kiện FORCE_LOGOUT / Heartbeat Timeout, ảnh lệnh `list`.
