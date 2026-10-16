#!/usr/bin/env python3
"""UPC ePortal agent, Python 3.8+, standard library only."""
import argparse
import http.cookiejar
import json
import logging
import os
import re
import signal
import ssl
import threading
import time
from html import unescape
from html.parser import HTMLParser
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, quote, urljoin, urlparse
from urllib.request import (HTTPCookieProcessor, HTTPRedirectHandler, HTTPSHandler,
                            ProxyHandler, Request, build_opener)

PORTAL = 'https://wlan.upc.edu.cn/eportal/index.jsp'
PROBES = [
    {'url': 'http://www.msftconnecttest.com/connecttest.txt', 'status': 200,
     'body': 'Microsoft Connect Test'},
    {'url': 'http://connectivitycheck.gstatic.com/generate_204', 'status': 204, 'body': ''},
]
STOP = threading.Event()


class PortalError(ValueError):
    """Locally authored diagnostic text, safe to include in the journal."""


class Inputs(HTMLParser):
    def __init__(self, html):
        super().__init__()
        self.values = {}
        self.feed(html)

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'input':
            self.values[attrs.get('id', attrs.get('name', ''))] = attrs.get('value', '')


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def q2(value):
    return quote(quote(value, safe="~()*!.'-"), safe="~()*!.'-")


