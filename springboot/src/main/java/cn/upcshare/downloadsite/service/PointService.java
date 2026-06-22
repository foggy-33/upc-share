package cn.upcshare.downloadsite.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class PointService {
    private final JdbcTemplate jdbc;

    public PointService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> rewardUpload(String uid) {
        jdbc.update("UPDATE users SET points = points + 1 WHERE uid = ?", uid);
        return pointSummary(uid);
    }

    public void rewardDownload(String uid) {
        jdbc.update("UPDATE users SET points = points + 0.1 WHERE uid = ?", uid);
    }

    public Map<String, Object> rewardPost(String uid) {
        jdbc.update("UPDATE users SET points = points + 1 WHERE uid = ?", uid);
        return pointSummary(uid);
    }

    public Map<String, Object> rewardComment(String uid) {
        jdbc.update("UPDATE users SET points = points + 0.5 WHERE uid = ?", uid);
        return pointSummary(uid);
    }

    public Map<String, Object> pointSummary(String uid) {
        return jdbc.queryForMap("""
                SELECT points
                FROM users
                WHERE uid = ?
                """, uid);
    }
}
