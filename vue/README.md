# Vue 前端

Vue 3 + Vite 前端，开发环境会把 `/api` 代理到 Spring Boot 的 `http://127.0.0.1:8080`。

```bash
cd vue
npm install
npm run dev
```

生产构建：

```bash
npm run build
```

构建产物在 `dist/`，可以由 Nginx 托管，并把 `/api` 反向代理到 Spring Boot。
