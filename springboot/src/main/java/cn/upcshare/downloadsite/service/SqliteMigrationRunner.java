package cn.upcshare.downloadsite.service;

import cn.upcshare.downloadsite.config.AppProperties;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.sqlite.SQLiteConfig;

import java.sql.*;
import java.util.*;

@Component
@Order(10)
public class SqliteMigrationRunner implements CommandLineRunner {
    private final JdbcTemplate mysql;
    private final AppProperties props;

    public SqliteMigrationRunner(JdbcTemplate mysql, AppProperties props) {
        this.mysql = mysql;
        this.props = props;
    }

    @Override
    public void run(String... args) throws Exception {
        if (!props.getMigration().isEnabled()) return;
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        try (Connection sqlite = DriverManager.getConnection("jdbc:sqlite:" + props.getMigration().getSqlitePath(), config.toProperties())) {
            migrate(sqlite, "files", List.of("id", "file_path", "original_name", "extension", "file_size", "description", "category", "sub_category", "created_at", "download_count", "status", "uploader"));
            migrate(sqlite, "users", List.of("id", "username", "password_hash", "created_at", "updated_at", "is_active", "is_admin"));
            migrate(sqlite, "download_log", List.of("id", "user_id", "file_id", "file_size", "downloaded_at", "event_id", "source_node", "cloud_synced_at"));
            migrate(sqlite, "site_settings", List.of("key", "value", "updated_at"));
            migrate(sqlite, "forum_posts", List.of("id", "user_id", "username", "content", "created_at"));
            migrate(sqlite, "forum_comments", List.of("id", "post_id", "user_id", "username", "content", "created_at"));
        }
        System.out.println("SQLite migration finished. Source database was read only: " + props.getMigration().getSqlitePath());
    }

    private void migrate(Connection sqlite, String table, List<String> columns) throws SQLException {
        if (!hasTable(sqlite, table)) return;
        Set<String> existing = columns(sqlite, table);
        List<String> select = columns.stream().filter(existing::contains).toList();
        if (select.isEmpty()) return;
        String selectSql = "SELECT " + select.stream().map(c -> c.equals("key") ? "`key`" : c).reduce((a, b) -> a + "," + b).orElse("*") + " FROM " + table;
        try (Statement st = sqlite.createStatement(); ResultSet rs = st.executeQuery(selectSql)) {
            int count = 0;
            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                for (String c : select) row.put(c, rs.getObject(c));
                upsert(table, columns, row);
                count++;
            }
            System.out.println(table + ": migrated " + count + " rows");
        }
    }

    private void upsert(String table, List<String> columns, Map<String, Object> row) {
        List<Object> values = new ArrayList<>();
        for (String c : columns) values.add(value(c, row));
        String cols = columns.stream().map(c -> c.equals("key") ? "`key`" : "`" + c + "`").reduce((a, b) -> a + "," + b).orElse("");
        String marks = String.join(",", Collections.nCopies(columns.size(), "?"));
        String updates = columns.stream().filter(c -> !c.equals("id") && !c.equals("key")).map(c -> "`" + c + "`=VALUES(`" + c + "`)").reduce((a, b) -> a + "," + b).orElse("`key`=`key`");
        mysql.update("INSERT INTO " + table + " (" + cols + ") VALUES (" + marks + ") ON DUPLICATE KEY UPDATE " + updates, values.toArray());
    }

    private Object value(String c, Map<String, Object> row) {
        Object v = row.get(c);
        if (v != null && !(v instanceof String s && s.isBlank() && c.equals("event_id"))) return v;
        if (c.equals("updated_at")) return row.getOrDefault("created_at", "");
        if (c.equals("status")) return "approved";
        if (c.equals("is_active")) return 1;
        if (c.equals("is_admin") || c.equals("file_size") || c.equals("download_count")) return 0;
        if (c.equals("event_id") && row.get("id") != null) return "legacy-" + row.get("id");
        return "";
    }

    private boolean hasTable(Connection c, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT name FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            return ps.executeQuery().next();
        }
    }

    private Set<String> columns(Connection c, String table) throws SQLException {
        Set<String> out = new HashSet<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) out.add(rs.getString("name"));
        }
        return out;
    }
}
