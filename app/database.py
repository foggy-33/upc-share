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
        CREATE INDEX IF NOT EXISTS idx_dl_user_date
        ON download_log (user_id, downloaded_at)
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
    db.commit()
    db.close()
