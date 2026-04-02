"""
学科资料下载站 - FastAPI 主应用
支持文件夹式学科分类，自动扫描 resources/ 目录
"""
import os
import time
import uuid
import hashlib
import re
from datetime import datetime
from pathlib import Path
from typing import Optional
from collections import defaultdict

from fastapi import FastAPI, UploadFile, File, Form, Query, HTTPException, Request
from fastapi.responses import FileResponse, HTMLResponse, RedirectResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel

from app.database import get_db, init_db
from app.models import FileRecord
from app.auth import (
    register_user, login_user, get_current_user, require_login,
    set_auth_cookie, clear_auth_cookie, require_admin,
)


# ── 应用层速率限制器 ─────────────────────────────────────
class RateLimiter:
    """简单的内存速率限制器（按 IP）"""
    def __init__(self, max_requests: int, window_seconds: int):
        self.max_requests = max_requests
        self.window = window_seconds
        self._records: dict[str, list[float]] = defaultdict(list)

    def is_allowed(self, key: str) -> bool:
        now = time.time()
        # 清理过期记录
        self._records[key] = [t for t in self._records[key] if now - t < self.window]
        if len(self._records[key]) >= self.max_requests:
            return False
        self._records[key].append(now)
        return True

# 上传限制：每个 IP 每 60 秒最多 6 次
upload_limiter = RateLimiter(max_requests=6, window_seconds=60)
# 注册限制：每个 IP 每 300 秒最多 5 次
register_limiter = RateLimiter(max_requests=5, window_seconds=300)
# 下载限制：每个用户每 10 秒最多 5 次下载请求（防刷）
download_limiter = RateLimiter(max_requests=3, window_seconds=10)

# ── 每用户每日下载量限制 ─────────────────────────────────
MAX_DAILY_DOWNLOAD_SIZE = 80 * 1024 * 1024   # 每用户每天最多下载 80MB
MAX_DAILY_DOWNLOAD_COUNT = 20                  # 每用户每天最多下载 20 次


def _get_user_daily_download(user_id: str) -> tuple[int, int]:
    """查询用户今日已下载的总大小(bytes)和次数"""
    today = datetime.now().strftime("%Y-%m-%d")
    db = get_db()
    row = db.execute(
        """SELECT COALESCE(SUM(file_size), 0) as total_size, COUNT(*) as total_count
           FROM download_log
           WHERE user_id = ? AND downloaded_at >= ?""",
        (user_id, today),
    ).fetchone()
    db.close()
    return row["total_size"], row["total_count"]


def _log_download(user_id: str, file_id: str, file_size: int):
    """记录一次下载"""
    db = get_db()
    db.execute(
        "INSERT INTO download_log (user_id, file_id, file_size, downloaded_at) VALUES (?, ?, ?, ?)",
        (user_id, file_id, file_size, datetime.now().isoformat()),
    )
    db.commit()
    db.close()


def _get_client_ip(request: Request) -> str:
    """获取真实客户端 IP（兼容 Nginx 代理）"""
    forwarded = request.headers.get("x-real-ip") or request.headers.get("x-forwarded-for")
    if forwarded:
        return forwarded.split(",")[0].strip()
    return request.client.host if request.client else "unknown"

# ── 基础配置 ──────────────────────────────────────────────
BASE_DIR = Path(__file__).resolve().parent.parent
RESOURCES_DIR = BASE_DIR / "resources"
RESOURCES_DIR.mkdir(exist_ok=True)

ALLOWED_EXTENSIONS = {
    ".pdf", ".doc", ".docx",
    ".zip", ".rar", ".7z", ".tar", ".gz",
    ".ppt", ".pptx", ".xls", ".xlsx",
    ".txt", ".md", ".csv",
}
MAX_FILE_SIZE = 200 * 1024 * 1024  # 200 MB

app = FastAPI(title="学科资料下载站", version="2.0.0")

# 静态文件 & 模板
app.mount("/static", StaticFiles(directory=str(BASE_DIR / "static")), name="static")
app.mount("/photo", StaticFiles(directory=str(BASE_DIR / "photo")), name="photo")
templates = Jinja2Templates(directory=str(BASE_DIR / "templates"))


