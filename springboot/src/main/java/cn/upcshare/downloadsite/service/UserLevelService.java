package cn.upcshare.downloadsite.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class UserLevelService {
    private final JdbcTemplate jdbc;

    public UserLevelService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String effectiveLevel(String uid, String username, boolean admin, String configuredLevel) {
        return effectiveLevel(admin, configuredLevel, approvedUploadCount(username), downloadCount(uid), points(uid));
    }

    public String effectiveLevel(boolean admin, String configuredLevel, long approvedUploads, long downloads, double points) {
        if (admin) return "admin";
        String configured = configuredLevel == null || configuredLevel.isBlank() ? "auto" : configuredLevel;
        if ("orange".equals(configured) && points >= 200) return "orange";
        if ("yellow".equals(configured) && points >= 50) return "yellow";
        if (!"auto".equals(configured) && !"yellow".equals(configured) && !"orange".equals(configured)) {
            return configured;
        }
        if (points >= 200) return "orange";
        if (points >= 50) return "yellow";
        if (approvedUploads >= 1) return "green";
        if (downloads >= 2) return "blue";
        return "gray";
    }

    public double points(String uid) {
        try {
            return jdbc.query("SELECT points FROM users WHERE uid=?",
                    rs -> rs.next() ? rs.getDouble("points") : 0, uid);
        } catch (Exception ignored) {
            return 0;
        }
    }

    public long downloadCount(String uid) {
        try {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM download_log WHERE user_id=?", Long.class, uid);
            return count == null ? 0 : count;
        } catch (Exception ignored) {
            return 0;
        }
    }

    public long approvedUploadCount(String username) {
        try {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM files WHERE uploader=? AND status='approved'", Long.class, username);
            return count == null ? 0 : count;
        } catch (Exception ignored) {
            return 0;
        }
    }
}
