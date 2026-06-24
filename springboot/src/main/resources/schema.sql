CREATE TABLE IF NOT EXISTS files (
  id VARCHAR(64) PRIMARY KEY,
  file_path VARCHAR(1024) NOT NULL,
  original_name VARCHAR(512) NOT NULL,
  extension VARCHAR(32) NOT NULL,
  file_size BIGINT DEFAULT 0,
  description TEXT,
  category VARCHAR(255) DEFAULT '',
  sub_category VARCHAR(512) DEFAULT '',
  created_at VARCHAR(64) NOT NULL,
  download_count BIGINT DEFAULT 0,
  status VARCHAR(32) DEFAULT 'pending',
  uploader VARCHAR(255) DEFAULT '',
  INDEX idx_files_status (status),
  INDEX idx_files_category (category, sub_category(191)),
  INDEX idx_files_created (created_at),
  INDEX idx_files_public_list (status, category, sub_category(128), original_name(191)),
  INDEX idx_files_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS users (
  uid VARCHAR(6) PRIMARY KEY,
  username VARCHAR(64) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  created_at VARCHAR(64) NOT NULL,
  avatar_path VARCHAR(255) NOT NULL DEFAULT '',
  user_level VARCHAR(32) NOT NULL DEFAULT 'auto',
  last_ip VARCHAR(64) NOT NULL DEFAULT '',
  is_active TINYINT DEFAULT 1,
  is_admin TINYINT DEFAULT 0,
  points DECIMAL(10,1) NOT NULL DEFAULT 0.0,
  is_blacklisted TINYINT NOT NULL DEFAULT 0,
  blacklist_reason VARCHAR(255) NOT NULL DEFAULT '',
  is_sensitive TINYINT NOT NULL DEFAULT 0,
  matched_words VARCHAR(255) NOT NULL DEFAULT '',
  sensitive_source_type VARCHAR(64) NOT NULL DEFAULT ''
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS download_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id VARCHAR(64) NOT NULL,
  file_id VARCHAR(64) NOT NULL,
  file_size BIGINT DEFAULT 0,
  downloaded_at VARCHAR(64) NOT NULL,
  event_id VARCHAR(128) DEFAULT '',
  source_node VARCHAR(64) DEFAULT '',
  cloud_synced_at VARCHAR(64) DEFAULT '',
  INDEX idx_dl_event_id (event_id),
  INDEX idx_dl_user_date (user_id, downloaded_at),
  INDEX idx_dl_file_id (file_id),
  INDEX idx_dl_cloud_sync (cloud_synced_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS site_settings (
  `key` VARCHAR(128) PRIMARY KEY,
  value TEXT NOT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS forum_posts (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id VARCHAR(6) NOT NULL,
  username VARCHAR(64) NOT NULL,
  section VARCHAR(32) NOT NULL DEFAULT '灌水区',
  title VARCHAR(160) NOT NULL DEFAULT '',
  content TEXT NOT NULL,
  view_count BIGINT DEFAULT 0,
  is_pinned TINYINT DEFAULT 0,
  ip_address VARCHAR(64) NOT NULL DEFAULT '',
  created_at VARCHAR(64) NOT NULL,
  INDEX idx_forum_posts_created (created_at),
  INDEX idx_forum_posts_id_created (id, created_at),
  INDEX idx_forum_posts_ip (ip_address)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS forum_sections (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(64) NOT NULL UNIQUE,
  min_level VARCHAR(32) NOT NULL DEFAULT 'gray',
  sort_order INT NOT NULL DEFAULT 0,
  is_active TINYINT NOT NULL DEFAULT 1,
  created_at VARCHAR(64) NOT NULL,
  INDEX idx_forum_sections_active_order (is_active, sort_order, id),
  INDEX idx_forum_sections_level (min_level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS forum_comments (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  post_id BIGINT NOT NULL,
  user_id VARCHAR(6) NOT NULL,
  username VARCHAR(64) NOT NULL,
  content TEXT NOT NULL,
  ip_address VARCHAR(64) NOT NULL DEFAULT '',
  created_at VARCHAR(64) NOT NULL,
  INDEX idx_forum_comments_post (post_id, created_at),
  INDEX idx_forum_comments_ip (ip_address)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS forum_post_likes (
  post_id BIGINT NOT NULL,
  user_id VARCHAR(6) NOT NULL,
  created_at VARCHAR(64) NOT NULL,
  PRIMARY KEY (post_id, user_id),
  INDEX idx_forum_post_likes_user (user_id, created_at),
  INDEX idx_forum_post_likes_post (post_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS forum_comment_likes (
  comment_id BIGINT NOT NULL,
  user_id VARCHAR(6) NOT NULL,
  created_at VARCHAR(64) NOT NULL,
  PRIMARY KEY (comment_id, user_id),
  INDEX idx_forum_comment_likes_user (user_id, created_at),
  INDEX idx_forum_comment_likes_comment (comment_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS forum_images (
  id VARCHAR(64) PRIMARY KEY,
  user_id VARCHAR(6) NOT NULL,
  username VARCHAR(64) NOT NULL,
  file_path VARCHAR(1024) NOT NULL,
  original_name VARCHAR(512) NOT NULL,
  mime_type VARCHAR(64) NOT NULL,
  file_size BIGINT DEFAULT 0,
  created_at VARCHAR(64) NOT NULL,
  INDEX idx_forum_images_user (user_id, created_at),
  INDEX idx_forum_images_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS site_audit_logs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_type VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NOT NULL DEFAULT '',
  username VARCHAR(64) NOT NULL DEFAULT '',
  ip_address VARCHAR(64) NOT NULL DEFAULT '',
  title VARCHAR(255) NOT NULL DEFAULT '',
  content_snippet TEXT,
  created_at VARCHAR(64) NOT NULL,
  INDEX idx_audit_event_created (event_type, created_at),
  INDEX idx_audit_ip_created (ip_address, created_at),
  INDEX idx_audit_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS content_admin_groups (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  group_name VARCHAR(64) NOT NULL UNIQUE,
  log_categories TEXT,
  album_categories TEXT,
  user_groups TEXT,
  can_modify_user TINYINT DEFAULT 0,
  can_enter_user_backend TINYINT DEFAULT 0,
  can_modify_user_group TINYINT DEFAULT 0,
  can_manage_user_template TINYINT DEFAULT 0,
  can_publish_site_notice TINYINT DEFAULT 0,
  created_at VARCHAR(64) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS content_admin_members (
  user_id VARCHAR(64) PRIMARY KEY,
  group_id BIGINT NOT NULL,
  created_at VARCHAR(64) NOT NULL,
  INDEX idx_content_admin_group (group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO users
  (uid, username, password_hash, created_at, is_active, is_admin)
VALUES
  ('000000', 'system', '!disabled-system-account', NOW(), 0, 0);

INSERT IGNORE INTO forum_sections (name,min_level,sort_order,is_active,created_at) VALUES
  ('前沿快讯','gray',10,1,NOW()),
  ('资源分享','gray',20,1,NOW()),
  ('求助','gray',30,1,NOW()),
  ('灌水区','gray',40,1,NOW());
