"""
用户认证模块 — JWT + bcrypt
"""
import os
import secrets
from datetime import datetime, timedelta
from typing import Optional

import bcrypt
from jose import JWTError, jwt
from fastapi import Request, HTTPException, Response

from app.database import get_db

# ── 密钥配置（首次运行自动生成，持久化到 .secret_key 文件）───
_SECRET_FILE = os.path.join(os.path.dirname(os.path.dirname(__file__)), ".secret_key")

def _load_or_create_secret() -> str:
    if os.path.exists(_SECRET_FILE):
        with open(_SECRET_FILE, "r") as f:
            return f.read().strip()
    key = secrets.token_hex(32)
    with open(_SECRET_FILE, "w") as f:
        f.write(key)
    return key

SECRET_KEY = _load_or_create_secret()
ALGORITHM = "HS256"
TOKEN_EXPIRE_HOURS = 24 * 7  # 7 天有效期
COOKIE_NAME = "access_token"


# ── 密码处理 ──────────────────────────────────────────────
def hash_password(password: str) -> str:
    """bcrypt 哈希密码"""
    return bcrypt.hashpw(password.encode("utf-8"), bcrypt.gensalt()).decode("utf-8")


def verify_password(password: str, hashed: str) -> bool:
    """验证密码"""
    return bcrypt.checkpw(password.encode("utf-8"), hashed.encode("utf-8"))


# ── JWT Token ─────────────────────────────────────────────
def create_token(user_id: int, username: str, is_admin: bool = False) -> str:
    """生成 JWT token"""
    expire = datetime.utcnow() + timedelta(hours=TOKEN_EXPIRE_HOURS)
    payload = {
        "sub": str(user_id),
        "username": username,
        "is_admin": is_admin,
        "exp": expire,
    }
    return jwt.encode(payload, SECRET_KEY, algorithm=ALGORITHM)


def decode_token(token: str) -> Optional[dict]:
    """解析 JWT token，失败返回 None"""
    try:
        payload = jwt.decode(token, SECRET_KEY, algorithms=[ALGORITHM])
        return payload
    except JWTError:
        return None


# ── 用户操作 ──────────────────────────────────────────────
def register_user(username: str, password: str) -> dict:
    """注册用户，返回结果 dict"""
    username = username.strip()
    if len(username) < 2 or len(username) > 20:
        return {"ok": False, "msg": "用户名长度需在 2-20 个字符之间"}
    if len(password) < 6:
        return {"ok": False, "msg": "密码长度至少 6 个字符"}

    db = get_db()
    existing = db.execute("SELECT id FROM users WHERE username = ?", (username,)).fetchone()
    if existing:
        db.close()
        return {"ok": False, "msg": "该用户名已被注册"}

    pw_hash = hash_password(password)
    db.execute(
        "INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)",
        (username, pw_hash, datetime.now().isoformat()),
    )
    db.commit()
    user_id = db.execute("SELECT id FROM users WHERE username = ?", (username,)).fetchone()[0]
    db.close()
    return {"ok": True, "user_id": user_id, "username": username}


def login_user(username: str, password: str) -> dict:
    """登录验证，返回结果 dict"""
    db = get_db()
    row = db.execute(
        "SELECT id, username, password_hash, is_active, is_admin FROM users WHERE username = ?",
        (username.strip(),),
    ).fetchone()
    db.close()

    if not row:
        return {"ok": False, "msg": "用户名或密码错误"}
    if not row["is_active"]:
        return {"ok": False, "msg": "账号已被禁用"}
    if not verify_password(password, row["password_hash"]):
        return {"ok": False, "msg": "用户名或密码错误"}

    token = create_token(row["id"], row["username"], bool(row["is_admin"]))
    return {"ok": True, "token": token, "username": row["username"], "is_admin": bool(row["is_admin"])}


def get_current_user(request: Request) -> Optional[dict]:
    """从 Cookie 中获取当前登录用户，未登录返回 None"""
    token = request.cookies.get(COOKIE_NAME)
    if not token:
        return None
    payload = decode_token(token)
    if not payload:
        return None
    return {"id": payload["sub"], "username": payload["username"], "is_admin": payload.get("is_admin", False)}


def require_admin(request: Request) -> dict:
    """要求管理员权限，否则抛 403"""
    user = get_current_user(request)
    if not user:
        raise HTTPException(status_code=401, detail="请先登录")
    if not user.get("is_admin"):
        raise HTTPException(status_code=403, detail="需要管理员权限")
    return user


def require_login(request: Request) -> dict:
    """要求登录，未登录抛 HTTPException"""
    user = get_current_user(request)
    if not user:
        raise HTTPException(status_code=401, detail="请先登录")
    return user


def set_auth_cookie(response: Response, token: str):
    """设置认证 Cookie（HttpOnly + Secure）"""
    response.set_cookie(
        key=COOKIE_NAME,
        value=token,
        httponly=True,       # JS 无法读取，防 XSS
        secure=True,         # HTTPS 环境必须为 True
        samesite="lax",      # 防 CSRF
        max_age=TOKEN_EXPIRE_HOURS * 3600,
        path="/",
    )


def clear_auth_cookie(response: Response):
    """清除认证 Cookie"""
    response.delete_cookie(key=COOKIE_NAME, path="/")
