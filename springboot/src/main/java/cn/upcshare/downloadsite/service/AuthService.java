package cn.upcshare.downloadsite.service;

import cn.upcshare.downloadsite.config.AppProperties;
import cn.upcshare.downloadsite.support.ApiException;
import cn.upcshare.downloadsite.support.CurrentUser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class AuthService {
    public static final String COOKIE_NAME = "access_token";
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecretKey key;

    public AuthService(JdbcTemplate jdbc, AppProperties props) {
        this.jdbc = jdbc;
        String secret = props.getJwtSecret();
        if (secret.length() < 32) secret = (secret + "00000000000000000000000000000000").substring(0, 32);
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String hash(String password) {
        return encoder.encode(password);
    }

    public boolean verify(String password, String hash) {
        if (password == null || hash == null || hash.isBlank()) return false;
        try {
            return encoder.matches(password, hash);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public String token(String uid, String username, boolean admin) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(uid)
                .claim("username", username)
                .claim("is_admin", admin)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(7 * 24 * 3600)))
                .signWith(key)
                .compact();
    }

    public Optional<CurrentUser> currentUser(HttpServletRequest request) {
        if (request.getCookies() == null) return Optional.empty();
        for (Cookie cookie : request.getCookies()) {
            if (!COOKIE_NAME.equals(cookie.getName())) continue;
            if (cookie.getValue() == null || cookie.getValue().isBlank()) continue;
            try {
                var claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(cookie.getValue()).getPayload();
                String username = String.valueOf(claims.get("username"));
                Optional<CurrentUser> user = verifiedUser(currentUid(claims.getSubject(), username));
                if (user.isPresent()) return user;
            } catch (Exception ignored) {
                // Browsers may send stale cookies from old domain settings together with the fresh one.
                // Keep scanning instead of treating the first bad token as the whole session.
            }
        }
        return Optional.empty();
    }

    private Optional<CurrentUser> verifiedUser(String uid) {
        var users = jdbc.query("""
                SELECT uid, username, is_admin
                FROM users
                WHERE uid = ? AND is_active = 1
                LIMIT 1
                """, (rs, rowNum) -> new CurrentUser(
                rs.getString("uid"),
                rs.getString("username"),
                rs.getInt("is_admin") != 0
        ), uid);
        return users.stream().findFirst();
    }

    private String currentUid(String subject, String username) {
        if (subject != null && subject.matches("\\d{6}")) return subject;
        return jdbc.query("""
                SELECT uid
                FROM users
                WHERE username = ?
                LIMIT 1
                """, rs -> rs.next() ? rs.getString("uid") : subject, username);
    }

    public CurrentUser requireLogin(HttpServletRequest request) {
        return currentUser(request).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "请先登录"));
    }

    public CurrentUser requireAdmin(HttpServletRequest request) {
        CurrentUser user = requireLogin(request);
        if (!user.admin()) throw new ApiException(HttpStatus.FORBIDDEN, "需要管理员权限");
        return user;
    }

    public Optional<Map<String, Object>> findUser(String username) {
        var rows = jdbc.query("""
                SELECT uid, username, password_hash, is_active, is_admin
                FROM users
                WHERE username = ?
                LIMIT 1
                """, (rs, rowNum) -> {
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("uid", rs.getString("uid"));
            user.put("username", rs.getString("username"));
            user.put("password_hash", rs.getString("password_hash"));
            user.put("is_active", rs.getInt("is_active"));
            user.put("is_admin", rs.getInt("is_admin"));
            return user;
        }, username.trim());
        return rows.stream().findFirst();
    }
}
