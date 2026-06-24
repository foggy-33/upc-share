package cn.upcshare.downloadsite.service;

import cn.upcshare.downloadsite.support.ApiException;
import cn.upcshare.downloadsite.support.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Service
public class ContentAdminService {
    private final JdbcTemplate jdbc;
    private final AuthService auth;

    public ContentAdminService(JdbcTemplate jdbc, AuthService auth) {
        this.jdbc = jdbc;
        this.auth = auth;
    }

    public CurrentUser requireSuperAdmin(HttpServletRequest request) {
        CurrentUser user = auth.requireAdmin(request);
        if (!"foggy".equals(user.username())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "需要超级管理员权限");
        }
        return user;
    }

    public Map<String, Object> requireContentAdmin(HttpServletRequest request) {
        CurrentUser user = auth.requireLogin(request);
        if ("foggy".equals(user.username())) {
            Map<String, Object> superAdmin = new LinkedHashMap<>();
            superAdmin.put("uid", user.uid());
            superAdmin.put("username", user.username());
            superAdmin.put("group_name", "超级管理员");
            superAdmin.put("log_categories", "*");
            superAdmin.put("album_categories", "*");
            superAdmin.put("user_groups", "*");
            superAdmin.put("can_modify_user", true);
            superAdmin.put("can_enter_user_backend", true);
            superAdmin.put("can_modify_user_group", true);
            superAdmin.put("can_manage_user_template", true);
            superAdmin.put("can_manage_forum_sections", true);
            superAdmin.put("can_publish_site_notice", true);
            return superAdmin;
        }
        var rows = jdbc.queryForList("""
                SELECT u.uid,u.username,g.*
                FROM content_admin_members m
                JOIN users u ON u.uid=m.user_id
                JOIN content_admin_groups g ON g.id=m.group_id
                WHERE u.uid=? AND u.is_active=1
                LIMIT 1
                """, user.uid());
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "当前账号不是内容管理员");
        }
        return rows.get(0);
    }

    public boolean can(Map<String, Object> admin, String key) {
        Object value = admin.get(key);
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        return "1".equals(String.valueOf(value)) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    public void requirePermission(Map<String, Object> admin, String key, String message) {
        if (!can(admin, key)) throw new ApiException(HttpStatus.FORBIDDEN, message);
    }

    public Set<String> allowedValues(Map<String, Object> admin, String key) {
        String raw = String.valueOf(admin.getOrDefault(key, ""));
        Set<String> values = new LinkedHashSet<>();
        Arrays.stream(raw.split("[,，\\n\\r]+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .forEach(values::add);
        return values;
    }

    public boolean allows(Map<String, Object> admin, String key, String value) {
        Set<String> values = allowedValues(admin, key);
        return values.contains("*") || values.contains(value);
    }
}
