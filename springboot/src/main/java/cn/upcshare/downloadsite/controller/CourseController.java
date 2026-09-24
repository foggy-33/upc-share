package cn.upcshare.downloadsite.controller;

import cn.upcshare.downloadsite.service.AuthService;
import cn.upcshare.downloadsite.support.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/courses")
public class CourseController {
    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final List<Map<String, Object>> courses;
    private final Map<String, Map<String, Object>> byId;

    public CourseController(JdbcTemplate jdbc, AuthService auth, ObjectMapper mapper) throws IOException {
        this.jdbc = jdbc;
        this.auth = auth;
        try (var input = new ClassPathResource("courses.json").getInputStream()) {
            this.courses = mapper.readValue(input, new TypeReference<List<Map<String, Object>>>() {});
        }
        this.byId = new HashMap<>();
        for (var course : courses) {
            byId.put(String.valueOf(course.get("id")), course);
        }
    }

    @GetMapping
    List<Map<String, Object>> list() {
        var scores = ratingSummary();
        var result = new ArrayList<Map<String, Object>>(courses.size());
        for (var course : courses) {
            var item = new LinkedHashMap<>(course);
            item.remove("description");
            item.remove("core_note");
            item.remove("offerings");
            item.putAll(scores.getOrDefault(course.get("id"), emptyRating()));
            result.add(item);
        }
        return result;
    }

    @GetMapping("/{id}")
    Map<String, Object> detail(@PathVariable String id) {
        var result = new LinkedHashMap<>(course(id));
        result.putAll(ratingSummary().getOrDefault(id, emptyRating()));
        return result;
    }

    @GetMapping("/{id}/reviews")
    Map<String, Object> reviews(@PathVariable String id,
                                @RequestParam(defaultValue = "1") int page,
                                @RequestParam(defaultValue = "20") int size,
                                HttpServletRequest request) {
        course(id);
        page = Math.max(1, page);
        size = Math.min(50, Math.max(1, size));
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM course_reviews WHERE course_id=?", Long.class, id);
        var rows = jdbc.queryForList("""
                SELECT user_id, username, score, comment, created_at, updated_at
                FROM course_reviews WHERE course_id=?
                ORDER BY updated_at DESC, user_id LIMIT ? OFFSET ?
                """, id, size, (page - 1) * size);
        var result = new LinkedHashMap<String, Object>();
        result.put("reviews", rows);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        auth.currentUser(request).ifPresent(user -> {
            var mine = jdbc.queryForList("""
                    SELECT user_id, username, score, comment, created_at, updated_at
                    FROM course_reviews WHERE course_id=? AND user_id=?
                    """, id, user.uid());
            if (!mine.isEmpty()) result.put("my_review", mine.get(0));
        });
        return result;
    }

    @PostMapping("/{id}/reviews")
    Map<String, Object> saveReview(@PathVariable String id,
                                   @RequestBody Map<String, Object> body,
                                   HttpServletRequest request) {
        course(id);
        var user = auth.requireLogin(request);
        Object rawScore = body.get("score");
        if (!(rawScore instanceof Number number)
                || number.doubleValue() != number.intValue()
                || number.intValue() < 1 || number.intValue() > 10) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "评分必须是 1 到 10 的整数");
        }
        Object rawComment = body.get("comment");
        if (rawComment != null && !(rawComment instanceof String)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "评价内容格式错误");
        }
        String comment = rawComment == null ? "" : ((String) rawComment).trim();
        if (comment.length() > 1000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "评价不能超过 1000 字");
        }
        String now = LocalDateTime.now().toString();
        jdbc.update("""
                INSERT INTO course_reviews (course_id,user_id,username,score,comment,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE username=VALUES(username),score=VALUES(score),
                                        comment=VALUES(comment),updated_at=VALUES(updated_at)
                """, id, user.uid(), user.username(), number.intValue(), comment, now, now);
        return Map.of("ok", true);
    }

    private Map<String, Object> course(String id) {
        var course = byId.get(id);
        if (course == null) throw new ApiException(HttpStatus.NOT_FOUND, "课程不存在");
        return course;
    }

    private Map<String, Map<String, Object>> ratingSummary() {
        var result = new HashMap<String, Map<String, Object>>();
        for (var row : jdbc.queryForList("""
                SELECT course_id, COUNT(*) AS review_count, ROUND(AVG(score),1) AS average_score
                FROM course_reviews GROUP BY course_id
                """)) {
            result.put(String.valueOf(row.get("course_id")), Map.of(
                    "review_count", row.get("review_count"),
                    "average_score", row.get("average_score")));
        }
        return result;
    }

    private Map<String, Object> emptyRating() {
        return Map.of("review_count", 0, "average_score", 0);
    }
}
