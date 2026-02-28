# FileHub — 文件下载站

基于 **Python + FastAPI** 的简约文件下载站，支持 PDF / Word 文档上传、下载、搜索与分类管理。

## 功能

- 📄 PDF / Word (.doc .docx) 文件上传与下载
- 🔍 关键词搜索（文件名 + 描述）
- 🏷️ 分类筛选
- 📊 下载计数
- 🎨 Apple 简约风格前端
- 🖱️ 拖拽上传
- 📱 响应式设计

## 快速启动

```bash
# 1. 安装依赖
pip install -r requirements.txt

# 2. 启动服务
python run.py

# 或使用 uvicorn
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

浏览器访问 http://localhost:8000

## 项目结构

```
download-site/
├── app/
│   ├── __init__.py
│   ├── main.py          # FastAPI 主应用
│   ├── database.py      # SQLite 数据库
│   └── models.py         # 数据模型
├── static/
│   ├── css/style.css     # 样式文件
│   └── js/
│       ├── app.js        # 首页逻辑
│       └── admin.js      # 管理页逻辑
├── templates/
│   ├── index.html        # 首页
│   └── admin.html        # 上传管理页
├── uploads/              # 文件存储目录（自动创建）
├── data/                 # 数据库目录（自动创建）
├── requirements.txt
├── run.py                # 启动脚本
└── README.md
```

## 云服务器部署

### 使用 systemd 守护进程

```bash
# 创建 systemd 服务
sudo cat > /etc/systemd/system/filehub.service << EOF
[Unit]
Description=FileHub Download Server
After=network.target

[Service]
User=www-data
WorkingDirectory=/opt/download-site
ExecStart=/opt/download-site/venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8000
Restart=always

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl enable filehub
sudo systemctl start filehub
```

### 配合 Nginx 反向代理

```nginx
server {
    listen 80;
    server_name your-domain.com;

    client_max_body_size 100M;

    location / {
        proxy_pass http://127.0.0.1:8000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

## API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/files` | 文件列表（支持 q/category/page/size 参数） |
| GET | `/api/categories` | 获取所有分类 |
| POST | `/api/upload` | 上传文件（multipart/form-data） |
| GET | `/api/download/{id}` | 下载文件 |
| DELETE | `/api/files/{id}` | 删除文件 |
