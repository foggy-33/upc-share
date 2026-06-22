package cn.upcshare.downloadsite.controller;

import cn.upcshare.downloadsite.service.ContentAdminService;
import cn.upcshare.downloadsite.support.ApiException;
import cn.upcshare.downloadsite.support.Formatters;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
        result.put("can_publish_site_notice", contentAdmins.can(admin, "can_publish_site_notice"));
        result.put("can_publish_notification", contentAdmins.can(admin, "can_publish_notification"));
        return result;
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

    @PostMapping("/settings/notification")
    Map<String, Object> notification(@RequestBody Map<String, String> body, HttpServletRequest request) {
        var admin = contentAdmins.requireContentAdmin(request);
        contentAdmins.requirePermission(admin, "can_publish_notification", "无权发布通知");
        saveSetting("notification_text", body.getOrDefault("value", ""));
        return Map.of("ok", true);
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
}
