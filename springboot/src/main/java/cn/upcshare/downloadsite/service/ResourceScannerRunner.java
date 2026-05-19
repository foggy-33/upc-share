package cn.upcshare.downloadsite.service;

import cn.upcshare.downloadsite.config.AppProperties;
import cn.upcshare.downloadsite.controller.FileController;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Stream;

@Component
@Order(20)
public class ResourceScannerRunner implements CommandLineRunner {
    private static final Set<String> ALLOWED = Set.of(
            ".pdf", ".doc", ".docx", ".zip", ".rar", ".7z", ".tar", ".gz",
            ".ppt", ".pptx", ".xls", ".xlsx", ".txt", ".md", ".csv"
    );

    private final JdbcTemplate jdbc;
    private final AppProperties props;

    public ResourceScannerRunner(JdbcTemplate jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @Override
    public void run(String... args) throws Exception {
        if (!props.isScanResourcesOnStartup()) return;
        Path root = Paths.get(props.getResourcesDir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) return;

        int count = 0;
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                String ext = FileController.extension(file.getFileName().toString());
                if (!ALLOWED.contains(ext)) continue;

                String rel = root.relativize(file).toString().replace("\\", "/");
                String[] parts = rel.split("/");
                String category = parts.length > 1 ? parts[0] : "uncategorized";
                String subCategory = "";
                if (parts.length > 2) {
                    subCategory = String.join("/", java.util.Arrays.copyOfRange(parts, 1, parts.length - 1));
                }
                String id = FileController.md5(rel);
                int affected = jdbc.update("""
                        INSERT IGNORE INTO files
                        (id,file_path,original_name,extension,file_size,description,category,sub_category,created_at,download_count,status,uploader)
                        VALUES (?,?,?,?,?,?,?,?,?,0,'approved','system')
                        """, id, rel, file.getFileName().toString(), ext, Files.size(file), "",
                        category, subCategory, Instant.ofEpochMilli(Files.getLastModifiedTime(file).toMillis()).toString());
                count += affected;
            }
        }
        if (count > 0) {
            System.out.println("Resource scanner imported " + count + " files from " + root);
        }
    }
}
