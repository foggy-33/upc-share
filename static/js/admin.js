/**
 * 学科资料站 — 管理页交互逻辑
 */
(function () {
    'use strict';

    const API = '/api';
    let selectedFile = null;
    let adminPage = 1;

    // ── DOM ──────────────────────────────────
    const uploadZone     = document.getElementById('uploadZone');
    const fileInput      = document.getElementById('fileInput');
    const uploadForm     = document.getElementById('uploadForm');
    const filePreviewIcon = document.getElementById('filePreviewIcon');
    const filePreviewName = document.getElementById('filePreviewName');
    const filePreviewSize = document.getElementById('filePreviewSize');
    const removeFileBtn  = document.getElementById('removeFile');
    const fileDescription = document.getElementById('fileDescription');
    const fileCategory   = document.getElementById('fileCategory');
    const fileSubCategory = document.getElementById('fileSubCategory');
    const uploadBtn      = document.getElementById('uploadBtn');
    const manageList     = document.getElementById('manageList');
    const adminPagination = document.getElementById('adminPagination');
    const rescanBtn      = document.getElementById('rescanBtn');
    const scanResult     = document.getElementById('scanResult');

    const ALLOWED = ['pdf','doc','docx','ppt','pptx','xls','xlsx','zip','rar','7z','tar','gz','txt','md','csv'];

    init();

    function init() {
        bindUploadEvents();
        bindRescan();
        loadAdminFiles();
    }

    function bindUploadEvents() {
        uploadZone.addEventListener('click', () => fileInput.click());
        fileInput.addEventListener('change', () => { if (fileInput.files.length) handleFile(fileInput.files[0]); });
        uploadZone.addEventListener('dragover', (e) => { e.preventDefault(); uploadZone.classList.add('dragover'); });
        uploadZone.addEventListener('dragleave', () => uploadZone.classList.remove('dragover'));
        uploadZone.addEventListener('drop', (e) => {
            e.preventDefault(); uploadZone.classList.remove('dragover');
            if (e.dataTransfer.files.length) handleFile(e.dataTransfer.files[0]);
        });
        removeFileBtn.addEventListener('click', resetUpload);
        uploadBtn.addEventListener('click', doUpload);
    }

    function bindRescan() {
        rescanBtn.addEventListener('click', async () => {
            rescanBtn.disabled = true;
            scanResult.textContent = '扫描中...';
            try {
                const res = await fetch(`${API}/rescan`, { method: 'POST' });
                const data = await res.json();
                scanResult.textContent = data.message;
                loadAdminFiles();
            } catch { scanResult.textContent = '扫描失败'; }
            finally { rescanBtn.disabled = false; }
        });
    }

    function handleFile(file) {
        const ext = file.name.split('.').pop().toLowerCase();
        if (!ALLOWED.includes(ext)) { showToast('不支持的文件格式', 'error'); return; }
        if (file.size > 500 * 1024 * 1024) { showToast('文件超过 500MB', 'error'); return; }

        selectedFile = file;
        filePreviewName.textContent = file.name;
        filePreviewSize.textContent = formatSize(file.size);

        // icon style
        const iconMap = { doc:'word',docx:'word',ppt:'ppt',pptx:'ppt',xls:'xls',xlsx:'xls',zip:'archive',rar:'archive','7z':'archive',tar:'archive',gz:'archive' };
        const cls = iconMap[ext] || '';
        filePreviewIcon.textContent = ext.toUpperCase();
        filePreviewIcon.className = 'file-preview-icon' + (cls ? ' ' + cls : '');

        uploadForm.style.display = 'block';
    }

    function resetUpload() {
        selectedFile = null; fileInput.value = '';
        uploadForm.style.display = 'none';
        fileDescription.value = ''; fileCategory.value = ''; fileSubCategory.value = '';
    }

    async function doUpload() {
        if (!selectedFile) return;
        if (!fileCategory.value.trim()) { showToast('请填写学科分类', 'error'); return; }

        const btnText = uploadBtn.querySelector('.btn-text');
        const btnLoader = uploadBtn.querySelector('.btn-loader');
        uploadBtn.disabled = true; btnText.textContent = '上传中...'; btnLoader.style.display = 'block';

        const formData = new FormData();
        formData.append('file', selectedFile);
        formData.append('description', fileDescription.value.trim());
        formData.append('category', fileCategory.value.trim());
        formData.append('sub_category', fileSubCategory.value.trim());

        try {
            const res = await fetch(`${API}/upload`, { method: 'POST', body: formData });
            const data = await res.json();
            if (res.ok) { showToast(`${data.filename} 上传成功`, 'success'); resetUpload(); loadAdminFiles(); }
            else showToast(data.detail || '上传失败', 'error');
        } catch { showToast('网络错误', 'error'); }
        finally { uploadBtn.disabled = false; btnText.textContent = '上传文件'; btnLoader.style.display = 'none'; }
    }

    // ── 管理列表 ──────────────────────────────
    async function loadAdminFiles() {
        try {
            const res = await fetch(`${API}/files?page=${adminPage}&size=30`);
            const data = await res.json();
            renderManageList(data.items);
            renderAdminPagination(data.page, data.pages);
        } catch (e) { console.error(e); }
    }

    function renderManageList(items) {
        if (!items.length) {
            manageList.innerHTML = '<p style="text-align:center;color:var(--text-tertiary);padding:32px;">暂无文件</p>';
            return;
        }
        manageList.innerHTML = items.map(f => {
            const ext = f.extension.replace('.','');
            return `
            <div class="manage-item">
                <div class="manage-icon ${ext}">${ext.toUpperCase()}</div>
                <div class="manage-info">
                    <div class="manage-name">${esc(f.original_name)}</div>
                    <div class="manage-meta">${esc(f.category)}${f.sub_category?' / '+esc(f.sub_category):''} · ${f.file_size} · ${f.download_count} 次下载</div>
                </div>
                <div class="manage-actions">
                    <a class="btn-icon" href="${API}/download/${f.id}" title="下载">
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
                            <polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/>
                        </svg>
                    </a>
                    <button class="btn-icon danger" title="删除" onclick="__deleteFile('${f.id}')">
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <polyline points="3 6 5 6 21 6"/>
                            <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>
                        </svg>
                    </button>
                </div>
            </div>`;
        }).join('');
    }

    function renderAdminPagination(page, pages) {
        if (pages <= 1) { adminPagination.innerHTML = ''; return; }
        let html = '';
        for (let i = 1; i <= Math.min(pages, 10); i++) {
            html += `<button class="page-btn ${i===page?'active':''}" onclick="__adminGoPage(${i})">${i}</button>`;
        }
        adminPagination.innerHTML = html;
    }

    window.__adminGoPage = function(p) { adminPage = p; loadAdminFiles(); };

    window.__deleteFile = async function(id) {
        if (!confirm('确定要删除此文件吗？')) return;
        try {
            const res = await fetch(`${API}/files/${id}`, { method: 'DELETE' });
            if (res.ok) { showToast('删除成功', 'success'); loadAdminFiles(); }
            else showToast('删除失败', 'error');
        } catch { showToast('网络错误', 'error'); }
    };

    // ── Toast ─────────────────────────────────
    function showToast(msg, type = 'success') {
        let c = document.querySelector('.toast-container');
        if (!c) { c = document.createElement('div'); c.className = 'toast-container'; document.body.appendChild(c); }
        const t = document.createElement('div'); t.className = `toast ${type}`; t.textContent = msg;
        c.appendChild(t); setTimeout(() => t.remove(), 3000);
    }

    function formatSize(bytes) {
        const u = ['B','KB','MB','GB']; let i=0, s=bytes;
        while (s >= 1024 && i < u.length-1) { s /= 1024; i++; }
        return `${s.toFixed(1)} ${u[i]}`;
    }

    function esc(s) { const d = document.createElement('div'); d.textContent = s; return d.innerHTML; }
})();
