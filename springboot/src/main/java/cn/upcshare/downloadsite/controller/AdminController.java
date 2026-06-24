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
import org.springframework.transaction.annotation.Transactional;
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
        String where = q.filter(s -> !s.isBlank()).map(s -> " WHERE u.username LIKE ? OR u.uid LIKE ?").orElse("");
        Object[] params = q.filter(s -> !s.isBlank())
                .map(s -> new Object[]{"%" + s + "%", "%" + s + "%"})
                .orElseGet(() -> new Object[]{});
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM users u" + where, Long.class, params);
        List<Object> listParams = new ArrayList<>(List.of(params));
        listParams.add(size);
        listParams.add((page - 1) * size);
        var items = jdbc.query("""
                SELECT u.uid AS uid,
                       u.username AS username,
                       u.created_at AS created_at,
                       u.user_level AS user_level,
                       u.is_active AS is_active,
                       u.is_admin AS is_admin,
                       u.points AS points
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
            item.put("is_active", rs.getInt("is_active") != 0);
            item.put("is_admin", admin);
            item.put("user_level", configuredLevel == null || configuredLevel.isBlank() ? "auto" : configuredLevel);
            item.put("effective_level", levels.effectiveLevel(uid, username, admin, configuredLevel));
            item.put("approved_upload_count", approvedUploads);
            item.put("download_count", stats.get("count"));
            item.put("download_size_raw", stats.get("size"));
            item.put("download_size", Formatters.size(stats.get("size")));
            item.put("points", rs.getDouble("points"));
            return item;
        }, listParams.toArray());
        return FileController.pageResult(total, page, size, items);
    }

    @PostMapping("/users/{uid}/ban")
    Map<String, Object> ban(@PathVariable String uid, HttpServletRequest request) {
        auth.requireAdmin(request);
        jdbc.update("UPDATE users SET is_active=0 WHERE uid=? AND is_admin=0", uid);
        return Map.of("ok", true, "msg", "User banned");
    }

    @PostMapping("/users/{uid}/unban")
    Map<String, Object> unban(@PathVariable String uid, HttpServletRequest request) {
        auth.requireAdmin(request);
        jdbc.update("UPDATE users SET is_active=1 WHERE uid=? AND is_admin=0", uid);
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
                SET is_admin=?
                WHERE uid=? AND username<>'foggy'
                """, enabled ? 1 : 0, uid);
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
        validateUserLevel(uid, normalized);
        updateUserLevel(uid, normalized);
        return Map.of("ok", true, "msg", "User level updated");
    }

    @PostMapping("/users/levels/bulk")
    @Transactional
    Map<String, Object> setUserLevelsBulk(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        CurrentUser actor = auth.requireAdmin(request);
        if (!"foggy".equals(actor.username())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only foggy can manage user levels");
        }
        String level = stringValue(body.get("level"));
        Object rawUids = body.get("uids");
        if (!(rawUids instanceof List<?> values)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请选择需要修改的用户");
        }
        List<String> uids = values.stream()
                .map(this::stringValue)
                .filter(uid -> !uid.isBlank())
                .distinct()
                .limit(200)
                .toList();
        if (uids.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "请选择需要修改的用户");
        for (String uid : uids) validateUserLevel(uid, level);
        for (String uid : uids) updateUserLevel(uid, level);
        return Map.of("ok", true, "updated", uids.size(), "level", level);
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
                .map(s -> " WHERE username LIKE ? OR matched_words LIKE ? OR last_ip LIKE ?")
                .orElse("");
        Object[] params = q.filter(s -> !s.isBlank())
                .map(s -> new Object[]{"%" + s + "%", "%" + s + "%", "%" + s + "%"})
                .orElseGet(() -> new Object[]{});
        String base = " FROM users WHERE is_sensitive=1";
        String filtered = where.isBlank() ? base : base + " AND (" + where.substring(7) + ")";
        long total = jdbc.queryForObject("SELECT COUNT(*)" + filtered, Long.class, params);
        List<Object> listParams = new ArrayList<>(List.of(params));
        listParams.add(size);
        listParams.add((page - 1) * size);
        var rows = jdbc.queryForList("""
                SELECT uid AS id,uid AS user_id,username,last_ip AS ip_address,
                       matched_words,sensitive_source_type AS source_type,created_at
                """ + filtered + " ORDER BY uid DESC LIMIT ? OFFSET ?", listParams.toArray());
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
        String where = q.filter(s -> !s.isBlank()).map(s -> " AND (last_ip LIKE ? OR blacklist_reason LIKE ?)").orElse("");
        Object[] params = q.filter(s -> !s.isBlank()).map(s -> new Object[]{"%" + s + "%", "%" + s + "%"}).orElseGet(() -> new Object[]{});
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE is_blacklisted=1" + where, Long.class, params);
        List<Object> listParams = new ArrayList<>(List.of(params));
        listParams.add(size);
        listParams.add((page - 1) * size);
        var rows = jdbc.queryForList("""
                SELECT uid,username,last_ip AS ip_address,blacklist_reason AS reason,created_at
                FROM users WHERE is_blacklisted=1
                """ + where + " ORDER BY uid DESC LIMIT ? OFFSET ?", listParams.toArray());
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
            var commentIds = jdbc.queryForList("SELECT id FROM forum_comments WHERE post_id=?", Long.class, postId);
            for (Long commentId : commentIds) {
                jdbc.update("DELETE FROM forum_comment_likes WHERE comment_id=?", commentId);
            }
            jdbc.update("DELETE FROM forum_post_likes WHERE post_id=?", postId);
            commentsByPost += jdbc.update("DELETE FROM forum_comments WHERE post_id=?", postId);
        }
        var directCommentIds = jdbc.queryForList("SELECT id FROM forum_comments WHERE ip_address=?", Long.class, targetIp);
        for (Long commentId : directCommentIds) {
            jdbc.update("DELETE FROM forum_comment_likes WHERE comment_id=?", commentId);
        }
        int comments = jdbc.update("DELETE FROM forum_comments WHERE ip_address=?", targetIp);
        int posts = jdbc.update("DELETE FROM forum_posts WHERE ip_address=?", targetIp);
        String finalReason = reason.filter(s -> !s.isBlank()).orElse("管理员按 IP 清理内容并加入黑名单");
        jdbc.update("""
                UPDATE users
                SET is_blacklisted=1,blacklist_reason=?,is_active=0
                WHERE last_ip=? AND is_admin=0
                """, finalReason, targetIp);
        jdbc.update("""
                INSERT INTO site_audit_logs (event_type,user_id,username,ip_address,title,content_snippet,created_at)
                VALUES (?,?,?,?,?,?,?)
                """, "clear_ip", actor.uid(), actor.username(), targetIp, "按 IP 清理内容",
                "删除帖子 " + posts + " 篇，删除评论 " + (comments + commentsByPost) + " 条，并加入黑名单",
                LocalDateTime.now().toString());
        return Map.of("ok", true, "posts", posts, "comments", comments + commentsByPost);
    }

    @GetMapping("/settings/site-notice")
    Map<String, Object> siteNotice(HttpServletRequest request) {
        auth.requireAdmin(request);
        var rows = jdbc.queryForList("SELECT value FROM site_settings WHERE `key`='notice_text' LIMIT 1");
        return Map.of("value", rows.isEmpty() ? "" : String.valueOf(rows.get(0).get("value")));
    }

    @PostMapping("/settings/site-notice")
    Map<String, Object> saveSiteNotice(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        auth.requireAdmin(request);
        String value = stringValue(body.get("value"));
        if (value.length() > 2000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "公告内容不能超过 2000 个字符");
        }
        jdbc.update("""
                INSERT INTO site_settings (`key`,value,updated_at)
                VALUES ('notice_text',?,NOW())
                ON DUPLICATE KEY UPDATE value=VALUES(value),updated_at=NOW()
                """, value);
        return Map.of("ok", true, "value", value);
    }

    @GetMapping("/forum/sections")
    Map<String, Object> forumSections(HttpServletRequest request) {
        contentAdmins.requireSuperAdmin(request);
        var rows = jdbc.queryForList("""
                SELECT id,name,min_level,sort_order,is_active,created_at,
                       (SELECT COUNT(*) FROM forum_posts p WHERE p.section=forum_sections.name) post_count
                FROM forum_sections
                ORDER BY sort_order,id
                """);
        return Map.of("items", rows);
    }

    @PostMapping("/forum/sections")
    @Transactional
    Map<String, Object> saveForumSection(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        contentAdmins.requireSuperAdmin(request);
        long id = longValue(body.get("id"));
        String name = stringValue(body.get("name"));
        String minLevel = stringValue(body.get("min_level"));
        int sortOrder = intValue(body.get("sort_order"));
        int active = boolInt(body.get("is_active"));
        if (name.isBlank() || name.length() > 64) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "板块名称不能为空且不能超过 64 个字符");
        }
        if (!List.of("gray", "blue", "green", "yellow", "orange", "admin").contains(minLevel)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid forum section level");
        }
        if (id > 0) {
            var rows = jdbc.queryForList("SELECT name FROM forum_sections WHERE id=? LIMIT 1", id);
            if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Forum section not found");
            String oldName = String.valueOf(rows.get(0).get("name"));
            int changed = jdbc.update("""
                    UPDATE forum_sections
                    SET name=?,min_level=?,sort_order=?,is_active=?
                    WHERE id=?
                    """, name, minLevel, sortOrder, active, id);
            if (changed == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Forum section not found");
            if (!oldName.equals(name)) {
                jdbc.update("UPDATE forum_posts SET section=? WHERE section=?", name, oldName);
            }
        } else {
            jdbc.update("""
                    INSERT INTO forum_sections (name,min_level,sort_order,is_active,created_at)
                    VALUES (?,?,?,?,?)
                    """, name, minLevel, sortOrder, active, LocalDateTime.now().toString());
        }
        return Map.of("ok", true);
    }

    @DeleteMapping("/forum/sections/{id}")
    Map<String, Object> deleteForumSection(@PathVariable long id, HttpServletRequest request) {
        contentAdmins.requireSuperAdmin(request);
        var rows = jdbc.queryForList("SELECT name FROM forum_sections WHERE id=? LIMIT 1", id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Forum section not found");
        String name = String.valueOf(rows.get(0).get("name"));
        Long postCount = jdbc.queryForObject("SELECT COUNT(*) FROM forum_posts WHERE section=?", Long.class, name);
        if (postCount != null && postCount > 0) {
            jdbc.update("UPDATE forum_sections SET is_active=0 WHERE id=?", id);
            return Map.of("ok", true, "disabled", true);
        }
        jdbc.update("DELETE FROM forum_sections WHERE id=?", id);
        return Map.of("ok", true, "deleted", true);
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
                boolInt(body.get("can_publish_notification"))
        };
        if (id > 0) {
            jdbc.update("""
                    UPDATE content_admin_groups
                    SET group_name=?,log_categories=?,album_categories=?,user_groups=?,can_modify_user=?,
                        can_enter_user_backend=?,can_modify_user_group=?,can_manage_user_template=?,
                        can_publish_site_notice=?,can_publish_notification=?
                    WHERE id=?
                    """, append(values, id));
        } else {
            jdbc.update("""
                    INSERT INTO content_admin_groups
                    (group_name,log_categories,album_categories,user_groups,can_modify_user,can_enter_user_backend,
                     can_modify_user_group,can_manage_user_template,can_publish_site_notice,can_publish_notification,created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """, append(values, LocalDateTime.now().toString()));
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

    @PostMapping("/content-admin/members/bulk")
    @Transactional
    Map<String, Object> saveContentAdminMembers(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        contentAdmins.requireSuperAdmin(request);
        long groupId = longValue(body.get("group_id"));
        Object rawUids = body.get("uids");
        if (!(rawUids instanceof List<?> values) || groupId <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请选择用户和管理组");
        }
        List<String> uids = values.stream()
                .map(this::stringValue)
                .filter(uid -> !uid.isBlank())
                .distinct()
                .limit(100)
                .toList();
        if (uids.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "请选择用户");
        Long groupCount = jdbc.queryForObject("SELECT COUNT(*) FROM content_admin_groups WHERE id=?", Long.class, groupId);
        if (groupCount == null || groupCount == 0) throw new ApiException(HttpStatus.NOT_FOUND, "管理组不存在");
        for (String uid : uids) {
            Long userCount = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE uid=? AND username<>'foggy'", Long.class, uid);
            if (userCount == null || userCount == 0) throw new ApiException(HttpStatus.NOT_FOUND, "用户不存在：" + uid);
        }
        for (String uid : uids) {
            jdbc.update("""
                    INSERT INTO content_admin_members (user_id,group_id,created_at)
                    VALUES (?,?,?)
                    ON DUPLICATE KEY UPDATE group_id=VALUES(group_id),created_at=VALUES(created_at)
                    """, uid, groupId, LocalDateTime.now().toString());
        }
        return Map.of("ok", true, "updated", uids.size());
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

    private void validateUserLevel(String uid, String level) {
        if (!USER_LEVELS.contains(level)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid user level");
        }
        var rows = jdbc.queryForList("SELECT username,points FROM users WHERE uid=? LIMIT 1", uid);
        if (rows.isEmpty() || "foggy".equals(String.valueOf(rows.get(0).get("username")))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "User not found or cannot be changed");
        }
        if ("yellow".equals(level) || "orange".equals(level)) {
            Object rawPoints = rows.get(0).get("points");
            double currentPoints = rawPoints instanceof Number number ? number.doubleValue() : 0;
            double required = "orange".equals(level) ? 200 : 50;
            if (currentPoints < required) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        String.valueOf(rows.get(0).get("username")) + " 积分不足：" +
                                ("orange".equals(level) ? "社区之星需要至少 200 积分" : "活跃达人需要至少 50 积分"));
            }
        }
    }

    private void updateUserLevel(String uid, String level) {
        if ("admin".equals(level)) {
            jdbc.update("UPDATE users SET is_admin=1,user_level='auto' WHERE uid=? AND username<>'foggy'", uid);
        } else {
            jdbc.update("UPDATE users SET is_admin=0,user_level=? WHERE uid=? AND username<>'foggy'", level, uid);
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

    private int intValue(Object value) {
        try {
            return Integer.parseInt(stringValue(value));
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
