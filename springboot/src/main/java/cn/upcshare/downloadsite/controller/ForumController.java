package cn.upcshare.downloadsite.controller;

import cn.upcshare.downloadsite.service.AuthService;
import cn.upcshare.downloadsite.service.ModerationService;
import cn.upcshare.downloadsite.service.UserLevelService;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RestController
@RequestMapping("/api/forum")
public class ForumController {
    private static final Set<String> SECTIONS = Set.of("前沿快讯", "资源分享", "求助", "灌水区");

    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final UserLevelService levels;
    private final ModerationService moderation;

    public ForumController(JdbcTemplate jdbc, AuthService auth, UserLevelService levels, ModerationService moderation) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.levels = levels;
        this.moderation = moderation;
    }

    @GetMapping("/posts")
    Map<String, Object> posts(@RequestParam Optional<String> q,
                              @RequestParam Optional<String> section,
                              @RequestParam(defaultValue = "latest") String sort,
                              @RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "20") int size,
                              HttpServletRequest request) {
        var user = auth.currentUser(request);
        boolean admin = user.map(c -> c.admin()).orElse(false);
        boolean pinManager = user.map(c -> "foggy".equals(c.username())).orElse(false);
        page = Math.max(1, page);
        size = Math.min(50, Math.max(1, size));
        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        q.filter(s -> !s.isBlank()).ifPresent(s -> {
            conditions.add("(p.title LIKE ? OR p.content LIKE ? OR p.username LIKE ?)");
            params.add("%" + s + "%");
            params.add("%" + s + "%");
            params.add("%" + s + "%");
        });
        section.filter(SECTIONS::contains).ifPresent(s -> {
            conditions.add("(p.is_pinned=1 OR p.section = ?)");
            params.add(s);
        });
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM forum_posts p" + where, Long.class, params.toArray());
        var listParams = new ArrayList<>(params);
        listParams.add(size);
        listParams.add((page - 1) * size);
        String orderBy = "hot".equals(sort)
                ? " ORDER BY p.is_pinned DESC, (SELECT COUNT(*) FROM forum_comments c WHERE c.post_id=p.id) DESC, p.id DESC"
                : " ORDER BY p.is_pinned DESC, p.id DESC";
        var posts = jdbc.queryForList(
                """
                SELECT p.id,p.user_id,p.username,p.section,p.title,p.view_count,p.is_pinned,p.created_at,
                       u.is_admin,u.user_level
                FROM forum_posts p
                LEFT JOIN users u ON u.uid=p.user_id
                """ + where + orderBy + " LIMIT ? OFFSET ?",
                listParams.toArray()
        );
        for (var post : posts) {
            post.put("can_delete", admin);
            post.put("can_pin", pinManager);
            post.put("is_pinned", ((Number) post.get("is_pinned")).intValue() != 0);
            post.put("user_level", levels.effectiveLevel(
                    String.valueOf(post.get("user_id")),
                    String.valueOf(post.get("username")),
                    post.get("is_admin") != null && ((Number) post.get("is_admin")).intValue() != 0,
                    String.valueOf(post.get("user_level"))
            ));
            post.remove("is_admin");
            Long commentCount = jdbc.queryForObject("SELECT COUNT(*) FROM forum_comments WHERE post_id=?", Long.class, post.get("id"));
            post.put("comment_count", commentCount == null ? 0 : commentCount);
        }
        return FileController.pageResult(total, page, size, posts);
    }

    @GetMapping("/posts/{id}")
    Map<String, Object> post(@PathVariable long id, HttpServletRequest request) {
        var user = auth.currentUser(request);
        boolean admin = user.map(c -> c.admin()).orElse(false);
        boolean pinManager = user.map(c -> "foggy".equals(c.username())).orElse(false);
        jdbc.update("UPDATE forum_posts SET view_count=COALESCE(view_count,0)+1 WHERE id=?", id);
        var rows = jdbc.queryForList("""
                SELECT p.id,p.user_id,p.username,p.section,p.title,p.content,p.view_count,p.is_pinned,p.created_at,
                       u.avatar_path,u.updated_at user_updated_at,u.is_admin,u.user_level
                FROM forum_posts p
                LEFT JOIN users u ON u.uid=p.user_id
                WHERE p.id=?
                LIMIT 1
                """, id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Post not found");
        var post = rows.get(0);
        post.put("can_delete", admin);
        post.put("can_pin", pinManager);
        post.put("is_pinned", ((Number) post.get("is_pinned")).intValue() != 0);
        post.put("avatar_url", avatarUrl(post.get("user_id"), post.get("avatar_path"), post.get("user_updated_at")));
        post.put("user_level", levels.effectiveLevel(
                String.valueOf(post.get("user_id")),
                String.valueOf(post.get("username")),
                post.get("is_admin") != null && ((Number) post.get("is_admin")).intValue() != 0,
                String.valueOf(post.get("user_level"))
        ));
        post.remove("avatar_path");
        post.remove("user_updated_at");
        post.remove("is_admin");
        var comments = jdbc.queryForList(
                """
                SELECT c.id,c.post_id,c.user_id,c.username,c.content,c.created_at,
                       u.avatar_path,u.updated_at user_updated_at,u.is_admin,u.user_level
                FROM forum_comments c
                LEFT JOIN users u ON u.uid=c.user_id
                WHERE c.post_id=?
                ORDER BY c.id ASC
                """,
                id
        );
        comments.forEach(c -> {
            c.put("can_delete", admin);
            c.put("avatar_url", avatarUrl(c.get("user_id"), c.get("avatar_path"), c.get("user_updated_at")));
            c.put("user_level", levels.effectiveLevel(
                    String.valueOf(c.get("user_id")),
                    String.valueOf(c.get("username")),
                    c.get("is_admin") != null && ((Number) c.get("is_admin")).intValue() != 0,
                    String.valueOf(c.get("user_level"))
            ));
            c.remove("avatar_path");
            c.remove("user_updated_at");
            c.remove("is_admin");
        });
        post.put("comments", comments);
        return post;
    }

    @GetMapping("/mine")
    Map<String, Object> mine(@RequestParam(defaultValue = "1") int page,
                             @RequestParam(defaultValue = "10") int size,
                             HttpServletRequest request) {
        var user = auth.requireLogin(request);
        page = Math.max(1, page);
        size = Math.min(20, Math.max(1, size));
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM forum_posts WHERE user_id=?", Long.class, user.uid());
        var posts = jdbc.queryForList("""
                SELECT id,user_id,username,section,title,view_count,
                       (SELECT COUNT(*) FROM forum_comments c WHERE c.post_id=forum_posts.id) comment_count,
                       created_at
                FROM forum_posts
                WHERE user_id=?
                ORDER BY is_pinned DESC, id DESC
                LIMIT ? OFFSET ?
                """, user.uid(), size, (page - 1) * size);
        return FileController.pageResult(total, page, size, posts);
    }

    @PostMapping("/posts")
    Map<String, Object> createPost(@RequestBody Map<String, String> body, HttpServletRequest request) {
        var user = auth.requireLogin(request);
        String section = body.getOrDefault("section", "").trim();
        String title = body.getOrDefault("title", "").trim();
        String content = body.getOrDefault("content", "").trim();
        String ip = moderation.clientIp(request);
        if (!SECTIONS.contains(section)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid forum section");
        }
        if (title.isBlank() || title.length() > 80) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Title must be 1-80 characters");
        }
        if (content.isBlank() || content.length() > 5000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Content must be 1-5000 characters");
        }
        moderation.inspectContent("post", user.uid(), user.username(), ip, title, content);
        jdbc.update("INSERT INTO forum_posts (user_id,username,section,title,content,ip_address,created_at) VALUES (?,?,?,?,?,?,?)",
                user.uid(), user.username(), section, title, content, ip, LocalDateTime.now().toString());
        jdbc.update("UPDATE users SET last_ip=?, updated_at=? WHERE uid=?", ip, LocalDateTime.now().toString(), user.uid());
        moderation.recordEvent("post", user.uid(), user.username(), ip, title, content);
        return Map.of("ok", true, "msg", "Post created");
    }

    @PostMapping("/posts/{id}/comments")
    Map<String, Object> createComment(@PathVariable long id, @RequestBody Map<String, String> body, HttpServletRequest request) {
        var user = auth.requireLogin(request);
        String content = body.getOrDefault("content", "").trim();
        String ip = moderation.clientIp(request);
        if (content.isBlank() || content.length() > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Comment must be 1-500 characters");
        }
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM forum_posts WHERE id=?", Long.class, id);
        if (count == null || count == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Post not found");
        String title = jdbc.queryForObject("SELECT title FROM forum_posts WHERE id=?", String.class, id);
        moderation.inspectContent("comment", user.uid(), user.username(), ip, title, content);
        jdbc.update("INSERT INTO forum_comments (post_id,user_id,username,content,ip_address,created_at) VALUES (?,?,?,?,?,?)",
                id, user.uid(), user.username(), content, ip, LocalDateTime.now().toString());
        jdbc.update("UPDATE users SET last_ip=?, updated_at=? WHERE uid=?", ip, LocalDateTime.now().toString(), user.uid());
        moderation.recordEvent("comment", user.uid(), user.username(), ip, title, content);
        return Map.of("ok", true, "msg", "Comment created");
    }

    @PostMapping("/posts/{id}/pin")
    Map<String, Object> setPinned(@PathVariable long id,
                                  @RequestParam boolean pinned,
                                  HttpServletRequest request) {
        var user = auth.requireAdmin(request);
        if (!"foggy".equals(user.username())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only foggy can pin posts");
        }
        int changed = jdbc.update("UPDATE forum_posts SET is_pinned=? WHERE id=?", pinned ? 1 : 0, id);
        if (changed == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Post not found");
        return Map.of("ok", true, "msg", pinned ? "Post pinned" : "Post unpinned");
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

    @GetMapping("/users/{uid}/posts")
    Map<String, Object> userPosts(@PathVariable String uid,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "10") int size) {
        page = Math.max(1, page);
        size = Math.min(20, Math.max(1, size));
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM forum_posts WHERE user_id=?", Long.class, uid);
        var posts = jdbc.queryForList("""
                SELECT id,user_id,username,section,title,view_count,
                       (SELECT COUNT(*) FROM forum_comments c WHERE c.post_id=forum_posts.id) comment_count,
                       created_at
                FROM forum_posts
                WHERE user_id=?
                ORDER BY is_pinned DESC, id DESC
                LIMIT ? OFFSET ?
                """, uid, size, (page - 1) * size);
        return FileController.pageResult(total, page, size, posts);
    }

    private String avatarUrl(Object uid, Object avatarPath, Object version) {
        String path = String.valueOf(avatarPath == null ? "" : avatarPath);
        if (path.isBlank()) return "";
        String v = String.valueOf(version == null ? "" : version).replaceAll("[^A-Za-z0-9]", "");
        return "/api/auth/avatar/" + uid + (v.isBlank() ? "" : "?v=" + v);
    }
}