def rsa_password(password, exponent, modulus):
    """Match RSAUtils encryptedString on reversed JS UTF-16 code units."""
    e, n = int(exponent, 16), int(modulus, 16)
    chunk = 2 * ((n.bit_length() - 1) // 16)
    if chunk < 2 or e < 3:
        raise ValueError('invalid RSA key')
    encoded = password.encode('utf-16-le')
    units = [int.from_bytes(encoded[i:i+2], 'little') for i in range(0, len(encoded), 2)][::-1]
    units += [0] * ((-len(units)) % chunk)
    blocks = []
    for offset in range(0, len(units), chunk):
        block = sum((units[i] + (units[i+1] << 8)) << (8*(i-offset))
                    for i in range(offset, offset+chunk, 2))
        result = format(pow(block, e, n), 'x')
        blocks.append(result.zfill(((len(result)+3)//4)*4))
    return ' '.join(blocks)


def load_config(path):
    cfg = json.loads(path.read_text(encoding='utf-8-sig'))
    for key in ('username', 'password'):
        cfg[key] = os.getenv('UPC_'+key.upper(), cfg.get(key, ''))
        if not isinstance(cfg[key], str) or not cfg[key] or cfg[key].startswith('你的'):
            raise ValueError('请填写 username/password')
    cfg.setdefault('portal_url', PORTAL)
    cfg.setdefault('service', 'cmcc')
    cfg.setdefault('probes', PROBES)
    cfg['interval'] = max(5, int(cfg.get('interval', 20)))
    cfg['timeout'] = max(2, int(cfg.get('timeout', 8)))
    cfg['failure_threshold'] = max(1, int(cfg.get('failure_threshold', 2)))
    if not cfg['probes']:
        raise ValueError('probes must not be empty')
    return cfg


class PortalClient:
    def __init__(self, cfg):
        self.cfg = cfg
        self.host = urlparse(cfg['portal_url']).hostname
        if not self.host or urlparse(cfg['portal_url']).scheme != 'https':
            raise ValueError('portal_url 必须使用 HTTPS')
        if cfg.get('insecure', False):
            raise ValueError('请修复 CA/时间，不支持 insecure')
        self.opener = build_opener(ProxyHandler({}), HTTPSHandler(context=ssl.create_default_context()),
                                   HTTPCookieProcessor(http.cookiejar.CookieJar()), NoRedirect())
        self.base = urljoin(cfg['portal_url'], './')
        self.user_index = ''
        self.keepalive_seconds = 0
        self.keepalive_at = 0

    def request(self, url, fields=None):
        data = None if fields is None else '&'.join(k+'='+q2(str(v)) for k, v in fields.items()).encode('ascii')
        headers = {'User-Agent': 'Mozilla/5.0 UPC-AutoLogin/2.0'}
        if data is not None:
            if urlparse(url).hostname != self.host or urlparse(url).scheme != 'https':
                raise ValueError('refused foreign/non-HTTPS submission')
            headers['Content-Type'] = 'application/x-www-form-urlencoded; charset=UTF-8'
        try:
            with self.opener.open(Request(url, data=data, headers=headers), timeout=self.cfg['timeout']) as r:
                return r.status, r.headers, r.read(2*1024*1024)
        except HTTPError as exc:
            with exc:
                return exc.code, exc.headers, exc.read(2*1024*1024)

    def online(self):
        for probe in self.cfg['probes']:
            try:
                status, _, body = self.request(probe['url'])
                if status == int(probe['status']) and body.decode('utf-8', 'replace').strip() == probe['body']:
                    return True
            except (OSError, URLError):
                pass
        return False

    def candidate(self, url, body):
        text = body.decode('utf-8', 'replace')
        candidates = re.findall(r'''(?:location(?:\.href)?\s*=|location\.replace\s*\()\s*["']([^"']+)''', text, re.I)
        candidates += re.findall(r'''url\s*=\s*([^\s"'<>]+)''', text, re.I)
        candidates += re.findall(r'''https?://[^\s"'<>]+''', text)
        for value in candidates:
            value = urljoin(url, unescape(value))
            if urlparse(value).hostname == self.host and urlparse(value).path.startswith('/eportal/'):
                return value
        return None

    def discover(self):
        # Always obtain the current IP/NAS/MAC from a gateway redirect.
        for probe in self.cfg['probes']:
            try:
                _, headers, body = self.request(probe['url'])
                url = urljoin(probe['url'], headers['Location']) if headers.get('Location') else self.candidate(probe['url'], body)
                for _ in range(6):
                    if not url:
                        break
                    parsed = urlparse(url)
                    if parsed.hostname != self.host or not parsed.path.startswith('/eportal/'):
                        break
                    url = parsed._replace(scheme='https').geturl()
                    status, headers, body = self.request(url)
                    if 300 <= status < 400 and headers.get('Location'):
                        url = urljoin(url, headers['Location'])
                        continue
                    query = urlparse(url).query
                    if status == 200 and 'wlanuserip' in {k.lower() for k in parse_qs(query)}:
                        self.base = urljoin(url, './')
                        charset = re.search(rb'charset=["\']?([\w-]+)', body, re.I)
                        encoding = charset[1].decode('ascii') if charset else 'utf-8'
                        return query, body.decode(encoding, 'replace')
                    url = self.candidate(url, body)
            except (OSError, URLError):
                continue
        raise PortalError('未发现当前门户重定向；检查物理连接、DHCP 和探测地址')

    def prepare(self):
        query, html = self.discover()
        inputs = Inputs(html).values
        encrypt = inputs.get('passwordEncrypt', '').lower()
        if encrypt not in ('true', 'false'):
            raise PortalError('页面缺少 passwordEncrypt，无法确定协议')
        password = self.cfg['password']
        if encrypt == 'true':
            mac = parse_qs(query).get('mac', ['111111111'])[0]
            password = rsa_password(password+'>'+mac, inputs['publicKeyExponent'], inputs['publicKeyModulus'])
        return dict(userId=self.cfg['username'], password=password, service=self.cfg['service'],
                    queryString=query, operatorPwd='', operatorUserId='', validcode='', passwordEncrypt=encrypt)

    def post(self, method, fields):
        status, _, body = self.request(self.base+'InterFace.do?method='+method, fields)
        if status != 200:
            raise ValueError('portal HTTP '+str(status))
        result = json.loads(body.decode('utf-8-sig'))
        if not isinstance(result, dict):
            raise ValueError('invalid portal response')
        return result

    def login(self):
        result = self.post('login', self.prepare())
        if result.get('result') != 'success':
            raise PortalError('门户拒绝登录；浏览器检查账号/服务、验证码或运营商绑定')
        self.user_index = str(result.get('userIndex', ''))
        self.keepalive_seconds = max(0, float(result.get('keepaliveInterval') or 0)*60)
        self.keepalive_at = time.monotonic()+self.keepalive_seconds

    def keepalive(self):
        if self.user_index and self.keepalive_seconds and time.monotonic() >= self.keepalive_at:
            self.keepalive_at = time.monotonic()+self.keepalive_seconds
            result = self.post('keepalive', {'userIndex': self.user_index})
            if result.get('result') != 'success':
                logging.warning('门户保活未成功，继续外网探测')


def run(client, cfg, once=False):
    failures = checks = 0
    last_online = None
    while not STOP.is_set():
        online = client.online()
        if online:
            if last_online is not True:
                logging.info('ONLINE: 外网探测通过，无需登录')
            failures = checks = 0
            try:
                client.keepalive()
            except (ValueError, OSError, URLError):
                logging.warning('保活请求失败，继续监测')
        else:
            checks += 1
            if once or checks >= cfg['failure_threshold']:
                logging.warning('OFFLINE: 连续探测失败，获取当前门户参数并登录')
                try:
                    client.login()
                    for _ in range(3):
                        if STOP.wait(3):
                            return 0
                        if client.online():
                            online = True
                            break
                    if not online:
                        raise PortalError('门户接受登录，但外网尚未恢复')
                    logging.info('RECONNECTED: 登录成功，外网连接已恢复')
                    failures = checks = 0
                except (ValueError, KeyError, OSError, URLError) as exc:
                    failures += 1
                    # Never log arbitrary server messages/URLs containing tokens.
                    reason = str(exc) if isinstance(exc, PortalError) else type(exc).__name__
                    logging.error('重连失败，第 %d 次：%s；运行 --diagnose 检查', failures, reason)
        last_online = online
        if once:
            return 0 if online else 1
        delay = min(cfg['interval']*2**min(failures, 4), 300)
        if online and client.keepalive_seconds and client.user_index:
            delay = min(delay, max(1, client.keepalive_at-time.monotonic()))
        STOP.wait(delay)
    return 0


def main():
    parser = argparse.ArgumentParser(description='UPC 自动登录与断线重连')
    parser.add_argument('--config', type=Path, default=Path('/etc/upc-autologin/config.json'))
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--once', action='store_true', help='联网跳过，否则尝试一次登录')
    mode.add_argument('--diagnose', action='store_true', help='只读检查，不登录、不注销')
    parser.add_argument('--lock-file', default='/run/upc-autologin/agent.lock')
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s')
    try:
        cfg = load_config(args.config)
        client = PortalClient(cfg)
        if args.diagnose:
            print('配置有效；service='+cfg['service'])
            if client.online():
                print('ONLINE: 外网可达；未提交登录，也未注销。')
                print('当前已认证，无法验证未认证门户参数和真实登录。')
                return 0
            try:
                fields = client.prepare()
                print('门户发现及密码编码成功；passwordEncrypt='+fields['passwordEncrypt'])
                print('未提交账号密码；真实登录仍需未认证状态验证。')
                return 0
            except (ValueError, KeyError, OSError, URLError) as exc:
                print('门户发现/协议检查失败：'+str(exc) if isinstance(exc, ValueError) else type(exc).__name__)
                return 1
        import fcntl  # Linux only; diagnostics and offline tests also run on Windows.
        try:
            lock = open(args.lock_file, 'x')
            os.chmod(args.lock_file, 0o644)
        except FileExistsError:
            lock = open(args.lock_file, 'r')
        with lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                logging.error('已有实例运行；请使用 --diagnose 或 journalctl')
                return 2
            for sig in (signal.SIGINT, signal.SIGTERM):
                signal.signal(sig, lambda *_: STOP.set())
            return run(client, cfg, args.once)
    except (ValueError, OSError, URLError) as exc:
        logging.error('启动失败：%s', str(exc))
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
