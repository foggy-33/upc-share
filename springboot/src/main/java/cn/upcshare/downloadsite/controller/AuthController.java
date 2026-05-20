package cn.upcshare.downloadsite.controller;

import cn.upcshare.downloadsite.config.AppProperties;
import cn.upcshare.downloadsite.service.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final AppProperties props;

    public AuthController(JdbcTemplate jdbc, AuthService auth, AppProperties props) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.props = props;
    }

    @PostMapping("/register")
    ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, String> body) {
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
        jdbc.update("INSERT INTO users (username,password_hash,created_at,updated_at) VALUES (?,?,?,?)",
                username, auth.hash(password), now, now);
        return ResponseEntity.ok(Map.of("ok", true, "msg", "Register succeeded"));
    }

    @PostMapping("/login")
    ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body, HttpServletResponse response) {
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
        String token = auth.token(
                ((Number) user.get("id")).longValue(),
                String.valueOf(user.get("username")),
                ((Number) user.get("is_admin")).intValue() == 1
        );
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
                .<Map<String, Object>>map(u -> Map.of("logged_in", true, "username", u.username(), "is_admin", u.admin()))
                .orElseGet(() -> Map.of("logged_in", false));
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
}
