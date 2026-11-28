# Thành viên 3 — Truyền tải Dữ liệu Nhị phân (File, Image, Video, Sound)

**Phụ trách rubric:** File (0.5/2.5), Image (0.25/1.25), Video (0.25/1.25), Sound (0.25/1.25).  
**File chính:** `server/service/MediaService.java`, `server/service/MediaServer.java`, `server/util/Thumbnailer.java`, `client/net/MediaClient.java`, `client/media/AudioRecorder.java`, `client/ui/ChatPane.java` (`imageNode`, `videoNode`, `soundNode`, `fileNode`).

---

## 1. Vai trò trong hệ thống
Thành viên 3 chịu trách nhiệm xây dựng toàn bộ **Kênh 2 (Binary Media Channel)** — đường truyền dữ liệu nhị phân độc lập chạy song song với Kênh 1 (Control/Messaging). Nhờ tách kênh vật lý riêng biệt (`port 9001` tách khỏi `port 9000`), việc tải lên hoặc tải xuống các tệp tin, hình ảnh độ phân giải cao, đoạn phim (`.mp4`) dung lượng lớn (lên tới 150MB) hay tin nhắn thoại (`.wav`) diễn ra mượt mà, tuyệt đối không làm nghẽn băng thông hoặc gây trễ luồng trò chuyện văn bản thời gian thực.

Dữ liệu đa phương tiện được quản lý thống nhất qua bảng `Attachments` liên kết khóa ngoại (`1-1` / `1-N`) với `Messages`:

| Cột dữ liệu (`Attachments`) | Kiểu dữ liệu | Vai trò kỹ thuật |
| :--- | :--- | :--- |
| `attachment_id` | `BIGINT (PK)` | Khóa chính tự tăng định danh tệp đính kèm |
| `message_id` | `BIGINT (FK)` | Liên kết trực tiếp với bản ghi tin nhắn trong bảng `Messages` |
| `file_name` | `VARCHAR(255)` | Tên gốc của tệp tin (đã lọc ký tự đặc biệt khi lưu đĩa) |
| `file_size` | `BIGINT` | Kích thước chính xác tính bằng byte để đối chiếu luồng nhị phân |
| `mime_type` | `VARCHAR(100)` | Loại nội dung (`image/jpeg`, `image/png`, `video/mp4`, `audio/wav`, `application/octet-stream`) |
| `duration_sec` | `INT` | Thời lượng tính bằng giây (phục vụ hiển thị và thanh tua của `SOUND` / `VIDEO`) |
| `file_path` | `VARCHAR(500)` | Đường dẫn tương đối tới tệp gốc lưu trữ trên Server (`storage/<convId>/<uuid>_<name>`) |
| `thumbnail_path` | `VARCHAR(500)` | Đường dẫn tới ảnh thu nhỏ `300x300px` cho `IMAGE` và ảnh bìa khung hình đầu cho `VIDEO` |

---

## 2. Kiến trúc Truyền tải Nhị phân & Các Chức năng Trọng tâm

### 2.1. Giao thức Bắt tay 2 Kênh & Chunking 8KB
Quá trình truyền tải bất kỳ tệp đa phương tiện nào đều đi qua 2 giai đoạn chặt chẽ để đảm bảo xác thực danh tính và chống tràn bộ nhớ:
1. **Xin cấp phép trên Kênh 1 (`FILE_INIT`):** Client gửi metadata gồm `clientMsgId`, `conversationId`, `msgType`, `fileName`, `fileSize`, `md5` (và `durationSec` nếu là ghi âm). `MediaService.fileInit` kiểm tra quyền thành viên, giới hạn dung lượng (`MAX_FILE_BYTES`), và đối chiếu phần mở rộng tệp qua `isValidExtension()` (`IMAGE`: `.jpg/.jpeg/.png`, `VIDEO`: `.mp4`, `SOUND`: `.wav`, `FILE`: mọi định dạng). Nếu hợp lệ, Server sinh mã token dùng một lần `transferId` (UUID) và trả về gói `FILE_READY` kèm cổng `mediaPort` (Kênh 2).
2. **Truyền luồng byte thô trên Kênh 2 (`MediaServer`):** Client mở kết nối TCP phụ tới cổng `9001`, gửi khung JSON `UPLOAD_BEGIN` chứa `transferId`, sau đó dùng `FileInputStream` đọc và ghi liên tiếp từng khối đệm cố định **`byte[8192]` (8KB)**.
   * **Tiết kiệm RAM tuyệt đối:** Dù truyền video 150MB hay 500MB, bộ nhớ cấp phát cho luồng truyền của cả Client và Server luôn giữ nguyên ở mức 8KB, loại bỏ hoàn toàn lỗi `OutOfMemoryError`.
   * **Toàn vẹn dữ liệu (MD5):** Trong lúc ghi tệp tạm `.part`, `MediaServer` đồng thời cập nhật `MessageDigest.getInstance("MD5")`. Khi nhận đủ `fileSize` byte, Server so khớp chuỗi hex MD5 với giá trị Client khai báo; nếu sai lệch, tệp tạm lập tức bị xóa và trả về lỗi `ERR_MD5_MISMATCH`.
   * **Hủy truyền giữa chừng (`FILE_CANCEL`):** Khi người dùng bấm nút **Hủy** trên thanh tiến trình, Client gửi `FILE_CANCEL` qua Kênh 1. `MediaService` đặt cờ `t.cancelled = true` và đóng `activeSocket` trên Kênh 2; `MediaServer` bắt được ngắt luồng và tự động gọi `Files.deleteIfExists(tmp)` để dọn sạch tệp tạm dở dang trên ổ đĩa.

