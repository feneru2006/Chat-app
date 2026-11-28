# Zalo (đồ án Lập trình Ứng dụng Mạng) — Khung dự án Java

Bám theo `docs/Dac_Ta_Ky_Thuat_Do_An_Lap_Trinh_Mang_Zalo_v4.1.docx`.
Maven đa module: `common` (Framing, Packet, Protocol) · `server` · `client` (JavaFX).

## 1. Chạy thử
Cách nhanh sau khi đã có CSDL + chứng chỉ (bước 1-3 bên dưới): `bash scripts/run-server.sh` / `scripts\run-server.bat` và `scripts/run-client.sh` / `scripts\run-client.bat` (mỗi script tự build rồi chạy).

Yêu cầu: JDK 17+ (JDK 21 càng tốt), Maven, MySQL 8 / MariaDB.

```bash
# 1) CSDL
mysql -u root -p < db/schema.sql

# 2) Chứng chỉ TLS: TỰ DÒ IP máy hiện tại, chạy lại được. Server dùng server.jks, mọi Client dùng client-trust.jks
bash scripts/gen-cert.sh          # Windows PowerShell: .\scripts\gen-cert.bat
#    Tường lửa Windows (chạy 1 lần, chuột phải -> Run as administrator): scripts\open-firewall.bat

# 3) Sửa db.user / db.password trong server.properties, rồi:
mvn -q install -DskipTests
mvn -q -pl server exec:java -Dexec.args="server.properties"

# 4) Client (mở nhiều terminal để chạy nhiều Client). Đặt client.properties + client-trust.jks cạnh thư mục chạy
mvn -q -pl client javafx:run
```
Debug nhanh không cần TLS: `tls.enabled=false` ở cả `server.properties` và `client.properties`
(nhớ bật lại khi demo và chụp Wireshark — TC-07).

Chạy trên nhiều máy LAN: đặt `server.host=<IP máy Server>` trong `client.properties` (hoặc nhập ở màn hình đăng nhập),
mở tường lửa cổng 9000, 9001 (Server) — các cổng P2P do hệ điều hành cấp ngẫu nhiên nên Client cũng cần cho phép kết nối đến.

Kiểm tra nhanh lõi giao thức (không cần DB/mạng):
`java -cp common/target/classes:<gson.jar> com.zalo.common.tools.FramingSelfTest` → in `ALL PASS`.

## 2. Cấu trúc & phân công
| Thành viên | Rubric | File chính |
|---|---|---|
| **TV1** Server core, TLS, State người dùng | State (Online/Offline/Typing) | `server/ServerMain`, `net/ClientHandler`, `net/SessionManager`, `service/AuthService` |
| **TV2** P2P, Group, State tin nhắn | P2P, Group, State | `client/net/P2PService`, `ClientCore` (mục P2P, State), `service/P2pSignalingService`, `ConversationService`, `MessageService.delivered/read` |
| **TV3** Binary: File/Image/Video/Sound | File, Image, Video, Sound | `service/MediaService`, `MediaServer`, `util/Thumbnailer`, `client/net/MediaClient`, `media/AudioRecorder`, `ChatPane` (imageNode/videoNode/soundNode/fileNode) |
| **TV4** DB, History, Emoji, Time | History, Emoji, Time | `db/*Dao`, `db/schema.sql`, `MessageService.sync/older`, `UiUtil.time` |
| **TV5** Client mạng + Sender + Test | Sender, Framing, Outbox | `common/Framing`, `client/net/NetworkService`, `ChatPane` (Sender), `LoginView`, `MainView` |

## 2b. Thành viên 1 (đã hoàn thiện)
Xem `docs/TV1_Demo_va_Bao_cao.md`: log file, lệnh quản trị (`list`/`kick`/`stats`/`quit`), giới hạn kết nối, tắt server êm, `TestClient` để demo TC-04/TC-07, `gen-cert.bat` cho Windows.