# ── 文件夹扫描 ────────────────────────────────────────────
def scan_resources():
    """扫描 resources/ 目录，将文件录入数据库（增量）"""
    db = get_db()
    existing = {row[0] for row in db.execute("SELECT file_path FROM files").fetchall()}

    count = 0
    for subject_dir in sorted(RESOURCES_DIR.iterdir()):
        if not subject_dir.is_dir():
            continue
        subject_name = subject_dir.name

        for file_path in sorted(subject_dir.rglob("*")):
            if not file_path.is_file():
                continue
            ext = file_path.suffix.lower()
            if ext not in ALLOWED_EXTENSIONS:
                continue

            rel_path = str(file_path.relative_to(RESOURCES_DIR)).replace("\\", "/")
            if rel_path in existing:
                continue

            sub_path = str(file_path.relative_to(subject_dir)).replace("\\", "/")
            sub_category = ""
            parts = Path(sub_path).parts
            if len(parts) > 1:
                sub_category = "/".join(parts[:-1])

            file_id = hashlib.md5(rel_path.encode()).hexdigest()
            file_size = file_path.stat().st_size
            mtime = datetime.fromtimestamp(file_path.stat().st_mtime).isoformat()

            db.execute(
                """INSERT OR IGNORE INTO files
                   (id, file_path, original_name, extension, file_size,
                    description, category, sub_category, created_at, download_count,
                    status, uploader)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (file_id, rel_path, file_path.name, ext, file_size,
                 "", subject_name, sub_category, mtime, 0, "approved", "system"),
            )
            count += 1

    db.commit()
    db.close()
    return count


@app.on_event("startup")
async def startup():
    init_db()
    n = scan_resources()
    if n:
        print(f"[FileHub] 扫描到 {n} 个新文件已入库")


# ── 认证数据模型 ──────────────────────────────────────
class AuthRequest(BaseModel):
    username: str
    password: str


class NoticeUpdateRequest(BaseModel):
    text: str


class ForumPostCreateRequest(BaseModel):
    content: str


class ForumCommentCreateRequest(BaseModel):
    content: str


def _ensure_notice_setting(db):
    """确保公告配置表和默认记录存在，避免未迁移时报错。"""
    db.execute(
        """
        CREATE TABLE IF NOT EXISTS site_settings (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )
        """
    )

    # 兼容旧版库：site_settings 可能缺少字段
    cols = {row["name"] for row in db.execute("PRAGMA table_info(site_settings)").fetchall()}
    if "value" not in cols:
        db.execute("ALTER TABLE site_settings ADD COLUMN value TEXT NOT NULL DEFAULT ''")
    if "updated_at" not in cols:
        db.execute("ALTER TABLE site_settings ADD COLUMN updated_at TEXT NOT NULL DEFAULT ''")

    db.execute(
        "UPDATE site_settings SET updated_at = datetime('now', 'localtime') WHERE updated_at = '' OR updated_at IS NULL"
    )
    db.execute(
        """INSERT OR IGNORE INTO site_settings (key, value, updated_at)
           VALUES ('notice_text', '欢迎大家使用upcshare！', datetime('now', 'localtime'))"""
    )


# ── 页面路由 ──────────────────────────────────────────
@app.get("/", response_class=HTMLResponse)
async def index(request: Request):
    user = get_current_user(request)
    return templates.TemplateResponse(request, "index.html", {"request": request, "user": user})


@app.get("/login", response_class=HTMLResponse)
async def login_page(request: Request):
    if get_current_user(request):
        return RedirectResponse("/", status_code=302)
    return templates.TemplateResponse(request, "login.html", {"request": request})


@app.get("/register", response_class=HTMLResponse)
async def register_page(request: Request):
    if get_current_user(request):
        return RedirectResponse("/", status_code=302)
    return templates.TemplateResponse(request, "register.html", {"request": request})


@app.get("/admin", response_class=HTMLResponse)
async def admin_page(request: Request):
    user = get_current_user(request)
    if not user:
        return RedirectResponse("/login?next=/admin", status_code=302)
    return templates.TemplateResponse(request, "admin.html", {"request": request, "user": user})


@app.get("/dashboard", response_class=HTMLResponse)
async def dashboard_page(request: Request):
    """管理员后台"""
    user = get_current_user(request)
    if not user:
        return RedirectResponse("/login?next=/dashboard", status_code=302)
    if not user.get("is_admin"):
        raise HTTPException(403, "无权访问管理后台")
    return templates.TemplateResponse(request, "dashboard.html", {"request": request, "user": user})


@app.get("/api/categories")
async def get_categories():
    """获取所有学科分类"""
    db = get_db()
    rows = db.execute("SELECT DISTINCT category FROM files ORDER BY category").fetchall()
    db.close()
    return [row[0] for row in rows if row[0]] # 过滤掉空字符串


@app.get("/api/subcategories")
async def get_subcategories():
    """获取所有子目录"""
    db = get_db()
    rows = db.execute("SELECT DISTINCT sub_category FROM files ORDER BY sub_category").fetchall()
    db.close()
    return [row[0] for row in rows if row[0]] # 过滤掉空字符串


@app.get("/api/notice")
async def get_notice():
    """获取站点公告"""
    db = get_db()
    _ensure_notice_setting(db)
    db.commit()
    row = db.execute(
        "SELECT value, updated_at FROM site_settings WHERE key = 'notice_text'"
    ).fetchone()
    db.close()

    if not row:
        return {"text": "欢迎大家使用upcshare！", "updated_at": ""}
    return {"text": row["value"], "updated_at": row["updated_at"]}


# ── 管理员 API ──────────────────────────────────────
@app.get("/api/admin/files")
async def admin_list_files(
    request: Request,
    status: Optional[str] = Query(None, description="按状态筛选: pending/approved/rejected"),
    q: Optional[str] = Query(None, description="搜索关键词"),
    page: int = Query(1, ge=1),
    size: int = Query(50, ge=1, le=200),
):
    """管理员：获取文件列表（支持按状态筛选和搜索）"""
    require_admin(request)
    db = get_db()
    offset = (page - 1) * size
    conditions, params = [], []
    if status:
        conditions.append("status = ?")
        params.append(status)
    if q:
        conditions.append("(original_name LIKE ? OR category LIKE ? OR sub_category LIKE ?)")
        params.extend([f"%{q}%", f"%{q}%", f"%{q}%"])
    where = "WHERE " + " AND ".join(conditions) if conditions else ""
    total = db.execute(f"SELECT COUNT(*) FROM files {where}", params).fetchone()[0]
    rows = db.execute(
        f"SELECT * FROM files {where} ORDER BY created_at DESC LIMIT ? OFFSET ?",
        params + [size, offset],
    ).fetchall()
    files = [FileRecord.from_row(r) for r in rows]
    db.close()
    return {
        "total": total, "page": page, "size": size,
        "pages": (total + size - 1) // size if total else 0,
        "items": [f.to_dict() for f in files],
    }


@app.post("/api/admin/approve/{file_id}")
async def admin_approve_file(file_id: str, request: Request):
    """管理员：审核通过"""
    require_admin(request)
    db = get_db()
    row = db.execute("SELECT * FROM files WHERE id = ?", (file_id,)).fetchone()
    if not row:
        db.close()
        raise HTTPException(404, "文件不存在")
    db.execute("UPDATE files SET status = 'approved' WHERE id = ?", (file_id,))
    db.commit()
    db.close()
    return {"ok": True, "msg": "已通过审核"}


@app.post("/api/admin/reject/{file_id}")
async def admin_reject_file(file_id: str, request: Request):
    """管理员：拒绝并删除文件"""
    require_admin(request)
    db = get_db()
    row = db.execute("SELECT * FROM files WHERE id = ?", (file_id,)).fetchone()
    if not row:
        db.close()
        raise HTTPException(404, "文件不存在")
    record = FileRecord.from_row(row)
    # 删除磁盘文件
    file_path = RESOURCES_DIR / record.file_path
    if file_path.exists():
        file_path.unlink()
    # 删除数据库记录
    db.execute("DELETE FROM files WHERE id = ?", (file_id,))
    db.commit()
    db.close()
    return {"ok": True, "msg": "已拒绝并删除文件"}


@app.delete("/api/admin/files/{file_id}")
async def admin_delete_file(file_id: str, request: Request):
    """管理员：删除任意文件"""
    require_admin(request)
    db = get_db()
    row = db.execute("SELECT * FROM files WHERE id = ?", (file_id,)).fetchone()
    if not row:
        db.close()
        raise HTTPException(404, "文件不存在")
    record = FileRecord.from_row(row)
    file_path = RESOURCES_DIR / record.file_path
    if file_path.exists():
        file_path.unlink()
    db.execute("DELETE FROM files WHERE id = ?", (file_id,))
    db.commit()
    db.close()
    return {"ok": True, "msg": "已删除文件"}


@app.get("/api/admin/users")
async def admin_list_users(
    request: Request,
    q: Optional[str] = Query(None, description="搜索用户名"),
    page: int = Query(1, ge=1),
    size: int = Query(50, ge=1, le=200),
):
    """管理员：获取用户列表（含下载统计）"""
    require_admin(request)
    db = get_db()
    offset = (page - 1) * size

    conditions, params = [], []
    if q:
        conditions.append("u.username LIKE ?")
        params.append(f"%{q}%")
    where = "WHERE " + " AND ".join(conditions) if conditions else ""

    total = db.execute(f"SELECT COUNT(*) FROM users u {where}", params).fetchone()[0]
    rows = db.execute(
        f"""
        SELECT
            u.id,
            u.username,
            u.created_at,
            u.is_active,
            u.is_admin,
            COALESCE(COUNT(dl.id), 0) AS download_count,
            COALESCE(SUM(dl.file_size), 0) AS download_size
        FROM users u
        LEFT JOIN download_log dl ON dl.user_id = CAST(u.id AS TEXT)
        {where}
        GROUP BY u.id
        ORDER BY u.created_at DESC
        LIMIT ? OFFSET ?
        """,
        params + [size, offset],
    ).fetchall()
    db.close()

    items = []
    for row in rows:
        items.append(
            {
                "id": row["id"],
                "username": row["username"],
                "created_at": row["created_at"],
                "is_active": bool(row["is_active"]),
                "is_admin": bool(row["is_admin"]),
                "download_count": row["download_count"],
                "download_size": _fmt_size(row["download_size"] or 0),
            }
        )

    return {
        "total": total,
        "page": page,
        "size": size,
        "pages": (total + size - 1) // size if total else 0,
        "items": items,
    }


@app.post("/api/admin/users/{user_id}/ban")
async def admin_ban_user(user_id: int, request: Request):
    """管理员：封禁用户"""
    admin_user = require_admin(request)
    if int(admin_user["id"]) == user_id:
        raise HTTPException(400, "不能封禁当前登录账号")

    db = get_db()
    row = db.execute(
        "SELECT id, username, is_admin, is_active FROM users WHERE id = ?",
        (user_id,),
    ).fetchone()
    if not row:
        db.close()
        raise HTTPException(404, "用户不存在")
    if row["is_admin"]:
        db.close()
        raise HTTPException(400, "不能封禁管理员账号")

    db.execute("UPDATE users SET is_active = 0 WHERE id = ?", (user_id,))
    db.commit()
    db.close()
    return {"ok": True, "msg": f"已封禁用户 {row['username']}"}


@app.post("/api/admin/users/{user_id}/unban")
async def admin_unban_user(user_id: int, request: Request):
    """管理员：解封用户"""
    require_admin(request)
    db = get_db()
    row = db.execute(
        "SELECT id, username, is_active FROM users WHERE id = ?",
        (user_id,),
    ).fetchone()
    if not row:
        db.close()
        raise HTTPException(404, "用户不存在")

    db.execute("UPDATE users SET is_active = 1 WHERE id = ?", (user_id,))
    db.commit()
    db.close()
    return {"ok": True, "msg": f"已解封用户 {row['username']}"}


# ── 认证 API ────────────────────────────────────────
@app.post("/api/auth/register")
async def api_register(body: AuthRequest, request: Request):
    # 注册频率限制
    client_ip = _get_client_ip(request)
    if not register_limiter.is_allowed(client_ip):
        return JSONResponse({"ok": False, "msg": "注册过于频繁，请 5 分钟后再试"}, status_code=429)
    result = register_user(body.username, body.password)
    if not result["ok"]:
        return JSONResponse({"ok": False, "msg": result["msg"]}, status_code=400)
    return {"ok": True, "msg": "注册成功"}


@app.post("/api/auth/login")
async def api_login(body: AuthRequest, request: Request):
    result = login_user(body.username, body.password)
    if not result["ok"]:
        return JSONResponse({"ok": False, "msg": result["msg"]}, status_code=401)
    response = JSONResponse({"ok": True, "username": result["username"]})
    set_auth_cookie(response, result["token"], request)
    return response


@app.post("/api/auth/logout")
async def api_logout():
    response = JSONResponse({"ok": True, "msg": "已退出登录"})
    clear_auth_cookie(response)
    return response


@app.get("/api/auth/me")
async def api_me(request: Request):
    user = get_current_user(request)
    if not user:
        return {"logged_in": False}
    return {"logged_in": True, "username": user["username"], "is_admin": user.get("is_admin", False)}


# ── API 路由 ──────────────────────────────────────────────
@app.get("/api/files")
async def list_files(
    q: Optional[str] = Query(None, description="搜索关键词"),
    category: Optional[str] = Query(None, description="学科分类"),
    sub_category: Optional[str] = Query(None, description="子分类路径"),
    ext: Optional[str] = Query(None, description="文件类型筛选"),
    page: int = Query(1, ge=1),
    size: int = Query(20, ge=1, le=100),
):
    """获取文件列表（仅已审核通过的）"""
    db = get_db()
    offset = (page - 1) * size
    conditions, params = ["status = 'approved'"], []

    if q:
        conditions.append("(original_name LIKE ? OR description LIKE ? OR category LIKE ?)")
        params.extend([f"%{q}%", f"%{q}%", f"%{q}%"])
    if category:
        conditions.append("category = ?")
        params.append(category)
    if sub_category is not None:
        conditions.append("sub_category = ?")
        params.append(sub_category)
    if ext:
        conditions.append("extension = ?")
        params.append(ext if ext.startswith(".") else f".{ext}")

    where = "WHERE " + " AND ".join(conditions) if conditions else ""
    total = db.execute(f"SELECT COUNT(*) FROM files {where}", params).fetchone()[0]
    rows = db.execute(
        f"SELECT * FROM files {where} ORDER BY category, sub_category, original_name LIMIT ? OFFSET ?",
        params + [size, offset],
    ).fetchall()
    files = [FileRecord.from_row(r) for r in rows]
    db.close()

    return {
        "total": total, "page": page, "size": size,
        "pages": (total + size - 1) // size if total else 0,
        "items": [f.to_dict() for f in files],
    }


@app.get("/api/subjects")
async def get_subjects():
    """获取学科分类统计"""
    db = get_db()
    rows = db.execute("""
        SELECT category, COUNT(*) as file_count, SUM(file_size) as total_size
        FROM files WHERE category IS NOT NULL AND category != '' AND status = 'approved'
        GROUP BY category ORDER BY category
    """).fetchall()

    subjects = []
    for r in rows:
        ext_rows = db.execute(
            "SELECT extension, COUNT(*) as cnt FROM files WHERE category = ? AND status = 'approved' GROUP BY extension",
            (r["category"],),
        ).fetchall()
        subjects.append({
            "name": r["category"],
            "file_count": r["file_count"],
            "total_size": _fmt_size(r["total_size"] or 0),
            "extensions": {row["extension"]: row["cnt"] for row in ext_rows},
        })
    db.close()
    return subjects


@app.get("/api/subjects/{subject}/folders")
async def get_subject_folders(subject: str):
    """获取某学科下的子文件夹结构"""
    db = get_db()
    rows = db.execute("""
        SELECT sub_category, COUNT(*) as file_count
        FROM files WHERE category = ? AND sub_category != '' AND status = 'approved'
        GROUP BY sub_category ORDER BY sub_category
    """, (subject,)).fetchall()
    root_count = db.execute(
        "SELECT COUNT(*) FROM files WHERE category = ? AND sub_category = '' AND status = 'approved'",
        (subject,),
    ).fetchone()[0]
    db.close()
    return {
        "subject": subject,
        "root_file_count": root_count,
        "folders": [{"path": r["sub_category"], "file_count": r["file_count"]} for r in rows],
    }


@app.get("/api/stats")
async def get_stats():
    """全站统计"""
    db = get_db()
    total_files = db.execute("SELECT COUNT(*) FROM files WHERE status = 'approved'").fetchone()[0]
    total_size = db.execute("SELECT SUM(file_size) FROM files WHERE status = 'approved'").fetchone()[0] or 0
    total_downloads = db.execute("SELECT SUM(download_count) FROM files WHERE status = 'approved'").fetchone()[0] or 0
    total_download_size_bytes = db.execute(
        "SELECT SUM(file_size * download_count) FROM files WHERE status = 'approved'"
    ).fetchone()[0] or 0
    subject_count = db.execute("SELECT COUNT(DISTINCT category) FROM files WHERE category != '' AND status = 'approved'").fetchone()[0]
    db.close()

    total_download_size_gb = total_download_size_bytes / (1024 ** 3)
    return {
        "total_files": total_files,
        "total_size": _fmt_size(total_size),
        "total_downloads": total_downloads,
        "total_download_size_gb": f"{total_download_size_gb:.2f} GB",
        "subject_count": subject_count,
    }


@app.get("/api/forum/posts")
async def list_forum_posts(
    request: Request,
    page: int = Query(1, ge=1),
    size: int = Query(20, ge=1, le=50),
    q: Optional[str] = Query(None, description="搜索关键词"),
):
    """论坛：获取求助帖列表（含评论）"""
    user = get_current_user(request)
    is_admin = bool(user and user.get("is_admin"))

    db = get_db()
    offset = (page - 1) * size

    # Based on whether there is a query parameter q, different query statements are constructed
    if q:
        # Fuzzy search for content and username fields
        total = db.execute(
            "SELECT COUNT(*) FROM forum_posts WHERE content LIKE ? OR username LIKE ?", (f"%{q}%", f"%{q}%")
        ).fetchone()[0]
        post_rows = db.execute(
            """
            SELECT id, user_id, username, content, created_at
            FROM forum_posts
            WHERE content LIKE ? OR username LIKE ?
            ORDER BY id DESC
            LIMIT ? OFFSET ?
            """,
            (f"%{q}%", f"%{q}%", size, offset),
        ).fetchall()
    else:
        total = db.execute("SELECT COUNT(*) FROM forum_posts").fetchone()[0]
        post_rows = db.execute(
            """
            SELECT id, user_id, username, content, created_at
            FROM forum_posts
            ORDER BY id DESC
            LIMIT ? OFFSET ?
            """,
            (size, offset),
        ).fetchall()

    posts = []
    for p in post_rows:
        comment_rows = db.execute(
            """
            SELECT id, post_id, user_id, username, content, created_at
            FROM forum_comments
            WHERE post_id = ?
            ORDER BY id ASC
            """,
            (p["id"],),
        ).fetchall()
        posts.append(
            {
                "id": p["id"],
                "user_id": p["user_id"],
                "username": p["username"],
                "content": p["content"],
                "created_at": p["created_at"],
                "can_delete": is_admin,
                "comments": [
                    {
                        "id": c["id"],
                        "post_id": c["post_id"],
                        "user_id": c["user_id"],
                        "username": c["username"],
                        "content": c["content"],
                        "created_at": c["created_at"],
                        "can_delete": is_admin,
                    }
                    for c in comment_rows
                ],
            }
        )

    db.close()
    return {
        "total": total,
        "page": page,
        "size": size,
        "pages": (total + size - 1) // size if total else 0,
        "items": posts,
        "is_admin": is_admin,
        "logged_in": bool(user),
    }


@app.post("/api/forum/posts")
async def create_forum_post(body: ForumPostCreateRequest, request: Request):
    """论坛：发布求助帖（需登录）"""
    user = require_login(request)
    content = (body.content or "").strip()
    if not content:
        raise HTTPException(400, "内容不能为空")
    if len(content) > 1000:
        raise HTTPException(400, "内容不能超过 1000 字")

    db = get_db()
    db.execute(
        "INSERT INTO forum_posts (user_id, username, content, created_at) VALUES (?, ?, ?, ?)",
        (int(user["id"]), user["username"], content, datetime.now().isoformat()),
    )
    db.commit()
    db.close()
    return {"ok": True, "msg": "求助已发布"}


@app.post("/api/forum/posts/{post_id}/comments")
async def create_forum_comment(post_id: int, body: ForumCommentCreateRequest, request: Request):
    """论坛：评论求助帖（需登录）"""
    user = require_login(request)
    content = (body.content or "").strip()
    if not content:
        raise HTTPException(400, "评论不能为空")
    if len(content) > 500:
        raise HTTPException(400, "评论不能超过 500 字")

    db = get_db()
    post = db.execute("SELECT id FROM forum_posts WHERE id = ?", (post_id,)).fetchone()
    if not post:
        db.close()
        raise HTTPException(404, "帖子不存在")

    db.execute(
        "INSERT INTO forum_comments (post_id, user_id, username, content, created_at) VALUES (?, ?, ?, ?, ?)",
        (post_id, int(user["id"]), user["username"], content, datetime.now().isoformat()),
    )
    db.commit()
    db.close()
    return {"ok": True, "msg": "评论成功"}


@app.delete("/api/forum/posts/{post_id}")
async def delete_forum_post(post_id: int, request: Request):
    """论坛：管理员删除帖子（含其评论）"""
    require_admin(request)
    db = get_db()
    post = db.execute("SELECT id FROM forum_posts WHERE id = ?", (post_id,)).fetchone()
    if not post:
        db.close()
        raise HTTPException(404, "帖子不存在")

    db.execute("DELETE FROM forum_comments WHERE post_id = ?", (post_id,))
    db.execute("DELETE FROM forum_posts WHERE id = ?", (post_id,))
    db.commit()
    db.close()
    return {"ok": True, "msg": "帖子已删除"}


@app.delete("/api/forum/comments/{comment_id}")
async def delete_forum_comment(comment_id: int, request: Request):
    """论坛：管理员删除评论"""
    require_admin(request)
    db = get_db()
    comment = db.execute("SELECT id FROM forum_comments WHERE id = ?", (comment_id,)).fetchone()
    if not comment:
        db.close()
        raise HTTPException(404, "评论不存在")

    db.execute("DELETE FROM forum_comments WHERE id = ?", (comment_id,))
    db.commit()
    db.close()
    return {"ok": True, "msg": "评论已删除"}


@app.post("/api/upload")
async def upload_file(
    request: Request,
    file: UploadFile = File(...),
    description: str = Form(""),
    category: str = Form(""),
    sub_category: str = Form(""),
):
    """上传文件到指定学科目录（需登录，上传后待审核）"""
    user = require_login(request)

    # 应用层上传频率限制
    client_ip = _get_client_ip(request)
    if not upload_limiter.is_allowed(client_ip):
        raise HTTPException(429, "上传过于频繁，请稍后再试（每分钟最多 6 次）")

    if not file.filename:
        raise HTTPException(400, "文件名不能为空")

    filename = file.filename

    # ── 文件名安全检查（防路径穿越和恶意命名）──
    # 去除路径分隔符，只保留文件名
    filename = Path(filename).name
    # 禁止 .. 和特殊字符
    if ".." in filename or re.search(r'[<>:"|?*\x00-\x1f]', filename):
        raise HTTPException(400, "文件名包含非法字符")
    # 文件名长度限制
    if len(filename.encode("utf-8")) > 200:
        raise HTTPException(400, "文件名过长（最多 200 字节）")

    ext = Path(filename).suffix.lower()
    if ext not in ALLOWED_EXTENSIONS:
        raise HTTPException(400, f"不支持的文件格式，允许: {', '.join(sorted(ALLOWED_EXTENSIONS))}")

    content = await file.read()
    if len(content) > MAX_FILE_SIZE:
        raise HTTPException(400, "文件大小超过 200MB 限制")

    # ── 内容去重：计算文件 MD5，检查是否已存在相同文件 ──
    content_hash = hashlib.md5(content).hexdigest()
    if not category:
        category = "未分类"

    db = get_db()
    # 同一学科+子目录下，内容完全相同的文件视为重复
    existing = db.execute(
        """SELECT original_name FROM files
           WHERE category = ? AND sub_category = ?
           AND id IN (SELECT id FROM files WHERE file_size = ?)""",
        (category, sub_category, len(content)),
    ).fetchall()

    if existing:
        # 进一步用内容 hash 精确比对（数据库中可能无 hash 字段，比对磁盘文件）
        for row in existing:
            check_path = RESOURCES_DIR / category
            if sub_category:
                check_path = check_path / sub_category
            check_file = check_path / row["original_name"]
            if check_file.exists():
                existing_hash = hashlib.md5(check_file.read_bytes()).hexdigest()
                if existing_hash == content_hash:
                    db.close()
                    raise HTTPException(
                        409,
                        f"该目录下已存在相同内容的文件「{row['original_name']}」，无需重复上传"
                    )

    target_dir = RESOURCES_DIR / category
    if sub_category:
        target_dir = target_dir / sub_category
    target_dir.mkdir(parents=True, exist_ok=True)

    save_path = target_dir / filename
    if save_path.exists():
        stem, suffix = save_path.stem, save_path.suffix
        c = 1
        while save_path.exists():
            save_path = target_dir / f"{stem}_{c}{suffix}"
            c += 1

    with open(save_path, "wb") as f:
        f.write(content)

    rel_path = str(save_path.relative_to(RESOURCES_DIR)).replace("\\", "/")
    file_id = hashlib.md5(rel_path.encode()).hexdigest()

    db = get_db()
    db.execute(
        """INSERT INTO files
           (id, file_path, original_name, extension, file_size,
            description, category, sub_category, created_at, download_count,
            status, uploader)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        (file_id, rel_path, save_path.name, ext, len(content),
         description, category, sub_category, datetime.now().isoformat(), 0,
         "pending", user["username"]),
    )
    db.commit()
    db.close()
    return {"message": "上传成功，等待管理员审核", "id": file_id, "filename": save_path.name}





