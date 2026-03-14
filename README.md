# 概述

一个基于 FastAPI 的学科资料下载站，支持目录扫描入库、用户登录上传、管理员审核和下载限流。

## 特性

- 学科目录自动扫描：启动时扫描 `resources/`，增量写入 SQLite
- 文件检索：支持关键字、学科、子目录、扩展名筛选
- 用户系统：注册、登录、使用JWT（HttpOnly Cookie）
- 上传审核：管理员审核后可见
- 下载防刷：请求频率限制 + 每日次数/流量限制
- 轻量部署：使用`Uvicorn + Nginx + systemd`

## 技术栈

- Backend: FastAPI, Uvicorn
- Template: Jinja2
- Auth: `python-jose` + `bcrypt`
- Database: SQLite (WAL)
- Frontend: 原生 HTML/CSS/JS

## 项目结构

```text
.
├── app/
│   ├── auth.py
│   ├── database.py
│   ├── main.py
│   └── models.py
├── data/                    # SQLite 数据库目录（默认 data/files.db）
├── deploy/                  # Linux 部署脚本与配置
├── resources/               # 实际文件目录（按学科组织）
├── static/
├── templates/
├── requirements.txt
└── run.py
```

## 本地运行

### 1. 准备环境

```bash
python -m venv .venv
```

Windows PowerShell:

```powershell
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

Linux/macOS:

```bash
source .venv/bin/activate
pip install -r requirements.txt
```

### 2. 启动服务

```bash
python run.py
```

或：

```bash
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

访问：`http://127.0.0.1:8000`



## 关键限制与安全策略

- 上传格式白名单：`.pdf .doc .docx .zip .rar .7z .tar .gz .ppt .pptx .xls .xlsx .txt .md .csv`
- 单文件大小上限：`200MB`
- 上传频率：每 IP 每 60 秒最多 `6` 次
- 注册频率：每 IP 每 300 秒最多 `5` 次
- 下载频率：每用户+IP 每 10 秒最多 `5` 次
- 每日下载限制：每用户每天最多 `20` 次、`200MB`
- 文件名安全检查：禁止路径穿越和非法字符
- 文件去重：同目录下按内容 MD5 去重

## API 概览

### 页面路由

- `GET /` 首页
- `GET /login` 登录页
- `GET /register` 注册页
- `GET /admin` 上传页（需登录）
- `GET /dashboard` 管理后台（需管理员）

### 认证

- `POST /api/auth/register`
- `POST /api/auth/login`
- `POST /api/auth/logout`
- `GET /api/auth/me`

### 文件与统计

- `GET /api/files`
- `GET /api/subjects`
- `GET /api/subjects/{subject}/folders`
- `GET /api/stats`
- `POST /api/upload`
- `GET /api/download/{file_id}`

### 管理员

- `GET /api/admin/files`
- `POST /api/admin/approve/{file_id}`
- `POST /api/admin/reject/{file_id}`
- `DELETE /api/admin/files/{file_id}`

## 部署（Ubuntu）

项目内已提供：

- `deploy/deploy.sh`
- `deploy/download-site.service`
- `deploy/nginx-download-site.conf`

执行一键部署：

```bash
bash deploy/deploy.sh
```

部署后常用命令：

```bash
sudo systemctl status download-site
sudo journalctl -u download-site -f
sudo systemctl restart download-site
```

## 注意事项

- 当前登录态使用 `Secure` Cookie，仅 HTTPS 下会被浏览器发送。
- 纯 HTTP 本地开发时，登录后可能看起来“未登录”。
- 生产环境建议使用 Nginx + HTTPS（仓库已提供示例配置）。

