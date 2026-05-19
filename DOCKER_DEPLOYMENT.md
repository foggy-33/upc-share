# upcshare.cn Deployment

Target architecture:

- `upcshare.cn` and `www.upcshare.cn` run on the public cloud server.
- `in.upcshare.cn` runs on the campus network server.
- Users always enter from `https://upcshare.cn`.
- The frontend probes `https://in.upcshare.cn/api/ping`. If it is reachable from the user's browser, the page redirects to the same path on `in.upcshare.cn`.
- The top-right badge shows `校园网访问`, `公网访问`, or `线路检测中`.
- Both nodes use the same Java + MySQL stack: Vue static assets are packaged into the Spring Boot jar, Spring Boot listens on `127.0.0.1:8080`, and Nginx terminates HTTPS.

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

Copy `.env.example` to `.env` on each server and set strong secrets:

```env
MYSQL_DATABASE=download_site
MYSQL_USER=download_site
MYSQL_PASSWORD=change-this
MYSQL_ROOT_PASSWORD=change-root-this

JWT_SECRET=at-least-32-bytes-random-secret
NODE_NAME=public
COOKIE_SECURE=true
RESOURCES_DIR=/app/resources
SCAN_RESOURCES_ON_STARTUP=true
MIGRATE_SQLITE=false
```

Use the same `JWT_SECRET` on both nodes if you expect login cookies to remain valid when switching domains. Cookies are still host-scoped by the browser, so users may need to log in once per domain unless a shared cookie domain is added later.

Set `NODE_NAME=public` on the public server and `NODE_NAME=campus` on the campus server. `/api/ping` returns this value, and download logs store it in `source_node`.

## 4. Start Java + MySQL

On the public server and on the campus server:

```bash
docker compose up -d --build
docker compose ps
curl -fsS http://127.0.0.1:8080/api/ping
```

Spring Boot initializes the MySQL schema from `springboot/src/main/resources/schema.sql`. The container exposes Java only on `127.0.0.1:8080`; public traffic should enter through Nginx.

## 5. Nginx

Use the matching Nginx file:

- Public server: `deploy/nginx-public.conf`
- Campus server: `deploy/nginx-campus.conf`

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

## 6. Verification

From a public network:

```bash
curl -I https://upcshare.cn/api/ping
curl -I https://in.upcshare.cn/api/ping
```

Expected behavior:

- If `in.upcshare.cn` is not reachable, opening `https://upcshare.cn` stays on the public server and shows `公网访问`.
- If `in.upcshare.cn` is reachable, opening `https://upcshare.cn` redirects to `https://in.upcshare.cn/...` and shows `校园网访问`.

## 7. Data Placement

Each node has its own MySQL and `resources/` volume by default. For a consistent file list on both domains, keep the public and campus node synchronized at the database and resource-file level. The current application is ready for MySQL on both nodes; cross-node replication or scheduled sync should be chosen according to the server connectivity you actually have.
