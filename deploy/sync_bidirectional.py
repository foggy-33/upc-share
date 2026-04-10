#!/usr/bin/env python3
"""
Bidirectional incremental sync:
1) Push local unsynced download events to cloud.
2) Pull cloud users increment and upsert to local.

This avoids full SQLite overwrite and keeps cloud historical data.
"""
from __future__ import annotations

import argparse
import json
import sqlite3
import sys
from datetime import datetime
from pathlib import Path
from typing import Any
from urllib.parse import urlencode
from urllib.request import Request, urlopen


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Bidirectional sync helper")
    parser.add_argument("--db", default="/opt/download-site/data/files.db", help="local SQLite path")
    parser.add_argument("--cloud-base", required=True, help="cloud api base, e.g. https://upcshare.cn")
    parser.add_argument("--local-base", default="http://127.0.0.1:8000", help="local api base")
    parser.add_argument("--token", required=True, help="SYNC_API_TOKEN")
    parser.add_argument("--batch", type=int, default=300, help="batch size")
    parser.add_argument(
        "--users-since-file",
        default="/opt/download-site/data/.sync_users_since",
        help="checkpoint file for user incremental sync",
    )
    parser.add_argument("--timeout", type=int, default=20, help="HTTP timeout seconds")
    return parser.parse_args()


def normalize_base(url: str) -> str:
    return (url or "").strip().rstrip("/")


def http_json(
    method: str,
    url: str,
    token: str,
    payload: dict[str, Any] | None = None,
    timeout: int = 20,
) -> dict[str, Any]:
    data = None
    headers = {"X-Sync-Token": token}
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"

    req = Request(url=url, method=method.upper(), data=data, headers=headers)
    with urlopen(req, timeout=timeout) as resp:
        raw = resp.read().decode("utf-8")
        return json.loads(raw) if raw else {}


def get_unsynced_events(db: sqlite3.Connection, limit: int) -> list[dict[str, Any]]:
    rows = db.execute(
        """
        SELECT
            dl.event_id,
            dl.user_id,
            COALESCE(u.username, '') AS username,
            dl.file_id,
            dl.file_size,
            dl.downloaded_at,
            dl.source_node
        FROM download_log dl
        LEFT JOIN users u ON CAST(u.id AS TEXT) = dl.user_id
        WHERE (dl.cloud_synced_at IS NULL OR dl.cloud_synced_at = '')
          AND dl.event_id IS NOT NULL
          AND dl.event_id != ''
        ORDER BY dl.id ASC
        LIMIT ?
        """,
        (limit,),
    ).fetchall()
    return [dict(row) for row in rows]


def mark_events_synced(db: sqlite3.Connection, event_ids: list[str]):
    if not event_ids:
        return
    now_ts = datetime.now().isoformat()
    placeholders = ",".join("?" for _ in event_ids)
    db.execute(
        f"UPDATE download_log SET cloud_synced_at = ? WHERE event_id IN ({placeholders})",
        [now_ts, *event_ids],
    )
    db.commit()


def push_download_events(
    db: sqlite3.Connection,
    cloud_base: str,
    token: str,
    batch: int,
    timeout: int,
) -> int:
    total_synced = 0
    while True:
        events = get_unsynced_events(db, batch)
        if not events:
            return total_synced

        payload = {"events": events}
        resp = http_json(
            "POST",
            f"{cloud_base}/api/sync/download-events",
            token,
            payload=payload,
            timeout=timeout,
        )
        accepted = list(resp.get("accepted_event_ids") or [])
        if not accepted:
            # avoid infinite loop when upstream rejects all rows
            raise RuntimeError("cloud did not accept any download events")

        mark_events_synced(db, accepted)
        total_synced += len(accepted)

        if len(events) < batch:
            return total_synced


def load_since(path: Path) -> str:
    if not path.exists():
        return ""
    return path.read_text(encoding="utf-8").strip()


def save_since(path: Path, since: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text((since or "").strip(), encoding="utf-8")


def pull_users(
    cloud_base: str,
    local_base: str,
    token: str,
    batch: int,
    timeout: int,
    since_file: Path,
) -> int:
    since = load_since(since_file)
    total = 0

    while True:
        query = urlencode({"since": since, "limit": batch})
        resp = http_json("GET", f"{cloud_base}/api/sync/users?{query}", token, timeout=timeout)
        items = list(resp.get("items") or [])
        if not items:
            return total

        http_json(
            "POST",
            f"{local_base}/api/sync/users/upsert",
            token,
            payload={"users": items},
            timeout=timeout,
        )
        total += len(items)

        next_since = str(resp.get("next_since") or items[-1].get("updated_at") or since).strip()
        if next_since:
            since = next_since
            save_since(since_file, since)

        if len(items) < batch:
            return total


def main() -> int:
    args = parse_args()

    db_path = Path(args.db).resolve()
    if not db_path.exists():
        print(f"[sync] local db not found: {db_path}", file=sys.stderr)
        return 1

    cloud_base = normalize_base(args.cloud_base)
    local_base = normalize_base(args.local_base)
    if not cloud_base.startswith(("http://", "https://")):
        print("[sync] --cloud-base must start with http:// or https://", file=sys.stderr)
        return 1
    if not local_base.startswith(("http://", "https://")):
        print("[sync] --local-base must start with http:// or https://", file=sys.stderr)
        return 1

    db = sqlite3.connect(str(db_path))
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA busy_timeout=5000")

    try:
        pushed = push_download_events(
            db=db,
            cloud_base=cloud_base,
            token=args.token,
            batch=max(1, int(args.batch)),
            timeout=max(3, int(args.timeout)),
        )
        pulled = pull_users(
            cloud_base=cloud_base,
            local_base=local_base,
            token=args.token,
            batch=max(1, int(args.batch)),
            timeout=max(3, int(args.timeout)),
            since_file=Path(args.users_since_file),
        )
        print(f"[sync] done. pushed_events={pushed}, pulled_users={pulled}")
        return 0
    finally:
        db.close()


if __name__ == "__main__":
    raise SystemExit(main())

