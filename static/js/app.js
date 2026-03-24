/**
 * 学科资料站 — 首页交互逻辑
 */
(function () {
    'use strict';

    const API = '/api';
    let currentSubject = '';
    let currentSubCategory = '';
    let currentExt = '';
    let currentPage = 1;
    let searchPage = 1;
    let debounceTimer = null;

    // ── DOM ──────────────────────────────────
    const searchInput   = document.getElementById('searchInput');
    const subjectsSection = document.getElementById('subjectsSection');
    const subjectsGrid  = document.getElementById('subjectsGrid');
    const filesSection  = document.getElementById('filesSection');
    const searchSection = document.getElementById('searchSection');
    const breadcrumbHome = document.getElementById('breadcrumbHome');
    const breadcrumbCurrent = document.getElementById('breadcrumbCurrent');
    const foldersRow    = document.getElementById('foldersRow');
    const filterBar     = document.getElementById('filterBar');
    const fileTableBody = document.getElementById('fileTableBody');
    const fileCount     = document.getElementById('fileCount');
    const filesSectionTitle = document.getElementById('filesSectionTitle');
    const emptyState    = document.getElementById('emptyState');
    const pagination    = document.getElementById('pagination');
    const searchTableBody = document.getElementById('searchTableBody');
    const searchCount   = document.getElementById('searchCount');
    const searchTitle   = document.getElementById('searchTitle');
    const searchEmpty   = document.getElementById('searchEmpty');
    const searchPagination = document.getElementById('searchPagination');

    init();

    function init() {
        loadStats();
        loadSubjects();
        bindEvents();
    }

    function bindEvents() {
        // 搜索
        searchInput.addEventListener('input', () => {
            clearTimeout(debounceTimer);
            debounceTimer = setTimeout(() => {
                const q = searchInput.value.trim();
                if (q) {
                    searchPage = 1;
                    doSearch(q);
                } else {
                    showSubjectsView();
                }
            }, 300);
        });

        // Ctrl+K
        document.addEventListener('keydown', (e) => {
            if ((e.metaKey || e.ctrlKey) && e.key === 'k') { e.preventDefault(); searchInput.focus(); }
            if (e.key === 'Escape') { searchInput.value = ''; showSubjectsView(); }
        });

        // 面包屑返回
        breadcrumbHome.addEventListener('click', (e) => { e.preventDefault(); showSubjectsView(); });

        // 类型筛选
        filterBar.querySelectorAll('.filter-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                currentExt = btn.dataset.ext;
                currentPage = 1;
                filterBar.querySelectorAll('.filter-btn').forEach(b => b.classList.toggle('active', b.dataset.ext === currentExt));
                loadFiles();
            });
        });
    }

    // ── 自适应视图切换 ───────────────────────
    function showSubjectsView() {
        searchInput.value = '';
        subjectsSection.style.display = '';
        filesSection.style.display = 'none';
        searchSection.style.display = 'none';
        currentSubject = '';
    }

    function showFilesView(subject) {
        currentSubject = subject;
        currentSubCategory = '';
        currentExt = '';
        currentPage = 1;
        subjectsSection.style.display = 'none';
        filesSection.style.display = '';
        searchSection.style.display = 'none';
        breadcrumbCurrent.textContent = subject;
        filesSectionTitle.textContent = subject;
        // reset filter
        filterBar.querySelectorAll('.filter-btn').forEach(b => b.classList.toggle('active', b.dataset.ext === ''));
        loadFolders(subject);
        loadFiles();
    }

    function showSearchView() {
        subjectsSection.style.display = 'none';
        filesSection.style.display = 'none';
        searchSection.style.display = '';
    }

    // ── 统计 ──────────────────────────────────
    async function loadStats() {
        try {
            const res = await fetch(`${API}/stats`);
            const s = await res.json();
            document.getElementById('statSubjects').textContent = s.subject_count;
            document.getElementById('statFiles').textContent = s.total_files;
            document.getElementById('statSize').textContent = s.total_size;
            document.getElementById('statDownloads').textContent = s.total_downloads;
        } catch (e) { console.error(e); }
    }

    // ── 学科卡片 ──────────────────────────────
    async function loadSubjects() {
        try {
            const res = await fetch(`${API}/subjects`);
            const subjects = await res.json();
            renderSubjects(subjects);
        } catch (e) { console.error(e); }
    }

    function renderSubjects(subjects) {
        if (!subjects.length) {
            subjectsGrid.innerHTML = '<div class="empty-state"><p>暂无学科资料，请将文件放入 resources/ 目录</p></div>';
            return;
        }
        subjectsGrid.innerHTML = subjects.map((s) => {
            const exts = Object.entries(s.extensions || {}).slice(0, 4)
                .map(([ext]) => {
                    const e = ext.replace('.','');
                    return `<span class="subject-card-tag" style="background:${getExtColor(e)};border-color:${getExtColor(e)};color:#fff;">${e}</span>`;
                }).join('');
            return `
            <div class="subject-card" onclick="__openSubject('${escapeAttr(s.name)}')">
                <div class="subject-card-name">${esc(s.name)}</div>
                <div class="subject-card-meta">${s.file_count} 个文件 · ${s.total_size}</div>
                <div class="subject-card-tags">${exts}</div>
            </div>`;
        }).join('');
    }

    window.__openSubject = function(name) { showFilesView(name); };

    // ── 子文件夹 ──────────────────────────────
    async function loadFolders(subject) {
        try {
            const res = await fetch(`${API}/subjects/${encodeURIComponent(subject)}/folders`);
            const data = await res.json();
            if (data.folders.length === 0) {
                foldersRow.style.display = 'none';
                return;
            }
            foldersRow.style.display = '';
            let html = `<div class="folder-chip active" onclick="__setSubCat('')">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
                全部 <span class="folder-chip-count">${data.root_file_count + data.folders.reduce((a,f)=>a+f.file_count,0)}</span>
            </div>`;
            html += data.folders.map(f =>
                `<div class="folder-chip" onclick="__setSubCat('${escapeAttr(f.path)}')">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
                    ${esc(f.path)} <span class="folder-chip-count">${f.file_count}</span>
                </div>`
            ).join('');
            foldersRow.innerHTML = html;
        } catch (e) { foldersRow.style.display = 'none'; }
    }

    window.__setSubCat = function(sc) {
        currentSubCategory = sc;
        currentPage = 1;
        foldersRow.querySelectorAll('.folder-chip').forEach(c => {
            const isAll = !c.getAttribute('onclick').includes("'") || c.getAttribute('onclick').includes("''");
            if (sc === '') c.classList.toggle('active', isAll);
            else c.classList.toggle('active', c.getAttribute('onclick').includes(`'${sc}'`));
        });
        loadFiles();
    };

    // ── 文件列表 ──────────────────────────────
    async function loadFiles() {
        const params = new URLSearchParams({ page: currentPage, size: 30, category: currentSubject });
        if (currentSubCategory) params.set('sub_category', currentSubCategory);
        if (currentExt) params.set('ext', currentExt);

        try {
            const res = await fetch(`${API}/files?${params}`);
            const data = await res.json();
            renderFileTable(data.items, fileTableBody);
            fileCount.textContent = `共 ${data.total} 个文件`;
            emptyState.style.display = data.items.length ? 'none' : 'block';
            document.querySelector('.file-table-wrap').style.display = data.items.length ? '' : 'none';
            renderPagination(data.page, data.pages, pagination, '__goPage');
        } catch (e) { console.error(e); }
    }

    // ── 搜索 ──────────────────────────────────
    async function doSearch(q) {
        showSearchView();
        const params = new URLSearchParams({ q, page: searchPage, size: 30 });
        try {
            const res = await fetch(`${API}/files?${params}`);
            const data = await res.json();
            searchTitle.textContent = `搜索"${q}"的结果`;
            searchCount.textContent = `找到 ${data.total} 个文件`;
            renderSearchTable(data.items);
            searchEmpty.style.display = data.items.length ? 'none' : 'block';
            renderPagination(data.page, data.pages, searchPagination, '__searchGoPage');
        } catch (e) { console.error(e); }
    }

    // ── 渲染文件行 ────────────────────────────
    function renderFileTable(items, tbody) {
        if (!items.length) { tbody.innerHTML = ''; return; }
        tbody.innerHTML = items.map(f => {
            const ext = f.extension.replace('.','');
            const subPath = f.sub_category ? `<div class="file-sub-path">${esc(f.sub_category)}</div>` : '';
            return `<tr>
                <td><div class="file-name-cell">
                    <div class="file-ext-badge ${ext}">${ext.toUpperCase()}</div>
                    <div><div class="file-name-text">${esc(f.original_name)}</div>${subPath}</div>
                </div></td>
                <td>${f.file_size}</td>
                <td>${fmtDate(f.created_at)}</td>
                <td>${f.download_count}</td>
                <td style="text-align:right">
                    <a class="dl-btn-sm" href="${API}/download/${f.id}">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
                            <polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/>
                        </svg> 下载
                    </a>
                </td>
            </tr>`;
        }).join('');
    }

    function renderSearchTable(items) {
        if (!items.length) { searchTableBody.innerHTML = ''; return; }
        searchTableBody.innerHTML = items.map(f => {
            const ext = f.extension.replace('.','');
            return `<tr>
                <td><div class="file-name-cell">
                    <div class="file-ext-badge ${ext}">${ext.toUpperCase()}</div>
                    <div class="file-name-text">${esc(f.original_name)}</div>
                </div></td>
                <td><span class="file-subject-tag">${esc(f.category)}</span></td>
                <td>${f.file_size}</td>
                <td>${fmtDate(f.created_at)}</td>
                <td style="text-align:right">
                    <a class="dl-btn-sm" href="${API}/download/${f.id}">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
                            <polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/>
                        </svg> 下载
                    </a>
                </td>
            </tr>`;
        }).join('');
    }

    // ── 分页 ──────────────────────────────────
    function renderPagination(page, pages, container, fnName) {
        if (pages <= 1) { container.innerHTML = ''; return; }
        let html = `<button class="page-btn" ${page<=1?'disabled':''} onclick="${fnName}(${page-1})">‹</button>`;
        const range = getPageRange(page, pages);
        for (const p of range) {
            if (p === '...') html += `<span class="page-btn" style="border:none;pointer-events:none;">…</span>`;
            else html += `<button class="page-btn ${p===page?'active':''}" onclick="${fnName}(${p})">${p}</button>`;
        }
        html += `<button class="page-btn" ${page>=pages?'disabled':''} onclick="${fnName}(${page+1})">›</button>`;
        container.innerHTML = html;
    }

    function getPageRange(cur, total) {
        if (total <= 7) return Array.from({length:total},(_,i)=>i+1);
        if (cur <= 4) return [1,2,3,4,5,'...',total];
        if (cur >= total-3) { const a = [1,'...']; for(let i=total-4;i<=total;i++) a.push(i); return a; }
        return [1,'...',cur-1,cur,cur+1,'...',total];
    }

    window.__goPage = function(p) { currentPage = p; loadFiles(); window.scrollTo({top:200,behavior:'smooth'}); };
    window.__searchGoPage = function(p) { searchPage = p; doSearch(searchInput.value.trim()); };

    // ── 工具函数 ──────────────────────────────
    function esc(s) { const d = document.createElement('div'); d.textContent = s; return d.innerHTML; }
    function escapeAttr(s) { return s.replace(/'/g, "\\'").replace(/"/g, '&quot;'); }
    function fmtDate(iso) { if (!iso) return ''; const d = new Date(iso); return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`; }
    function getExtColor(ext) {
        const map = {
            pdf:'#e74c3c',
            doc:'#2b79c2',
            docx:'#2b79c2',
            zip:'#f39c12',
            rar:'#8e44ad',
            '7z':'#8e44ad',
            ppt:'#e67e22',
            pptx:'#e67e22',
            xls:'#27ae60',
            xlsx:'#27ae60',
            txt:'#95a5a6',
            md:'#95a5a6',
            csv:'#95a5a6'
        };
        return map[ext.toLowerCase()] || '#7f8c8d';
    }
})();
