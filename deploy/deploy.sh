#!/bin/bash
# ============================================
# 一键部署脚本 - 学科资料下载站
# 适用于 Ubuntu 20.04/22.04/24.04
# 服务器最低配置：2核2G 40GB
# ============================================

set -e

APP_DIR="/opt/download-site"
SERVICE_NAME="download-site"

echo "========== 1. 安装系统依赖 =========="
sudo apt update
sudo apt install -y python3 python3-pip python3-venv nginx git

echo "========== 2. 设置项目目录 =========="
sudo mkdir -p $APP_DIR
sudo chown $USER:$USER $APP_DIR

# 如果当前目录有项目文件，复制过去
if [ -f "./requirements.txt" ]; then
    echo "从当前目录复制项目文件..."
    cp -r ./* $APP_DIR/
fi

cd $APP_DIR

echo "========== 3. 创建虚拟环境并安装依赖 =========="
python3 -m venv venv
source venv/bin/activate
pip install --upgrade pip
pip install -r requirements.txt

echo "========== 4. 创建必要目录 =========="
mkdir -p data resources uploads

echo "========== 5. 设置文件权限 =========="
sudo chown -R www-data:www-data $APP_DIR
sudo chmod -R 755 $APP_DIR
sudo chmod -R 775 $APP_DIR/data $APP_DIR/resources $APP_DIR/uploads

echo "========== 6. 部署 Systemd 服务 =========="
sudo cp deploy/download-site.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable $SERVICE_NAME
sudo systemctl start $SERVICE_NAME

echo "========== 7. 配置 Nginx =========="
sudo cp deploy/nginx-download-site.conf /etc/nginx/sites-available/$SERVICE_NAME
sudo ln -sf /etc/nginx/sites-available/$SERVICE_NAME /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t
sudo systemctl reload nginx

echo "========== 8. 配置防火墙 =========="
sudo ufw allow 80/tcp 2>/dev/null || true
sudo ufw allow 443/tcp 2>/dev/null || true
sudo ufw allow 22/tcp 2>/dev/null || true

echo ""
echo "=========================================="
echo "  部署完成！"
echo "  访问地址: http://$(curl -s ifconfig.me 2>/dev/null || echo '你的服务器IP')"
echo "  管理后台: http://$(curl -s ifconfig.me 2>/dev/null || echo '你的服务器IP')/admin"
echo ""
echo "  常用命令："
echo "    查看状态: sudo systemctl status $SERVICE_NAME"
echo "    查看日志: sudo journalctl -u $SERVICE_NAME -f"
echo "    重启服务: sudo systemctl restart $SERVICE_NAME"
echo "=========================================="
