/**
 * 访问入口选择：
 * 1) 优先探测校内入口可达性
 * 2) 可达则跳转校内，不可达则停留公网
 * 3) 在导航栏显示当前访问线路
 */
(function () {
    'use strict';

    const cfgEl = document.getElementById('accessRouteConfig');
    if (!cfgEl) return;

    let cfg = {};
    try {
        cfg = JSON.parse(cfgEl.textContent || '{}');
    } catch (e) {
        return;
    }

    const campusOrigin = normalizeOrigin(cfg.campus_origin);
    const probePath = normalizePath(cfg.probe_path || '/api/ping');
    const timeoutMs = asPositiveInt(cfg.timeout_ms, 1200);
    const failCacheMs = asPositiveInt(cfg.fail_cache_ms, 120000);
    const currentOrigin = normalizeOrigin(window.location.origin);
    const statusEl = document.getElementById('accessRouteBadge');
    const failCacheKey = `upcshare_access_route_fail_v1_${campusOrigin || 'none'}`;

    if (campusOrigin && currentOrigin === campusOrigin) {
        renderStatus('campus');
        return;
    }

    renderStatus('checking');

    if (!campusOrigin || campusOrigin === currentOrigin) {
        renderStatus('public');
        return;
    }

    if (isRecentCampusProbeFail()) {
        renderStatus('public');
        return;
    }

    probeCampusAndRedirect();

    async function probeCampusAndRedirect() {
        const ok = await probeOrigin(campusOrigin);
        if (!ok) {
            markCampusProbeFail();
            renderStatus('public');
            return;
        }

        clearCampusProbeFail();
        renderStatus('campus');

        const targetUrl = buildTargetUrl(campusOrigin);
        if (targetUrl !== window.location.href) {
            window.location.replace(targetUrl);
        }
    }

    async function probeOrigin(origin) {
        const probeUrl = `${origin}${probePath}${probePath.includes('?') ? '&' : '?'}_probe=${Date.now()}`;
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), timeoutMs);

        try {
            await fetch(probeUrl, {
                method: 'GET',
                mode: 'no-cors',
                cache: 'no-store',
                credentials: 'omit',
                signal: controller.signal
            });
            return true;
        } catch (e) {
            return false;
        } finally {
            clearTimeout(timer);
        }
    }

    function buildTargetUrl(origin) {
        const current = new URL(window.location.href);
        const query = current.search || '';
        const hash = current.hash || '';
        return `${origin}${current.pathname}${query}${hash}`;
    }

    function renderStatus(mode) {
        if (!statusEl) return;
        statusEl.classList.remove('route-campus', 'route-public', 'route-checking');
        if (mode === 'campus') {
            statusEl.textContent = '校内访问';
            statusEl.classList.add('route-campus');
            return;
        }
        if (mode === 'public') {
            statusEl.textContent = '公网访问';
            statusEl.classList.add('route-public');
            return;
        }
        statusEl.textContent = '线路检测中';
        statusEl.classList.add('route-checking');
    }

    function isRecentCampusProbeFail() {
        try {
            const raw = localStorage.getItem(failCacheKey);
            if (!raw) return false;
            const data = JSON.parse(raw);
            const ts = Number(data.ts) || 0;
            return ts > 0 && Date.now() - ts < failCacheMs;
        } catch (e) {
            return false;
        }
    }

    function markCampusProbeFail() {
        try {
            localStorage.setItem(failCacheKey, JSON.stringify({ ts: Date.now() }));
        } catch (e) {
            // ignore localStorage errors
        }
    }

    function clearCampusProbeFail() {
        try {
            localStorage.removeItem(failCacheKey);
        } catch (e) {
            // ignore localStorage errors
        }
    }

    function normalizeOrigin(url) {
        return (url || '').trim().replace(/\/+$/, '');
    }

    function normalizePath(path) {
        const p = (path || '').trim();
        if (!p) return '/api/ping';
        return p.startsWith('/') ? p : `/${p}`;
    }

    function asPositiveInt(value, fallback) {
        const n = Number(value);
        if (!Number.isFinite(n) || n <= 0) return fallback;
        return Math.floor(n);
    }
})();
