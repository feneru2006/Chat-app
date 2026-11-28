# Thành viên 4 — Cơ sở dữ liệu, History, Emoji, Time

**Phụ trách rubric:** History (0.5/2.5), Emoji (0.5/2.5), Time (0.5/2.5).

**File chính:** `db/schema.sql`, `server/db/Db.java`, `server/db/MessageDao.java`,
`server/db/ConversationDao.java`, `server/service/MessageService.java`,
`client/core/ClientCore.java`, `client/core/MsgModel.java`, `client/ui/UiUtil.java`,
`client/ui/EmojiPicker.java`.

## 1. Vai trò trong hệ thống

Thành viên 4 giữ vai trò “trí nhớ dài hạn” và chuẩn hóa dữ liệu xuyên suốt P2P lẫn
nhóm. Server là điểm ghi duy nhất cho History: mọi tin TEXT/Emoji đều được lưu bằng
`MessageDao.insert`, P2P chỉ là đường truyền thời gian thực; bản sao của tin P2P cũng
được gửi về Server để lưu lại và đồng bộ Offline.

Dữ liệu quan trọng được phân chia theo đúng mô hình:

| Nhóm dữ liệu | Bảng | Vai trò |
|---|---|---|
| Người dùng | `Users`, `Friendships` | Tài khoản, bạn bè |
| Hội thoại hợp nhất | `Conversations` | Cùng lưu P2P và nhóm, theo `type=PRIVATE/GROUP` |
| Thành viên | `ConversationMembers` | Vai trò, lịch sử đã xóa, `last_delivered_message_id`, `last_read_message_id` |
| Tin nhắn | `Messages` | Nội dung, người gửi, thời gian Server, loại tin |
| Tệp đính kèm | `Attachments` | Thông tin file/media của một tin |
| Xóa riêng | `MessageDeletions` | Xóa phía tôi theo từng `(message_id,user_id)` |
| Cảm xúc | `MessageReactions` | Cảm xúc theo từng `(message_id,user_id)` |

Schema hiện có đủ 8 bảng và dùng `InnoDB`, UTF-8 4-byte cho toàn bộ dữ liệu văn bản.

## 2. CSDL, History và phân trang

### 2.1 Hội thoại và tin nhắn

`Conversations` ghi `last_message_id` và `updated_at` để phục vụ danh sách hội thoại,
`Messages` lưu `client_msg_id` để chống gửi trùng. Hai trường này tạo khóa duy nhất:

```sql
UNIQUE KEY uq_conv_client_msg (conversation_id, client_msg_id)
```

`MessageDao.insert` dùng `PreparedStatement` và tham số `?`; dữ liệu không được ghép
trực tiếp vào câu SQL. Nhờ đó nội dung có dấu nháy đơn, dấu nháy đôi hoặc ký tự
đặc biệt không làm vỡ cú pháp SQL.

### 2.2 Đồng bộ khi Offline

Khi Client còn đang chạy (hoặc mất mạng tạm thời), Client gửi Server bản đồ mốc
đang giữ trong bộ nhớ:

```json
{"conversations": {"5": 1040, "7": 0}}
```

Trong đó mỗi giá trị là `message_id` lớn nhất mà Client đang giữ của hội thoại.
Mốc này chưa được lưu bền vững xuống máy Client. Khi kết nối lại mà ứng dụng vẫn
còn chạy, Client gọi `SYNC`; Server gọi `MessageDao.newer`, lấy tối đa 50 tin mới
hơn mốc theo thứ tự `message_id ASC`,
đồng thời loại bỏ:

- tin nằm trước `cleared_before_msg_id`;
- tin đã bị ghi trong `MessageDeletions`;
- tin thuộc hội thoại mà người dùng không là thành viên.

Nếu còn tin, `hasMore=true` và Client gửi tiếp `SYNC` bằng mốc `c.lastMessageId()`.
Đây là cơ chế đồng bộ tiếp diễn sau reconnect, không truy vấn toàn bộ lịch sử.
Nếu đóng hẳn rồi chạy lại ứng dụng, Client không có cursor cũ để `SYNC`: khi người
dùng mở hội thoại, Client tải 30 tin mới nhất bằng `LOAD_OLDER_MESSAGES`, sau đó
có thể cuộn lên để lấy các trang cũ. Server vẫn lưu lịch sử đầy đủ.

### 2.3 LOAD_OLDER_MESSAGES

Khi cuộn lên, Client gửi `beforeMessageId`. Server dùng keyset pagination:

```sql
SELECT m.*
FROM Messages m
WHERE m.conversation_id=? AND m.message_id<?
ORDER BY m.message_id DESC
LIMIT ?
```

