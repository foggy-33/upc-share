/**
 * Access route selector:
 * - Supports manual mode: campus / public
 * - Shows current route badge on the page
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
    const currentOrigin = normalizeOrigin(window.location.origin);

    const statusEl = document.getElementById('accessRouteBadge');
    const routeSummaryEl = document.getElementById('accessRouteSummary');
    const modeSelectEl = document.getElementById('accessRouteModeSelect');
    const modePickerEl = document.getElementById('accessRouteModePicker');
    const modeCurrentEl = document.getElementById('accessRouteModeCurrent');
    const optionButtons = Array.from(document.querySelectorAll('.route-mode-option[data-mode]'));

    const modeStorageKey = 'upcshare_access_route_mode_v2';

    initModeControls();

    const mode = getMode();

    if (mode === 'campus') {
        if (!campusOrigin) {
            setMode('public');
            syncModeUI('public');
            renderStatus('public');
            return;
        }
        if (isCurrentCampus()) {
            renderStatus('campus');
            return;
        }
        renderStatus('checking');
        probeCampusAndRedirect();
        return;
    }

    // public mode
    renderStatus('public');
    if (isCurrentCampus()) {
        window.location.replace(buildTargetUrl(publicOrigin));
    }

    async function probeCampusAndRedirect() {
        const ok = await probeOrigin(campusOrigin);
        if (!ok) {
            renderStatus('public');
            return;
        }

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
        return `${origin}${current.pathname}${current.search || ''}${current.hash || ''}`;
    }

    function initModeControls() {
        const savedMode = sanitizeMode(getMode());

        if (modeSelectEl) {
            modeSelectEl.value = savedMode;
            modeSelectEl.addEventListener('change', () => applyModeChange(modeSelectEl.value || 'public'));
        }

        if (!campusOrigin) {
            if (modeSelectEl) {
                for (const opt of modeSelectEl.options) {
                    if (opt.value === 'campus') {
                        opt.disabled = true;
                        opt.textContent = '校园网（未配置）';
                    }
                }
            }
            optionButtons.forEach((btn) => {
                if (btn.dataset.mode === 'campus') {
                    btn.disabled = true;
                    btn.title = '校园线路未配置';
                }
            });
            if (savedMode === 'campus') {
                setMode('public');
            }
        }

        optionButtons.forEach((btn) => {
            btn.addEventListener('click', () => {
                if (btn.disabled) return;
                applyModeChange(btn.dataset.mode || 'public');
            });
        });

        if (modeCurrentEl) {
            modeCurrentEl.addEventListener('click', (event) => {
                event.stopPropagation();
                toggleModeMenu();
            });
        }

        document.addEventListener('click', (event) => {
            if (!modePickerEl) return;
            if (!modePickerEl.contains(event.target)) {
                closeModeMenu();
            }
        });

        document.addEventListener('keydown', (event) => {
            if (event.key === 'Escape') {
                closeModeMenu();
            }
        });

        syncModeUI(getMode());
    }

    function applyModeChange(nextMode) {
        const safeMode = sanitizeMode(nextMode);
        setMode(safeMode);
        syncModeUI(safeMode);
        closeModeMenu();
        window.location.reload();
    }

    function syncModeUI(mode) {
        const safeMode = sanitizeMode(mode);

        if (modeSelectEl) {
            modeSelectEl.value = safeMode;
        }

        optionButtons.forEach((btn) => {
            const isActive = btn.dataset.mode === safeMode;
            btn.classList.toggle('active', isActive);
            btn.setAttribute('aria-selected', isActive ? 'true' : 'false');
        });

        if (modeCurrentEl) {
            modeCurrentEl.textContent = modeLabel(safeMode);
        }
    }

    function modeLabel(mode) {
        return mode === 'campus' ? '校园网' : '公网';
    }

    function toggleModeMenu() {
        if (!modePickerEl || !modeCurrentEl) return;
        const isOpen = modePickerEl.classList.contains('open');
        modePickerEl.classList.toggle('open', !isOpen);
        modeCurrentEl.setAttribute('aria-expanded', isOpen ? 'false' : 'true');
    }

    function closeModeMenu() {
        if (!modePickerEl || !modeCurrentEl) return;
        modePickerEl.classList.remove('open');
        modeCurrentEl.setAttribute('aria-expanded', 'false');
    }

    function sanitizeMode(mode) {
        if (mode === 'campus' || mode === 'public') {
            return mode;
        }
        return 'public';
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

        if (mode === 'checking') {
            if (statusEl) {
                statusEl.textContent = '线路检测中';
                statusEl.classList.add('route-checking');
            }
            updateSummaryText('线路检测中');
            return;
        }

        if (statusEl) {
            statusEl.textContent = '公网访问';
            statusEl.classList.add('route-public');
        }
        updateSummaryText('公网访问');
    }

    function updateSummaryText(text) {
        if (routeSummaryEl) {
            routeSummaryEl.textContent = `当前线路：${text}`;
        }
    }

    function getMode() {
        try {
            const mode = (localStorage.getItem(modeStorageKey) || '').trim();
            if (mode) return sanitizeMode(mode);

            // migration from old key/version and removed auto mode
            const legacy = (localStorage.getItem('upcshare_access_route_mode_v1') || '').trim();
            if (legacy === 'campus') return 'campus';
            return 'public';
        } catch (e) {
            return 'public';
        }
    }

    function setMode(mode) {
        try {
            localStorage.setItem(modeStorageKey, sanitizeMode(mode));
        } catch (e) {
            // ignore
        }
    }

    function isCurrentCampus() {
        return !!campusOrigin && currentOrigin === campusOrigin;
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
