package cn.upcshare.downloadsite.controller;

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
    private final JdbcTemplate jdbc;
    private final ContentAdminService contentAdmins;

    public ContentAdminController(JdbcTemplate jdbc, ContentAdminService contentAdmins) {
        this.jdbc = jdbc;
        this.contentAdmins = contentAdmins;
    }

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

    @GetMapping("/forum/sections")
    Map<String, Object> forumSections(HttpServletRequest request) {
        var admin = contentAdmins.requireContentAdmin(request);
        contentAdmins.requirePermission(admin, "can_manage_forum_sections", "无权管理论坛板块");
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

    @DeleteMapping("/forum/sections/{id}")
    Map<String, Object> deleteForumSection(@PathVariable long id, HttpServletRequest request) {
        var admin = contentAdmins.requireContentAdmin(request);
        contentAdmins.requirePermission(admin, "can_manage_forum_sections", "无权管理论坛板块");
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
