package cn.upcshare.downloadsite.controller;

import cn.upcshare.downloadsite.config.AppProperties;
import cn.upcshare.downloadsite.service.AuthService;
import cn.upcshare.downloadsite.support.Formatters;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final Path resourcesDir;

    public AdminController(JdbcTemplate jdbc, AuthService auth, AppProperties props) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.resourcesDir = Paths.get(props.getResourcesDir()).toAbsolutePath().normalize();
    }

    @GetMapping("/files")
    Map<String, Object> files(@RequestParam Optional<String> status,
                              @RequestParam Optional<String> q,
                              @RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "50") int size,
                              HttpServletRequest request) {
        auth.requireAdmin(request);
        page = Math.max(1, page);
        size = Math.min(200, Math.max(1, size));
        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        status.filter(s -> !s.isBlank()).ifPresent(s -> {
            conditions.add("status=?");
            params.add(s);
        });
        q.filter(s -> !s.isBlank()).ifPresent(s -> {
            conditions.add("(original_name LIKE ? OR category LIKE ? OR sub_category LIKE ?)");
            params.add("%" + s + "%");
            params.add("%" + s + "%");
            params.add("%" + s + "%");
        });
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM files" + where, Long.class, params.toArray());
        List<Object> listParams = new ArrayList<>(params);
        listParams.add(size);
        listParams.add((page - 1) * size);
        var rows = jdbc.queryForList("SELECT * FROM files" + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?", listParams.toArray());
        return FileController.pageResult(total, page, size, rows.stream().map(Formatters::fileDto).toList());
    }

    @PostMapping("/approve/{id}")
    Map<String, Object> approve(@PathVariable String id, HttpServletRequest request) {
        auth.requireAdmin(request);
        jdbc.update("UPDATE files SET status='approved' WHERE id=?", id);
        return Map.of("ok", true, "msg", "File approved");
    }

    @PostMapping("/reject/{id}")
    Map<String, Object> reject(@PathVariable String id, HttpServletRequest request) throws Exception {
        auth.requireAdmin(request);
        deleteFileAndRow(id);
        return Map.of("ok", true, "msg", "File rejected and deleted");
    }

    @DeleteMapping("/files/{id}")
    Map<String, Object> delete(@PathVariable String id, HttpServletRequest request) throws Exception {
        auth.requireAdmin(request);
        deleteFileAndRow(id);
        return Map.of("ok", true, "msg", "File deleted");
    }

    @GetMapping("/users")
    Map<String, Object> users(@RequestParam Optional<String> q,
                              @RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "50") int size,
                              HttpServletRequest request) {
        auth.requireAdmin(request);
        page = Math.max(1, page);
        size = Math.min(200, Math.max(1, size));
        String where = q.filter(s -> !s.isBlank()).map(s -> " WHERE u.username LIKE ?").orElse("");
        Object[] params = q.filter(s -> !s.isBlank()).map(s -> new Object[]{"%" + s + "%"}).orElseGet(() -> new Object[]{});
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM users u" + where, Long.class, params);
        List<Object> listParams = new ArrayList<>(List.of(params));
        listParams.add(size);
        listParams.add((page - 1) * size);
        var items = jdbc.query("""
                SELECT u.id AS id,
                       u.username AS username,
                       u.created_at AS created_at,
                       u.is_admin AS is_admin,
                       COALESCE(d.download_count,0) AS download_count,
                       COALESCE(d.download_size_raw,0) AS download_size_raw
                FROM users u
                LEFT JOIN (
                    SELECT user_id, COUNT(*) AS download_count, COALESCE(SUM(file_size),0) AS download_size_raw
                    FROM download_log
                    GROUP BY user_id
                ) d ON d.user_id = CAST(u.id AS CHAR)
                %s
                ORDER BY u.id DESC LIMIT ? OFFSET ?
                """.formatted(where), (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            long id = rs.getLong("id");
            String rawUsername = rs.getString("username");
            String username = rawUsername == null ? "" : String.valueOf(rawUsername).trim();
            long downloadSize = rs.getLong("download_size_raw");
            item.put("id", id);
            item.put("username", username);
            item.put("created_at", rs.getString("created_at"));
            item.put("is_active", true);
            item.put("is_admin", rs.getInt("is_admin") != 0);
            item.put("download_count", rs.getLong("download_count"));
            item.put("download_size_raw", downloadSize);
            item.put("download_size", Formatters.size(downloadSize));
            return item;
        }, listParams.toArray());
        return FileController.pageResult(total, page, size, items);
    }

    @PostMapping("/users/{id}/ban")
    Map<String, Object> ban(@PathVariable long id, HttpServletRequest request) {
        auth.requireAdmin(request);
        jdbc.update("UPDATE users SET is_active=0, updated_at=? WHERE id=? AND is_admin=0", LocalDateTime.now().toString(), id);
        return Map.of("ok", true, "msg", "User banned");
    }

    @PostMapping("/users/{id}/unban")
    Map<String, Object> unban(@PathVariable long id, HttpServletRequest request) {
        auth.requireAdmin(request);
        jdbc.update("UPDATE users SET is_active=1, updated_at=? WHERE id=? AND is_admin=0", LocalDateTime.now().toString(), id);
        return Map.of("ok", true, "msg", "User unbanned");
    }

    private void deleteFileAndRow(String id) throws Exception {
        var rows = jdbc.queryForList("SELECT file_path FROM files WHERE id=?", id);
        if (!rows.isEmpty()) {
            Path file = resourcesDir.resolve(String.valueOf(rows.get(0).get("file_path"))).normalize();
            if (file.startsWith(resourcesDir)) Files.deleteIfExists(file);
        }
        jdbc.update("DELETE FROM files WHERE id=?", id);
    }

}
