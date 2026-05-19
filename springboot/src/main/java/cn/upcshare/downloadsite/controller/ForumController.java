package cn.upcshare.downloadsite.controller;

import cn.upcshare.downloadsite.service.AuthService;
import cn.upcshare.downloadsite.support.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/forum")
public class ForumController {
    private final JdbcTemplate jdbc;
    private final AuthService auth;

    public ForumController(JdbcTemplate jdbc, AuthService auth) {
        this.jdbc = jdbc;
        this.auth = auth;
    }

    @GetMapping("/posts")
    Map<String, Object> posts(@RequestParam Optional<String> q,
                              @RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "20") int size,
                              HttpServletRequest request) {
        var user = auth.currentUser(request);
        boolean admin = user.map(c -> c.admin()).orElse(false);
        page = Math.max(1, page);
        size = Math.min(50, Math.max(1, size));
        String where = q.filter(s -> !s.isBlank()).map(s -> " WHERE content LIKE ? OR username LIKE ?").orElse("");
        Object[] params = q.filter(s -> !s.isBlank()).map(s -> new Object[]{"%" + s + "%", "%" + s + "%"}).orElseGet(() -> new Object[]{});
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM forum_posts" + where, Long.class, params);
        var listParams = new ArrayList<>(Arrays.asList(params));
        listParams.add(size);
        listParams.add((page - 1) * size);
        var posts = jdbc.queryForList(
                "SELECT id,user_id,username,content,created_at FROM forum_posts" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                listParams.toArray()
        );
        for (var post : posts) {
            post.put("can_delete", admin);
            var comments = jdbc.queryForList(
                    "SELECT id,post_id,user_id,username,content,created_at FROM forum_comments WHERE post_id=? ORDER BY id ASC",
                    post.get("id")
            );
            comments.forEach(c -> c.put("can_delete", admin));
            post.put("comments", comments);
        }
        return FileController.pageResult(total, page, size, posts);
    }

    @PostMapping("/posts")
    Map<String, Object> createPost(@RequestBody Map<String, String> body, HttpServletRequest request) {
        var user = auth.requireLogin(request);
        String content = body.getOrDefault("content", "").trim();
        if (content.isBlank() || content.length() > 1000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Content must be 1-1000 characters");
        }
        jdbc.update("INSERT INTO forum_posts (user_id,username,content,created_at) VALUES (?,?,?,?)",
                user.id(), user.username(), content, LocalDateTime.now().toString());
        return Map.of("ok", true, "msg", "Post created");
    }

    @PostMapping("/posts/{id}/comments")
    Map<String, Object> createComment(@PathVariable long id, @RequestBody Map<String, String> body, HttpServletRequest request) {
        var user = auth.requireLogin(request);
        String content = body.getOrDefault("content", "").trim();
        if (content.isBlank() || content.length() > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Comment must be 1-500 characters");
        }
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM forum_posts WHERE id=?", Long.class, id);
        if (count == null || count == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Post not found");
        jdbc.update("INSERT INTO forum_comments (post_id,user_id,username,content,created_at) VALUES (?,?,?,?,?)",
                id, user.id(), user.username(), content, LocalDateTime.now().toString());
        return Map.of("ok", true, "msg", "Comment created");
    }

    @DeleteMapping("/posts/{id}")
    Map<String, Object> deletePost(@PathVariable long id, HttpServletRequest request) {
        auth.requireAdmin(request);
        jdbc.update("DELETE FROM forum_comments WHERE post_id=?", id);
        jdbc.update("DELETE FROM forum_posts WHERE id=?", id);
        return Map.of("ok", true, "msg", "Post deleted");
    }

    @DeleteMapping("/comments/{id}")
    Map<String, Object> deleteComment(@PathVariable long id, HttpServletRequest request) {
        auth.requireAdmin(request);
        jdbc.update("DELETE FROM forum_comments WHERE id=?", id);
        return Map.of("ok", true, "msg", "Comment deleted");
    }
}
