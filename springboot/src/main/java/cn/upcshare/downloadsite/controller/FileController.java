package cn.upcshare.downloadsite.controller;

import cn.upcshare.downloadsite.config.AppProperties;
import cn.upcshare.downloadsite.service.AuthService;
import cn.upcshare.downloadsite.service.UserLevelService;
import cn.upcshare.downloadsite.support.ApiException;
import cn.upcshare.downloadsite.support.Formatters;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class FileController {
    private static final Set<String> ALLOWED = Set.of(
            ".pdf", ".doc", ".docx", ".zip", ".rar", ".7z", ".tar", ".gz",
            ".ppt", ".pptx", ".xls", ".xlsx", ".txt", ".md", ".csv"
    );
    private static final long DAILY_BYTES = 80L * 1024 * 1024;
    private static final long DAILY_COUNT = 20;

    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final UserLevelService levels;
    private final Path resourcesDir;
    private final String nodeName;

    public FileController(JdbcTemplate jdbc, AuthService auth, UserLevelService levels, AppProperties props) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.levels = levels;
        this.resourcesDir = Paths.get(props.getResourcesDir()).toAbsolutePath().normalize();
        this.nodeName = props.getNodeName();
    }

    @GetMapping("/files")
    Map<String, Object> files(@RequestParam Optional<String> q,
                              @RequestParam Optional<String> category,
                              @RequestParam Optional<String> sub_category,
                              @RequestParam Optional<String> ext,
                              @RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "20") int size) {
        page = Math.max(1, page);
        size = Math.min(100, Math.max(1, size));
        List<Object> params = new ArrayList<>();
        List<String> conditions = new ArrayList<>(List.of("status = 'approved'"));
        q.filter(s -> !s.isBlank()).ifPresent(s -> {
            conditions.add("(original_name LIKE ? OR description LIKE ? OR category LIKE ?)");
            params.add("%" + s + "%");
            params.add("%" + s + "%");
            params.add("%" + s + "%");
        });
        category.filter(s -> !s.isBlank()).ifPresent(s -> {
            conditions.add("category = ?");
            params.add(s);
        });
        sub_category.ifPresent(s -> {
            conditions.add("sub_category = ?");
            params.add(s);
        });
        ext.filter(s -> !s.isBlank()).ifPresent(s -> {
            conditions.add("extension = ?");
            params.add(s.startsWith(".") ? s : "." + s);
        });

        String where = " WHERE " + String.join(" AND ", conditions);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM files" + where, Long.class, params.toArray());
        List<Object> listParams = new ArrayList<>(params);
        listParams.add(size);
        listParams.add((page - 1) * size);
        var rows = jdbc.queryForList(
                "SELECT * FROM files" + where + " ORDER BY category, sub_category, original_name LIMIT ? OFFSET ?",
                listParams.toArray()
        );
        return pageResult(total, page, size, rows.stream().map(Formatters::fileDto).toList());
    }

    @GetMapping("/subjects")
    List<Map<String, Object>> subjects() {
        var rows = jdbc.queryForList("""
                SELECT category, COUNT(*) file_count, COALESCE(SUM(file_size),0) total_size
                FROM files
                WHERE category != '' AND status='approved'
                GROUP BY category
                ORDER BY category
                """);
        List<Map<String, Object>> result = new ArrayList<>();
        for (var row : rows) {
            var exts = jdbc.queryForList(
                    "SELECT extension, COUNT(*) cnt FROM files WHERE category=? AND status='approved' GROUP BY extension",
                    row.get("category")
            );
            Map<String, Object> extMap = new LinkedHashMap<>();
            for (var e : exts) extMap.put(String.valueOf(e.get("extension")), e.get("cnt"));
            result.add(Map.of(
                    "name", row.get("category"),
                    "file_count", row.get("file_count"),
                    "total_size", Formatters.size(((Number) row.get("total_size")).longValue()),
                    "extensions", extMap
            ));
        }
        return result;
    }

    @GetMapping("/subjects/{subject}/folders")
    Map<String, Object> folders(@PathVariable String subject) {
        var folders = jdbc.queryForList("""
                SELECT sub_category path, COUNT(*) file_count
                FROM files
                WHERE category=? AND sub_category!='' AND status='approved'
                GROUP BY sub_category
                ORDER BY sub_category
                """, subject);
        long root = jdbc.queryForObject(
                "SELECT COUNT(*) FROM files WHERE category=? AND sub_category='' AND status='approved'",
                Long.class,
                subject
        );
        return Map.of("subject", subject, "root_file_count", root, "folders", folders);
    }

    @GetMapping("/stats")
    Map<String, Object> stats() {
        var r = jdbc.queryForMap("""
                SELECT COUNT(*) total_files,
                       COALESCE(SUM(file_size),0) total_size,
                       COALESCE(SUM(download_count),0) total_downloads,
                       COALESCE(SUM(file_size * download_count),0) total_download_size_bytes,
                       COUNT(DISTINCT category) subject_count
                FROM files
                WHERE status='approved'
                """);
        double gb = ((Number) r.get("total_download_size_bytes")).doubleValue() / Math.pow(1024, 3);
        return Map.of(
                "total_files", r.get("total_files"),
                "total_size", Formatters.size(((Number) r.get("total_size")).longValue()),
                "total_downloads", r.get("total_downloads"),
                "total_download_size_gb", String.format("%.2f GB", gb),
                "subject_count", r.get("subject_count")
        );
    }

    @GetMapping("/categories")
    List<String> categories() {
        return jdbc.queryForList("SELECT DISTINCT category FROM files WHERE category != '' ORDER BY category", String.class);
    }

    @GetMapping("/subcategories")
    List<String> subcategories() {
        return jdbc.queryForList("SELECT DISTINCT sub_category FROM files WHERE sub_category != '' ORDER BY sub_category", String.class);
    }

    @GetMapping("/notice")
    Map<String, Object> notice() {
        var rows = jdbc.queryForList("SELECT value, updated_at FROM site_settings WHERE `key`='notice_text' LIMIT 1");
        if (rows.isEmpty()) return Map.of("text", "", "updated_at", "");
        var row = rows.get(0);
        return Map.of("text", row.get("value"), "updated_at", row.get("updated_at"));
    }

    @PostMapping("/upload")
    Map<String, Object> upload(@RequestParam MultipartFile file,
                               @RequestParam(defaultValue = "") String description,
                               @RequestParam(defaultValue = "uncategorized") String category,
                               @RequestParam(defaultValue = "") String sub_category,
                               HttpServletRequest request) throws Exception {
        var user = auth.requireLogin(request);
        String name = Paths.get(Objects.requireNonNull(file.getOriginalFilename())).getFileName().toString();
        if (name.isBlank() || name.contains("..") || name.matches(".*[<>:\"|?*\\x00-\\x1f].*")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid file name");
        }
        String ext = extension(name);
        if (!ALLOWED.contains(ext)) throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported file type");

        String safeCategory = category.isBlank() ? "uncategorized" : category;
        Path dir = resourcesDir.resolve(safeCategory).resolve(sub_category).normalize();
        if (!dir.startsWith(resourcesDir)) throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid directory");
        Files.createDirectories(dir);

        Path target = uniquePath(dir.resolve(name));
        file.transferTo(target);
        String rel = resourcesDir.relativize(target).toString().replace("\\", "/");
        String id = md5(rel);
        jdbc.update("""
                INSERT INTO files
                (id,file_path,original_name,extension,file_size,description,category,sub_category,created_at,download_count,status,uploader)
                VALUES (?,?,?,?,?,?,?,?,?,0,'pending',?)
                """, id, rel, target.getFileName().toString(), ext, Files.size(target), description,
                safeCategory, sub_category, LocalDateTime.now().toString(), user.username());
        return Map.of("message", "Upload succeeded and is waiting for review", "id", id, "filename", target.getFileName().toString());
    }

    @GetMapping("/download/{id}")
    ResponseEntity<FileSystemResource> download(@PathVariable String id, HttpServletRequest request) throws IOException {
        var user = auth.requireLogin(request);
        var rows = jdbc.queryForList("SELECT * FROM files WHERE id=?", id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "File not found");
        var row = rows.get(0);
        if (!"approved".equals(row.get("status")) && !user.admin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "File is not approved");
        }

        long fileSize = ((Number) row.get("file_size")).longValue();
        if (!hasUnlimitedCampusDownload(user)) {
            var daily = jdbc.queryForMap("""
                    SELECT COALESCE(SUM(file_size),0) total_size, COUNT(*) total_count
                    FROM download_log
                    WHERE user_id=? AND downloaded_at >= ?
                    """, user.uid(), LocalDate.now().toString());
            if (((Number) daily.get("total_count")).longValue() >= DAILY_COUNT) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Daily download count limit reached");
            }
            if (((Number) daily.get("total_size")).longValue() + fileSize > DAILY_BYTES) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Daily download traffic limit reached");
            }
        }

        Path path = resourcesDir.resolve(String.valueOf(row.get("file_path"))).normalize();
        if (!path.startsWith(resourcesDir) || !Files.exists(path)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "File is missing on disk");
        }

        jdbc.update("UPDATE files SET download_count=download_count+1 WHERE id=?", id);
        jdbc.update("""
                INSERT INTO download_log (user_id,file_id,file_size,downloaded_at,event_id,source_node,cloud_synced_at)
                VALUES (?,?,?,?,UUID(),?,'')
                """, user.uid(), id, fileSize, LocalDateTime.now().toString(), nodeName);

        String filename = URLEncoder.encode(String.valueOf(row.get("original_name")), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(path))
                .body(new FileSystemResource(path));
    }

    static Map<String, Object> pageResult(long total, int page, int size, Object items) {
        return Map.of(
                "total", total,
                "page", page,
                "size", size,
                "pages", total == 0 ? 0 : (total + size - 1) / size,
                "items", items
        );
    }

    public static String extension(String name) {
        int i = name.lastIndexOf('.');
        return i >= 0 ? name.substring(i).toLowerCase() : "";
    }

    public static String md5(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] b = md.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte x : b) out.append(String.format("%02x", x));
        return out.toString();
    }

    private static Path uniquePath(Path p) throws IOException {
        if (!Files.exists(p)) return p;
        String n = p.getFileName().toString();
        String ext = extension(n);
        String stem = ext.isEmpty() ? n : n.substring(0, n.length() - ext.length());
        for (int i = 1; ; i++) {
            Path c = p.resolveSibling(stem + "_" + i + ext);
            if (!Files.exists(c)) return c;
        }
    }

    private boolean hasUnlimitedCampusDownload(cn.upcshare.downloadsite.support.CurrentUser user) {
        if (!"campus".equalsIgnoreCase(nodeName)) return false;
        String configuredLevel = jdbc.query("""
                SELECT user_level
                FROM users
                WHERE uid=?
                LIMIT 1
                """, rs -> rs.next() ? rs.getString("user_level") : "auto", user.uid());
        String level = levels.effectiveLevel(user.uid(), user.username(), user.admin(), configuredLevel);
        return Set.of("green", "yellow", "orange", "admin").contains(level);
    }
}
