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
    const fileCategory   = document.getElementById('fileCategory');
    const fileSubCategory = document.getElementById('fileSubCategory');
    const uploadBtn      = document.getElementById('uploadBtn');



    const ALLOWED = ['pdf','doc','docx','ppt','pptx','xls','xlsx','zip','rar','7z','tar','gz','txt','md','csv'];

    init();

    function init() {
        bindUploadEvents();
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
        fileCategory.value = ''; fileSubCategory.value = '';
    }

    async function doUpload() {
        if (!selectedFile) return;
        if (!fileCategory.value.trim()) { showToast('请填写学科分类', 'error'); return; }

        const btnText = uploadBtn.querySelector('.btn-text');
        const btnLoader = uploadBtn.querySelector('.btn-loader');
        uploadBtn.disabled = true; btnText.textContent = '上传中...'; btnLoader.style.display = 'block';

        const formData = new FormData();
        formData.append('file', selectedFile);
        formData.append('description', '');
        formData.append('category', fileCategory.value.trim());
        formData.append('sub_category', fileSubCategory.value.trim());

        try {
            const res = await fetch(`${API}/upload`, { method: 'POST', body: formData });
            const data = await res.json();
            if (res.ok) { showToast(`${data.filename} 上传成功`, 'success'); resetUpload(); }
            else showToast(data.detail || '上传失败', 'error');
        } catch { showToast('网络错误', 'error'); }
        finally { uploadBtn.disabled = false; btnText.textContent = '上传文件'; btnLoader.style.display = 'none'; }
    }



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
