# UPC Ubuntu 校园网自动登录

Python 3.8+，无第三方依赖。作为宿主机 systemd 服务运行，不随网站 Docker 容器启动。

## 安装或升级

先把当前项目更新同步到服务器，再执行：

```bash
cd /opt/download-site
sudo bash deploy/upc-autologin/install.sh
sudo nano /etc/upc-autologin/config.json
```

安装器保留已有账号配置，修复目录权限并创建专用用户。`portal_url` 只需填写
`https://wlan.upc.edu.cn/eportal/index.jsp`。旧配置中的固定 IP/MAC 查询参数会被忽略，
每次需要登录时，脚本通过 HTTP 外网探测获取网关提供的当前参数。

`service`：`default` 校园网、`local` 校园网内、`cmcc` 中国移动、`unicom` 中国联通、`ctcc` 中国电信。
默认 `cmcc` 来自参考成功页，请按自己的套餐填写。

先做不会提交登录、不会注销网络的诊断：

```bash
sudo /usr/local/bin/upc-autologin --diagnose
sudo -u upc-autologin /usr/local/bin/upc-autologin --diagnose
```

第二条同时确认后台用户可以读取配置。在已联网状态下，诊断只验证配置和外网探测，
不能证明未认证状态的真实登录成功。

启用（升级已运行的服务时也执行 restart，让新版生效）：

```bash
sudo systemctl enable upc-autologin
sudo systemctl restart upc-autologin
systemctl status upc-autologin --no-pager
journalctl -u upc-autologin -n 50 --no-pager
```

正常联网日志为 `ONLINE`；真实断线后重新认证并通过外网探测，才记录 `RECONNECTED`。
无需在 SSH 中注销或关闭网络。只有在本机终端或备用管理通道就绪时，才考虑主动断线实测。

## 重连行为

- 两个独立 HTTP 探测点，任一返回预期状态和正文即认为联网。重定向和门户 HTML 不算联网。
- 默认连续两轮探测失败才尝试登录，减少短暂波动造成的重复认证。
- 支持 HTTP Location、常见 JS literal 和 meta refresh 跳转，获取当前 NAS/IP/MAC。
- 根据当前页面 `passwordEncrypt` 和公钥使用网页兼容 RSA 编码，不猜测缺失字段。
- 按网页相同方式双重 URL 编码；认证只提交至配置门户的 HTTPS 地址。
- 读取登录响应中的保活间隔；失败重试逐步退避，最大约 5 分钟（另加请求耗时）。
- 登录接口返回成功后再次探测外网，通过后才报告恢复。
- systemd 崩溃重启和单实例锁；`--once` 与后台服务不会并发登录。
- 不调用注销接口、不重启网卡、不改路由，也不会自动切换到热点。

配置可增加 `probes` 数组，元素为 `url`、`status`、`body`（必须与真实响应匹配），以及
`failure_threshold`、`interval`、`timeout`。旧版 `probe_url` 已由默认的双探测点替代。
支持通过 `UPC_USERNAME`、`UPC_PASSWORD` 环境变量提供凭据。保持 `insecure: false`。

如果物理链路或 DHCP 断开，或学校门户新增验证码/运营商二次认证，账号登录脚本不能代替这些步骤。
动态发现也依赖网关对探测地址发出门户跳转。诊断离线时可检查该阶段，且不会提交凭据。

## 离线验证

以下测试只使用模拟响应，不访问校园网、不提交账号密码：

```bash
python3 -m unittest discover -s deploy/upc-autologin -p test_autologin.py -v
```

覆盖当前参数发现、HTTP/JS/meta 跳转、密码编码、模拟掉线恢复、联网时不登录、
门户成功但外网失败的判定，以及不复用过期参数。通过模拟测试不能替代校园网真实认证验证。
