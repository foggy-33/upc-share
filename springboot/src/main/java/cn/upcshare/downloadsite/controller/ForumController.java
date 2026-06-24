package cn.upcshare.downloadsite.controller;

import cn.upcshare.downloadsite.service.AuthService;
import cn.upcshare.downloadsite.service.ModerationService;
import cn.upcshare.downloadsite.service.PointService;
import cn.upcshare.downloadsite.service.UserLevelService;
import cn.upcshare.downloadsite.support.ApiException;
import cn.upcshare.downloadsite.support.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/forum")
public class ForumController {
    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final UserLevelService levels;
    private final ModerationService moderation;
    private final PointService points;

    public ForumController(JdbcTemplate jdbc, AuthService auth, UserLevelService levels, ModerationService moderation,
                           PointService points) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.levels = levels;
        this.moderation = moderation;
        this.points = points;
    }

    @GetMapping("/sections")
    List<Map<String, Object>> sections(HttpServletRequest request) {
        var user = auth.currentUser(request);
        String level = user.map(this::currentUserLevel).orElse("gray");
        var rows = jdbc.queryForList("""
                SELECT id,name,min_level,sort_order,is_active
                FROM forum_sections
                WHERE is_active=1
                ORDER BY sort_order,id
                """);
        return rows.stream()
                .filter(row -> levels.canAccess(level, String.valueOf(row.get("min_level"))))
                .peek(row -> row.put("min_level_label", levelLabel(String.valueOf(row.get("min_level")))))
                .toList();
    }

    @GetMapping("/posts")
    Map<String, Object> posts(@RequestParam Optional<String> q,
                              @RequestParam Optional<String> section,
                              @RequestParam(defaultValue = "latest") String sort,
                              @RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "20") int size,
                              HttpServletRequest request) {
        var user = auth.currentUser(request);
        boolean admin = user.map(CurrentUser::admin).orElse(false);
        boolean pinManager = user.map(c -> "foggy".equals(c.username())).orElse(false);
        List<String> visibleSections = visibleSections(user);
        page = Math.max(1, page);
        size = Math.min(50, Math.max(1, size));
        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (visibleSections.isEmpty()) {
            conditions.add("1=0");
        } else {
            conditions.add("p.section IN (" + placeholders(visibleSections.size()) + ")");
            params.addAll(visibleSections);
        }
        q.filter(s -> !s.isBlank()).ifPresent(s -> {
            conditions.add("(p.title LIKE ? OR p.content LIKE ? OR p.username LIKE ?)");
            params.add("%" + s + "%");
            params.add("%" + s + "%");
            params.add("%" + s + "%");
        });
        section.filter(s -> !s.isBlank()).ifPresent(s -> {
            conditions.add("p.section = ?");
            params.add(s);
        });
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM forum_posts p" + where, Long.class, params.toArray());
        var listParams = new ArrayList<>(params);
        listParams.add(size);
        listParams.add((page - 1) * size);
        String orderBy = "hot".equals(sort)
                ? " ORDER BY p.is_pinned DESC, like_count DESC, comment_count DESC, p.id DESC"
                : " ORDER BY p.is_pinned DESC, p.id DESC";
        var posts = jdbc.queryForList(
                """
                 SELECT p.id,p.user_id,p.username,p.section,p.title,p.view_count,p.is_pinned,p.created_at,
                        (SELECT COUNT(*) FROM forum_comments c WHERE c.post_id=p.id) comment_count,
                        (SELECT COUNT(*) FROM forum_post_likes pl WHERE pl.post_id=p.id) like_count,
                        (SELECT COUNT(*) FROM files f WHERE f.uploader=p.username AND f.status='approved') approved_upload_count,
                        (SELECT COUNT(*) FROM download_log d WHERE d.user_id=p.user_id) user_download_count,
                        u.is_admin,u.user_level,u.points
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
                    post.get("is_admin") != null && ((Number) post.get("is_admin")).intValue() != 0,
                    String.valueOf(post.get("user_level")),
                    ((Number) post.get("approved_upload_count")).longValue(),
                    ((Number) post.get("user_download_count")).longValue(),
                    numberValue(post.get("points"))
            ));
            post.remove("is_admin");
            post.remove("approved_upload_count");
            post.remove("user_download_count");
            post.remove("points");
        }
        return FileController.pageResult(total, page, size, posts);
    }

    @GetMapping("/posts/{id}")
    Map<String, Object> post(@PathVariable long id,
                             @RequestParam(defaultValue = "latest") String comment_sort,
                             HttpServletRequest request) {
        var user = auth.currentUser(request);
        boolean admin = user.map(CurrentUser::admin).orElse(false);
        boolean pinManager = user.map(c -> "foggy".equals(c.username())).orElse(false);
        String currentUid = user.map(CurrentUser::uid).orElse("");
        var rows = jdbc.queryForList("""
                 SELECT p.id,p.user_id,p.username,p.section,p.title,p.content,p.view_count,p.is_pinned,p.created_at,
                        u.avatar_path,u.is_admin,u.user_level,u.points,
                        (SELECT COUNT(*) FROM forum_post_likes pl WHERE pl.post_id=p.id) like_count,
                        (SELECT COUNT(*) FROM forum_post_likes pl WHERE pl.post_id=p.id AND pl.user_id=?) liked_by_me,
                        (SELECT COUNT(*) FROM files f WHERE f.uploader=p.username AND f.status='approved') approved_upload_count,
                        (SELECT COUNT(*) FROM download_log d WHERE d.user_id=p.user_id) user_download_count,
                        fs.min_level section_min_level
                FROM forum_posts p
                LEFT JOIN users u ON u.uid=p.user_id
                LEFT JOIN forum_sections fs ON fs.name=p.section
                WHERE p.id=?
                LIMIT 1
                """, currentUid, id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Post not found");
        var post = rows.get(0);
        requireSectionAccess(user, String.valueOf(post.get("section")), String.valueOf(post.get("section_min_level")));
        jdbc.update("UPDATE forum_posts SET view_count=COALESCE(view_count,0)+1 WHERE id=?", id);
        post.put("can_delete", admin);
        post.put("can_pin", pinManager);
        post.put("is_pinned", ((Number) post.get("is_pinned")).intValue() != 0);
        post.put("liked_by_me", ((Number) post.get("liked_by_me")).intValue() != 0);
        post.put("avatar_url", avatarUrl(post.get("user_id"), post.get("avatar_path")));
        post.put("user_level", levels.effectiveLevel(
                post.get("is_admin") != null && ((Number) post.get("is_admin")).intValue() != 0,
                String.valueOf(post.get("user_level")),
                ((Number) post.get("approved_upload_count")).longValue(),
                ((Number) post.get("user_download_count")).longValue(),
                numberValue(post.get("points"))
        ));
        post.remove("avatar_path");
        post.remove("is_admin");
        post.remove("approved_upload_count");
        post.remove("user_download_count");
        post.remove("points");
        post.remove("section_min_level");
        String commentOrder = "hot".equals(comment_sort)
                ? " ORDER BY like_count DESC,c.id DESC"
                : " ORDER BY c.id DESC";
        var comments = jdbc.queryForList(
                """
                 SELECT c.id,c.post_id,c.user_id,c.username,c.content,c.created_at,
                        u.avatar_path,u.is_admin,u.user_level,u.points,
                        (SELECT COUNT(*) FROM forum_comment_likes cl WHERE cl.comment_id=c.id) like_count,
                        (SELECT COUNT(*) FROM forum_comment_likes cl WHERE cl.comment_id=c.id AND cl.user_id=?) liked_by_me,
                        (SELECT COUNT(*) FROM files f WHERE f.uploader=c.username AND f.status='approved') approved_upload_count,
                        (SELECT COUNT(*) FROM download_log d WHERE d.user_id=c.user_id) user_download_count
                FROM forum_comments c
                LEFT JOIN users u ON u.uid=c.user_id
                WHERE c.post_id=?
                """ + commentOrder,
                currentUid, id
        );
        comments.forEach(c -> {
            c.put("can_delete", admin);
            c.put("liked_by_me", ((Number) c.get("liked_by_me")).intValue() != 0);
            c.put("avatar_url", avatarUrl(c.get("user_id"), c.get("avatar_path")));
            c.put("user_level", levels.effectiveLevel(
                    c.get("is_admin") != null && ((Number) c.get("is_admin")).intValue() != 0,
                    String.valueOf(c.get("user_level")),
                    ((Number) c.get("approved_upload_count")).longValue(),
                    ((Number) c.get("user_download_count")).longValue(),
                    numberValue(c.get("points"))
            ));
            c.remove("avatar_path");
            c.remove("is_admin");
            c.remove("approved_upload_count");
            c.remove("user_download_count");
            c.remove("points");
        });
        post.put("comments", comments);
        return post;
    }

    @PostMapping("/posts/{id}/like")
    Map<String, Object> togglePostLike(@PathVariable long id, HttpServletRequest request) {
        var user = auth.requireLogin(request);
        requirePostAccess(id, Optional.of(user));
        int removed = jdbc.update("DELETE FROM forum_post_likes WHERE post_id=? AND user_id=?", id, user.uid());
        boolean liked = removed == 0;
        if (liked) {
            jdbc.update("""
                    INSERT INTO forum_post_likes (post_id,user_id,created_at)
                    VALUES (?,?,?)
                    """, id, user.uid(), LocalDateTime.now().toString());
        }
        long likeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM forum_post_likes WHERE post_id=?", Long.class, id);
        return Map.of("ok", true, "liked", liked, "like_count", likeCount);
    }

    @PostMapping("/comments/{id}/like")
    Map<String, Object> toggleCommentLike(@PathVariable long id, HttpServletRequest request) {
        var user = auth.requireLogin(request);
        var rows = jdbc.queryForList("""
                SELECT p.section,fs.min_level
                FROM forum_comments c
                JOIN forum_posts p ON p.id=c.post_id
                LEFT JOIN forum_sections fs ON fs.name=p.section
                WHERE c.id=?
                LIMIT 1
                """, id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Comment not found");
        var row = rows.get(0);
        requireSectionAccess(Optional.of(user), String.valueOf(row.get("section")), String.valueOf(row.get("min_level")));
        int removed = jdbc.update("DELETE FROM forum_comment_likes WHERE comment_id=? AND user_id=?", id, user.uid());
        boolean liked = removed == 0;
        if (liked) {
            jdbc.update("""
                    INSERT INTO forum_comment_likes (comment_id,user_id,created_at)
                    VALUES (?,?,?)
                    """, id, user.uid(), LocalDateTime.now().toString());
        }
        long likeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM forum_comment_likes WHERE comment_id=?", Long.class, id);
        return Map.of("ok", true, "liked", liked, "like_count", likeCount);
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
    @Transactional
    Map<String, Object> createPost(@RequestBody Map<String, String> body, HttpServletRequest request) {
        var user = auth.requireLogin(request);
        String section = body.getOrDefault("section", "").trim();
        String title = body.getOrDefault("title", "").trim();
        String content = body.getOrDefault("content", "").trim();
        String ip = moderation.clientIp(request);
        requireSectionAccess(Optional.of(user), section, sectionMinLevel(section));
        if (title.isBlank() || title.length() > 80) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Title must be 1-80 characters");
        }
        if (content.isBlank() || content.length() > 20000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Content must be 1-20000 characters");
        }
        moderation.inspectContent("post", user.uid(), user.username(), ip, title, content);
        jdbc.update("INSERT INTO forum_posts (user_id,username,section,title,content,ip_address,created_at) VALUES (?,?,?,?,?,?,?)",
                user.uid(), user.username(), section, title, content, ip, LocalDateTime.now().toString());
        jdbc.update("UPDATE users SET last_ip=? WHERE uid=?", ip, user.uid());
        moderation.recordEvent("post", user.uid(), user.username(), ip, title, content);
        var pointSummary = points.rewardPost(user.uid());
        return Map.of("ok", true, "msg", "Post created", "points", pointSummary.get("points"));
    }

    @PostMapping("/posts/{id}/comments")
    @Transactional
    Map<String, Object> createComment(@PathVariable long id, @RequestBody Map<String, String> body, HttpServletRequest request) {
        var user = auth.requireLogin(request);
        String content = body.getOrDefault("content", "").trim();
        String ip = moderation.clientIp(request);
        if (content.isBlank() || content.length() > 5000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Comment must be 1-5000 characters");
        }
        var rows = jdbc.queryForList("""
                SELECT p.title,p.section,fs.min_level
                FROM forum_posts p
                LEFT JOIN forum_sections fs ON fs.name=p.section
                WHERE p.id=?
                LIMIT 1
                """, id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Post not found");
        var row = rows.get(0);
        requireSectionAccess(Optional.of(user), String.valueOf(row.get("section")), String.valueOf(row.get("min_level")));
        String title = String.valueOf(row.get("title"));
        moderation.inspectContent("comment", user.uid(), user.username(), ip, title, content);
        jdbc.update("INSERT INTO forum_comments (post_id,user_id,username,content,ip_address,created_at) VALUES (?,?,?,?,?,?)",
                id, user.uid(), user.username(), content, ip, LocalDateTime.now().toString());
        jdbc.update("UPDATE users SET last_ip=? WHERE uid=?", ip, user.uid());
        moderation.recordEvent("comment", user.uid(), user.username(), ip, title, content);
        var pointSummary = points.rewardComment(user.uid());
        return Map.of("ok", true, "msg", "Comment created", "points", pointSummary.get("points"));
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
        var commentIds = jdbc.queryForList("SELECT id FROM forum_comments WHERE post_id=?", Long.class, id);
        for (Long commentId : commentIds) {
            jdbc.update("DELETE FROM forum_comment_likes WHERE comment_id=?", commentId);
        }
        jdbc.update("DELETE FROM forum_post_likes WHERE post_id=?", id);
        jdbc.update("DELETE FROM forum_comments WHERE post_id=?", id);
        jdbc.update("DELETE FROM forum_posts WHERE id=?", id);
        return Map.of("ok", true, "msg", "Post deleted");
    }

    @DeleteMapping("/comments/{id}")
    Map<String, Object> deleteComment(@PathVariable long id, HttpServletRequest request) {
        auth.requireAdmin(request);
        jdbc.update("DELETE FROM forum_comment_likes WHERE comment_id=?", id);
        jdbc.update("DELETE FROM forum_comments WHERE id=?", id);
        return Map.of("ok", true, "msg", "Comment deleted");
    }

    @GetMapping("/users/{uid}/posts")
    Map<String, Object> userPosts(@PathVariable String uid,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "10") int size,
                                  HttpServletRequest request) {
        var user = auth.currentUser(request);
        List<String> visibleSections = visibleSections(user);
        page = Math.max(1, page);
        size = Math.min(20, Math.max(1, size));
        if (visibleSections.isEmpty()) return FileController.pageResult(0, page, size, List.of());
        List<Object> params = new ArrayList<>();
        params.add(uid);
        params.addAll(visibleSections);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM forum_posts WHERE user_id=? AND section IN (" + placeholders(visibleSections.size()) + ")",
                Long.class, params.toArray());
        List<Object> listParams = new ArrayList<>(params);
        listParams.add(size);
        listParams.add((page - 1) * size);
        var posts = jdbc.queryForList("""
                SELECT id,user_id,username,section,title,view_count,
                       (SELECT COUNT(*) FROM forum_comments c WHERE c.post_id=forum_posts.id) comment_count,
                       created_at
                FROM forum_posts
                WHERE user_id=? AND section IN (""" + placeholders(visibleSections.size()) + """
                )
                ORDER BY is_pinned DESC, id DESC
                LIMIT ? OFFSET ?
                """, listParams.toArray());
        return FileController.pageResult(total, page, size, posts);
    }

    private void requirePostAccess(long postId, Optional<CurrentUser> user) {
        var rows = jdbc.queryForList("""
                SELECT p.section,fs.min_level
                FROM forum_posts p
                LEFT JOIN forum_sections fs ON fs.name=p.section
                WHERE p.id=?
                LIMIT 1
                """, postId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Post not found");
        var row = rows.get(0);
        requireSectionAccess(user, String.valueOf(row.get("section")), String.valueOf(row.get("min_level")));
    }

    private void requireSectionAccess(Optional<CurrentUser> user, String section, String minLevel) {
        String required = minLevel == null || "null".equals(minLevel) || minLevel.isBlank() ? sectionMinLevel(section) : minLevel;
        String actual = user.map(this::currentUserLevel).orElse("gray");
        if (!levels.canAccess(actual, required)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "当前等级无法访问该板块");
        }
    }

    private String sectionMinLevel(String section) {
        var rows = jdbc.queryForList("""
                SELECT min_level
                FROM forum_sections
                WHERE name=? AND is_active=1
                LIMIT 1
                """, section);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid forum section");
        return String.valueOf(rows.get(0).get("min_level"));
    }

    private List<String> visibleSections(Optional<CurrentUser> user) {
        String level = user.map(this::currentUserLevel).orElse("gray");
        var rows = jdbc.queryForList("""
                SELECT name,min_level
                FROM forum_sections
                WHERE is_active=1
                ORDER BY sort_order,id
                """);
        if (rows.isEmpty()) return Collections.emptyList();
        return rows.stream()
                .filter(row -> levels.canAccess(level, String.valueOf(row.get("min_level"))))
                .map(row -> String.valueOf(row.get("name")))
                .toList();
    }

    private String currentUserLevel(CurrentUser user) {
        return levels.effectiveLevel(user.uid(), user.username(), user.admin(), configuredLevel(user.uid()));
    }

    private String configuredLevel(String uid) {
        return jdbc.query("SELECT user_level FROM users WHERE uid=?",
                rs -> rs.next() ? rs.getString("user_level") : "auto", uid);
    }

    private String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private String levelLabel(String level) {
        return switch (level) {
            case "blue" -> "正式用户";
            case "green" -> "贡献者";
            case "yellow" -> "活跃达人";
            case "orange" -> "社区之星";
            case "admin" -> "管理员";
            default -> "新人";
        };
    }

    private String avatarUrl(Object uid, Object avatarPath) {
        String path = String.valueOf(avatarPath == null ? "" : avatarPath);
        if (path.isBlank()) return "";
        String v = path.replaceAll("[^A-Za-z0-9]", "");
        return "/api/auth/avatar/" + uid + (v.isBlank() ? "" : "?v=" + v);
    }

    private double numberValue(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }
}
