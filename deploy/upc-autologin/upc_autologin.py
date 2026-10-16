#!/usr/bin/env python3
"""UPC ePortal auto-login daemon (Python standard library only)."""

from __future__ import annotations

import argparse
import json
import logging
import os
import re
import ssl
import sys
import time
from dataclasses import dataclass
from html import unescape
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode, urljoin, urlparse
from urllib.request import HTTPCookieProcessor, HTTPRedirectHandler, Request, build_opener
import http.cookiejar


DEFAULT_PORTAL = "https://wlan.upc.edu.cn/eportal/index.jsp"
DEFAULT_PROBE = "http://www.msftconnecttest.com/connecttest.txt"
USER_AGENT = "Mozilla/5.0 (X11; Ubuntu; Linux x86_64) UPC-AutoLogin/1.0"


@dataclass
class Config:
    username: str
    password: str
    service: str = "cmcc"
    portal_url: str = DEFAULT_PORTAL
    probe_url: str = DEFAULT_PROBE
    interval: int = 20
    timeout: int = 8
    insecure: bool = False


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def load_config(path: Path) -> Config:
    raw = json.loads(path.read_text(encoding="utf-8"))
    username = os.getenv("UPC_USERNAME", str(raw.get("username", ""))).strip()
    password = os.getenv("UPC_PASSWORD", str(raw.get("password", "")))
    if not username or not password:
        raise ValueError("username/password 未配置（也可使用 UPC_USERNAME、UPC_PASSWORD 环境变量）")
    return Config(
        username=username,
        password=password,
        service=str(raw.get("service", "cmcc")),
        portal_url=str(raw.get("portal_url", DEFAULT_PORTAL)),
        probe_url=str(raw.get("probe_url", DEFAULT_PROBE)),
        interval=max(5, int(raw.get("interval", 20))),
        timeout=max(2, int(raw.get("timeout", 8))),
        insecure=bool(raw.get("insecure", False)),
    )


class PortalClient:
    def __init__(self, cfg: Config):
        self.cfg = cfg
        jar = http.cookiejar.CookieJar()
        context = ssl._create_unverified_context() if cfg.insecure else ssl.create_default_context()
        from urllib.request import HTTPSHandler
        self.opener = build_opener(HTTPCookieProcessor(jar), HTTPSHandler(context=context), HTTPRedirectHandler())
        self.no_redirect = build_opener(HTTPCookieProcessor(jar), HTTPSHandler(context=context), NoRedirect())

    def _request(self, url: str, data: bytes | None = None, follow=True):
        request = Request(url, data=data, headers={"User-Agent": USER_AGENT})
        return (self.opener if follow else self.no_redirect).open(request, timeout=self.cfg.timeout)

    def online(self) -> bool:
        try:
            with self._request(self.cfg.probe_url, follow=False) as response:
                body = response.read(128).decode("utf-8", "ignore").strip()
                return response.status == 200 and "Microsoft Connect Test" in body
        except HTTPError as exc:
            # A captive-portal redirect is deliberately not followed.
            return False if 300 <= exc.code < 400 else False
        except (URLError, TimeoutError, OSError):
            return False

    @staticmethod
    def _hidden(html: str, name: str, default="") -> str:
        patterns = [
            rf'<input[^>]+id=["\']{re.escape(name)}["\'][^>]+value=["\']([^"\']*)',
            rf'<input[^>]+value=["\']([^"\']*)["\'][^>]+id=["\']{re.escape(name)}["\']',
        ]
        for pattern in patterns:
            match = re.search(pattern, html, re.I)
            if match:
                return unescape(match.group(1))
        return default

    def discover(self) -> tuple[str, str, str]:
        """Return (portal base URL, raw captive query string, HTML)."""
        with self._request(self.cfg.portal_url) as response:
            final_url = response.geturl()
            raw = response.read().decode("gb18030", "replace")
        parsed = urlparse(final_url)
        query = parsed.query or urlparse(self.cfg.portal_url).query
        base = urljoin(final_url, "./")
        return base, query, raw

    @staticmethod
    def _q2(value: str) -> str:
        return quote(quote(value, safe=""), safe="")

    def login(self) -> tuple[bool, str]:
        base, query, html = self.discover()
        encrypt = self._hidden(html, "passwordEncrypt", "false").lower()
        if encrypt == "true":
            return False, "门户要求 RSA 加密密码；当前轻量脚本不会降级明文，请联系维护者适配页面公钥"

        fields = {
            "userId": self._q2(self.cfg.username),
            "password": self._q2(self.cfg.password),
            "service": self._q2(self.cfg.service),
            "queryString": self._q2(query),
            "operatorPwd": "",
            "operatorUserId": "",
            "validcode": "",
            "passwordEncrypt": self._q2(encrypt),
        }
        # Values above are already encoded exactly as the portal JavaScript does.
        body = "&".join(f"{key}={value}" for key, value in fields.items()).encode("ascii")
        endpoint = urljoin(base, "InterFace.do?method=login")
        request = Request(endpoint, data=body, headers={
            "User-Agent": USER_AGENT,
            "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
            "Referer": self.cfg.portal_url,
        })
        try:
            with self.opener.open(request, timeout=self.cfg.timeout) as response:
                payload = json.loads(response.read().decode("utf-8", "replace"))
        except (ValueError, HTTPError, URLError, TimeoutError, OSError) as exc:
            return False, f"登录请求失败: {exc}"
        ok = payload.get("result") == "success"
        return ok, str(payload.get("message") or ("登录成功" if ok else "门户返回失败"))


def main() -> int:
    parser = argparse.ArgumentParser(description="UPC 校园网自动登录与断线重连")
    parser.add_argument("--config", type=Path, default=Path("/etc/upc-autologin/config.json"))
    parser.add_argument("--once", action="store_true", help="只检查并尝试一次")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    try:
        cfg = load_config(args.config)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        logging.error("配置错误: %s", exc)
        return 2

    client = PortalClient(cfg)
    failures = 0
    while True:
        if client.online():
            failures = 0
            logging.debug("网络正常")
        else:
            logging.warning("检测到断网，尝试登录")
            try:
                ok, message = client.login()
            except (HTTPError, URLError, TimeoutError, OSError) as exc:
                ok, message = False, f"无法访问门户: {exc}"
            if ok:
                time.sleep(2)
                if client.online():
                    logging.info("登录成功，互联网连接已恢复")
                    failures = 0
                else:
                    logging.warning("门户返回成功，但外网探测尚未通过: %s", message)
            else:
                failures += 1
                logging.error("登录失败: %s", message)
        if args.once:
            return 0 if client.online() else 1
        delay = min(cfg.interval * (2 ** min(failures, 4)), 300)
        time.sleep(delay)


if __name__ == "__main__":
    sys.exit(main())
