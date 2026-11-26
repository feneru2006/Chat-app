package com.zalo.common;

/**
 * Hằng số giao thức dùng chung Client/Server (hợp đồng gói tin — đặc tả mục 5.5).
 * Mọi chuỗi "type" của gói tin JSON đều khai báo ở đây để tránh gõ sai chính tả.
 */
public final class Protocol {
    private Protocol() {}

    // ---------- Cổng, thời gian, giới hạn ----------
    public static final int DEFAULT_TLS_PORT = 9000;
    public static final int DEFAULT_MEDIA_PORT = 9001;
    public static final int MAX_FRAME_BYTES = 1_048_576;       // 1 MB / khung JSON (chống gói rác)
    public static final int AUTH_TIMEOUT_MS = 10_000;          // phải AUTH trong 10s đầu
    public static final int PING_INTERVAL_MS = 20_000;         // Client gửi PING mỗi 20s
    public static final int HEARTBEAT_TIMEOUT_MS = 45_000;     // Server: 45s im lặng = Offline
    public static final int CHUNK_SIZE = 8192;                 // Chunking 8KB
    public static final int OUT_QUEUE_CAPACITY = 500;          // hàng đợi gửi riêng mỗi ClientHandler
    public static final int SYNC_LIMIT = 50;                   // tối đa 50 tin/hội thoại khi sync
    public static final int OLDER_PAGE = 30;                   // phân trang 30 tin cũ hơn
    public static final int THUMB_MAX_PX = 300;                // thumbnail tối đa 300x300
    public static final long MAX_FILE_BYTES = 500L * 1024 * 1024;
    public static final int[] RECONNECT_BACKOFF_SEC = {3, 5, 10};

    // ---------- Loại tin nhắn (Messages.msg_type) ----------
    public static final String MSG_TEXT = "TEXT";
    public static final String MSG_IMAGE = "IMAGE";
    public static final String MSG_VIDEO = "VIDEO";
    public static final String MSG_SOUND = "SOUND";
    public static final String MSG_FILE = "FILE";
    public static final String MSG_SYSTEM = "SYSTEM";

    // ---------- Trạng thái tin nhắn (State) ----------
    public static final String ST_SENT = "SENT";
    public static final String ST_DELIVERED = "DELIVERED";
    public static final String ST_READ = "READ";

    // ---------- Client -> Server ----------
    public static final String REGISTER = "REGISTER";
    public static final String AUTH = "AUTH";
    public static final String PING = "PING";
    public static final String SEND_MSG = "SEND_MSG";
    public static final String ACK_DELIVERED = "ACK_DELIVERED";
    public static final String MARK_AS_READ = "MARK_AS_READ";
    public static final String SYNC = "SYNC";
    public static final String LOAD_OLDER_MESSAGES = "LOAD_OLDER_MESSAGES";
    public static final String GET_CONVERSATIONS = "GET_CONVERSATIONS";
    public static final String OPEN_PRIVATE = "OPEN_PRIVATE";
    public static final String CREATE_GROUP = "CREATE_GROUP";
    public static final String GROUP_ADD_MEMBER = "GROUP_ADD_MEMBER";
    public static final String GROUP_REMOVE_MEMBER = "GROUP_REMOVE_MEMBER";
    public static final String GROUP_RENAME = "GROUP_RENAME";
    public static final String GROUP_LEAVE = "GROUP_LEAVE";
    public static final String SEARCH_USER = "SEARCH_USER";
    public static final String FRIEND_REQUEST = "FRIEND_REQUEST";
    public static final String FRIEND_ACCEPT = "FRIEND_ACCEPT";
    public static final String FRIEND_LIST = "FRIEND_LIST";
    public static final String P2P_LOOKUP = "P2P_LOOKUP";
    public static final String TYPING = "TYPING";
    public static final String RECALL_MSG = "RECALL_MSG";
    public static final String DELETE_FOR_ME = "DELETE_FOR_ME";
    public static final String CLEAR_HISTORY = "CLEAR_HISTORY";
    public static final String REACT = "REACT";
    public static final String FILE_INIT = "FILE_INIT";
    public static final String FILE_CANCEL = "FILE_CANCEL";
    public static final String FILE_DOWNLOAD_REQ = "FILE_DOWNLOAD_REQ";

