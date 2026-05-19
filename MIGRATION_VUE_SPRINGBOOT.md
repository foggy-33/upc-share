# Vue + Spring Boot + MySQL Migration

The refactor is kept in parallel directories so the old FastAPI service can stay intact until cutover:

- `springboot/`: Spring Boot backend compatible with the old `/api/...` routes.
- `vue/`: Vue 3 + Vite frontend.
- `resources/`: physical files. Keep this directory from the source server.
- `data/files.db`: old SQLite database. The Java migration reads it but does not modify it.

## Data Preservation Plan

1. Back up the source server before switching:
   ```bash
   tar -czf download-site-backup.tgz data resources uploads .secret_key
   ```
2. Start MySQL and create the target database.
3. Run Spring Boot once with:
   ```bash
   MIGRATE_SQLITE=true SQLITE_PATH=../data/files.db RESOURCES_DIR=../resources
   ```
4. Check that users, files, download logs, site settings, and forum data exist in MySQL.
5. Restart without `MIGRATE_SQLITE=true`.
6. Point Nginx traffic to the Spring Boot backend after verification.

The backend also scans `resources/` on startup and inserts missing approved file records, so a file copied from the old server but absent from SQLite/MySQL is still discoverable.

## Performance Notes

- MySQL replaces SQLite's single-writer bottleneck.
- HikariCP connection pooling is enabled; tune with `DB_POOL_MAX`.
- Tomcat concurrency is tunable with `TOMCAT_MAX_THREADS`, `TOMCAT_MAX_CONNECTIONS`, and `TOMCAT_ACCEPT_COUNT`.
- File downloads stream from disk through `FileSystemResource` and include `Content-Length`.
- Public list, admin list, quota, sync, and forum indexes are created for fresh databases and also ensured on startup for existing ones.
