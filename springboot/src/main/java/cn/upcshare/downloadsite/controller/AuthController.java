package cn.upcshare.downloadsite.controller;

import cn.upcshare.downloadsite.config.AppProperties;
import cn.upcshare.downloadsite.service.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
        if (user == null
                || ((Number) user.get("is_active")).intValue() == 0
                || !auth.verify(body.getOrDefault("password", ""), String.valueOf(user.get("password_hash")))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("ok", false, "msg", "Invalid username or password"));
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
        cookie.setMaxAge(7 * 24 * 3600);
        response.addCookie(cookie);
        return ResponseEntity.ok(Map.of("ok", true, "username", user.get("username")));
    }

    @PostMapping("/logout")
    Map<String, Object> logout(HttpServletResponse response) {
        Cookie cookie = new Cookie(AuthService.COOKIE_NAME, "");
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
        return Map.of("ok", true, "msg", "Logged out");
    }

    @GetMapping("/me")
    Map<String, Object> me(HttpServletRequest request) {
        return auth.currentUser(request)
                .<Map<String, Object>>map(u -> Map.of("logged_in", true, "username", u.username(), "is_admin", u.admin()))
                .orElseGet(() -> Map.of("logged_in", false));
    }
}
