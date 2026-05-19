# Spring Boot + MySQL Backend

This backend keeps the old FastAPI API shape under `/api/...`, but stores metadata in MySQL and keeps physical files in the existing `resources/` directory.

## Start MySQL

```bash
cd springboot
docker compose up -d mysql
```

## Run the backend

```bash
cd springboot
mvn spring-boot:run
```

Common environment variables:

```bash
MYSQL_HOST=127.0.0.1
MYSQL_PORT=3306
MYSQL_DATABASE=download_site
MYSQL_USER=download_site
MYSQL_PASSWORD=change-me
RESOURCES_DIR=../resources
JWT_SECRET=<content of ../.secret_key>
DB_POOL_MAX=40
TOMCAT_MAX_THREADS=300
```

## Preserve and Import Old Server Data

The migration is read-only for the old SQLite database. It imports:

- `files`
- `users`
- `download_log`
- `site_settings`
- `forum_posts`
- `forum_comments`

The real resource files are not copied by the backend. Point `RESOURCES_DIR` to the old server's `resources/` directory or copy that directory as-is before switching traffic.

First startup on the new stack:

```bash
cd springboot
MIGRATE_SQLITE=true SQLITE_PATH=../data/files.db RESOURCES_DIR=../resources mvn spring-boot:run
```

After the import finishes, restart without `MIGRATE_SQLITE=true`:

```bash
cd springboot
RESOURCES_DIR=../resources mvn spring-boot:run
```

`SCAN_RESOURCES_ON_STARTUP=true` is enabled by default. It scans `resources/` and inserts any approved files missing from MySQL, so files already present on the source server remain visible after migration.

## Concurrency Tuning

The default configuration enables:

- HikariCP MySQL pooling through `DB_POOL_MAX` and `DB_POOL_MIN_IDLE`
- Tomcat thread and connection limits through `TOMCAT_MAX_THREADS`, `TOMCAT_MAX_CONNECTIONS`, and `TOMCAT_ACCEPT_COUNT`
- gzip compression for JSON and text responses
- covering indexes for public file lists, admin lists, download quotas, and forum lookups
- `FileSystemResource` downloads with `Content-Length`, which avoids buffering whole files in Java memory
