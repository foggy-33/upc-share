# upcshare.cn Deployment

Target architecture:

- `upcshare.cn` and `www.upcshare.cn` resolve to the public cloud server.
- The public cloud server only runs the public ingress: Nginx + frps. It does not store files, MySQL, or the Java app.
- `in.upcshare.cn` resolves to the campus network server.
- The campus server runs the real service: Java + MySQL + `resources/`.
- Users always enter from `https://upcshare.cn`.
- Public traffic reaches the campus Java service through frp.
- The frontend probes `https://in.upcshare.cn/api/ping`. If it is reachable from the user's browser, the page redirects to the same path on `in.upcshare.cn`, bypassing the cloud/frp path.
- The top-right badge shows `校园网访问`, `公网访问`, or `线路检测中`.
- Only the campus node uses the Java + MySQL stack. Vue static assets are packaged into the Spring Boot jar, and Spring Boot listens on `127.0.0.1:8080`.

## 1. DNS

Public DNS:

```text
upcshare.cn      -> public server IPv4/IPv6
www.upcshare.cn  -> public server IPv4/IPv6
```

Campus DNS or split-horizon DNS:

```text
in.upcshare.cn   -> campus server address reachable inside campus network
```

If `in.upcshare.cn` is not reachable from the current user's network, the frontend stays on `upcshare.cn` and shows `公网访问`.

## 2. Build-Time Route Config

The route probe is compiled into the Vue bundle. Keep these values on both servers:

```env
VITE_CAMPUS_ORIGIN=https://in.upcshare.cn
VITE_PUBLIC_ORIGINS=https://upcshare.cn,https://www.upcshare.cn
VITE_ACCESS_ROUTE_PROBE_PATH=/api/ping
VITE_ACCESS_ROUTE_TIMEOUT_MS=1200
```

The browser cannot do ICMP ping, so `/api/ping` is the practical reachability check.

## 3. Environment

Copy `.env.example` to `.env` on the campus server and set strong secrets:

```env
MYSQL_DATABASE=download_site
MYSQL_USER=download_site
MYSQL_PASSWORD=change-this
MYSQL_ROOT_PASSWORD=change-root-this

JWT_SECRET=at-least-32-bytes-random-secret
NODE_NAME=campus
COOKIE_SECURE=true
RESOURCES_DIR=/app/resources
SCAN_RESOURCES_ON_STARTUP=true
MIGRATE_SQLITE=false
```

`/api/ping` returns `NODE_NAME`, and download logs store it in `source_node`. With this architecture the real app should normally use `NODE_NAME=campus`, even when reached through `upcshare.cn`, because the request is still served by the campus machine.

## 4. Start Java + MySQL On Campus Server

On the campus server:

```bash
docker compose up -d --build
docker compose ps
curl -fsS http://127.0.0.1:8080/api/ping
```

Spring Boot initializes the MySQL schema from `springboot/src/main/resources/schema.sql`. The container exposes Java only on `127.0.0.1:8080`.

Keep all files in the campus server project directory:

```text
/opt/download-site/resources/
/opt/download-site/data/files.db   # only used once for old SQLite migration
```

Do not copy `resources/` to the cloud server.

## 5. frp

On the public cloud server, install `frps` and use:

```text
deploy/frps.toml
deploy/frps.service
```

On the campus server, install `frpc` and use:

```text
deploy/frpc-campus.toml
deploy/frpc-campus.service
```

Set the same strong token in both files. Replace `PUBLIC_SERVER_IP` in `frpc-campus.toml`.

The default tunnel is:

```text
public server 127.0.0.1:18080 -> campus server 127.0.0.1:8080
```

## 6. Nginx

Use the matching Nginx file:

- Public server: `deploy/nginx-public.conf`, proxies to local frp port `127.0.0.1:18080`.
- Campus server: `deploy/nginx-campus.conf`, proxies directly to Java at `127.0.0.1:8080`.

`deploy/nginx-download-site.conf` is kept as a combined reference, but installing it on a single node requires both certificates to exist.

Then:

```bash
sudo nginx -t
sudo systemctl reload nginx
```

Certificates are expected at:

```text
/etc/letsencrypt/live/upcshare.cn/fullchain.pem
/etc/letsencrypt/live/upcshare.cn/privkey.pem
/etc/letsencrypt/live/in.upcshare.cn/fullchain.pem
/etc/letsencrypt/live/in.upcshare.cn/privkey.pem
```

## 7. Verification

On the public cloud server:

```bash
curl -fsS http://127.0.0.1:18080/api/ping
```

From a public network:

```bash
curl -I https://upcshare.cn/api/ping
curl -I https://in.upcshare.cn/api/ping
```

Expected behavior:

- If `in.upcshare.cn` is not reachable, opening `https://upcshare.cn` stays on the public server and shows `公网访问`.
- If `in.upcshare.cn` is reachable, opening `https://upcshare.cn` redirects to `https://in.upcshare.cn/...` and shows `校园网访问`.

## 8. Data Placement

There is only one data owner: the campus server.

```text
Campus server:
  Java app
  MySQL
  resources/

Cloud server:
  Nginx
  frps
```

The cloud server does not need `resources/`, MySQL, or Docker Compose for the app.
