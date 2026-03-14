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

浏览器访问 http://localhost:8000

## 项目结构

```
用户浏览器
    │
    ▼ HTTPS (443)
┌─────────────────────────────────────────┐
│  Nginx (反向代理 + SSL + 限流 + 静态文件)│
└────────────┬───────────────┬────────────┘
             │               │
     动态请求 │        静态文件│ /static/*
             ▼               ▼
   ┌──────────────┐   /opt/download-site/static/
   │  Uvicorn     │
   │ (ASGI Server)│
   │  :8000       │
   └──────┬───────┘
          │
          ▼
   ┌──────────────┐
   │ FastAPI App  │
   │ (Python)     │
   ├──────────────┤
   │  auth.py     │ ← JWT + bcrypt 认证
   │  main.py     │ ← 路由 + 业务逻辑
   │  database.py │ ← SQLite 连接管理
   │  models.py   │ ← 数据模型
   └──────┬───────┘
          │
          ▼
   ┌──────────────┐     ┌─────────────────┐
   │  SQLite      │     │  resources/     │
   │ data/files.db│     │  (文件存储目录)  │
   └──────────────┘     └─────────────────┘
```

2. 各层职责
层	技术	职责
反向代理	Nginx + Certbot	SSL 终端、HTTP→HTTPS 重定向、静态文件直接响应、请求限流限速
应用服务器	Uvicorn (ASGI)	异步事件循环，处理并发 HTTP 请求
Web 框架	FastAPI	路由定义、请求验证、模板渲染、API 响应
认证	JWT + bcrypt	无状态身份验证，密码安全存储
数据库	SQLite (WAL)	文件元数据、用户信息、下载记录
文件存储	本地磁盘 resources	实际文件按学科/子目录组织

3. 认证流程

注册: 用户名+密码 → bcrypt 哈希 → 存入 users 表
                                          │
登录: 用户名+密码 → bcrypt 验证 ──────────→ 生成 JWT Token
                                          │
      JWT payload         │
                                          ▼
      Token 写入 HttpOnly Cookie ──→ 浏览器自动携带
                                          │
      每次请求 → 从 Cookie 读取 Token      │
              → jose.jwt.decode 验证签名+过期
              → 返回用户信息 { id, username, is_admin }

4. 上传审核流程

用户上传文件
    │
    ▼
① 频率限制检查（每 IP 每分钟 ≤ 6 次）
    │
    ▼
② 文件名安全检查（防路径穿越、特殊字符、长度限制）
    │
    ▼
③ 文件格式检查（白名单：pdf/doc/zip/rar 等 14 种）
    │
    ▼
④ 文件大小检查（≤ 200MB）
    │
    ▼
⑤ 内容去重（MD5 哈希比对同目录已有文件）
    │
    ▼
⑥ 保存文件到 resources/{学科}/{子目录}/
    │
    ▼
⑦ 写入数据库，status = 'pending', uploader = 用户名
    │
    ▼
⑧ 管理员在 /dashboard 审核
    ├── 通过 → status = 'approved' → 首页可见可下载
    └── 拒绝 → 删除磁盘文件 + 数据库记录

5. 下载限流机制（三层防护）
   第一层：Nginx
├── 单 IP 最多 3 个并发下载连接
├── 每个连接限速 5MB/s
└── 总带宽 100Mbps ≈ 12.5MB/s，留余量给其他用户

第二层：FastAPI 应用层
├── RateLimiter：每用户每 10 秒最多 5 次下载请求（防刷）
└── 内存中的滑动窗口计数器

第三层：每日限额
├── 每用户每天最多下载 20 次
├── 每用户每天最多下载 200MB
└── 通过 download_log 表记录，按日期汇总查询

6. 前端架构
   templates/
├── index.html      ← 首页（学科卡片、文件列表、搜索）
├── login.html      ← 登录页
├── register.html   ← 注册页
├── admin.html      ← 上传页（普通用户）
└── dashboard.html  ← 管理后台（仅管理员）

static/
├── css/style.css   ← 全局样式
└── js/
    ├── app.js      ← 首页交互（学科浏览、搜索、分页）
    ├── admin.js    ← 上传拖拽、表单提交
    └── dashboard.js ← 管理后台（审核、搜索、下载、删除）
7. 部署架构
服务器: 阿里云 ECS (2G RAM, 100Mbps)
├── OS: Ubuntu
├── Systemd 服务: download-site.service
│   ├── uvicorn --workers 1 (单进程，够用)
│   ├── MemoryMax=1G, MemoryHigh=800M
│   ├── CPUQuota=80%
│   └── LimitNOFILE=4096
├── Nginx + Certbot (Let's Encrypt SSL, 自动续期)
└── 数据目录: /opt/download-site/data/files.db
## 云服务器部署

### 使用 systemd 守护进程

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

