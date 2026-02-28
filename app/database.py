"""
SQLite 数据库管理
"""
import sqlite3
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
DB_PATH = BASE_DIR / "data" / "files.db"


def get_db() -> sqlite3.Connection:
    """获取数据库连接"""
    DB_PATH.parent.mkdir(exist_ok=True)
    conn = sqlite3.connect(str(DB_PATH))
    conn.row_factory = sqlite3.Row
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
            download_count INTEGER DEFAULT 0
        )
    """)
    db.commit()
    db.close()
