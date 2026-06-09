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

    private Map<String, Object> findImage(String id) {
        if (!id.matches("[A-Za-z0-9]{32}")) throw new ApiException(HttpStatus.NOT_FOUND, "Image not found");
        var rows = jdbc.queryForList("SELECT * FROM forum_images WHERE id=? LIMIT 1", id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Image not found");
        return rows.get(0);
    }
}
