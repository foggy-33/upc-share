/**
 * 管理后台 — 审核 & 文件管理（搜索 / 下载 / 删除）
 */
(function () {
    'use strict';

    const API = '/api/admin';
    let currentStatus = 'pending';
    let currentPage = 1;
    let searchQuery = '';
    let debounceTimer = null;
    const PAGE_SIZE = 50;

    // ── DOM ──
    const fileBody      = document.getElementById('fileBody');
    const emptyMsg      = document.getElementById('emptyMsg');
    const pagination    = document.getElementById('pagination');
    const tabs          = document.querySelectorAll('.dash-tab');
    const searchInput   = document.getElementById('dashSearchInput');

    init();

    function init() {
        tabs.forEach(tab => {
            tab.addEventListener('click', () => {
                tabs.forEach(t => t.classList.remove('active'));
                tab.classList.add('active');
                currentStatus = tab.dataset.status;
                currentPage = 1;
                loadFiles();
            });
        });
        searchInput.addEventListener('input', () => {
            clearTimeout(debounceTimer);
            debounceTimer = setTimeout(() => {
                searchQuery = searchInput.value.trim();
                currentPage = 1;
                loadFiles();
            }, 300);
        });
        loadFiles();
        loadCounts();
    }

    /* ── 加载文件列表 ── */
    async function loadFiles() {
        try {
            let url = `${API}/files?page=${currentPage}&size=${PAGE_SIZE}`;
            if (currentStatus) url += `&status=${encodeURIComponent(currentStatus)}`;
            if (searchQuery) url += `&q=${encodeURIComponent(searchQuery)}`;
            const res = await fetch(url);
            if (!res.ok) throw new Error('请求失败');
            const data = await res.json();
            renderTable(data.items);
            renderPagination(data.page, data.pages);
        } catch (e) {
            fileBody.innerHTML = '';
            emptyMsg.style.display = 'block';
            emptyMsg.textContent = '加载失败: ' + e.message;
        }
    }

    /* ── 加载各状态计数 ── */
    async function loadCounts() {
        try {
            const [pRes, allRes] = await Promise.all([
                fetch(`${API}/files?status=pending&size=1`),
                fetch(`${API}/files?size=1`),
            ]);
            const [p, all] = await Promise.all([pRes.json(), allRes.json()]);
            document.getElementById('badgePending').textContent = p.total;
            document.getElementById('badgeAll').textContent = all.total;
        } catch {}
    }

    /* ── 渲染表格 ── */
    function renderTable(items) {
        if (!items || items.length === 0) {
            fileBody.innerHTML = '';
            emptyMsg.style.display = 'block';
            emptyMsg.textContent = '暂无数据';
            return;
        }
        emptyMsg.style.display = 'none';

        fileBody.innerHTML = items.map(f => {
            const statusCls = f.status || 'pending';
            const statusText = { pending: '待审核', approved: '已通过', rejected: '已拒绝' }[statusCls] || statusCls;
            const time = (f.created_at || '').replace('T', ' ').slice(0, 16);
            const category = esc(f.category || '');
            const sub = f.sub_category ? '/' + esc(f.sub_category) : '';

            let actions = `<button class="action-btn download" onclick="dashDownload('${f.id}')">下载</button>`;
            if (f.status === 'pending') {
                actions += `<button class="action-btn approve" onclick="dashAction('approve','${f.id}')">通过</button>`
                         + `<button class="action-btn reject" onclick="dashAction('reject','${f.id}')">拒绝</button>`;
            } else {
                actions += `<button class="action-btn delete" onclick="dashAction('delete','${f.id}')">删除</button>`;
            }

            return `<tr>
                <td class="file-name-cell" title="${esc(f.original_name)}">${esc(f.original_name)}</td>
                <td>${category}${sub}</td>
                <td class="uploader-cell">${esc(f.uploader || 'system')}</td>
                <td class="size-cell">${esc(f.file_size)}</td>
                <td><span class="status-badge ${statusCls}">${statusText}</span></td>
                <td class="time-cell">${time}</td>
                <td style="white-space:nowrap;">${actions}</td>
            </tr>`;
        }).join('');
    }

    /* ── 分页 ── */
    function renderPagination(page, pages) {
        if (pages <= 1) { pagination.innerHTML = ''; return; }
        let html = '';
        html += `<button ${page <= 1 ? 'disabled' : ''} onclick="dashPage(${page - 1})">上一页</button>`;
        html += `<button disabled>第 ${page} / ${pages} 页</button>`;
        html += `<button ${page >= pages ? 'disabled' : ''} onclick="dashPage(${page + 1})">下一页</button>`;
        pagination.innerHTML = html;
    }

    /* ── 下载文件 ── */
    window.dashDownload = function (fileId) {
        window.open(`/api/download/${fileId}`, '_blank');
    };

    /* ── 操作（审核/拒绝/删除）── */
    window.dashAction = async function (action, fileId) {
        const confirmText = { approve: '确认通过该文件？', reject: '确认拒绝并删除该文件？', delete: '确认永久删除该文件？' };
        if (!confirm(confirmText[action] || '确认？')) return;

        try {
            let url, method;
            if (action === 'approve')     { url = `${API}/approve/${fileId}`; method = 'POST'; }
            else if (action === 'reject') { url = `${API}/reject/${fileId}`;  method = 'POST'; }
            else                          { url = `${API}/files/${fileId}`;   method = 'DELETE'; }

            const res = await fetch(url, { method });
            const data = await res.json();
            if (res.ok) {
                showToast(data.msg || '操作成功', 'success');
                loadFiles();
                loadCounts();
            } else {
                showToast(data.detail || '操作失败', 'error');
            }
        } catch {
            showToast('网络错误', 'error');
        }
    };

    window.dashPage = function (p) {
        currentPage = p;
        loadFiles();
    };

    /* ── 工具 ── */
    function esc(s) {
        const d = document.createElement('div');
        d.textContent = s || '';
        return d.innerHTML;
    }

    function showToast(msg, type) {
        let c = document.querySelector('.toast-container');
        if (!c) { c = document.createElement('div'); c.className = 'toast-container'; document.body.appendChild(c); }
        const t = document.createElement('div');
        t.className = `toast ${type || 'success'}`;
        t.textContent = msg;
        c.appendChild(t);
        setTimeout(() => t.remove(), 3000);
    }
})();
