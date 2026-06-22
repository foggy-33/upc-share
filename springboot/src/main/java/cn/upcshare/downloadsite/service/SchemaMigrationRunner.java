package cn.upcshare.downloadsite.service;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SchemaMigrationRunner implements CommandLineRunner {
    private final JdbcTemplate jdbc;

    public SchemaMigrationRunner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        addColumnIfMissing("users", "avatar_path", "VARCHAR(255) NOT NULL DEFAULT ''");
        addColumnIfMissing("users", "user_level", "VARCHAR(32) NOT NULL DEFAULT 'auto'");
        addColumnIfMissing("users", "last_ip", "VARCHAR(64) NOT NULL DEFAULT ''");
        addColumnIfMissing("users", "is_active", "TINYINT NOT NULL DEFAULT 1");
        addColumnIfMissing("users", "is_admin", "TINYINT NOT NULL DEFAULT 0");
        addColumnIfMissing("users", "points", "DECIMAL(10,1) NOT NULL DEFAULT 0.0");
        addColumnIfMissing("users", "is_blacklisted", "TINYINT NOT NULL DEFAULT 0");
        addColumnIfMissing("users", "blacklist_reason", "VARCHAR(255) NOT NULL DEFAULT ''");
        addColumnIfMissing("users", "is_sensitive", "TINYINT NOT NULL DEFAULT 0");
        addColumnIfMissing("users", "matched_words", "VARCHAR(255) NOT NULL DEFAULT ''");
        addColumnIfMissing("users", "sensitive_source_type", "VARCHAR(64) NOT NULL DEFAULT ''");

        addColumnIfMissing("site_settings", "updated_at",
                "DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");
        jdbc.update("""
                ALTER TABLE site_settings
                MODIFY COLUMN updated_at DATETIME NOT NULL
                DEFAULT CURRENT_TIMESTAMP
                ON UPDATE CURRENT_TIMESTAMP
                """);
        jdbc.update("""
                INSERT IGNORE INTO site_settings (`key`,value,updated_at)
                VALUES ('notice_text','Welcome to upcshare.',NOW())
                """);
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name=? AND column_name=?
                """, Integer.class, table, column);
        if (count != null && count > 0) return;
        jdbc.execute("ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + definition);
    }
}
