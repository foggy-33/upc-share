package cn.upcshare.downloadsite.controller;

import cn.upcshare.downloadsite.config.AppProperties;
import cn.upcshare.downloadsite.service.AuthService;
import cn.upcshare.downloadsite.service.ContentAdminService;
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
import org.springframework.web.bind.annotation.RequestBody;
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
    private final ContentAdminService contentAdmins;
    private final Path resourcesDir;

    public AdminController(JdbcTemplate jdbc, AuthService auth, UserLevelService levels, ContentAdminService contentAdmins, AppProperties props) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.levels = levels;
        this.contentAdmins = contentAdmins;
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

    @GetMapping("/audit/content")
    Map<String, Object> auditContent(@RequestParam Optional<String> q,
                                     @RequestParam Optional<String> ip,
                                     @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "50") int size,
                                     HttpServletRequest request) {
        auth.requireAdmin(request);
        page = Math.max(1, page);
        size = Math.min(100, Math.max(1, size));
        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        q.filter(s -> !s.isBlank()).ifPresent(s -> {
            conditions.add("(title LIKE ? OR content_snippet LIKE ? OR username LIKE ? OR ip_address LIKE ?)");
            params.add("%" + s + "%");
            params.add("%" + s + "%");
            params.add("%" + s + "%");
            params.add("%" + s + "%");
        });
        ip.filter(s -> !s.isBlank()).ifPresent(s -> {
            conditions.add("ip_address=?");
            params.add(s);
        });
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        String base = """
                SELECT 'post' AS source_type, id, user_id, username, ip_address, title,
                       LEFT(REPLACE(REPLACE(content, CHAR(10), ' '), CHAR(13), ' '), 180) AS content_snippet,
                       created_at
                FROM forum_posts
                UNION ALL
                SELECT 'comment' AS source_type, c.id, c.user_id, c.username, c.ip_address, p.title,
                       LEFT(REPLACE(REPLACE(c.content, CHAR(10), ' '), CHAR(13), ' '), 180) AS content_snippet,
                       c.created_at
                FROM forum_comments c
                LEFT JOIN forum_posts p ON p.id=c.post_id
                """;
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + base + ") audit" + where, Long.class, params.toArray());
        List<Object> listParams = new ArrayList<>(params);
        listParams.add(size);
        listParams.add((page - 1) * size);
        var rows = jdbc.queryForList("SELECT * FROM (" + base + ") audit" + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?", listParams.toArray());
        return FileController.pageResult(total, page, size, rows);
    }

    @GetMapping("/audit/events")
    Map<String, Object> auditEvents(@RequestParam Optional<String> q,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "50") int size,
                                    HttpServletRequest request) {
        auth.requireAdmin(request);
        page = Math.max(1, page);
        size = Math.min(100, Math.max(1, size));
        String where = q.filter(s -> !s.isBlank())
                .map(s -> " WHERE event_type LIKE ? OR username LIKE ? OR ip_address LIKE ? OR title LIKE ? OR content_snippet LIKE ?")
                .orElse("");
        Object[] params = q.filter(s -> !s.isBlank())
                .map(s -> new Object[]{"%" + s + "%", "%" + s + "%", "%" + s + "%", "%" + s + "%", "%" + s + "%"})
                .orElseGet(() -> new Object[]{});
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM site_audit_logs" + where, Long.class, params);
        List<Object> listParams = new ArrayList<>(List.of(params));
        listParams.add(size);
        listParams.add((page - 1) * size);
        var rows = jdbc.queryForList("SELECT * FROM site_audit_logs" + where + " ORDER BY id DESC LIMIT ? OFFSET ?", listParams.toArray());
        return FileController.pageResult(total, page, size, rows);
    }

    @GetMapping("/audit/sensitive-users")
    Map<String, Object> sensitiveUsers(@RequestParam Optional<String> q,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "50") int size,
                                       HttpServletRequest request) {
        auth.requireAdmin(request);
        page = Math.max(1, page);
        size = Math.min(100, Math.max(1, size));
        String where = q.filter(s -> !s.isBlank())
                .map(s -> " WHERE username LIKE ? OR matched_words LIKE ? OR ip_address LIKE ?")
                .orElse("");
        Object[] params = q.filter(s -> !s.isBlank())
                .map(s -> new Object[]{"%" + s + "%", "%" + s + "%", "%" + s + "%"})
                .orElseGet(() -> new Object[]{});
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM sensitive_users" + where, Long.class, params);
        List<Object> listParams = new ArrayList<>(List.of(params));
        listParams.add(size);
        listParams.add((page - 1) * size);
        var rows = jdbc.queryForList("SELECT * FROM sensitive_users" + where + " ORDER BY id DESC LIMIT ? OFFSET ?", listParams.toArray());
        return FileController.pageResult(total, page, size, rows);
    }

    @GetMapping("/audit/blacklist")
    Map<String, Object> blacklist(@RequestParam Optional<String> q,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "50") int size,
                                  HttpServletRequest request) {
        auth.requireAdmin(request);
        page = Math.max(1, page);
        size = Math.min(100, Math.max(1, size));
        String where = q.filter(s -> !s.isBlank()).map(s -> " WHERE ip_address LIKE ? OR reason LIKE ?").orElse("");
        Object[] params = q.filter(s -> !s.isBlank()).map(s -> new Object[]{"%" + s + "%", "%" + s + "%"}).orElseGet(() -> new Object[]{});
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM ip_blacklist" + where, Long.class, params);
        List<Object> listParams = new ArrayList<>(List.of(params));
        listParams.add(size);
        listParams.add((page - 1) * size);
        var rows = jdbc.queryForList("SELECT * FROM ip_blacklist" + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?", listParams.toArray());
        return FileController.pageResult(total, page, size, rows);
    }

    @PostMapping("/audit/clear-ip")
    Map<String, Object> clearIp(@RequestParam String ip,
                                @RequestParam Optional<String> reason,
                                HttpServletRequest request) {
        CurrentUser actor = auth.requireAdmin(request);
        String targetIp = ip == null ? "" : ip.trim();
        if (targetIp.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IP cannot be empty");
        }
        var postIds = jdbc.queryForList("SELECT id FROM forum_posts WHERE ip_address=?", Long.class, targetIp);
        int commentsByPost = 0;
        for (Long postId : postIds) {
            commentsByPost += jdbc.update("DELETE FROM forum_comments WHERE post_id=?", postId);
        }
        int comments = jdbc.update("DELETE FROM forum_comments WHERE ip_address=?", targetIp);
        int posts = jdbc.update("DELETE FROM forum_posts WHERE ip_address=?", targetIp);
        String finalReason = reason.filter(s -> !s.isBlank()).orElse("管理员按 IP 清理内容并加入黑名单");
        jdbc.update("""
                INSERT INTO ip_blacklist (ip_address,reason,created_at)
                VALUES (?,?,?)
                ON DUPLICATE KEY UPDATE reason=VALUES(reason), created_at=VALUES(created_at)
                """, targetIp, finalReason, LocalDateTime.now().toString());
        jdbc.update("""
                INSERT INTO site_audit_logs (event_type,user_id,username,ip_address,title,content_snippet,created_at)
                VALUES (?,?,?,?,?,?,?)
                """, "clear_ip", actor.uid(), actor.username(), targetIp, "按 IP 清理内容",
                "删除帖子 " + posts + " 篇，删除评论 " + (comments + commentsByPost) + " 条，并加入黑名单",
                LocalDateTime.now().toString());
        return Map.of("ok", true, "posts", posts, "comments", comments + commentsByPost);
    }

    @GetMapping("/content-admin/groups")
    Map<String, Object> contentAdminGroups(HttpServletRequest request) {
        contentAdmins.requireSuperAdmin(request);
        var groups = jdbc.queryForList("SELECT * FROM content_admin_groups ORDER BY id DESC");
        return Map.of("items", groups);
    }

    @PostMapping("/content-admin/groups")
    Map<String, Object> saveContentAdminGroup(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        contentAdmins.requireSuperAdmin(request);
        long id = longValue(body.get("id"));
        String name = stringValue(body.get("group_name"));
        if (name.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "组名不能为空");
        String now = LocalDateTime.now().toString();
        Object[] values = {
                name,
                stringValue(body.get("log_categories")),
                stringValue(body.get("album_categories")),
                stringValue(body.get("user_groups")),
                boolInt(body.get("can_modify_user")),
                boolInt(body.get("can_enter_user_backend")),
                boolInt(body.get("can_modify_user_group")),
                boolInt(body.get("can_manage_user_template")),
                boolInt(body.get("can_publish_site_notice")),
                boolInt(body.get("can_publish_notification")),
                now
        };
        if (id > 0) {
            jdbc.update("""
                    UPDATE content_admin_groups
                    SET group_name=?,log_categories=?,album_categories=?,user_groups=?,can_modify_user=?,
                        can_enter_user_backend=?,can_modify_user_group=?,can_manage_user_template=?,
                        can_publish_site_notice=?,can_publish_notification=?,updated_at=?
                    WHERE id=?
                    """, append(values, id));
        } else {
            jdbc.update("""
                    INSERT INTO content_admin_groups
                    (group_name,log_categories,album_categories,user_groups,can_modify_user,can_enter_user_backend,
                     can_modify_user_group,can_manage_user_template,can_publish_site_notice,can_publish_notification,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    """, append(values, now));
        }
        return Map.of("ok", true);
    }

    @GetMapping("/content-admin/members")
    Map<String, Object> contentAdminMembers(HttpServletRequest request) {
        contentAdmins.requireSuperAdmin(request);
        var members = jdbc.queryForList("""
                SELECT u.uid,u.username,u.is_active,m.group_id,g.group_name,m.created_at
                FROM content_admin_members m
                JOIN users u ON u.uid=m.user_id
                JOIN content_admin_groups g ON g.id=m.group_id
                ORDER BY m.created_at DESC
                """);
        return Map.of("items", members);
    }

    @PostMapping("/content-admin/members")
    Map<String, Object> saveContentAdminMember(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        contentAdmins.requireSuperAdmin(request);
        String uid = stringValue(body.get("uid"));
        long groupId = longValue(body.get("group_id"));
        if (uid.isBlank() || groupId <= 0) throw new ApiException(HttpStatus.BAD_REQUEST, "请选择用户和管理组");
        Long userCount = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE uid=?", Long.class, uid);
        Long groupCount = jdbc.queryForObject("SELECT COUNT(*) FROM content_admin_groups WHERE id=?", Long.class, groupId);
        if (userCount == null || userCount == 0 || groupCount == null || groupCount == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "用户或管理组不存在");
        }
        jdbc.update("""
                INSERT INTO content_admin_members (user_id,group_id,created_at)
                VALUES (?,?,?)
                ON DUPLICATE KEY UPDATE group_id=VALUES(group_id), created_at=VALUES(created_at)
                """, uid, groupId, LocalDateTime.now().toString());
        return Map.of("ok", true);
    }

    @DeleteMapping("/content-admin/members/{uid}")
    Map<String, Object> deleteContentAdminMember(@PathVariable String uid, HttpServletRequest request) {
        contentAdmins.requireSuperAdmin(request);
        jdbc.update("DELETE FROM content_admin_members WHERE user_id=?", uid);
        return Map.of("ok", true);
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

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private int boolInt(Object value) {
        if (value instanceof Boolean b) return b ? 1 : 0;
        if (value instanceof Number n) return n.intValue() != 0 ? 1 : 0;
        String text = stringValue(value).toLowerCase();
        return ("1".equals(text) || "true".equals(text) || "yes".equals(text) || "on".equals(text)) ? 1 : 0;
    }

    private long longValue(Object value) {
        try {
            return Long.parseLong(stringValue(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private Object[] append(Object[] values, Object tail) {
        Object[] result = new Object[values.length + 1];
        System.arraycopy(values, 0, result, 0, values.length);
        result[values.length] = tail;
        return result;
    }

}