    // ---------- Server -> Client ----------
    public static final String REGISTER_RESULT = "REGISTER_RESULT";
    public static final String AUTH_RESULT = "AUTH_RESULT";
    public static final String PONG = "PONG";
    public static final String FORCE_LOGOUT = "FORCE_LOGOUT";
    public static final String MSG_ACK = "MSG_ACK";
    public static final String NEW_MESSAGE = "NEW_MESSAGE";
    public static final String MSG_STATUS = "MSG_STATUS";
    public static final String SYNC_RESULT = "SYNC_RESULT";
    public static final String OLDER_RESULT = "OLDER_RESULT";
    public static final String CONVERSATIONS = "CONVERSATIONS";
    public static final String CONVERSATION_ADDED = "CONVERSATION_ADDED";
    public static final String CONVERSATION_UPDATED = "CONVERSATION_UPDATED";
    public static final String CONVERSATION_REMOVED = "CONVERSATION_REMOVED";
    public static final String SEARCH_RESULT = "SEARCH_RESULT";
    public static final String FRIEND_LIST_RESULT = "FRIEND_LIST_RESULT";
    public static final String FRIEND_REQUEST_IN = "FRIEND_REQUEST_IN";
    public static final String FRIEND_ACCEPTED = "FRIEND_ACCEPTED";
    public static final String P2P_PEER_INFO = "P2P_PEER_INFO";
    public static final String USER_ONLINE = "USER_ONLINE";
    public static final String USER_OFFLINE = "USER_OFFLINE";
    public static final String MSG_RECALLED = "MSG_RECALLED";
    public static final String MSG_DELETED_ME = "MSG_DELETED_ME";
    public static final String HISTORY_CLEARED = "HISTORY_CLEARED";
    public static final String MSG_REACTION = "MSG_REACTION";
    public static final String FILE_READY = "FILE_READY";
    public static final String FILE_DOWNLOAD_READY = "FILE_DOWNLOAD_READY";
    public static final String ERROR = "ERROR";

    // ---------- Kênh 2 (media) ----------
    public static final String UPLOAD_BEGIN = "UPLOAD_BEGIN";
    public static final String UPLOAD_RESULT = "UPLOAD_RESULT";
    public static final String DOWNLOAD_BEGIN = "DOWNLOAD_BEGIN";

    // ---------- Kênh 3 (P2P) ----------
    public static final String P2P_HELLO = "P2P_HELLO";
    public static final String P2P_ACK = "P2P_ACK";

    // ---------- Mã lỗi ----------
    public static final String ERR_BAD_CREDENTIALS = "ERR_BAD_CREDENTIALS";
    public static final String ERR_USERNAME_TAKEN = "ERR_USERNAME_TAKEN";
    public static final String ERR_UNAUTHENTICATED = "ERR_UNAUTHENTICATED";
    public static final String ERR_BAD_REQUEST = "ERR_BAD_REQUEST";
    public static final String ERR_NOT_MEMBER = "ERR_NOT_MEMBER";
    public static final String ERR_NOT_ADMIN = "ERR_NOT_ADMIN";
    public static final String ERR_FORBIDDEN = "ERR_FORBIDDEN";
    public static final String ERR_NOT_FOUND = "ERR_NOT_FOUND";
    public static final String ERR_STRANGER_BLOCKED = "ERR_STRANGER_BLOCKED";
    public static final String ERR_TOO_LARGE = "ERR_TOO_LARGE";
    public static final String ERR_MD5_MISMATCH = "ERR_MD5_MISMATCH";
    public static final String ERR_UNKNOWN_TYPE = "ERR_UNKNOWN_TYPE";
    public static final String ERR_SERVER = "ERR_SERVER";
}
