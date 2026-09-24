package cn.upcshare.downloadsite.controller;

import cn.upcshare.downloadsite.config.AppProperties;
import cn.upcshare.downloadsite.service.ContentAdminService;
import cn.upcshare.downloadsite.support.ApiException;
import cn.upcshare.downloadsite.support.Formatters;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RestController
@RequestMapping("/api/content-admin")
public class ContentAdminController {
    // 内容管理员后台按权限范围读写资料、帖子、用户和论坛板块数据。
    private final JdbcTemplate jdbc;
    private final ContentAdminService contentAdmins;
    private final Path resourcesDir;

    public ContentAdminController(JdbcTemplate jdbc, ContentAdminService contentAdmins, AppProperties props) {
        this.jdbc = jdbc;
        this.contentAdmins = contentAdmins;
        this.resourcesDir = Paths.get(props.getResourcesDir()).toAbsolutePath().normalize();
    }

    // 内容管理员当前身份接口：读取登录用户所属分组和权限开关，决定前端显示哪些后台模块。
    @GetMapping("/me")
    Map<String, Object> me(HttpServletRequest request) {
        var admin = contentAdmins.requireContentAdmin(request);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uid", admin.get("uid"));
        result.put("username", admin.get("username"));
        result.put("group_name", admin.get("group_name"));
        result.put("log_categories", admin.get("log_categories"));
        result.put("album_categories", admin.get("album_categories"));
        result.put("user_groups", admin.get("user_groups"));
        result.put("can_modify_user", contentAdmins.can(admin, "can_modify_user"));
        result.put("can_enter_user_backend", contentAdmins.can(admin, "can_enter_user_backend"));
        result.put("can_modify_user_group", contentAdmins.can(admin, "can_modify_user_group"));
        result.put("can_manage_user_template", contentAdmins.can(admin, "can_manage_user_template"));
        result.put("can_manage_forum_sections", contentAdmins.can(admin, "can_manage_forum_sections"));
        result.put("can_publish_site_notice", contentAdmins.can(admin, "can_publish_site_notice"));
        return result;
    }

    // 内容管理员板块列表接口：要求 can_manage_forum_sections 权限，读取 forum_sections 并统计帖子数。
    @GetMapping("/forum/sections")
    Map<String, Object> forumSections(HttpServletRequest request) {
        var admin = contentAdmins.requireContentAdmin(request);
        contentAdmins.requirePermission(admin, "can_manage_forum_sections", "无权管理论坛板块");
        // 内容管理员看到的板块管理数据来自 forum_sections，并附带每个板块的帖子数量。
        var rows = jdbc.queryForList("""
                SELECT id,name,min_level,sort_order,is_active,created_at,
                       (SELECT COUNT(*) FROM forum_posts p WHERE p.section=forum_sections.name) post_count
                FROM forum_sections
                ORDER BY sort_order,id
                """);
        return Map.of("items", rows);
    }

