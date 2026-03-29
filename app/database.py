"""
SQLite 数据库管理
"""
import sqlite3
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
DB_PATH = BASE_DIR / "data" / "files.db"


def get_db() -> sqlite3.Connection:
    """获取数据库连接（优化并发读写）"""
    DB_PATH.parent.mkdir(exist_ok=True)
    conn = sqlite3.connect(str(DB_PATH), timeout=10)
    conn.row_factory = sqlite3.Row
    # WAL 模式：允许多个读取者同时访问，写入不阻塞读取
    conn.execute("PRAGMA journal_mode=WAL")
    # 忙等待时间 5 秒，避免 database is locked
    conn.execute("PRAGMA busy_timeout=5000")
    return conn


def init_db():
    """初始化数据库表"""
    db = get_db()
    db.execute("""
        CREATE TABLE IF NOT EXISTS files (
            id TEXT PRIMARY KEY,
            file_path TEXT NOT NULL UNIQUE,
            original_name TEXT NOT NULL,
            extension TEXT NOT NULL,
            file_size INTEGER DEFAULT 0,
            description TEXT DEFAULT '',
            category TEXT DEFAULT '',
            sub_category TEXT DEFAULT '',
            created_at TEXT NOT NULL,
            download_count INTEGER DEFAULT 0,
            status TEXT DEFAULT 'pending',
            uploader TEXT DEFAULT ''
        )
    """)
    db.execute("""
        CREATE TABLE IF NOT EXISTS users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT NOT NULL UNIQUE,
            password_hash TEXT NOT NULL,
            created_at TEXT NOT NULL,
            is_active INTEGER DEFAULT 1,
            is_admin INTEGER DEFAULT 0
        )
    """)
    # ── 用户每日下载记录表（限制每用户每天下载量）───
    db.execute("""
        CREATE TABLE IF NOT EXISTS download_log (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id TEXT NOT NULL,
            file_id TEXT NOT NULL,
            file_size INTEGER DEFAULT 0,
            downloaded_at TEXT NOT NULL
        )
    """)
    db.execute("""
        CREATE TABLE IF NOT EXISTS site_settings (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )
    """)
    db.execute("""
        CREATE TABLE IF NOT EXISTS forum_posts (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER NOT NULL,
            username TEXT NOT NULL,
            content TEXT NOT NULL,
            created_at TEXT NOT NULL
        )
    """)
    db.execute("""
        CREATE TABLE IF NOT EXISTS forum_comments (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            post_id INTEGER NOT NULL,
            user_id INTEGER NOT NULL,
            username TEXT NOT NULL,
            content TEXT NOT NULL,
            created_at TEXT NOT NULL,
            FOREIGN KEY(post_id) REFERENCES forum_posts(id) ON DELETE CASCADE
        )
    """)
    db.execute("""
        CREATE INDEX IF NOT EXISTS idx_dl_user_date
        ON download_log (user_id, downloaded_at)
    """)
    db.execute("""
        CREATE INDEX IF NOT EXISTS idx_forum_posts_created
        ON forum_posts (created_at DESC)
    """)
    db.execute("""
        CREATE INDEX IF NOT EXISTS idx_forum_comments_post
        ON forum_comments (post_id, created_at ASC)
    """)
    # ── 自动迁移：给旧表加新字段 ──────────────────────
    for col, default in [("status", "'approved'"), ("uploader", "''")]:
        try:
            db.execute(f"ALTER TABLE files ADD COLUMN {col} TEXT DEFAULT {default}")
        except Exception:
            pass
    for col, default in [("is_admin", "0")]:
        try:
            db.execute(f"ALTER TABLE users ADD COLUMN {col} INTEGER DEFAULT {default}")
        except Exception:
            pass

    # 兼容旧版 site_settings（可能缺少 value / updated_at 字段）
    try:
        setting_cols = {
            row["name"] if isinstance(row, sqlite3.Row) else row[1]
            for row in db.execute("PRAGMA table_info(site_settings)").fetchall()
        }
        if "value" not in setting_cols:
            db.execute("ALTER TABLE site_settings ADD COLUMN value TEXT NOT NULL DEFAULT ''")
        if "updated_at" not in setting_cols:
            db.execute("ALTER TABLE site_settings ADD COLUMN updated_at TEXT NOT NULL DEFAULT ''")
        db.execute(
            "UPDATE site_settings SET updated_at = datetime('now', 'localtime') WHERE updated_at = '' OR updated_at IS NULL"
        )
    except Exception:
        # 若迁移失败，保留运行能力，后续由接口层兜底
        pass

    db.execute(
        """INSERT OR IGNORE INTO site_settings (key, value, updated_at)
           VALUES ('notice_text', '欢迎大家使用upcshare！', datetime('now', 'localtime'))"""
    )
    db.commit()
    db.close()
