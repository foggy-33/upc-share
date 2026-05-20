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
        return encoder.matches(password, hash);
    }

    public String token(long id, String username, boolean admin) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(id))
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
            try {
                var claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(cookie.getValue()).getPayload();
                return Optional.of(new CurrentUser(claims.getSubject(), String.valueOf(claims.get("username")), Boolean.TRUE.equals(claims.get("is_admin"))));
            } catch (Exception ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
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
                SELECT id, username, password_hash, is_active, is_admin
                FROM users
                WHERE username = ?
                LIMIT 1
                """, (rs, rowNum) -> {
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("id", rs.getLong("id"));
            user.put("username", rs.getString("username"));
            user.put("password_hash", rs.getString("password_hash"));
            user.put("is_active", rs.getInt("is_active"));
            user.put("is_admin", rs.getInt("is_admin"));
            return user;
        }, username.trim());
        return rows.stream().findFirst();
    }
}
