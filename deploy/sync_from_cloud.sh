#!/bin/bash
# One-way sync: cloud server -> campus server
# Sync scope:
#   1) SQLite database: data/files.db (via sqlite .backup snapshot)
#   2) Resource files:   resources/

set -euo pipefail

REMOTE_HOST=""
REMOTE_APP_DIR="/opt/download-site"
LOCAL_APP_DIR="/opt/download-site"
SERVICE_NAME="download-site"
SSH_PORT="22"
KEEP_LOCAL_DB_BACKUPS="3"

usage() {
  cat <<'EOF'
Usage:
  bash deploy/sync_from_cloud.sh --remote user@cloud-ip [options]

Required:
  --remote <user@host>           SSH target of cloud server

Options:
  --remote-app-dir <path>        Remote project dir (default: /opt/download-site)
  --local-app-dir <path>         Local project dir on campus server (default: /opt/download-site)
  --service-name <name>          Local systemd service name (default: download-site)
  --ssh-port <port>              SSH port (default: 22)
  --keep-db-backups <N>          Keep latest N local DB backups (default: 3)
  -h, --help                     Show this help

Examples:
  bash deploy/sync_from_cloud.sh --remote ubuntu@203.0.113.10
  bash deploy/sync_from_cloud.sh --remote root@1.2.3.4 --ssh-port 2222
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --remote)
      REMOTE_HOST="${2:-}"; shift 2 ;;
    --remote-app-dir)
      REMOTE_APP_DIR="${2:-}"; shift 2 ;;
    --local-app-dir)
      LOCAL_APP_DIR="${2:-}"; shift 2 ;;
    --service-name)
      SERVICE_NAME="${2:-}"; shift 2 ;;
    --ssh-port)
      SSH_PORT="${2:-}"; shift 2 ;;
    --keep-db-backups)
      KEEP_LOCAL_DB_BACKUPS="${2:-}"; shift 2 ;;
    -h|--help)
      usage; exit 0 ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1 ;;
  esac
done

if [[ -z "$REMOTE_HOST" ]]; then
  echo "Missing required argument: --remote user@host" >&2
  usage
  exit 1
fi

for cmd in ssh rsync sqlite3; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "Missing command: $cmd" >&2
    exit 1
  fi
done

if [[ ! -d "$LOCAL_APP_DIR" ]]; then
  echo "Local app dir not found: $LOCAL_APP_DIR" >&2
  exit 1
fi

mkdir -p "$LOCAL_APP_DIR/data" "$LOCAL_APP_DIR/resources"

TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
TMP_DIR="${LOCAL_APP_DIR}/data/.sync_tmp_${TIMESTAMP}"
LOCAL_DB="${LOCAL_APP_DIR}/data/files.db"
LOCAL_DB_BACKUP="${LOCAL_APP_DIR}/data/files.db.bak.${TIMESTAMP}"
REMOTE_DB="${REMOTE_APP_DIR}/data/files.db"
REMOTE_DB_SNAPSHOT="/tmp/files.db.sync.${TIMESTAMP}.$$"

mkdir -p "$TMP_DIR"
SERVICE_STOPPED="0"

cleanup() {
  local exit_code="$1"
  if [[ "$exit_code" -ne 0 ]] && [[ "$SERVICE_STOPPED" == "1" ]]; then
    echo "Sync failed, trying to restore local service: $SERVICE_NAME" >&2
    sudo systemctl start "$SERVICE_NAME" >/dev/null 2>&1 || true
  fi
  rm -rf "$TMP_DIR" >/dev/null 2>&1 || true
  ssh -p "$SSH_PORT" "$REMOTE_HOST" "rm -f '$REMOTE_DB_SNAPSHOT'" >/dev/null 2>&1 || true
}
trap 'cleanup $?' EXIT

echo "==== 1) Create remote SQLite snapshot ===="
ssh -p "$SSH_PORT" "$REMOTE_HOST" "set -e;
  test -f '$REMOTE_DB';
  sqlite3 '$REMOTE_DB' \".timeout 5000\" \".backup '$REMOTE_DB_SNAPSHOT'\";
  test -f '$REMOTE_DB_SNAPSHOT'"

echo "==== 2) Pull resource files (phase 1, low downtime) ===="
rsync -az --delete -e "ssh -p ${SSH_PORT}" \
  "${REMOTE_HOST}:${REMOTE_APP_DIR}/resources/" \
  "${LOCAL_APP_DIR}/resources/"

echo "==== 3) Pull DB snapshot ===="
rsync -az -e "ssh -p ${SSH_PORT}" \
  "${REMOTE_HOST}:${REMOTE_DB_SNAPSHOT}" \
  "${TMP_DIR}/files.db"

echo "==== 4) Stop local service ===="
sudo systemctl stop "$SERVICE_NAME"
SERVICE_STOPPED="1"

echo "==== 5) Final resource delta sync ===="
rsync -az --delete -e "ssh -p ${SSH_PORT}" \
  "${REMOTE_HOST}:${REMOTE_APP_DIR}/resources/" \
  "${LOCAL_APP_DIR}/resources/"

echo "==== 6) Replace local DB atomically ===="
if [[ -f "$LOCAL_DB" ]]; then
  cp -f "$LOCAL_DB" "$LOCAL_DB_BACKUP"
fi
install -m 664 "${TMP_DIR}/files.db" "${LOCAL_DB}.new"
mv -f "${LOCAL_DB}.new" "$LOCAL_DB"

if id -u www-data >/dev/null 2>&1; then
  chown -R www-data:www-data "${LOCAL_APP_DIR}/data" "${LOCAL_APP_DIR}/resources"
fi

echo "==== 7) Start local service ===="
sudo systemctl start "$SERVICE_NAME"
SERVICE_STOPPED="0"
sudo systemctl --no-pager --full status "$SERVICE_NAME" | sed -n '1,8p'

if [[ "$KEEP_LOCAL_DB_BACKUPS" =~ ^[0-9]+$ ]] && [[ "$KEEP_LOCAL_DB_BACKUPS" -ge 0 ]]; then
  ls -1t "${LOCAL_APP_DIR}/data"/files.db.bak.* 2>/dev/null | tail -n +"$((KEEP_LOCAL_DB_BACKUPS + 1))" | xargs -r rm -f
fi

echo ""
echo "Sync completed."
echo "Source : ${REMOTE_HOST}:${REMOTE_APP_DIR}"
echo "Target : ${LOCAL_APP_DIR}"
echo "Service: ${SERVICE_NAME}"