### 2.2. Hình ảnh (`IMAGE`) & Cơ chế Lazy-Loading Thumbnail
* Khi nhận xong tệp ảnh, `MediaServer` gọi `Thumbnailer.create(dest, th)` sử dụng `Java 2D` (`BufferedImage`, `Graphics2D` với nội suy `VALUE_INTERPOLATION_BILINEAR`) để thu phóng tỷ lệ ảnh về kích thước tối đa **`300x300px` (~20KB)**, định dạng `JPEG` và lưu đường dẫn vào `thumbnail_path`.
* Phía Client (`ChatPane.imageNode`), khi hiển thị bong bóng ảnh chỉ yêu cầu `FILE_DOWNLOAD_REQ {messageId, thumb: true}` để tải bản thu nhỏ ~20KB giúp khung chat hiển thị tức thì. Chỉ khi người dùng chủ động click vào ảnh, Client mới tải ảnh gốc độ phân giải cao (`thumb: false`) và mở cửa sổ phóng to.

### 2.3. Đoạn phim (`VIDEO`), Trích xuất Ảnh bìa & Thanh tua Tiến trình
* **Trích xuất khung hình đầu tiên làm ảnh bìa (Hybrid Fallback):**
  * **Phía Server (`Thumbnailer.createFromVideo`):** Tích hợp thư viện **JCodec** (`FrameGrab.getFrameFromFile(videoFile, 0)` + `AWTUtil.toBufferedImage`) để trích xuất khung hình đầu tiên của tệp `.mp4` ngay lúc upload và nén thành ảnh bìa `300x300px` lưu vào `thumbnail_path`.
  * **Phía Client (`ChatPane.loadVideoThumb` & `grabFirstFrameJavaFX`):** Đối với các tệp `.mp4` quay từ thiết bị di động sử dụng bộ mã hóa phức tạp (như H.265/HEVC hoặc H.264 High Profile) mà JCodec thuần Java không giải mã được, Client tự động kích hoạt bộ giải mã phần cứng native của JavaFX (`MediaPlayer` chạy ngầm tắt tiếng qua mốc `120ms` rồi chụp `MediaView.snapshot`) kết hợp bộ nhớ đệm **`VIDEO_THUMBS` (`ConcurrentHashMap`)** để hiển thị ảnh bìa đồng đều trên mọi bong bóng chat.
* **Trình phát Video nâng cao (`ChatPane.videoNode`):** Tích hợp `MediaView`, nút chuyển đổi **Play/Pause (`▶ / ⏸`)**, nhãn thời gian thực (`currentTime / totalDuration`) và thanh kéo tua tiến trình (**`Slider`** gọi `mediaPlayer.seek(...)`). Đồng thời quản lý biến tham chiếu `activeVideoStage` để ngăn chặn mở trùng nhiều cửa sổ phát nếu người dùng click liên tiếp vào video.

