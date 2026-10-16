#!/usr/bin/env bash
set -euo pipefail

if [[ ${EUID} -ne 0 ]]; then
  echo "请使用 sudo 运行: sudo ./install.sh" >&2
  exit 1
fi

cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
command -v python3 >/dev/null
getent group upc-autologin >/dev/null || groupadd --system upc-autologin
id upc-autologin >/dev/null 2>&1 || useradd --system --gid upc-autologin --no-create-home --shell /usr/sbin/nologin upc-autologin
install -m 0755 upc_autologin.py /usr/local/bin/upc-autologin
install -d -o root -g upc-autologin -m 0750 /etc/upc-autologin
if [[ ! -e /etc/upc-autologin/config.json ]]; then
  install -o root -g upc-autologin -m 0640 config.example.json /etc/upc-autologin/config.json
  echo "已创建 /etc/upc-autologin/config.json，请先填写账号和密码。"
fi
# 专用后台用户只能读取配置。
chown root:upc-autologin /etc/upc-autologin/config.json
chmod 0640 /etc/upc-autologin/config.json
install -m 0644 upc-autologin.service /etc/systemd/system/upc-autologin.service
systemctl daemon-reload
install -d -o upc-autologin -g upc-autologin -m 0750 /run/upc-autologin
echo "现有配置已保留。只读诊断: sudo /usr/local/bin/upc-autologin --diagnose"
echo "配置完成后运行: sudo systemctl enable --now upc-autologin"
