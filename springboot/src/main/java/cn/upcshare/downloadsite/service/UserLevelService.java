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
        if (admin) return "admin";
        String configured = configuredLevel == null || configuredLevel.isBlank() ? "auto" : configuredLevel;
        if (!"auto".equals(configured)) return configured;
        if (approvedUploadCount(username) >= 1) return "green";
        if (downloadCount(uid) >= 2) return "blue";
        return "gray";
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
