package cn.upcshare.downloadsite.service;

import cn.upcshare.downloadsite.support.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class ModerationService {
    private static final Set<String> DEFAULT_SENSITIVE_WORDS = Set.of("敏感词", "广告", "诈骗", "博彩", "色情", "违法");
    private static final int REGISTER_LIMIT_PER_HOUR = 5;
    private static final int CONTENT_LIMIT_PER_10_MINUTES = 12;

    private final JdbcTemplate jdbc;

    public ModerationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String clientIp(HttpServletRequest request) {
        String[] headers = {"X-Forwarded-For", "X-Real-IP", "CF-Connecting-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP"};
        for (String header : headers) {
            String value = request.getHeader(header);
            if (value == null || value.isBlank() || "unknown".equalsIgnoreCase(value)) continue;
            return value.split(",")[0].trim();
        }
        return request.getRemoteAddr() == null ? "" : request.getRemoteAddr();
    }

    public void rejectBlacklistedIp(String ip) {
        if (ip == null || ip.isBlank()) return;
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE last_ip=? AND is_blacklisted=1", Long.class, ip);
        if (count != null && count > 0) {
            throw new ApiException(HttpStatus.FORBIDDEN, "当前 IP 已被网站黑名单拦截");
        }
    }

    public void recordEvent(String eventType, String userId, String username, String ip, String title, String content) {
        jdbc.update("""
                INSERT INTO site_audit_logs (event_type,user_id,username,ip_address,title,content_snippet,created_at)
                VALUES (?,?,?,?,?,?,?)
                """, safe(eventType), safe(userId), safe(username), safe(ip), safe(title), snippet(content), now());
    }

    public void recordRegistration(String userId, String username, String ip) {
        recordEvent("register", userId, username, ip, "用户注册", username);
        Long recent = jdbc.queryForObject("""
                SELECT COUNT(*) FROM site_audit_logs
                WHERE event_type='register' AND ip_address=? AND created_at>=?
                """, Long.class, ip, LocalDateTime.now().minusHours(1).toString());
        if (recent != null && recent >= REGISTER_LIMIT_PER_HOUR) {
            jdbc.update("""
                    UPDATE users
                    SET is_active=0,is_blacklisted=1,blacklist_reason=?
                    WHERE last_ip=? AND is_admin=0
                    """, "同一 IP 一小时内频繁注册", ip);
            recordEvent("auto_lock", userId, username, ip, "频繁注册自动锁定", "同一 IP 一小时内注册次数过多");
        }
    }

    public void inspectContent(String type, String userId, String username, String ip, String title, String content) {
        rejectBlacklistedIp(ip);
        Set<String> matched = matchedSensitiveWords(title + "\n" + content);
        if (!matched.isEmpty()) {
            String words = String.join(",", matched);
            jdbc.update("""
                    UPDATE users
                    SET is_sensitive=1,matched_words=?,sensitive_source_type=?,last_ip=?
                    WHERE uid=?
                    """, words, safe(type), safe(ip), safe(userId));
            recordEvent("sensitive_" + type, userId, username, ip, title, "命中敏感词：" + words + "；" + content);
        }

        Long recent = jdbc.queryForObject("""
                SELECT COUNT(*) FROM site_audit_logs
                WHERE user_id=? AND event_type IN ('post','comment') AND created_at>=?
                """, Long.class, userId, LocalDateTime.now().minusMinutes(10).toString());
        if (recent != null && recent >= CONTENT_LIMIT_PER_10_MINUTES) {
            jdbc.update("UPDATE users SET is_active=0 WHERE uid=? AND is_admin=0", userId);
            recordEvent("auto_lock", userId, username, ip, "频繁发表自动锁定", "十分钟内发帖或评论次数过多");
            throw new ApiException(HttpStatus.FORBIDDEN, "发表过于频繁，账号已被系统自动锁定");
        }
    }

    public Set<String> sensitiveWords() {
        Set<String> words = new LinkedHashSet<>(DEFAULT_SENSITIVE_WORDS);
        try {
            var rows = jdbc.queryForList("SELECT value FROM site_settings WHERE `key`='sensitive_words' LIMIT 1");
            if (!rows.isEmpty()) {
                String value = String.valueOf(rows.get(0).getOrDefault("value", ""));
                Arrays.stream(value.split("[,，\\n\\r]+"))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .forEach(words::add);
            }
        } catch (Exception ignored) {
            // Defaults keep moderation available before settings are initialized.
        }
        return words;
    }

    private Set<String> matchedSensitiveWords(String text) {
        String haystack = String.valueOf(text == null ? "" : text).toLowerCase();
        Set<String> matched = new LinkedHashSet<>();
        for (String word : sensitiveWords()) {
            if (!word.isBlank() && haystack.contains(word.toLowerCase())) matched.add(word);
        }
        return matched;
    }

    private String snippet(String content) {
        String value = String.valueOf(content == null ? "" : content).replaceAll("\\s+", " ").trim();
        return value.length() > 180 ? value.substring(0, 180) + "..." : value;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String now() {
        return LocalDateTime.now().toString();
    }
}
