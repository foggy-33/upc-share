package cn.upcshare.downloadsite.service;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(5)
public class DatabaseIndexRunner implements CommandLineRunner {
    private final JdbcTemplate jdbc;

    public DatabaseIndexRunner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        migrateUserIds();
        ensure("ALTER TABLE users ADD COLUMN updated_at VARCHAR(64) NOT NULL DEFAULT ''");
        ensure("ALTER TABLE users ADD COLUMN avatar_path VARCHAR(255) NOT NULL DEFAULT ''");
        ensure("ALTER TABLE users ADD COLUMN user_level VARCHAR(32) NOT NULL DEFAULT 'auto'");
        ensure("ALTER TABLE users ADD COLUMN is_active TINYINT DEFAULT 1");
        ensure("ALTER TABLE users ADD COLUMN is_admin TINYINT DEFAULT 0");
        ensure("ALTER TABLE forum_posts ADD COLUMN section VARCHAR(32) NOT NULL DEFAULT '灌水区'");
        ensure("ALTER TABLE forum_posts ADD COLUMN title VARCHAR(160) NOT NULL DEFAULT ''");
        ensure("ALTER TABLE forum_posts ADD COLUMN view_count BIGINT DEFAULT 0");
        ensure("ALTER TABLE forum_posts ADD COLUMN is_pinned TINYINT DEFAULT 0");
        ensure("CREATE INDEX idx_files_public_list ON files (status, category, sub_category, original_name)");
        ensure("CREATE INDEX idx_files_status_created ON files (status, created_at)");
        ensure("CREATE INDEX idx_dl_file_id ON download_log (file_id)");
        ensure("CREATE INDEX idx_dl_cloud_sync ON download_log (cloud_synced_at, id)");
        ensure("CREATE INDEX idx_forum_posts_id_created ON forum_posts (id, created_at)");
        jdbc.update("UPDATE users SET is_active=1 WHERE is_active IS NULL");
        jdbc.update("UPDATE users SET user_level='auto' WHERE user_level IS NULL OR user_level=''");
        jdbc.update("UPDATE users SET is_active=1, is_admin=1 WHERE username='foggy'");
        jdbc.update("UPDATE forum_posts SET section='灌水区' WHERE section=''");
        jdbc.update("UPDATE forum_posts SET title=LEFT(TRIM(content), 80) WHERE title=''");
        jdbc.update("UPDATE forum_posts SET view_count=0 WHERE view_count IS NULL");
        jdbc.update("UPDATE forum_posts SET is_pinned=0 WHERE is_pinned IS NULL");
    }

    private void migrateUserIds() {
        if (!hasColumn("users", "id")) return;

        ensure("ALTER TABLE users ADD COLUMN uid VARCHAR(6)");
        jdbc.execute("""
                UPDATE users u
                JOIN (
                    SELECT id, LPAD(ROW_NUMBER() OVER (ORDER BY created_at, id), 6, '0') AS uid
                    FROM users
                ) ordered_users ON ordered_users.id = u.id
                SET u.uid = ordered_users.uid
                """);
        jdbc.execute("ALTER TABLE forum_posts MODIFY user_id VARCHAR(6) NOT NULL");
        jdbc.execute("ALTER TABLE forum_comments MODIFY user_id VARCHAR(6) NOT NULL");
        jdbc.execute("""
                UPDATE download_log d
                JOIN users u ON d.user_id COLLATE utf8mb4_unicode_ci = CAST(u.id AS CHAR) COLLATE utf8mb4_unicode_ci
                SET d.user_id = u.uid
                """);
        jdbc.execute("""
                UPDATE forum_posts p
                JOIN users u ON p.user_id COLLATE utf8mb4_unicode_ci = CAST(u.id AS CHAR) COLLATE utf8mb4_unicode_ci
                SET p.user_id = u.uid
                """);
        jdbc.execute("""
                UPDATE forum_comments c
                JOIN users u ON c.user_id COLLATE utf8mb4_unicode_ci = CAST(u.id AS CHAR) COLLATE utf8mb4_unicode_ci
                SET c.user_id = u.uid
                """);
        jdbc.execute("""
                ALTER TABLE users
                    DROP PRIMARY KEY,
                    DROP COLUMN id,
                    MODIFY uid VARCHAR(6) NOT NULL,
                    ADD PRIMARY KEY (uid)
                """);
    }

    private boolean hasColumn(String table, String column) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND column_name = ?
                """, Integer.class, table, column);
        return count != null && count > 0;
    }

    private void ensure(String sql) {
        try {
            jdbc.execute(sql);
        } catch (Exception ignored) {
            // Existing indexes are fine. Spring's schema.sql only amends fresh tables.
        }
    }
}
