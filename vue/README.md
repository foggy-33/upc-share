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

## 批量上传页（`/admin`）怎么验证

折算、查重、限速、拖入文件夹的摊平、结果导出的 CSV、以及「从文件夹导入」旁那颗「?」里的整理规范，都是纯函数或纯文本，不需要浏览器也不需要后端：

```bash
node --test src/uploader/logic.test.mjs      # 31 条用例
```

整页交互想在本地跑一遍，可以用仓库里自带的接口替身（它监听 8080，正好是上面那条代理的目标）：

```bash
node tools/mock-upcshare-api.cjs                                  # 终端 A
npm run dev                                                       # 终端 B
chrome --headless=new --remote-debugging-port=9447 \
  --user-data-dir=%TEMP%/upc-chrome http://127.0.0.1:5173/admin    # 终端 C
node tools/ui-check.mjs                                           # 终端 D：真实鼠标点一遍，27 项断言
```

`tools/` 下的两个脚本只服务开发验证，不参与构建，也不会碰到线上数据。
`tools/*.png` 是验证时截的图，给 code review 看效果用。

---

感谢 [upc-share](https://github.com/foggy-33/upc-share) 的作者为学校资源共享做出的伟大贡献；
感谢 DeepSeek V4.1 Flash 与 Qwen 3.8 Flash 的优渥能力。
