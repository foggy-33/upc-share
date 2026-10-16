"""Offline contract tests: no real network requests or credentials."""
import json
import unittest
from unittest.mock import patch
from urllib.parse import unquote
import upc_autologin as agent


class AgentTests(unittest.TestCase):
    def client(self):
        return agent.PortalClient(dict(portal_url=agent.PORTAL, probes=agent.PROBES,
                                       username='test@example', password='p&ss', service='cmcc',
                                       timeout=2, interval=5, failure_threshold=2))

    def test_current_redirect_and_double_encoding(self):
        client = self.client()
        calls = []
        def request(url, fields=None):
            calls.append((url, fields))
            if url == agent.PROBES[0]['url']:
                return 302, {'Location': 'http://wlan.upc.edu.cn/eportal/index.jsp?wlanuserip=10.0.0.99&nasip=10.0.0.1'}, b''
            if fields is None:
                return 200, {}, b'<input value="false" id="passwordEncrypt">'
            return 200, {}, json.dumps({'result': 'success', 'userIndex': 'session', 'keepaliveInterval': 1}).encode()
        client.request = request
        client.login()
        fields = calls[-1][1]
        self.assertIn('10.0.0.99', fields['queryString'])
        self.assertEqual(client.keepalive_seconds, 60)
        self.assertTrue(calls[1][0].startswith('https://'))
        for value in ('p&ss', 'user@domain', fields['queryString'], '中文'):
            self.assertEqual(unquote(unquote(agent.q2(value))), value)

    def test_redirects_and_wrong_body_are_not_online(self):
        client = self.client()
        client.request = lambda *a, **k: (302, {'Location': agent.PORTAL}, b'')
        self.assertFalse(client.online())
        client.request = lambda *a, **k: (200, {}, b'<html>login</html>')
        self.assertFalse(client.online())
        client.request = lambda *a, **k: (200, {}, b'Microsoft Connect Test')
        with patch.object(client, 'login') as login:
            self.assertEqual(agent.run(client, client.cfg, once=True), 0)
            login.assert_not_called()

    def test_no_fallback_to_stale_url(self):
        client = self.client()
        client.cfg['portal_url'] += '?wlanuserip=OLD'
        client.request = lambda *a, **k: (200, {}, b'ordinary response')
        with self.assertRaises(ValueError):
            client.prepare()

    def test_simulated_disconnect_reconnect(self):
        client = self.client()
        with patch.object(client, 'online', side_effect=[False, True]), \
             patch.object(client, 'login') as login, \
             patch.object(agent.STOP, 'wait', return_value=False):
            with self.assertLogs(level='INFO') as logs:
                self.assertEqual(agent.run(client, client.cfg, once=True), 0)
            login.assert_called_once()
            self.assertTrue(any('RECONNECTED' in line for line in logs.output))

    def test_portal_success_without_internet_is_failure(self):
        client = self.client()
        with patch.object(client, 'online', return_value=False), \
             patch.object(client, 'login'), patch.object(agent.STOP, 'wait', return_value=False):
            self.assertEqual(agent.run(client, client.cfg, once=True), 1)

    def test_rsa_known_packing(self):
        # Reverse 'ab' -> 'ba', pack little endian => 0x6162.
        n = (1 << 64) - 59
        result = format(pow(0x6162, 65537, n), 'x')
        expected = result.zfill(((len(result)+3)//4)*4)
        self.assertEqual(agent.rsa_password('ab', '10001', format(n, 'x')), expected)

    def test_js_and_meta_redirects(self):
        client = self.client()
        for body in [b"<script>location.href='http://wlan.upc.edu.cn/eportal/index.jsp?wlanuserip=NEW';</script>",
                     b'<meta http-equiv="refresh" content="0;url=https://wlan.upc.edu.cn/eportal/index.jsp?wlanuserip=NEW&amp;x=1">']:
            self.assertIn('wlanuserip=NEW', client.candidate(agent.PROBES[0]['url'], body))


if __name__ == '__main__':
    unittest.main()