### 2.4. Tin nhắn thoại (`SOUND`) — Thu âm & Thanh tua Trực tiếp
* **Thu âm chuẩn hóa (`AudioRecorder`):** Khi người dùng nhấn giữ nút Micro (`🎤`), ứng dụng mở `javax.sound.sampled.TargetDataLine` thu âm trực tiếp từ phần cứng ở định dạng chuẩn **`16kHz, 16-bit, Mono, Signed PCM`** (`AudioFormat(16000, 16, 1, true, false)`). Khi thả tay, luồng byte được đóng gói kèm header chuẩn `RIFF/WAVE` (`.wav`), tính thời lượng giây (`durationSec`) và đẩy qua Kênh 2.
* **Phát lại & Tua tiến trình (`ChatPane.soundNode`):** Sử dụng JavaFX `MediaPlayer` phát trực tiếp tệp `.wav` ngay trên bong bóng chat, kết hợp thanh trượt **`Slider`** đồng bộ với `currentTimeProperty()` cho phép người dùng kéo tới bất kỳ giây nào trong đoạn ghi âm để nghe lại.

---

## 3. Khó khăn Thực tế & Bài học Kinh nghiệm

### 3.1. Tràn bộ nhớ (OOM) và phình dung lượng khi truyền qua JSON
* **Vấn đề:** Ở phương án thử nghiệm ban đầu, việc mã hóa tệp nhị phân sang chuỗi `Base64` và đóng gói vào khung JSON trên Kênh 1 làm dung lượng dữ liệu phình to thêm **33%**, gây nghẽn toàn bộ tin nhắn văn bản và ném ngoại lệ `OutOfMemoryError` khi thử nghiệm video > 100MB.
* **Giải pháp:** Tách riêng Kênh 2 truyền byte thô thuần túy kết hợp đọc/ghi theo khối đệm `8KB`, giữ mức RAM tiêu thụ ổn định và đạt tốc độ truyền tối đa của băng thông mạng.

### 3.2. Lỗi tương thích Codec Video khi trích xuất Thumbnail
* **Vấn đề:** Thư viện JCodec phía Server hoạt động tốt với `.mp4` chuẩn H.264 Baseline nhưng thường trả về khung hình rỗng với các video quay từ điện thoại đời mới. Ngoài ra, cơ chế tái sử dụng ô (`Cell Recycling`) của JavaFX `ListView` khiến việc giải mã lặp lại gây hiện tượng ảnh bìa hiển thị không đồng đều giữa các bong bóng chat.
* **Giải pháp:** Kết hợp trích xuất phía Server bằng JCodec với cơ chế dự phòng `grabFirstFrameJavaFX` (chỉ chụp `snapshot` khi `currentTimeProperty` vượt qua `120ms`) và lưu kết quả vào `ConcurrentHashMap` (`VIDEO_THUMBS`) ở Client để đảm bảo mọi bong bóng video đều hiển thị ảnh bìa tức thì và đồng nhất.

### 3.3. Lỗ hổng Path Traversal khi tải tệp (`DOWNLOAD`)
* **Vấn đề:** Nếu Client gửi đường dẫn chứa ký tự điều hướng thư mục (`../`), Server có thể bị khai thác để đọc trái phép tệp hệ thống ngoài thư mục `storage`.
* **Giải pháp:** Trong `MediaServer.download`, đường dẫn tệp luôn được chuẩn hóa qua `storage.resolve(...).normalize()` và kiểm tra bắt buộc `file.startsWith(storage.normalize())` trước khi mở luồng đọc.

---

## 4. Kịch bản Demo Kiểm thử (File — Image — Video — Sound)

1. **Khởi động hệ thống:** Chạy MySQL (`zalo_network_app`), khởi chạy `ServerMain` (lắng nghe Kênh 1 cổng `9000` và Kênh 2 cổng `9001`), mở 2 cửa sổ Client đăng nhập 2 tài khoản khác nhau.
2. **Demo Image & Thumbnail (Tiêu chí Image):**
   * Gửi một ảnh `.jpg`/`.png` độ phân giải cao (> 2MB).
   * Quan sát khung chat bên nhận hiển thị ngay bản thu nhỏ `300x300px`; mở bảng `Attachments` trong phpMyAdmin xác nhận có đường dẫn `thumbnail_path`; bấm vào ảnh để mở cửa sổ xem ảnh gốc rõ nét.
3. **Demo Ghi âm & Thanh tua Sound (Tiêu chí Sound — TC-06):**
   * Nhấn giữ nút `🎤` trong khoảng 5–8 giây để nói thử rồi thả tay gửi.
   * Bấm nút `▶` trên bong bóng tin nhắn thoại ở cả hai máy, kiểm tra âm thanh rõ tiếng và kéo thanh `Slider` tới giữa đoạn ghi âm để kiểm chứng khả năng tua chính xác.
