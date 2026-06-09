package cn.upcshare.downloadsite.controller;

import cn.upcshare.downloadsite.config.AppProperties;
import cn.upcshare.downloadsite.service.AuthService;
import cn.upcshare.downloadsite.service.ModerationService;
import cn.upcshare.downloadsite.service.UserLevelService;
import cn.upcshare.downloadsite.support.ApiException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final Set<String> AVATAR_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".webp", ".gif");
    private static final long MAX_AVATAR_SIZE = 3L * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final UserLevelService levels;
    private final ModerationService moderation;
    private final AppProperties props;
    private final Path uploadsDir;

    public AuthController(JdbcTemplate jdbc, AuthService auth, UserLevelService levels, ModerationService moderation, AppProperties props) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.levels = levels;
        this.moderation = moderation;
        this.props = props;
        this.uploadsDir = Paths.get("uploads").toAbsolutePath().normalize();
    }

    @PostMapping("/register")
    ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String ip = moderation.clientIp(request);
        moderation.rejectBlacklistedIp(ip);
        String username = body.getOrDefault("username", "").trim();
        String password = body.getOrDefault("password", "");
        if (username.length() < 2 || username.length() > 20) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "msg", "Username must be 2-20 characters"));
        }
        if (password.length() < 6) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "msg", "Password must be at least 6 characters"));
        }
        if (auth.findUser(username).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "msg", "Username is already registered"));
        }
        String now = LocalDateTime.now().toString();
        try {
            jdbc.update("""
                    INSERT INTO users (uid,username,password_hash,created_at,updated_at,last_ip,is_admin)
                    SELECT LPAD(COALESCE(MAX(CAST(uid AS UNSIGNED)),0)+1,6,'0'), ?, ?, ?, ?, ?,
                           CASE WHEN ?='foggy' THEN 1 ELSE 0 END
                    FROM users
                    """, username, auth.hash(password), now, now, ip, username);
        } catch (DuplicateKeyException e) {
            if (auth.findUser(username).isPresent()) {
                return ResponseEntity.badRequest().body(Map.of("ok", false, "msg", "Username is already registered"));
            }
            throw e;
        }
        String uid = jdbc.queryForObject("SELECT uid FROM users WHERE username=? LIMIT 1", String.class, username);
        moderation.recordRegistration(uid, username, ip);
        return ResponseEntity.ok(Map.of("ok", true, "msg", "Register succeeded"));
    }

    @PostMapping("/login")
    ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body, HttpServletRequest request, HttpServletResponse response) {
        String ip = moderation.clientIp(request);
        moderation.rejectBlacklistedIp(ip);
        var user = auth.findUser(body.getOrDefault("username", "")).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("ok", false, "msg", "用户名或密码错误"));
        }
        if (((Number) user.get("is_active")).intValue() == 0) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("ok", false, "msg", "账号已被封禁，请联系管理员"));
        }
        if (!auth.verify(body.getOrDefault("password", ""), String.valueOf(user.get("password_hash")))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("ok", false, "msg", "用户名或密码错误"));
        }
        jdbc.update("UPDATE users SET last_ip=?, updated_at=? WHERE uid=?", ip, LocalDateTime.now().toString(), user.get("uid"));
        moderation.recordEvent("login", String.valueOf(user.get("uid")), String.valueOf(user.get("username")), ip, "用户登录", "");
        String token = auth.token(
                String.valueOf(user.get("uid")),
                String.valueOf(user.get("username")),
                ((Number) user.get("is_admin")).intValue() == 1
        );
        expireCookie(response, "");
        String domain = normalizedCookieDomain();
        if (!domain.isBlank()) {
            expireCookie(response, domain);
            expireCookie(response, "." + domain);
        }
        Cookie cookie = new Cookie(AuthService.COOKIE_NAME, token);
        cookie.setHttpOnly(true);
        cookie.setSecure(props.isCookieSecure());
        cookie.setPath("/");
        setCookieDomain(cookie);
        cookie.setMaxAge(7 * 24 * 3600);
        response.addCookie(cookie);
        return ResponseEntity.ok(Map.of("ok", true, "username", user.get("username")));
    }

    @PostMapping("/logout")
    ResponseEntity<Map<String, Object>> logout(HttpServletResponse response) {
        expireCookie(response, "");
        String domain = normalizedCookieDomain();
        if (!domain.isBlank()) {
            expireCookie(response, domain);
            expireCookie(response, "." + domain);
        }
        return ResponseEntity.ok(Map.of("ok", true, "msg", "Logged out"));
    }

    @GetMapping("/me")
    Map<String, Object> me(HttpServletRequest request) {
        return auth.currentUser(request)
                .<Map<String, Object>>map(u -> {
                    var rows = jdbc.queryForList("SELECT updated_at, avatar_path, user_level FROM users WHERE uid=? LIMIT 1", u.uid());
                    String updatedAt = rows.isEmpty() ? "" : String.valueOf(rows.get(0).getOrDefault("updated_at", ""));
                    String avatarPath = rows.isEmpty() ? "" : String.valueOf(rows.get(0).getOrDefault("avatar_path", ""));
                    String configuredLevel = rows.isEmpty() ? "auto" : String.valueOf(rows.get(0).getOrDefault("user_level", "auto"));
                    return Map.of(
                            "logged_in", true,
                            "uid", u.uid(),
                            "username", u.username(),
                            "is_admin", u.admin(),
                            "user_level", levels.effectiveLevel(u.uid(), u.username(), u.admin(), configuredLevel),
                            "avatar_url", avatarUrl(u.uid(), avatarPath, updatedAt)
                    );
                })
                .orElseGet(() -> Map.of("logged_in", false));
    }

    @GetMapping("/profile")
    Map<String, Object> profile(HttpServletRequest request) {
        var user = auth.requireLogin(request);
        var profile = jdbc.queryForMap("""
                SELECT uid, username, created_at, updated_at, avatar_path, user_level, is_admin, points
                FROM users
                WHERE uid=?
                LIMIT 1
                """, user.uid());
        Long postCount = jdbc.queryForObject("SELECT COUNT(*) FROM forum_posts WHERE user_id=?", Long.class, user.uid());
        var downloads = userDownloadStats(user.uid());
        var result = new LinkedHashMap<String, Object>();
        result.put("uid", profile.get("uid"));
        result.put("username", profile.get("username"));
        result.put("created_at", profile.get("created_at"));
        result.put("updated_at", profile.get("updated_at"));
        result.put("avatar_url", avatarUrl(user.uid(), profile.get("avatar_path"), profile.get("updated_at")));
        result.put("is_admin", ((Number) profile.get("is_admin")).intValue() != 0);
        result.put("user_level", levels.effectiveLevel(user.uid(), String.valueOf(profile.get("username")),
                ((Number) profile.get("is_admin")).intValue() != 0, String.valueOf(profile.get("user_level"))));
        result.put("points", profile.get("points"));
        result.put("download_count", downloads.get("count"));
        result.put("download_size", cn.upcshare.downloadsite.support.Formatters.size(downloads.get("size")));
        result.put("post_count", postCount == null ? 0 : postCount);
        return result;
    }

    @GetMapping("/users/{uid}")
    Map<String, Object> publicProfile(@PathVariable String uid) {
        var rows = jdbc.queryForList("""
                SELECT uid, username, created_at, updated_at, avatar_path, user_level, is_admin, points
                FROM users
                WHERE uid=?
                LIMIT 1
                """, uid);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "User not found");
        }
        var profile = rows.get(0);
        Long postCount = jdbc.queryForObject("SELECT COUNT(*) FROM forum_posts WHERE user_id=?", Long.class, uid);
        var downloads = userDownloadStats(uid);
        var result = new LinkedHashMap<String, Object>();
        result.put("uid", profile.get("uid"));
        result.put("username", profile.get("username"));
        result.put("created_at", profile.get("created_at"));
        result.put("avatar_url", avatarUrl(uid, profile.get("avatar_path"), profile.get("updated_at")));
        result.put("is_admin", ((Number) profile.get("is_admin")).intValue() != 0);
        result.put("user_level", levels.effectiveLevel(uid, String.valueOf(profile.get("username")),
                ((Number) profile.get("is_admin")).intValue() != 0, String.valueOf(profile.get("user_level"))));
        result.put("points", profile.get("points"));
        result.put("download_count", downloads.get("count"));
        result.put("download_size", cn.upcshare.downloadsite.support.Formatters.size(downloads.get("size")));
        result.put("post_count", postCount == null ? 0 : postCount);
        return result;
    }

    @PostMapping("/avatar")
    Map<String, Object> uploadAvatar(@RequestParam MultipartFile avatar, HttpServletRequest request) throws IOException {
        var user = auth.requireLogin(request);
        if (avatar.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请选择头像图片");
        }
        if (avatar.getSize() > MAX_AVATAR_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "头像不能超过 3MB");
        }
        String name = Paths.get(String.valueOf(avatar.getOriginalFilename())).getFileName().toString();
        String ext = extension(name);
        String contentType = avatar.getContentType() == null ? "" : avatar.getContentType().toLowerCase();
        if (!AVATAR_EXTENSIONS.contains(ext) || (!contentType.isBlank() && !contentType.startsWith("image/"))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "只支持 JPG、PNG、WebP 或 GIF 图片");
        }

        Path avatarDir = uploadsDir.resolve("avatars").normalize();
        Files.createDirectories(avatarDir);
        String oldPath = jdbc.queryForObject("SELECT avatar_path FROM users WHERE uid=?", String.class, user.uid());
        Path target = avatarDir.resolve(user.uid() + "-" + System.currentTimeMillis() + ext).normalize();
        if (!target.startsWith(avatarDir)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid avatar path");
        }
        avatar.transferTo(target);

        String rel = uploadsDir.relativize(target).toString().replace("\\", "/");
        String now = LocalDateTime.now().toString();
        jdbc.update("UPDATE users SET avatar_path=?, updated_at=? WHERE uid=?", rel, now, user.uid());
        deleteOldAvatar(oldPath);

        return Map.of("ok", true, "avatar_url", avatarUrl(user.uid(), rel, now), "updated_at", now);
    }

    @GetMapping("/avatar/{uid}")
    ResponseEntity<FileSystemResource> avatar(@PathVariable String uid) throws IOException {
        var rows = jdbc.queryForList("SELECT avatar_path FROM users WHERE uid=? LIMIT 1", uid);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Avatar not found");
        }
        String avatarPath = String.valueOf(rows.get(0).getOrDefault("avatar_path", ""));
        if (avatarPath.isBlank()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Avatar not found");
        }
        Path path = uploadsDir.resolve(avatarPath).normalize();
        if (!path.startsWith(uploadsDir) || !Files.exists(path)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Avatar not found");
        }
        String type = Files.probeContentType(path);
        MediaType mediaType = type == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(type);
        return ResponseEntity.ok().contentType(mediaType).body(new FileSystemResource(path));
    }

    private void setCookieDomain(Cookie cookie) {
        String domain = normalizedCookieDomain();
        if (!domain.isBlank()) {
            cookie.setDomain(domain);
        }
    }

    private String normalizedCookieDomain() {
        String domain = props.getCookieDomain();
        if (domain == null) return "";
        domain = domain.trim();
        while (domain.startsWith(".")) {
            domain = domain.substring(1);
        }
        return domain;
    }

    private void expireCookie(HttpServletResponse response, String domain) {
        StringBuilder value = new StringBuilder(AuthService.COOKIE_NAME)
                .append("=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; HttpOnly; SameSite=Lax");
        if (props.isCookieSecure()) {
            value.append("; Secure");
        }
        if (domain != null && !domain.isBlank()) {
            value.append("; Domain=").append(domain);
        }
        response.addHeader(HttpHeaders.SET_COOKIE, value.toString());
    }

    private String avatarUrl(Object uid, Object avatarPath, Object version) {
        String path = String.valueOf(avatarPath == null ? "" : avatarPath);
        if (path.isBlank()) return "";
        String v = String.valueOf(version == null ? "" : version).replaceAll("[^A-Za-z0-9]", "");
        return "/api/auth/avatar/" + uid + (v.isBlank() ? "" : "?v=" + v);
    }

    private String extension(String name) {
        int i = name.lastIndexOf('.');
        return i >= 0 ? name.substring(i).toLowerCase() : "";
    }

    private void deleteOldAvatar(String oldPath) {
        if (oldPath == null || oldPath.isBlank()) return;
        try {
            Path old = uploadsDir.resolve(oldPath).normalize();
            if (old.startsWith(uploadsDir.resolve("avatars").normalize())) {
                Files.deleteIfExists(old);
            }
        } catch (IOException ignored) {
            // A stale avatar file should not block a successful profile update.
        }
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
