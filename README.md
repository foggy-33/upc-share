# 学科资料下载站upcshare

一个基于 FastAPI 的资料管理与下载系统，支持学科目录扫描入库、用户上传、管理员审核、检索下载和分层限流。
域名为：upcshare.cn
## 核心能力

- 自动建库：应用启动时扫描 `resources/`，将新增文件增量写入 SQLite。
- 分类检索：按关键词、学科、子目录、扩展名组合查询。
- 用户体系：注册/登录、JWT 鉴权、基于角色的权限控制（普通用户/管理员）。
- 上传审核：上传后进入 `pending`，仅审核通过文件可被公开检索与下载。
- 下载防刷：请求频率限制 + 每日下载次数/总流量限制。

## 技术栈

- 后端框架：FastAPI
- 应用服务：Uvicorn (ASGI)
- 认证鉴权：`python-jose` (JWT) + `bcrypt`
- 数据库：SQLite（WAL 模式）
- 模板引擎：Jinja2
- 前端：原生 HTML/CSS/JavaScript

## 系统架构

```text
浏览器
  │
  ├─ 页面请求（HTML）───────────────┐
  ├─ API 请求（JSON）───────────────┼──> FastAPI (app/main.py)
  └─ 静态资源（CSS/JS）─────────────┘
                    │
                    ├─ 认证模块（app/auth.py）
                    │    - bcrypt 密码哈希
                    │    - JWT 生成/校验
                    │    - HttpOnly Cookie 会话承载
                    │
                    ├─ 数据访问层（app/database.py）
                    │    - SQLite 连接管理
                    │    - WAL + busy_timeout 并发优化
                    │
                    ├─ 数据模型（app/models.py）
                    │    - FileRecord 映射与序列化
                    │
                    ├─ 文件系统（resources/）
                    │    - 实际资料文件存储
                    │
                    └─ 数据库存储（data/files.db）
                         - files / users / download_log
```

## 技术原理

### 1. 启动扫描与增量入库

- 应用启动触发 `scan_resources()`，遍历 `resources/` 下各学科目录。
- 仅处理白名单扩展名（如 `pdf/doc/docx/zip/rar` 等）。
- 用 `file_path` 去重，避免重复写入。
- 自动提取 `category`（学科）和 `sub_category`（子目录）。
- 使用相对路径 MD5 作为稳定文件 ID，写入 `files` 表。

### 2. 认证与授权机制

- 注册时使用 `bcrypt` 进行密码哈希，不存储明文密码。
- 登录成功后签发 JWT（默认 7 天过期），并写入 `HttpOnly` Cookie。
- 请求到达时从 Cookie 解析 JWT，恢复用户身份与角色。
- 关键接口通过 `require_login`、`require_admin` 实现访问控制。

### 3. 上传审核链路

上传请求在服务端按顺序经过以下校验：

1. 频率限制（按 IP 的时间窗口）。
2. 文件名合法性检查（防路径穿越/异常字符）。
3. 扩展名白名单校验。
4. 大小限制（当前为 80MB）。
5. 内容去重（MD5）。
6. 落盘到 `resources/{学科}/{子目录}/`。
7. 写入数据库并标记为 `pending`。
8. 管理员审核通过后变更为 `approved`，才可对外展示与下载。

### 4. 下载防刷与配额控制

- 短时限流：内存滑动窗口 `RateLimiter` 控制高频请求。
- 日配额限制：按用户统计 `download_log`，约束每日下载次数与总流量。
- 审计能力：每次下载写日志，支持后续统计、风控和运营分析。

### 5. SQLite 并发策略

- 启用 `PRAGMA journal_mode=WAL`，降低读写互斥冲突。
- 设置 `PRAGMA busy_timeout=5000`，缓解瞬时写锁导致的失败。
- 建立 `download_log(user_id, downloaded_at)` 索引，提升按用户按日统计效率。

## 数据模型摘要

- `files`：文件元数据、分类信息、审核状态、上传者、下载计数。
- `users`：账号信息、密码哈希、启用状态、管理员标记。
- `download_log`：下载行为日志（用户、文件、体积、时间）。

## 目录结构

```text
.
├── app/
│   ├── auth.py                # JWT + bcrypt 认证与权限控制
│   ├── database.py            # SQLite 连接与初始化
│   ├── main.py                # 路由、上传下载、审核与限流逻辑
│   └── models.py              # 数据模型
├── data/                      # 数据库目录（默认 data/files.db）
├── deploy/                    # 服务器部署脚本与配置文件
├── resources/                 # 资料文件目录（按学科组织）
├── static/                    # 前端静态资源
├── templates/                 # Jinja2 页面模板
├── requirements.txt
└── run.py
```

## 页面与前端职责

- `templates/index.html`：首页与文件浏览。
- `templates/login.html` / `templates/register.html`：用户认证页面。
- `templates/admin.html`：上传入口。
- `templates/dashboard.html`：管理员审核后台。
- `static/js/app.js`：检索与列表交互。
- `static/js/admin.js`：上传与表单交互。
- `static/js/dashboard.js`：审核、查询与管理操作。

## 说明

- 生产环境建议结合反向代理与 HTTPS 使用，`Secure` Cookie 仅在 HTTPS 下发送。
- `deploy/` 中提供了服务化部署所需示例文件，可按服务器环境调整。

## 云服务器到校园服务器同步

项目使用 `SQLite(data/files.db) + resources/`，可以做完整单向同步（云 -> 校园）。

仓库已提供脚本：`deploy/sync_from_cloud.sh`

在校园服务器执行：

```bash
bash deploy/sync_from_cloud.sh --remote ubuntu@<云服务器IP>
```

常用参数：

```bash
bash deploy/sync_from_cloud.sh \
  --remote ubuntu@<云服务器IP> \
  --remote-app-dir /opt/download-site \
  --local-app-dir /opt/download-site \
  --service-name download-site \
  --ssh-port 22
```

同步内容：

- `data/files.db`：使用 `sqlite3 .backup` 生成一致性快照后替换本地库。
- `resources/`：使用 `rsync --delete` 做全量镜像同步。

注意：

- 这是单向覆盖同步（目标端与源端不一致的文件会被删除）。
- 如需“严格一致”（包括同步瞬间的写入），建议同步时临时停止云端写入或停云端服务。

