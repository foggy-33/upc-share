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
        ensure("CREATE INDEX idx_files_public_list ON files (status, category, sub_category, original_name)");
        ensure("CREATE INDEX idx_files_status_created ON files (status, created_at)");
        ensure("CREATE INDEX idx_dl_file_id ON download_log (file_id)");
        ensure("CREATE INDEX idx_dl_cloud_sync ON download_log (cloud_synced_at, id)");
        ensure("CREATE INDEX idx_forum_posts_id_created ON forum_posts (id, created_at)");
        jdbc.update("UPDATE users SET is_active=1 WHERE is_active<>1 OR is_active IS NULL");
    }

    private void ensure(String sql) {
        try {
            jdbc.execute(sql);
        } catch (Exception ignored) {
            // Existing indexes are fine. Spring's schema.sql only amends fresh tables.
        }
    }
}
