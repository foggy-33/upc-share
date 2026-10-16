#!/usr/bin/env bash
set -euo pipefail

if [[ ${EUID} -ne 0 ]]; then
  echo "请使用 sudo 运行: sudo ./install.sh" >&2
  exit 1
fi

install -m 0755 upc_autologin.py /usr/local/bin/upc-autologin
install -d -m 0750 /etc/upc-autologin
if [[ ! -e /etc/upc-autologin/config.json ]]; then
  install -o root -g nogroup -m 0640 config.example.json /etc/upc-autologin/config.json
  echo "已创建 /etc/upc-autologin/config.json，请先填写账号和密码。"
fi
# 保证 systemd 中的 nobody/nogroup 只能读取、不能修改配置。
chown root:nogroup /etc/upc-autologin/config.json
chmod 0640 /etc/upc-autologin/config.json
install -m 0644 upc-autologin.service /etc/systemd/system/upc-autologin.service
systemctl daemon-reload
echo "配置完成后运行: sudo systemctl enable --now upc-autologin"