@app.get("/api/download/{file_id}")
async def download_file(file_id: str, request: Request):
    """下载文件（需登录，有每日限额）"""
    user = get_current_user(request)
    if not user:
        return RedirectResponse("/login?next=/", status_code=302)

    # ── 下载频率限制（防刷）──
    client_ip = _get_client_ip(request)
    limiter_key = f"{user['id']}_{client_ip}"
    if not download_limiter.is_allowed(limiter_key):
        raise HTTPException(429, "下载过于频繁，请稍后再试")

    db = get_db()
    row = db.execute("SELECT * FROM files WHERE id = ?", (file_id,)).fetchone()
    if not row:
        db.close()
        raise HTTPException(404, "文件不存在")

    record = FileRecord.from_row(row)
    # 管理员可下载任何状态的文件，普通用户只能下载已审核通过的
    if record.status != "approved" and not user.get("is_admin"):
        db.close()
        raise HTTPException(403, "该文件尚未通过审核")
    file_path = RESOURCES_DIR / record.file_path
    if not file_path.exists():
        db.close()
        raise HTTPException(404, "文件已丢失")

    # ── 每日下载限额检查 ──
    daily_size, daily_count = _get_user_daily_download(user["id"])
    if daily_count >= MAX_DAILY_DOWNLOAD_COUNT:
        db.close()
        raise HTTPException(429, f"今日下载次数已达上限（{MAX_DAILY_DOWNLOAD_COUNT} 次），明天再来吧")
    if daily_size + record.file_size > MAX_DAILY_DOWNLOAD_SIZE:
        remaining = MAX_DAILY_DOWNLOAD_SIZE - daily_size
        db.close()
        raise HTTPException(429, f"今日下载流量即将超限（剩余 {_fmt_size(max(0, remaining))}），明天再来吧")

    # ── 记录下载并更新计数 ──
    db.execute("UPDATE files SET download_count = download_count + 1 WHERE id = ?", (file_id,))
    db.commit()
    db.close()
    _log_download(user["id"], file_id, record.file_size)

    return FileResponse(path=str(file_path), filename=record.original_name, media_type="application/octet-stream")





def _fmt_size(n: int) -> str:
    size = float(n)
    for unit in ("B", "KB", "MB", "GB"):
        if size < 1024:
            return f"{size:.1f} {unit}"
        size /= 1024
    return f"{size:.1f} TB"