4. **Demo Video, Ảnh bìa & Chống mở trùng cửa sổ (Tiêu chí Video):**
   * Gửi một tệp `.mp4`; xác nhận bong bóng video tự động hiển thị ảnh bìa trích xuất từ khung hình đầu tiên.
   * Click vào video để mở trình phát, kéo thanh `Slider` tua tới đoạn bất kỳ; giữ nguyên cửa sổ video đang mở và click tiếp vào bong bóng video trong khung chat để chứng minh hệ thống chỉ đưa cửa sổ hiện tại lên trên (`toFront`) chứ không mở thêm cửa sổ trùng lặp.
5. **Demo Truyền File 150MB song song chat & Hủy giữa chừng (Tiêu chí File — TC-05):**
   * Chọn gửi tệp `.mp4` hoặc `.zip` dung lượng ~150MB; **trong lúc thanh tiến trình đang chạy**, liên tục nhắn 5–10 tin văn bản qua lại giữa 2 máy để chứng minh Kênh 2 không làm trễ Kênh 1. Mở Task Manager quan sát RAM của `java.exe` giữ ổn định.
   * Gửi tiếp một tệp lớn khác và bấm nút **Hủy** ở mức ~40%; mở thư mục `storage/tmp` trên Server kiểm tra tệp `.part` đã bị xóa sạch.

---

## 5. Câu hỏi Vấn đáp Trọng tâm

* **Vì sao không dùng Base64 gửi file qua gói JSON trên Kênh 1 mà phải mở thêm Kênh 2?**
  * *Trả lời:* Base64 làm tăng kích thước dữ liệu thêm ~33% và buộc phải nạp toàn bộ chuỗi vào RAM để phân tích JSON (gây tràn bộ nhớ với file lớn), đồng thời làm nghẽn các gói tin chat văn bản đi chung luồng TCP. Kênh 2 truyền byte thô theo từng khối 8KB giải quyết triệt để cả 3 vấn đề trên.
* **Làm sao Server biết kết nối mở tới Kênh 2 là của người dùng nào khi Kênh 2 không gửi lại gói `AUTH`?**
  * *Trả lời:* Khi Client xin gửi/tải trên Kênh 1 (đã xác thực `userId`), `MediaService` cấp một mã token ngẫu nhiên dùng một lần `transferId` (`UUID`) lưu trong `ConcurrentHashMap`. Khi Client mở kết nối sang Kênh 2, gói `UPLOAD_BEGIN`/`DOWNLOAD_BEGIN` phải nộp đúng `transferId` này (`MediaService.take()` lấy ra và xóa ngay khỏi Map để chống dùng lại).
* **Cơ chế nào đảm bảo file nhận được trên Server giống hệt 100% file gốc trên máy người gửi?**
  * *Trả lời:* Client tính mã băm MD5 của file gốc trước khi gửi trong `FILE_INIT`. Trong quá trình nhận từng chunk 8KB, `MediaServer` đồng thời băm dữ liệu bằng `MessageDigest("MD5")`. Nếu nhận đủ số byte nhưng chuỗi MD5 không khớp, Server hủy bỏ và xóa ngay file tạm.
* **Thông số kỹ thuật của luồng ghi âm Voice là gì và tại sao chọn thông số đó?**
  * *Trả lời:* Hệ thống thu âm ở chuẩn `16kHz, 16-bit, Mono` (tương đương tốc độ bit `256 kbps`, ~32KB/giây). Đây là tần số lấy mẫu tiêu chuẩn cho giọng nói con người (dải tần thoại dưới 8kHz theo định lý Nyquist), giúp giọng nói rõ ràng nhưng dung lượng file `.wav` rất nhẹ để truyền tức thì qua mạng.

---

## 6. Giới hạn Đã biết
* Kênh 2 (Binary Media Channel) hiện sử dụng `TCP Socket` thuần để tối ưu thông lượng truyền tải dữ liệu nhị phân dung lượng lớn (chỉ Kênh 1 mã hóa TLS).
* Dữ liệu đa phương tiện trong chat 1-1 hiện đi qua Kênh 2 của Server (để phục vụ lưu trữ lâu dài vào `Attachments` và đồng bộ Offline) thay vì truyền trực tiếp qua cổng P2P ngang hàng.
* Chưa hỗ trợ tính năng tiếp tục truyền file bị gián đoạn giữa chừng (**Resume Upload/Download** từ vị trí byte đã nhận); nếu đứt mạng giữa chừng, người dùng cần gửi lại tệp từ đầu.