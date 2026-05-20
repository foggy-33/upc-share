# upcshare

upcshare 是面向校园网资料共享的下载站。当前版本使用 Spring Boot + MySQL + Vue，实际文件统一存放在校园服务器，公网云服务器只负责 Nginx 入口和 frp 转发。

## 架构

```text
用户浏览器
  |
  |-- https://upcshare.cn      公网入口，云服务器 Nginx -> frp
  |
  `-- https://in.upcshare.cn   校园网入口，直连校园服务器 Nginx

校园服务器
  - Spring Boot API 与静态页面
  - MySQL 元数据
  - /opt/download-site/resources 资料文件

云服务器
  - Nginx
  - frps
```

页面会探测 `https://in.upcshare.cn/api/ping`。如果校园网入口可达，就跳到 `in.upcshare.cn`；否则继续使用 `upcshare.cn` 通过 frp 访问。右上角会显示当前是校园网访问还是公网访问。

## 功能

- 资料库：按学科、目录、关键词检索资料，支持 PDF、Word、PPT、压缩包等文件类型标识。
- 上传审核：登录用户上传，管理员审核后公开。
- 下载记录：记录下载次数、流量、用户和访问节点。
- 用户管理：管理员查看用户、下载统计、封禁或解封普通用户。
- 论坛：登录后发帖、评论，管理员可删除不合适内容。
- 文件扫描：启动时可扫描 `resources/`，把校园服务器已有文件同步进 MySQL。

## 技术栈

- 后端：Java 17、Spring Boot 3、JdbcTemplate
- 数据库：MySQL 8
- 前端：Vue 3、Vite
- 部署：Docker Compose、Nginx、frp

旧版 Python 服务、单文件数据库、模板页面和基础 HTML 前端已经移除。生产环境只运行 Java 服务和 MySQL。

## 目录结构

```text
.
├── springboot/                 # Spring Boot 后端
│   └── src/main/resources/      # application.yml 与 schema.sql
├── vue/                        # Vue 前端源码
├── deploy/                     # Nginx、frp、systemd 示例配置
├── resources/                  # 资料文件目录，生产环境在校园服务器维护
├── docker-compose.yml          # 校园服务器 Java + MySQL
├── Dockerfile                  # 多阶段构建 Vue + Spring Boot
└── DOCKER_DEPLOYMENT.md        # 服务器部署说明
```

## 本地或校园服务器启动

复制环境变量文件：

```bash
cp .env.example .env
```

至少修改这些值：

```env
MYSQL_DATABASE=download_site
MYSQL_USER=download_site
MYSQL_PASSWORD=change-this
MYSQL_ROOT_PASSWORD=change-root-this
JWT_SECRET=replace-with-a-long-random-secret
NODE_NAME=campus
APP_COOKIE_DOMAIN=.upcshare.cn
COOKIE_SECURE=true
RESOURCES_DIR=/app/resources
SCAN_RESOURCES_ON_STARTUP=true
```

启动：

```bash
docker compose up -d --build
docker compose ps
curl -fsS http://127.0.0.1:8080/api/ping
```

应用监听在宿主机 `127.0.0.1:8080`，由 Nginx 对外提供 HTTPS。

## 数据位置

MySQL 保存元数据，资料文件保存在校园服务器：

```text
/opt/download-site/resources/
```

云服务器不要保存资料文件，也不要运行应用容器。公网访问应由云服务器 Nginx 转发到 frp，再进入校园服务器的 Java 服务。

## 常用维护命令

重新构建并启动：

```bash
cd /opt/download-site
git pull origin main
sudo docker compose up -d --build
```

查看日志：

```bash
sudo docker compose logs -f app
```

重新扫描 `resources/`：

```bash
curl -X POST http://127.0.0.1:8080/api/admin/scan
```

检查数据库中的文件记录：

```bash
sudo docker compose exec mysql sh -c 'mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -e "SELECT id,file_path,status FROM files LIMIT 5;"'
```

## 部署文档

完整公网/校园网/frp/Nginx 流程见 [DOCKER_DEPLOYMENT.md](DOCKER_DEPLOYMENT.md)。
