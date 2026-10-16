# UPC Ubuntu 校园网自动登录

脚本按 ePortal 网页的实际接口登录，定期探测互联网；断线后会自动重登并指数退避。仅使用 Python 3 标准库。

## 安装

```bash
cd campus-network
chmod +x install.sh
sudo ./install.sh
sudo nano /etc/upc-autologin/config.json
sudo systemctl enable --now upc-autologin
```

查看状态和日志：

```bash
systemctl status upc-autologin
journalctl -u upc-autologin -f
```

先手动测试（推荐）：

```bash
sudo /usr/local/bin/upc-autologin --config /etc/upc-autologin/config.json --once
```

`service` 可选值：`default`（校园网）、`local`（校园网内）、`cmcc`（中国移动）、`unicom`（中国联通）、`ctcc`（中国电信）。参考的成功页面显示为 `cmcc`，示例因此默认使用它。

示例已填入你提供的完整入口 URL。若 Ubuntu 的 IP、接入交换机或网卡发生变化，请在浏览器重新打开任意 HTTP 页面，复制最新跳转地址替换 `portal_url`；这些入口参数不能凭空可靠推导。

配置文件含明文密码，安装脚本将权限设为 `0640`。也可删除其中账号密码，通过 systemd 的 `EnvironmentFile=` 注入 `UPC_USERNAME` 和 `UPC_PASSWORD`。

如果日志提示门户要求 RSA 加密，说明学校改了登录策略；脚本会停止该次登录而不会把密码降级为明文发送。
