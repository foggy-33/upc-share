package cn.upcshare.downloadsite.controller;

import cn.upcshare.downloadsite.config.AppProperties;
import cn.upcshare.downloadsite.service.AuthService;
import cn.upcshare.downloadsite.support.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/forum/images")
public class ForumImageController {
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp");
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");

    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final Path imageRoot;

    public ForumImageController(JdbcTemplate jdbc, AuthService auth, AppProperties props) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.imageRoot = Paths.get(props.getResourcesDir()).toAbsolutePath().normalize().resolve("forum-images").normalize();
    }

    @PostMapping
    Map<String, Object> upload(@RequestParam MultipartFile file, HttpServletRequest request) throws Exception {
        var user = auth.requireLogin(request);
        if (file.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Image is empty");
        if (file.getSize() > MAX_IMAGE_BYTES) throw new ApiException(HttpStatus.BAD_REQUEST, "Image must be 10MB or smaller");

        String originalName = Paths.get(Objects.requireNonNullElse(file.getOriginalFilename(), "image")).getFileName().toString();
        String ext = FileController.extension(originalName);
        String contentType = Objects.requireNonNullElse(file.getContentType(), "").toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(ext) || !ALLOWED_TYPES.contains(contentType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported image type");
        }

        String day = LocalDate.now().toString();
        Path dir = imageRoot.resolve(day).normalize();
        if (!dir.startsWith(imageRoot)) throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid image path");
        Files.createDirectories(dir);

        String id = UUID.randomUUID().toString().replace("-", "");
        Path target = dir.resolve(id + ext).normalize();
        file.transferTo(target);
        String relPath = imageRoot.relativize(target).toString().replace("\\", "/");
        jdbc.update("""
                INSERT INTO forum_images (id,user_id,username,file_path,original_name,mime_type,file_size,created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, id, user.uid(), user.username(), relPath, originalName, contentType, Files.size(target), LocalDateTime.now().toString());

        return Map.of(
                "id", id,
                "url", "/api/forum/images/" + id,
                "original_name", originalName,
                "size", Files.size(target)
        );
    }

    @GetMapping("/{id}")
    ResponseEntity<FileSystemResource> image(@PathVariable String id) throws IOException {
        var row = findImage(id);
        Path path = imageRoot.resolve(String.valueOf(row.get("file_path"))).normalize();
        if (!path.startsWith(imageRoot) || !Files.exists(path)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Image not found");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(String.valueOf(row.get("mime_type"))))
                .contentLength(Files.size(path))
                .body(new FileSystemResource(path));
    }

    @DeleteMapping("/{id}")
    Map<String, Object> deleteImage(@PathVariable String id, HttpServletRequest request) throws IOException {
        var user = auth.requireLogin(request);
        var row = findImage(id);
        String ownerUid = String.valueOf(row.get("user_id"));
        if (!user.admin() && !user.uid().equals(ownerUid)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the image owner can delete this image");
        }
        Path path = imageRoot.resolve(String.valueOf(row.get("file_path"))).normalize();
        if (!path.startsWith(imageRoot)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid image path");
        }
        Files.deleteIfExists(path);
        jdbc.update("DELETE FROM forum_images WHERE id=?", id);
        return Map.of("ok", true, "deleted", true);
    }

    @GetMapping("/users/{uid}")
    Map<String, Object> userImages(@PathVariable String uid,
                                   @RequestParam(defaultValue = "1") int page,
                                   @RequestParam(defaultValue = "12") int size,
                                   @RequestParam(name = "include_unpublished", defaultValue = "false") boolean include_unpublished,
                                   HttpServletRequest request) {
        page = Math.max(1, page);
        size = Math.min(48, Math.max(1, size));
        Long userCount = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE uid=?", Long.class, uid);
        if (userCount == null || userCount == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "User not found");
        }
        if (include_unpublished) {
            var user = auth.requireLogin(request);
            if (!user.admin() && !user.uid().equals(uid)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Only the image owner can view unpublished images");
            }
        }
        String publishedCondition = """
                user_id=? AND (
                    EXISTS (
                        SELECT 1 FROM forum_posts p
                        WHERE p.user_id=forum_images.user_id
                          AND p.content LIKE CONCAT('%/api/forum/images/',forum_images.id,'%')
                    )
                    OR EXISTS (
                        SELECT 1 FROM forum_comments c
                        WHERE c.user_id=forum_images.user_id
                          AND c.content LIKE CONCAT('%/api/forum/images/',forum_images.id,'%')
                    )
                )
                """;
        String condition = include_unpublished ? "user_id=?" : publishedCondition;
        long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM forum_images WHERE " + condition,
                Long.class,
                uid
        );
        var rows = jdbc.queryForList("""
                SELECT id,original_name,mime_type,file_size,created_at
                FROM forum_images
                WHERE
                """ + condition + """
                ORDER BY created_at DESC,id DESC
                LIMIT ? OFFSET ?
                """, uid, size, (page - 1) * size);
        var items = rows.stream().map(row -> Map.of(
                "id", row.get("id"),
                "url", "/api/forum/images/" + row.get("id"),
                "original_name", row.get("original_name"),
                "mime_type", row.get("mime_type"),
                "file_size", row.get("file_size"),
                "created_at", row.get("created_at")
        )).toList();
        return FileController.pageResult(total, page, size, items);
    }

    private Map<String, Object> findImage(String id) {
        if (!id.matches("[A-Za-z0-9]{32}")) throw new ApiException(HttpStatus.NOT_FOUND, "Image not found");
        var rows = jdbc.queryForList("SELECT * FROM forum_images WHERE id=? LIMIT 1", id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Image not found");
        return rows.get(0);
    }
}
