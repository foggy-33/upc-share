#!/bin/bash
set -euo pipefail

APP_DIR="/opt/download-site"
SERVICE_NAME="download-site"

echo "== 1. Install runtime dependencies =="
sudo apt update
sudo apt install -y nginx git ca-certificates curl rsync docker.io docker-compose-plugin
sudo systemctl enable --now docker

echo "== 2. Prepare application directory =="
sudo mkdir -p "$APP_DIR"
sudo chown "$USER:$USER" "$APP_DIR"

if [ -f "./docker-compose.yml" ]; then
    rsync -a --delete \
      --exclude ".git" \
      --exclude ".venv" \
      --exclude "data" \
      --exclude "resources" \
      ./ "$APP_DIR"/
fi

cd "$APP_DIR"
mkdir -p data resources

if [ ! -f ".env" ] && [ -f ".env.example" ]; then
    cp .env.example .env
    echo "Created $APP_DIR/.env from .env.example. Edit secrets before exposing the site."
fi

echo "== 3. Install systemd service =="
sudo cp deploy/download-site.service "/etc/systemd/system/${SERVICE_NAME}.service"
sudo systemctl daemon-reload
sudo systemctl enable "$SERVICE_NAME"
sudo systemctl restart "$SERVICE_NAME"

echo "== 4. Install Nginx config =="
NODE_NAME="$(grep -E '^NODE_NAME=' .env 2>/dev/null | tail -n 1 | cut -d= -f2- || true)"
if [ "$NODE_NAME" = "campus" ]; then
    NGINX_SOURCE="deploy/nginx-campus.conf"
else
    NGINX_SOURCE="deploy/nginx-public.conf"
fi
sudo cp "$NGINX_SOURCE" "/etc/nginx/sites-available/${SERVICE_NAME}"
sudo ln -sf "/etc/nginx/sites-available/${SERVICE_NAME}" "/etc/nginx/sites-enabled/${SERVICE_NAME}"
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t
sudo systemctl reload nginx

if ! sudo nginx -T 2>/dev/null | grep -A 3 -E 'location /api/upload' | grep -q 'client_max_body_size 1100M'; then
    echo "ERROR: Nginx upload limit did not become active." >&2
    exit 1
fi

echo "== 5. Firewall =="
sudo ufw allow 80/tcp 2>/dev/null || true
sudo ufw allow 443/tcp 2>/dev/null || true
sudo ufw allow 22/tcp 2>/dev/null || true

echo "Deployment finished. Check status with: sudo systemctl status ${SERVICE_NAME}"