    // 内容管理员保存板块接口：新增或更新 forum_sections，改名时同步 forum_posts.section。
    @PostMapping("/forum/sections")
    @Transactional
    Map<String, Object> saveForumSection(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        var admin = contentAdmins.requireContentAdmin(request);
        contentAdmins.requirePermission(admin, "can_manage_forum_sections", "无权管理论坛板块");
        long id = longValue(body.get("id"));
        String name = stringValue(body.get("name"));
        String minLevel = stringValue(body.get("min_level"));
        int active = boolInt(body.get("is_active"));
        if (name.isBlank() || name.length() > 64) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "板块名称不能为空且不能超过 64 个字符");
        }
        if (!List.of("gray", "blue", "green", "yellow", "orange", "admin").contains(minLevel)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid forum section level");
        }
        if (id > 0) {
            // 板块改名时同步 forum_posts.section，避免旧帖子还挂在旧板块名下。
            var rows = jdbc.queryForList("SELECT name,sort_order FROM forum_sections WHERE id=? LIMIT 1", id);
            if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Forum section not found");
            String oldName = String.valueOf(rows.get(0).get("name"));
            int sortOrder = intValue(rows.get(0).get("sort_order"));
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
            Integer nextSort = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(sort_order), 0) + 10 FROM forum_sections", Integer.class);
            jdbc.update("""
                    INSERT INTO forum_sections (name,min_level,sort_order,is_active,created_at)
                    VALUES (?,?,?,?,?)
                    """, name, minLevel, nextSort == null ? 100 : nextSort, active, LocalDateTime.now().toString());
        }
        return Map.of("ok", true);
    }

    // 内容管理员删除板块接口：无帖子时删除，有帖子时停用，避免历史帖子丢失板块归属。
    @DeleteMapping("/forum/sections/{id}")
    Map<String, Object> deleteForumSection(@PathVariable long id, HttpServletRequest request) {
        var admin = contentAdmins.requireContentAdmin(request);
        contentAdmins.requirePermission(admin, "can_manage_forum_sections", "无权管理论坛板块");
        var rows = jdbc.queryForList("SELECT name FROM forum_sections WHERE id=? LIMIT 1", id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Forum section not found");
        String name = String.valueOf(rows.get(0).get("name"));
        Long postCount = jdbc.queryForObject("SELECT COUNT(*) FROM forum_posts WHERE section=?", Long.class, name);
        if (postCount != null && postCount > 0) {
            // 有历史帖子的板块只停用不删除，保留帖子与板块的历史关系。
            jdbc.update("UPDATE forum_sections SET is_active=0 WHERE id=?", id);
            return Map.of("ok", true, "disabled", true);
        }
        jdbc.update("DELETE FROM forum_sections WHERE id=?", id);
        return Map.of("ok", true, "deleted", true);
    }

    // 内容管理员文件列表接口：按分组允许的 album_categories 限制 files.category 查询范围。
    @GetMapping("/files")
    Map<String, Object> files(@RequestParam Optional<String> q,
                              @RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "50") int size,
                              HttpServletRequest request) {
        var admin = contentAdmins.requireContentAdmin(request);
        page = Math.max(1, page);
        size = Math.min(100, Math.max(1, size));
        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        addAllowedCondition(conditions, params, "category", contentAdmins.allowedValues(admin, "album_categories"));
        q.filter(s -> !s.isBlank()).ifPresent(s -> {
            conditions.add("(original_name LIKE ? OR description LIKE ? OR category LIKE ?)");
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

    // 内容管理员文件通过接口：确认资料分类在授权范围内后，把 files.status 更新为 approved。
    @PostMapping("/files/{id}/approve")
    Map<String, Object> approveFile(@PathVariable String id, HttpServletRequest request) {
        var admin = contentAdmins.requireContentAdmin(request);
        requireFileScope(admin, id);
        int changed = jdbc.update("UPDATE files SET status='approved' WHERE id=?", id);
        if (changed == 0) throw new ApiException(HttpStatus.NOT_FOUND, "File not found");
        return Map.of("ok", true, "msg", "File approved");
    }

    // 内容管理员文件驳回接口：确认授权范围后删除文件和 files 表记录。
    @PostMapping("/files/{id}/reject")
    Map<String, Object> rejectFile(@PathVariable String id, HttpServletRequest request) throws Exception {
        var admin = contentAdmins.requireContentAdmin(request);
        requireFileScope(admin, id);
        deleteFileAndRow(id);
        return Map.of("ok", true, "msg", "File rejected and deleted");
    }

    // 内容管理员文件删除接口：删除已存在资料，受 album_categories 范围限制。
    @DeleteMapping("/files/{id}")
    Map<String, Object> deleteFile(@PathVariable String id, HttpServletRequest request) throws Exception {
        var admin = contentAdmins.requireContentAdmin(request);
        requireFileScope(admin, id);
        deleteFileAndRow(id);
        return Map.of("ok", true, "msg", "File deleted");
    }

    // 内容管理员帖子列表接口：按分组允许的 log_categories 限制 forum_posts.section 查询范围。
    @GetMapping("/posts")
    Map<String, Object> posts(@RequestParam Optional<String> q,
                              @RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "50") int size,
                              HttpServletRequest request) {
        var admin = contentAdmins.requireContentAdmin(request);
        page = Math.max(1, page);
        size = Math.min(100, Math.max(1, size));
        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        addAllowedCondition(conditions, params, "section", contentAdmins.allowedValues(admin, "log_categories"));
        q.filter(s -> !s.isBlank()).ifPresent(s -> {
            conditions.add("(title LIKE ? OR content LIKE ? OR username LIKE ?)");
            params.add("%" + s + "%");
            params.add("%" + s + "%");
            params.add("%" + s + "%");
        });
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM forum_posts" + where, Long.class, params.toArray());
        List<Object> listParams = new ArrayList<>(params);
        listParams.add(size);
        listParams.add((page - 1) * size);
        var rows = jdbc.queryForList("""
                SELECT id,user_id,username,section,title,
                       LEFT(REPLACE(REPLACE(content, CHAR(10), ' '), CHAR(13), ' '), 180) content_snippet,
                       ip_address,created_at
                FROM forum_posts
                """ + where + " ORDER BY id DESC LIMIT ? OFFSET ?", listParams.toArray());
        return FileController.pageResult(total, page, size, rows);
    }

    // 内容管理员帖子删除接口：确认板块授权后，清理帖子点赞、评论点赞、评论和 forum_posts。
    @DeleteMapping("/posts/{id}")
    @Transactional
    Map<String, Object> deletePost(@PathVariable long id, HttpServletRequest request) {
        var admin = contentAdmins.requireContentAdmin(request);
        requirePostScope(admin, id);
        var commentIds = jdbc.queryForList("SELECT id FROM forum_comments WHERE post_id=?", Long.class, id);
        for (Long commentId : commentIds) {
            jdbc.update("DELETE FROM forum_comment_likes WHERE comment_id=?", commentId);
        }
        jdbc.update("DELETE FROM forum_post_likes WHERE post_id=?", id);
        jdbc.update("DELETE FROM forum_comments WHERE post_id=?", id);
        int changed = jdbc.update("DELETE FROM forum_posts WHERE id=?", id);
        if (changed == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Post not found");
        return Map.of("ok", true, "msg", "Post deleted");
    }

    // 内容管理员用户列表接口：要求 can_enter_user_backend 权限，并按允许的用户等级过滤 users。
    @GetMapping("/users")
    Map<String, Object> users(@RequestParam Optional<String> q,
                              @RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "50") int size,
                              HttpServletRequest request) {
        var admin = contentAdmins.requireContentAdmin(request);
        contentAdmins.requirePermission(admin, "can_enter_user_backend", "无权进入用户后台");
        page = Math.max(1, page);
        size = Math.min(100, Math.max(1, size));
        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        addAllowedCondition(conditions, params, "user_level", contentAdmins.allowedValues(admin, "user_groups"));
        q.filter(s -> !s.isBlank()).ifPresent(s -> {
            conditions.add("(username LIKE ? OR uid LIKE ?)");
            params.add("%" + s + "%");
            params.add("%" + s + "%");
        });
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM users" + where, Long.class, params.toArray());
        List<Object> listParams = new ArrayList<>(params);
        listParams.add(size);
        listParams.add((page - 1) * size);
        var rows = jdbc.queryForList("""
                SELECT uid,username,user_level,is_active,is_admin,created_at,last_ip
                FROM users
                """ + where + " ORDER BY uid DESC LIMIT ? OFFSET ?", listParams.toArray());
        return FileController.pageResult(total, page, size, rows);
    }

    // 内容管理员用户状态接口：要求 can_modify_user 权限，用于锁定或解锁授权范围内用户。
    @PostMapping("/users/{uid}/status")
    Map<String, Object> setUserStatus(@PathVariable String uid,
                                      @RequestParam boolean active,
                                      HttpServletRequest request) {
        var admin = contentAdmins.requireContentAdmin(request);
        contentAdmins.requirePermission(admin, "can_modify_user", "无权修改用户");
        var rows = jdbc.queryForList("SELECT user_level,is_admin FROM users WHERE uid=? LIMIT 1", uid);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "用户不存在");
        if (((Number) rows.get(0).get("is_admin")).intValue() != 0) {
            throw new ApiException(HttpStatus.FORBIDDEN, "不能修改管理员账号");
        }
        String level = String.valueOf(rows.get(0).get("user_level"));
        if (!contentAdmins.allows(admin, "user_groups", level)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权管理该用户组");
        }
        jdbc.update("UPDATE users SET is_active=? WHERE uid=?", active ? 1 : 0, uid);
        return Map.of("ok", true);
    }

    // 内容管理员公告发布接口：要求 can_publish_site_notice 权限，保存 site_settings.notice_text。
    @PostMapping("/settings/site-notice")
    Map<String, Object> siteNotice(@RequestBody Map<String, String> body, HttpServletRequest request) {
        var admin = contentAdmins.requireContentAdmin(request);
        contentAdmins.requirePermission(admin, "can_publish_site_notice", "无权发布站点公告");
        String value = body.getOrDefault("value", "").trim();
        if (value.length() > 2000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "公告内容不能超过 2000 个字符");
        }
        saveSetting("notice_text", value);
        return Map.of("ok", true);
    }

    // 内容管理员公告读取接口：读取 site_settings.notice_text，供内容管理员后台回显。
    @GetMapping("/settings/site-notice")
    Map<String, Object> currentSiteNotice(HttpServletRequest request) {
        var admin = contentAdmins.requireContentAdmin(request);
        contentAdmins.requirePermission(admin, "can_publish_site_notice", "无权管理站点公告");
        var rows = jdbc.queryForList("SELECT value FROM site_settings WHERE `key`='notice_text' LIMIT 1");
        return Map.of("value", rows.isEmpty() ? "" : String.valueOf(rows.get(0).get("value")));
    }

    private void saveSetting(String key, String value) {
        jdbc.update("""
                INSERT INTO site_settings (`key`,value,updated_at)
                VALUES (?,?,NOW())
                ON DUPLICATE KEY UPDATE value=VALUES(value),updated_at=NOW()
                """, key, value == null ? "" : value.trim());
    }

    private void requireFileScope(Map<String, Object> admin, String id) {
        var rows = jdbc.queryForList("SELECT category FROM files WHERE id=? LIMIT 1", id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "File not found");
        String category = String.valueOf(rows.get(0).get("category"));
        if (!contentAdmins.allows(admin, "album_categories", category)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权管理该资料分类");
        }
    }

    private void requirePostScope(Map<String, Object> admin, long id) {
        var rows = jdbc.queryForList("SELECT section FROM forum_posts WHERE id=? LIMIT 1", id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Post not found");
        String section = String.valueOf(rows.get(0).get("section"));
        if (!contentAdmins.allows(admin, "log_categories", section)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权管理该论坛板块");
        }
    }

    private void deleteFileAndRow(String id) throws Exception {
        var rows = jdbc.queryForList("SELECT file_path FROM files WHERE id=?", id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "File not found");
        Path file = resourcesDir.resolve(String.valueOf(rows.get(0).get("file_path"))).normalize();
        if (file.startsWith(resourcesDir)) Files.deleteIfExists(file);
        jdbc.update("DELETE FROM files WHERE id=?", id);
    }

    private void addAllowedCondition(List<String> conditions, List<Object> params, String column, Set<String> allowed) {
        if (allowed.contains("*")) return;
        if (allowed.isEmpty()) {
            conditions.add("1=0");
            return;
        }
        conditions.add(column + " IN (" + "?,".repeat(allowed.size()).replaceAll(",$", "") + ")");
        params.addAll(allowed);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private long longValue(Object value) {
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    private int intValue(Object value) {
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    private int boolInt(Object value) {
        if (value instanceof Boolean b) return b ? 1 : 0;
        if (value instanceof Number n) return n.intValue() == 0 ? 0 : 1;
        String text = String.valueOf(value);
        return "true".equalsIgnoreCase(text) || "1".equals(text) ? 1 : 0;
    }
}
