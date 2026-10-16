package cn.upcshare.downloadsite.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

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

    @Transactional
    public void recordDownload(String downloaderUid, String fileId, long fileSize, String nodeName) {
        jdbc.update("UPDATE files SET download_count=download_count+1 WHERE id=?", fileId);
        jdbc.update("""
                INSERT INTO download_log (user_id,file_id,file_size,downloaded_at,event_id,source_node,cloud_synced_at)
                VALUES (?,?,?,?,UUID(),?,'')
                """, downloaderUid, fileId, fileSize, LocalDateTime.now().toString(), nodeName);
        rewardDownload(downloaderUid);
        int rewarded = jdbc.update("""
                UPDATE users u JOIN files f ON f.uploader=u.username
                SET u.points=u.points+0.1 WHERE f.id=? AND f.status='approved'
                """, fileId);
        if (rewarded > 0) {
            jdbc.update("UPDATE files SET rewarded_download_count=rewarded_download_count+1 WHERE id=?", fileId);
        }
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
