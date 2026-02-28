"""
学科资料下载站 - FastAPI 主应用
支持文件夹式学科分类，自动扫描 resources/ 目录
"""
import os
import uuid
import hashlib
from datetime import datetime
from pathlib import Path
from typing import Optional

from fastapi import FastAPI, UploadFile, File, Form, Query, HTTPException, Request
from fastapi.responses import FileResponse, HTMLResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates

from app.database import get_db, init_db
from app.models import FileRecord

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
MAX_FILE_SIZE = 500 * 1024 * 1024  # 500 MB

app = FastAPI(title="学科资料下载站", version="2.0.0")

# 静态文件 & 模板
app.mount("/static", StaticFiles(directory=str(BASE_DIR / "static")), name="static")
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
                    description, category, sub_category, created_at, download_count)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (file_id, rel_path, file_path.name, ext, file_size,
                 "", subject_name, sub_category, mtime, 0),
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


# ── 页面路由 ──────────────────────────────────────────────
@app.get("/", response_class=HTMLResponse)
async def index(request: Request):
    return templates.TemplateResponse("index.html", {"request": request})


@app.get("/admin", response_class=HTMLResponse)
async def admin_page(request: Request):
    return templates.TemplateResponse("admin.html", {"request": request})


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
    """获取文件列表"""
    db = get_db()
    offset = (page - 1) * size
    conditions, params = [], []

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
        FROM files WHERE category IS NOT NULL AND category != ''
        GROUP BY category ORDER BY category
    """).fetchall()

    subjects = []
    for r in rows:
        ext_rows = db.execute(
            "SELECT extension, COUNT(*) as cnt FROM files WHERE category = ? GROUP BY extension",
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
        FROM files WHERE category = ? AND sub_category != ''
        GROUP BY sub_category ORDER BY sub_category
    """, (subject,)).fetchall()
    root_count = db.execute(
        "SELECT COUNT(*) FROM files WHERE category = ? AND sub_category = ''",
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
    total_files = db.execute("SELECT COUNT(*) FROM files").fetchone()[0]
    total_size = db.execute("SELECT SUM(file_size) FROM files").fetchone()[0] or 0
    total_downloads = db.execute("SELECT SUM(download_count) FROM files").fetchone()[0] or 0
    subject_count = db.execute("SELECT COUNT(DISTINCT category) FROM files WHERE category != ''").fetchone()[0]
    db.close()
    return {
        "total_files": total_files,
        "total_size": _fmt_size(total_size),
        "total_downloads": total_downloads,
        "subject_count": subject_count,
    }


@app.post("/api/upload")
async def upload_file(
    file: UploadFile = File(...),
    description: str = Form(""),
    category: str = Form(""),
    sub_category: str = Form(""),
):
    """上传文件到指定学科目录"""
    ext = Path(file.filename).suffix.lower()
    if ext not in ALLOWED_EXTENSIONS:
        raise HTTPException(400, f"不支持的文件格式，允许: {', '.join(sorted(ALLOWED_EXTENSIONS))}")

    content = await file.read()
    if len(content) > MAX_FILE_SIZE:
        raise HTTPException(400, "文件大小超过 500MB 限制")

    if not category:
        category = "未分类"
    target_dir = RESOURCES_DIR / category
    if sub_category:
        target_dir = target_dir / sub_category
    target_dir.mkdir(parents=True, exist_ok=True)

    save_path = target_dir / file.filename
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
            description, category, sub_category, created_at, download_count)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        (file_id, rel_path, save_path.name, ext, len(content),
         description, category, sub_category, datetime.now().isoformat(), 0),
    )
    db.commit()
    db.close()
    return {"message": "上传成功", "id": file_id, "filename": save_path.name}


@app.post("/api/rescan")
async def rescan():
    """手动触发重新扫描"""
    n = scan_resources()
    return {"message": f"扫描完成，新增 {n} 个文件"}


@app.get("/api/download/{file_id}")
async def download_file(file_id: str):
    """下载文件"""
    db = get_db()
    row = db.execute("SELECT * FROM files WHERE id = ?", (file_id,)).fetchone()
    if not row:
        raise HTTPException(404, "文件不存在")

    record = FileRecord.from_row(row)
    file_path = RESOURCES_DIR / record.file_path
    if not file_path.exists():
        raise HTTPException(404, "文件已丢失")

    db.execute("UPDATE files SET download_count = download_count + 1 WHERE id = ?", (file_id,))
    db.commit()
    db.close()

    return FileResponse(path=str(file_path), filename=record.original_name, media_type="application/octet-stream")


@app.delete("/api/files/{file_id}")
async def delete_file(file_id: str):
    """删除文件"""
    db = get_db()
    row = db.execute("SELECT * FROM files WHERE id = ?", (file_id,)).fetchone()
    if not row:
        raise HTTPException(404, "文件不存在")

    record = FileRecord.from_row(row)
    file_path = RESOURCES_DIR / record.file_path
    if file_path.exists():
        file_path.unlink()

    db.execute("DELETE FROM files WHERE id = ?", (file_id,))
    db.commit()
    db.close()
    return {"message": "删除成功"}


def _fmt_size(size: int) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if size < 1024:
            return f"{size:.1f} {unit}"
        size /= 1024
    return f"{size:.1f} TB"
