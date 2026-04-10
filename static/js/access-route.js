/**
 * Access route selector:
 * - Supports manual mode: auto / campus / public
 * - Shows current route badge on the page
 * - Works without campus CIDR detection
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
    const publicOrigin = normalizeOrigin(cfg.public_origin || window.location.origin);
    const probePath = normalizePath(cfg.probe_path || '/api/ping');
    const timeoutMs = asPositiveInt(cfg.timeout_ms, 1200);
    const failCacheMs = asPositiveInt(cfg.fail_cache_ms, 120000);
    const currentOrigin = normalizeOrigin(window.location.origin);

    const statusEl = document.getElementById('accessRouteBadge');
    const routeSummaryEl = document.getElementById('accessRouteSummary');
    const modeSelectEl = document.getElementById('accessRouteModeSelect');

    const failCacheKey = `upcshare_access_route_fail_v1_${campusOrigin || 'none'}`;
    const modeStorageKey = 'upcshare_access_route_mode_v1';

    if (modeSelectEl) {
        initModeSelector();
    }

    const mode = getMode();

    if (mode === 'public') {
        renderStatus('public');
        if (isCurrentCampus()) {
            window.location.replace(buildTargetUrl(publicOrigin));
        }
        return;
    }

    if (mode === 'campus') {
        if (!campusOrigin) {
            renderStatus('public');
            return;
        }
        if (isCurrentCampus()) {
            renderStatus('campus');
            return;
        }
        renderStatus('checking');
        probeCampusAndRedirect(false);
        return;
    }

    // auto mode
    if (!campusOrigin) {
        renderStatus('public');
        return;
    }
    if (isCurrentCampus()) {
        renderStatus('campus');
        return;
    }

    renderStatus('checking');

    if (isRecentCampusProbeFail()) {
        renderStatus('public');
        return;
    }

    probeCampusAndRedirect(true);

    async function probeCampusAndRedirect(useFailCache) {
        const ok = await probeOrigin(campusOrigin);
        if (!ok) {
            if (useFailCache) {
                markCampusProbeFail();
            }
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

    function initModeSelector() {
        const saved = getMode();
        modeSelectEl.value = saved;

        if (!campusOrigin) {
            for (const opt of modeSelectEl.options) {
                if (opt.value === 'campus') {
                    opt.disabled = true;
                    opt.textContent = '校园网（未配置）';
                }
            }
            if (saved === 'campus') {
                setMode('auto');
                modeSelectEl.value = 'auto';
            }
        }

        modeSelectEl.addEventListener('change', () => {
            const nextMode = modeSelectEl.value || 'auto';
            setMode(nextMode);
            window.location.reload();
        });
    }

    function renderStatus(mode) {
        if (statusEl) {
            statusEl.classList.remove('route-campus', 'route-public', 'route-checking');
        }

        if (mode === 'campus') {
            if (statusEl) {
                statusEl.textContent = '校园网访问';
                statusEl.classList.add('route-campus');
            }
            updateSummaryText('校园网访问');
            return;
        }

        if (mode === 'public') {
            if (statusEl) {
                statusEl.textContent = '公网访问';
                statusEl.classList.add('route-public');
            }
            updateSummaryText('公网访问');
            return;
        }

        if (statusEl) {
            statusEl.textContent = '线路检测中';
            statusEl.classList.add('route-checking');
        }
        updateSummaryText('线路检测中');
    }

    function updateSummaryText(text) {
        if (routeSummaryEl) {
            routeSummaryEl.textContent = `当前线路：${text}`;
        }
    }

    function getMode() {
        try {
            const mode = (localStorage.getItem(modeStorageKey) || 'auto').trim();
            if (mode === 'auto' || mode === 'campus' || mode === 'public') {
                return mode;
            }
        } catch (e) {
            // ignore
        }
        return 'auto';
    }

    function setMode(mode) {
        try {
            localStorage.setItem(modeStorageKey, mode);
        } catch (e) {
            // ignore
        }
    }

    function isCurrentCampus() {
        return !!campusOrigin && currentOrigin === campusOrigin;
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