#### 2d. Thành viên 3 (đã hoàn thiện)
Xem `docs/TV3_Demo_va_Bao_cao.md`: kiểm tra định dạng tệp hợp lệ theo loại (`isValidExtension`), tự động trích xuất khung hình đầu tiên làm ảnh bìa Video (kết hợp JCodec phía Server và JavaFX snapshot + bộ nhớ đệm `VIDEO_THUMBS` phía Client), thêm thanh kéo tua tiến trình (`Slider`) cho cả Video và Tin nhắn thoại (`SOUND`), và khóa chống mở trùng nhiều cửa sổ phát Video (`activeVideoStage`).

## 3. Đã làm sẵn (khung chạy được theo đặc tả)
- Packet Framing 4-byte + readFully; Server **đã biên dịch & tự kiểm tra** (TC-02 mô phỏng cắt/dính gói: PASS).
- TLS Kênh 1, AUTH ≤ 10s, **sender_id lấy từ phiên Socket**, Single Session (FORCE_LOGOUT), Heartbeat 20s/45s.
- Hàng đợi gửi riêng `ArrayBlockingQueue(500)` + luồng ghi riêng mỗi Client (chống Slow Consumer).
- Đăng ký/đăng nhập (PBKDF2), tìm người, kết bạn (PENDING→ACCEPTED, 1 Transaction tạo hội thoại), nhắn người lạ.
- Chat 1-1 & nhóm: Idempotency `(conversation_id, client_msg_id)`, Time = đồng hồ Server, quản trị nhóm (chỉ ADMIN), tin SYSTEM.
- **State theo từng người**: `last_delivered_message_id` / `last_read_message_id` (không có `Messages.status`), gói `ACK_DELIVERED`, `MARK_AS_READ`, `MSG_STATUS`; Client suy ra SENDING/SENT/DELIVERED/READ bằng `common/MessageStateCalc`.
- History: SYNC (≤50 tin/hội thoại, hasMore), LOAD_OLDER_MESSAGES (30 tin, keyset), Thu hồi, Xóa phía tôi, Xóa lịch sử, Reaction.
- P2P: ServerSocket cổng tự do, P2P_LOOKUP, ID nhỏ gọi ID lớn, fallback Server, giữ P2P khi Server tắt; **đã smoke-test 2 Client nối nhau, 100/100 gói đúng**.
- File/Image/Video/Sound: FILE_INIT → Kênh 2 chunk 8KB, MD5, FILE_CANCEL, thumbnail 300x300, ghi âm 16kHz/16-bit/mono, tải về có cache.
- Client: Outbox + Auto-Reconnect 3s→5s→10s + resync, "đang soạn" debounce 500ms, avatar + tên màu theo user_id, gom tin 2 phút.

## 4. Chưa kiểm chứng / còn làm (ưu tiên từ trên xuống)
1. **Chưa chạy thật với MySQL và chưa biên dịch phần giao diện JavaFX** (máy dựng khung không có DB/JavaFX). Phần `common`, `server`, lõi mạng Client biên dịch sạch; UI có thể còn lỗi vặt khi bạn build lần đầu — gửi log lỗi lại mình sửa.
2. Chạy lần lượt 7 test case TC-01…TC-07 trên 3–4 máy LAN, chụp Wireshark.
3. **Video**: đang dùng nút ▶ thay ảnh bìa. Nếu muốn đủ đặc tả: trích khung hình đầu bằng JavaCV `FFmpegFrameGrabber` ở `MediaServer.upload` (có TODO).
4. File/ảnh/voice trong chat 1-1 hiện đi qua **Kênh 2 (Server)**, chỉ TEXT/Emoji/Typing đi P2P. Nếu thầy yêu cầu media qua P2P: thêm gói chunk trên Kênh 3.
5. Chưa lưu lịch sử cục bộ trên Client (mở lại app sẽ tải 30 tin mới nhất từ Server) — vẫn đúng yêu cầu History vì Server lưu đầy đủ.
6. Chưa có upload avatar (avatar là chữ cái đầu, tô màu theo user_id).

## 5. Giới hạn nên nêu khi vấn đáp
- P2P chỉ hoạt động trong cùng LAN (không NAT traversal); Kênh 3 và Kênh 2 là TCP thường, chỉ Kênh 1 có TLS.
- JavaFX trên Windows hiển thị Emoji đơn sắc (dữ liệu vẫn nguyên vẹn 4-byte utf8mb4).
