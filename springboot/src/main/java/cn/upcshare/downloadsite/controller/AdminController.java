package cn.upcshare.downloadsite.controller;

import cn.upcshare.downloadsite.config.AppProperties;
import cn.upcshare.downloadsite.service.AuthService;
import cn.upcshare.downloadsite.service.UserLevelService;
import cn.upcshare.downloadsite.support.ApiException;
import cn.upcshare.downloadsite.support.CurrentUser;
import cn.upcshare.downloadsite.support.Formatters;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private static final List<String> USER_LEVELS = List.of("auto", "gray", "blue", "green", "yellow", "orange", "admin");

    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final UserLevelService levels;
    private final Path resourcesDir;

    public AdminController(JdbcTemplate jdbc, AuthService auth, UserLevelService levels, AppProperties props) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.levels = levels;
        this.resourcesDir = Paths.get(props.getResourcesDir()).toAbsolutePath().normalize();
    }

    @GetMapping("/files")
    Map<String, Object> files(@RequestParam Optional<String> status,
                              @RequestParam Optional<String> q,
                              @RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "50") int size,
                              HttpServletRequest request) {
        auth.requireAdmin(request);
        page = Math.max(1, page);
        size = Math.min(200, Math.max(1, size));
        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        status.filter(s -> !s.isBlank()).ifPresent(s -> {
            conditions.add("status=?");
            params.add(s);
        });
        q.filter(s -> !s.isBlank()).ifPresent(s -> {
            conditions.add("(original_name LIKE ? OR category LIKE ? OR sub_category LIKE ?)");
            params.add("%" + s + "%");
            params.add("%" + s + "%");
            params.add("%" + s + "%");
        });
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM files" + where, Long.class, params.toArray());
        List<Object> listParams = new ArrayList<>(params);
        listParams.add(size);
        listParams.add((page - 1) * size);
        var rows = jdbc.queryForList("SELECT * FROM files" + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?", listParams.toArray());
        return FileController.pageResult(total, page, size, rows.stream().map(Formatters::fileDto).toList());
    }

    @PostMapping("/approve/{id}")
    Map<String, Object> approve(@PathVariable String id, HttpServletRequest request) {
        auth.requireAdmin(request);
        jdbc.update("UPDATE files SET status='approved' WHERE id=?", id);
        return Map.of("ok", true, "msg", "File approved");
    }

    @PostMapping("/reject/{id}")
    Map<String, Object> reject(@PathVariable String id, HttpServletRequest request) throws Exception {
        auth.requireAdmin(request);
        deleteFileAndRow(id);
        return Map.of("ok", true, "msg", "File rejected and deleted");
    }

    @DeleteMapping("/files/{id}")
    Map<String, Object> delete(@PathVariable String id, HttpServletRequest request) throws Exception {
        auth.requireAdmin(request);
        deleteFileAndRow(id);
        return Map.of("ok", true, "msg", "File deleted");
    }

    @GetMapping("/users")
    Map<String, Object> users(@RequestParam Optional<String> q,
                              @RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "50") int size,
                              HttpServletRequest request) {
        auth.requireAdmin(request);
        page = Math.max(1, page);
        size = Math.min(200, Math.max(1, size));
        String where = q.filter(s -> !s.isBlank()).map(s -> " WHERE u.username LIKE ?").orElse("");
        Object[] params = q.filter(s -> !s.isBlank()).map(s -> new Object[]{"%" + s + "%"}).orElseGet(() -> new Object[]{});
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM users u" + where, Long.class, params);
        List<Object> listParams = new ArrayList<>(List.of(params));
        listParams.add(size);
        listParams.add((page - 1) * size);
        var items = jdbc.query("""
                SELECT u.uid AS uid,
                       u.username AS username,
                       u.created_at AS created_at,
                       u.updated_at AS updated_at,
                       u.user_level AS user_level,
                       u.is_active AS is_active,
                       u.is_admin AS is_admin
                FROM users u
                %s
                ORDER BY u.uid DESC LIMIT ? OFFSET ?
                """.formatted(where), (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            String uid = rs.getString("uid");
            String rawUsername = rs.getString("username");
            String username = rawUsername == null ? "" : String.valueOf(rawUsername).trim();
            Map<String, Long> stats = userDownloadStats(uid);
            long approvedUploads = levels.approvedUploadCount(username);
            boolean admin = rs.getInt("is_admin") != 0;
            String configuredLevel = rs.getString("user_level");
            item.put("uid", uid);
            item.put("username", username);
            item.put("created_at", rs.getString("created_at"));
            item.put("updated_at", rs.getString("updated_at"));
            item.put("is_active", rs.getInt("is_active") != 0);
            item.put("is_admin", admin);
            item.put("user_level", configuredLevel == null || configuredLevel.isBlank() ? "auto" : configuredLevel);
            item.put("effective_level", levels.effectiveLevel(uid, username, admin, configuredLevel));
            item.put("approved_upload_count", approvedUploads);
            item.put("download_count", stats.get("count"));
            item.put("download_size_raw", stats.get("size"));
            item.put("download_size", Formatters.size(stats.get("size")));
            return item;
        }, listParams.toArray());
        return FileController.pageResult(total, page, size, items);
    }

    @PostMapping("/users/{uid}/ban")
    Map<String, Object> ban(@PathVariable String uid, HttpServletRequest request) {
        auth.requireAdmin(request);
        jdbc.update("UPDATE users SET is_active=0, updated_at=? WHERE uid=? AND is_admin=0", LocalDateTime.now().toString(), uid);
        return Map.of("ok", true, "msg", "User banned");
    }

    @PostMapping("/users/{uid}/unban")
    Map<String, Object> unban(@PathVariable String uid, HttpServletRequest request) {
        auth.requireAdmin(request);
        jdbc.update("UPDATE users SET is_active=1, updated_at=? WHERE uid=? AND is_admin=0", LocalDateTime.now().toString(), uid);
        return Map.of("ok", true, "msg", "User unbanned");
    }

    @PostMapping("/users/{uid}/admin")
    Map<String, Object> setAdmin(@PathVariable String uid,
                                 @RequestParam boolean enabled,
                                 HttpServletRequest request) {
        CurrentUser actor = auth.requireAdmin(request);
        if (!"foggy".equals(actor.username())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only foggy can manage administrators");
        }
        int changed = jdbc.update("""
                UPDATE users
                SET is_admin=?, updated_at=?
                WHERE uid=? AND username<>'foggy'
                """, enabled ? 1 : 0, LocalDateTime.now().toString(), uid);
        if (changed == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "User not found or cannot be changed");
        }
        return Map.of("ok", true, "msg", enabled ? "Administrator granted" : "Administrator revoked");
    }

    @PostMapping("/users/{uid}/level")
    Map<String, Object> setUserLevel(@PathVariable String uid,
                                     @RequestParam String level,
                                     HttpServletRequest request) {
        CurrentUser actor = auth.requireAdmin(request);
        if (!"foggy".equals(actor.username())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only foggy can manage user levels");
        }
        String normalized = level == null ? "" : level.trim();
        if (!USER_LEVELS.contains(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid user level");
        }
        if ("admin".equals(normalized)) {
            int changed = jdbc.update("""
                    UPDATE users
                    SET is_admin=1, user_level='auto', updated_at=?
                    WHERE uid=? AND username<>'foggy'
                    """, LocalDateTime.now().toString(), uid);
            if (changed == 0) throw new ApiException(HttpStatus.NOT_FOUND, "User not found or cannot be changed");
            return Map.of("ok", true, "msg", "User level updated");
        }
        int changed = jdbc.update("""
                UPDATE users
                SET is_admin=0, user_level=?, updated_at=?
                WHERE uid=? AND username<>'foggy'
                """, normalized, LocalDateTime.now().toString(), uid);
        if (changed == 0) throw new ApiException(HttpStatus.NOT_FOUND, "User not found or cannot be changed");
        return Map.of("ok", true, "msg", "User level updated");
    }

    private void deleteFileAndRow(String id) throws Exception {
        var rows = jdbc.queryForList("SELECT file_path FROM files WHERE id=?", id);
        if (!rows.isEmpty()) {
            Path file = resourcesDir.resolve(String.valueOf(rows.get(0).get("file_path"))).normalize();
            if (file.startsWith(resourcesDir)) Files.deleteIfExists(file);
        }
        jdbc.update("DELETE FROM files WHERE id=?", id);
    }

    private Map<String, Long> userDownloadStats(String uid) {
        try {
            return jdbc.query("""
                    SELECT COUNT(*) AS download_count, COALESCE(SUM(file_size),0) AS download_size_raw
                    FROM download_log
                    WHERE user_id=?
                    """, rs -> {
                if (!rs.next()) return Map.of("count", 0L, "size", 0L);
                return Map.of("count", rs.getLong("download_count"), "size", rs.getLong("download_size_raw"));
            }, uid);
        } catch (Exception ignored) {
            return Map.of("count", 0L, "size", 0L);
        }
    }

}
