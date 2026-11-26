-- Schema v4.1 (khớp đặc tả): trạng thái tin nhắn suy ra từ last_delivered/last_read; không Mute/Block/Pin
CREATE DATABASE IF NOT EXISTS zalo_network_app
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE zalo_network_app;

CREATE TABLE Users (
  user_id            INT AUTO_INCREMENT PRIMARY KEY,
  username           VARCHAR(50)  NOT NULL UNIQUE,
  phone              VARCHAR(15)  NOT NULL UNIQUE,
  password_hash      VARCHAR(255) NOT NULL,
  display_name       VARCHAR(100) NOT NULL,
  avatar_url         VARCHAR(255) DEFAULT 'default_avatar.png',
  last_ip            VARCHAR(45)  NULL,
  p2p_port           INT          NULL,
  allow_stranger_msg BOOLEAN      DEFAULT TRUE,
  show_read_status   BOOLEAN      DEFAULT TRUE,
  is_online          BOOLEAN      DEFAULT FALSE,
  last_seen          BIGINT       DEFAULT 0,
  created_at         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE Friendships (
  user_id_1      INT NOT NULL,
  user_id_2      INT NOT NULL,
  status         ENUM('PENDING','ACCEPTED') NOT NULL DEFAULT 'PENDING',
  action_user_id INT NOT NULL,
  created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id_1, user_id_2),
  FOREIGN KEY (user_id_1) REFERENCES Users(user_id) ON DELETE CASCADE,
  FOREIGN KEY (user_id_2) REFERENCES Users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE Conversations (
  conversation_id INT AUTO_INCREMENT PRIMARY KEY,
  type            ENUM('PRIVATE','GROUP') NOT NULL,
  group_name      VARCHAR(120) NULL,
  group_avatar    VARCHAR(255) NULL,
  last_message_id BIGINT NULL,
  updated_at      BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE Messages (
  message_id      BIGINT AUTO_INCREMENT PRIMARY KEY,
  client_msg_id   VARCHAR(64) NOT NULL,
  conversation_id INT NOT NULL,
  sender_id       INT NOT NULL,
  reply_to_msg_id BIGINT NULL,
  msg_type        ENUM('TEXT','IMAGE','VIDEO','SOUND','FILE','SYSTEM') NOT NULL DEFAULT 'TEXT',
  content         TEXT NULL,
  is_recalled     BOOLEAN DEFAULT FALSE,
  created_at      BIGINT NOT NULL,
  UNIQUE KEY uq_conv_client_msg (conversation_id, client_msg_id),
  INDEX idx_conv_created (conversation_id, message_id),
  FOREIGN KEY (conversation_id) REFERENCES Conversations(conversation_id) ON DELETE CASCADE,
  FOREIGN KEY (sender_id) REFERENCES Users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ConversationMembers (
  conversation_id          INT NOT NULL,
  user_id                  INT NOT NULL,
  role                     ENUM('ADMIN','MEMBER') NOT NULL DEFAULT 'MEMBER',
  joined_at                TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  last_delivered_message_id BIGINT DEFAULT 0,
  last_read_message_id     BIGINT DEFAULT 0,
  cleared_before_msg_id    BIGINT DEFAULT 0,
  PRIMARY KEY (conversation_id, user_id),
  FOREIGN KEY (conversation_id) REFERENCES Conversations(conversation_id) ON DELETE CASCADE,
  FOREIGN KEY (user_id) REFERENCES Users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE Attachments (
  attachment_id  BIGINT AUTO_INCREMENT PRIMARY KEY,
  message_id     BIGINT NOT NULL,
  file_name      VARCHAR(255) NOT NULL,
  file_size      BIGINT NOT NULL,
  mime_type      VARCHAR(100) NOT NULL,
  duration_sec   INT NULL,
  file_path      VARCHAR(500) NOT NULL,
  thumbnail_path VARCHAR(500) NULL,
  FOREIGN KEY (message_id) REFERENCES Messages(message_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE MessageDeletions (
  message_id BIGINT NOT NULL,
  user_id    INT NOT NULL,
  deleted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (message_id, user_id),
  FOREIGN KEY (message_id) REFERENCES Messages(message_id) ON DELETE CASCADE,
  FOREIGN KEY (user_id) REFERENCES Users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE MessageReactions (
  message_id    BIGINT NOT NULL,
  user_id       INT NOT NULL,
  reaction_type ENUM('LIKE','HEART','HAHA','SAD','ANGRY') NOT NULL,
  updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (message_id, user_id),
  FOREIGN KEY (message_id) REFERENCES Messages(message_id) ON DELETE CASCADE,
  FOREIGN KEY (user_id) REFERENCES Users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