Mỗi trang lấy 30 tin hiện có. Cách này không dùng `OFFSET`, nên không bị dịch trang
khi dữ liệu mới được chèn giữa hai lần tải. Dữ liệu trả về được đảo lại để Client
hiển thị từ tin cũ đến tin mới trong trang.

### 2.4 Trạng thái tin và tin chưa đọc

`ConversationMembers.last_delivered_message_id` và
`last_read_message_id` được lưu theo từng thành viên, không tạo cột `status` trong
từng tin. Server cập nhật tăng dần bằng `GREATEST`, nhờ đó gói tin cũ không làm mốc
bị lùi.

Số tin chưa đọc được tính bằng:

```sql
SELECT COUNT(*)
FROM Messages m
JOIN ConversationMembers cm
  ON cm.conversation_id=m.conversation_id AND cm.user_id=?
WHERE m.conversation_id=?
  AND m.message_id>cm.last_read_message_id
  AND m.message_id>cm.cleared_before_msg_id
  AND m.sender_id<>?
  AND m.msg_type<>'SYSTEM'
  AND NOT EXISTS (
    SELECT 1 FROM MessageDeletions d
    WHERE d.message_id=m.message_id AND d.user_id=cm.user_id
  )
```

Hai điều kiện cuối giúp badge unread không tính tin hệ thống và tin người dùng đã
xóa riêng phía mình.

## 3. Emoji và chữ Unicode

`db/schema.sql` tạo database và bảng bằng:

```sql
CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
```

`Db.java` cũng mở kết nối JDBC với `characterEncoding=UTF-8`,
`connectionCollation=utf8mb4_unicode_ci`. `utf8mb4` chấp nhận emoji 4-byte như
`😀`, `🚀`, `❤️`; bộ `utf8` 3-byte cũ có thể đổi chúng thành dấu `????`.

Luồng dữ liệu Emoji:

1. `EmojiPicker` trả chuỗi Unicode khi người dùng chọn.
2. `Packet` / Gson / `DataOutputStream` truyền chuỗi UTF-8 qua TCP.
3. `MessageService` nhận chuỗi, `MessageDao.insert` ghi bằng `PreparedStatement`.
4. MySQL lưu nguyên chuỗi trong `Messages.content` kiểu `TEXT`.
5. History trả lại chuỗi qua Gson và `MsgModel` hiển thị nguyên vẹn trên JavaFX.

Từ khóa Reaction của ứng dụng (`LIKE`, `HEART`, `HAHA`, `SAD`, `ANGRY`) được map sang
emoji khi hiển thị, còn nội dung TEXT/Emoji vẫn là chuỗi Unicode thông thường.

## 4. Đồng bộ thời gian

Tin gửi cục bộ ban đầu dùng giờ máy để tạo bản Optimistic UI, nhưng sau ACK nó phải
được thay bằng mốc đồng hồ Server:

```java
long now = System.currentTimeMillis(); // đồng hồ Server
```

Giá trị này được lưu trong `Messages.created_at` kiểu `BIGINT` theo Unix milliseconds
và được trả lại trong JSON `createdAt`. `MsgModel.mergeFromServer` thay giờ tạm bằng
giờ Server. `UiUtil.time` chuyển epoch milliseconds sang múi giờ Việt Nam
`Asia/Ho_Chi_Minh` để hiển thị:

- cùng ngày: `HH:mm`;
- ngày khác: `dd/MM HH:mm`.

Mốc thời gian Server là nguồn hiển thị thống nhất; thời gian máy chỉ dùng tạm cho
nhãn tin đang gửi. Lưu ý: thứ tự truy vấn và phân trang hiện dựa trên
`message_id`, không phải sắp xếp theo `created_at`. Vì vậy tài liệu không khẳng định
timestamp là khóa sắp xếp; muốn sắp xếp tuyệt đối theo thời gian cần đổi cursor
phân trang sang cặp `(created_at, message_id)` và kiểm thử lại luồng SYNC.

## 5. Khó khăn thực tế và bài học

### 5.1 Dấu nháy làm vỡ SQL

Nội dung có thể chứa cả `'` và `"`. Ghép chuỗi bằng `"`/`'` làm câu SQL có thể bị
đóng chuỗi sớm hoặc cú pháp sai. Giải pháp là:

- dùng `?` trong mọi câu SQL có dữ liệu người dùng;
- bind bằng `setString`, `setLong`, `setInt`;
- không dùng escape thủ công hoặc nối chuỗi SQL.

Với tin nhắn lớn, giới hạn 8000 ký tự được kiểm tra trước khi ghi.

### 5.2 Emoji biến thành `????`

Nguyên nhân có thể nằm ở database, JDBC hoặc kết nối nếu một lớp dùng `utf8` thay
vì `utf8mb4`. Kiểm tra cả 3 lớp:

- schema database/bảng/cột;
- JDBC URL;
- file nguồn và codec Java.

Để kiểm tra framing và nội dung Emoji có dấu nháy:

```bat
mvn -q -pl common install -DskipTests
java -cp common/target/classes:<gson.jar> com.zalo.common.tools.FramingSelfTest
```

Kết quả mong đợi: `ALL PASS`, trong đó TC-02 báo `100/100 gói, đúng thứ tự, emoji nguyên vẹn`.
Phần này xác minh framing và chuỗi Emoji qua gói tin; kiểm tra lưu trữ phải chạy demo với MySQL.

### 5.3 Đồng hồ phân tán

Trong hệ phân tán, hai máy trạm có thể lệch vài giây hoặc sai múi giờ. Nếu mỗi máy
dùng giờ cục bộ để lưu và sắp xếp, hai tin về thứ tự có thể đảo nhau. Vì vậy mọi
`created_at` được cấp lại bởi Server, và Client chỉ dùng giờ máy cho trạng thái
Optimistic UI trước ACK.

## 6. Kịch bản demo History — Emoji — Time

1. Cài CSDL bằng `mysql -u root -p < db/schema.sql`.
2. Mở hai Client cho hai tài khoản trong cùng một hội thoại.
3. Gửi tin: `Lời có dấu ' và " kép; không vỡ SQL 😀 🚀`.
4. Kiểm tra tin xuất hiện đúng ở hai máy và không biến thành `????`.
5. Để demo `SYNC`: giữ Client A đang chạy, tạm ngắt kết nối mạng của A; trong lúc đó
   gửi 70–120 tin từ Client B; khôi phục mạng cho A và quan sát A đồng bộ theo các
   trang tối đa 50 tin.
6. Để demo tải khi khởi động mới: đóng hẳn A, chạy lại, mở hội thoại và xác nhận tải
   30 tin mới nhất. Đây là tải trang đầu, không phải `SYNC`.
7. Cuộn lên lịch sử; quan sát `LOAD_OLDER_MESSAGES` tải trang 30 tin bằng keyset.
8. Xóa một tin phía tôi ở một tài khoản; xác nhận tin đó vẫn còn ở máy kia, preview
   được cập nhật theo tin cuối người dùng còn xem được.
9. Đổi giờ máy Client trước khi gửi; sau ACK, hai máy phải hiển thị timestamp do
   Server trả về.
10. Kiểm tra thứ tự danh sách theo `message_id` và giờ hiển thị theo `created_at`
    trên cả hai máy.

### Truyện vấn đáp

- **Vì sao dùng `message_id` thay vì `created_at` để phân trang?**
  Code hiện tại dùng `message_id` tăng đơn điệu cùng index `(conversation_id,
  message_id)` làm cursor; timestamp dùng để hiển thị. Vì vậy không nên nói code
  đang sắp xếp theo `created_at`.
- **Vì sao dùng keyset thay vì `OFFSET`?**
  Offset quét và bỏ nhiều dòng, đồng thời dịch dữ liệu nếu list thay đổi. Keyset chỉ
  lọc `message_id < beforeId`.
- **Vì sao P2P không làm sai History?**
  P2P không quyết định dữ liệu lịch sử: Server nhận bản sao, gán lại `createdAt`,
  lưu và phát ACK.
- **Xóa phía tôi khác thu hồi như thế nào?**
  Thu hồi sửa `Messages.is_recalled` cho tất cả. Xóa phía tôi thêm một dòng
  `MessageDeletions(message_id,user_id)` và chỉ ẩn cho người dùng đó.
- **State tin nhắn lưu ở đâu?**
  Trong `ConversationMembers.last_delivered_message_id` và
  `last_read_message_id`, suy ra SENT/DELIVERED/READ theo từng thành viên.
- **Server Unix timestamp là UTC hay giờ địa phương?**
  Giá trị epoch milliseconds không phụ thuộc múi giờ của Client. Client chuyển qua
  `Asia/Ho_Chi_Minh` khi hiển thị.

## 7. Giới hạn đã biết

- Chưa lưu cache History cục bộ đầy đủ trên Client; dữ liệu tin dài hạn nằm ở Server.
- `SYNC` sau reconnect dùng cursor trong RAM; sau khi đóng hẳn ứng dụng, mở hội thoại
  sẽ tải 30 tin mới nhất rồi phân trang, không khôi phục cursor local.
- History hiện truy xuất/sắp xếp theo `message_id`, không theo một cursor thời gian.
- JavaFX trên Windows có thể hiển thị Emoji đơn sắc tùy font; dữ liệu vẫn là UTF-8
  4-byte đúng. `utf8mb4` đảm bảo lưu ký tự, không đảm bảo Emoji được vẽ màu.
