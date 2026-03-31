/**
 * 学科资料站 — 首页交互逻辑（资料区 + 论坛）
 */
(function () {
    'use strict';

    const API = '/api';
    let currentSubject = '';
    let currentSubCategory = '';
    let currentExt = '';
    let currentPage = 1;
    let searchPage = 1;
    let forumPage = 1;
    let debounceTimer = null;
    let me = { logged_in: false, is_admin: false, username: '' };

    // ── DOM ──────────────────────────────────
    const navMaterialsBtn = document.getElementById('navMaterialsBtn');
    const navForumBtn = document.getElementById('navForumBtn');
    const heroSection = document.querySelector('.hero');
    const searchInput = document.getElementById('searchInput');
    const subjectsSection = document.getElementById('subjectsSection');
    const subjectsGrid = document.getElementById('subjectsGrid');
    const filesSection = document.getElementById('filesSection');
    const searchSection = document.getElementById('searchSection');
    const forumSection = document.getElementById('forumSection');
    const breadcrumbHome = document.getElementById('breadcrumbHome');
    const breadcrumbCurrent = document.getElementById('breadcrumbCurrent');
    const foldersRow = document.getElementById('foldersRow');
    const filterBar = document.getElementById('filterBar');
    const fileTableBody = document.getElementById('fileTableBody');
    const fileCount = document.getElementById('fileCount');
    const filesSectionTitle = document.getElementById('filesSectionTitle');
    const emptyState = document.getElementById('emptyState');
    const pagination = document.getElementById('pagination');
    const searchTableBody = document.getElementById('searchTableBody');
    const searchCount = document.getElementById('searchCount');
    const searchTitle = document.getElementById('searchTitle');
    const searchEmpty = document.getElementById('searchEmpty');
    const searchPagination = document.getElementById('searchPagination');
    const noticeTextEl = document.getElementById('noticeText');
    const noticeDateEl = document.getElementById('noticeDate');

    const forumList = document.getElementById('forumList');
    const forumPagination = document.getElementById('forumPagination');
    const forumPostInput = document.getElementById('forumPostInput');
    const forumPostCounter = document.getElementById('forumPostCounter');
    const forumPostBtn = document.getElementById('forumPostBtn');
    const forumSearchInput = document.getElementById('forumSearchInput');

    init();

    async function init() {
        await loadMe();
        loadNotice();
        loadStats();
        loadSubjects();
        bindEvents();
        switchNav('materials');
    }

    async function loadMe() {
        try {
            const res = await fetch(`${API}/auth/me`);
            if (!res.ok) return;
            const data = await res.json();
            me = {
                logged_in: !!data.logged_in,
                is_admin: !!data.is_admin,
                username: data.username || ''
            };
        } catch (e) {
            console.error(e);
        }
    }

    async function loadNotice() {
        if (!noticeTextEl || !noticeDateEl) return;
        try {
            const res = await fetch(`${API}/notice`);
            if (!res.ok) return;
            const data = await res.json();
            noticeTextEl.textContent = data.text || '欢迎大家使用upcshare！';
            noticeDateEl.textContent = formatNoticeDate(data.updated_at) || '-';
        } catch (e) {
            console.error(e);
        }
    }

    function bindEvents() {
        if (navMaterialsBtn) {
            navMaterialsBtn.addEventListener('click', () => switchNav('materials'));
        }
        if (navForumBtn) {
            navForumBtn.addEventListener('click', () => switchNav('forum'));
        }

        if (forumPostInput) {
            forumPostInput.addEventListener('input', () => {
                forumPostCounter.textContent = `${forumPostInput.value.length} / 1000`;
            });
        }
        if (forumPostBtn) {
            forumPostBtn.addEventListener('click', publishPost);
        }

        if (forumSearchInput) {
            forumSearchInput.addEventListener('input', () => {
                clearTimeout(debounceTimer);
                debounceTimer = setTimeout(() => {
                    forumPage = 1;
                    loadForumPosts();
                }, 300);
            });
        }

        // 点击空白处关闭论坛三点菜单
        document.addEventListener('click', (e) => {
            if (!e.target.closest('.forum-menu-wrap')) {
                closeForumMenus();
            }
        });

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
            if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
                e.preventDefault();
                searchInput.focus();
            }
            if (e.key === 'Escape') {
                closeForumMenus();
                searchInput.value = '';
                showSubjectsView();
            }
        });

        // 面包屑返回
        breadcrumbHome.addEventListener('click', (e) => {
            e.preventDefault();
            showSubjectsView();
        });

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

    function switchNav(view) {
        const isForum = view === 'forum';
        navMaterialsBtn.classList.toggle('active', !isForum);
        navForumBtn.classList.toggle('active', isForum);

        if (isForum) {
            showForumView();
        } else {
            showMaterialsView();
        }
    }

    function showForumView() {
        if (heroSection) heroSection.style.display = 'none';
        subjectsSection.style.display = 'none';
        filesSection.style.display = 'none';
        searchSection.style.display = 'none';
        forumSection.style.display = '';
        loadForumPosts();
    }

    function showMaterialsView() {
        if (heroSection) heroSection.style.display = '';
        forumSection.style.display = 'none';

        const q = searchInput.value.trim();
        if (q) {
            doSearch(q);
        } else if (currentSubject) {
            filesSection.style.display = '';
            subjectsSection.style.display = 'none';
            searchSection.style.display = 'none';
            loadFiles();
        } else {
            showSubjectsView();
        }
    }

    // ── 自适应视图切换 ───────────────────────
    function showSubjectsView() {
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
            document.getElementById('statDownloadSizeGB').textContent = s.total_download_size_gb || '0.00 GB';
        } catch (e) {
            console.error(e);
        }
    }

    // ── 学科卡片 ──────────────────────────────
    async function loadSubjects() {
        try {
            const res = await fetch(`${API}/subjects`);
            const subjects = await res.json();
            renderSubjects(subjects);
        } catch (e) {
            console.error(e);
        }
    }

    function renderSubjects(subjects) {
        if (!subjects.length) {
            subjectsGrid.innerHTML = '<div class="empty-state"><p>暂无学科资料，请将文件放入 resources/ 目录</p></div>';
            return;
        }
        subjectsGrid.innerHTML = subjects.map((s) => {
            const exts = Object.entries(s.extensions || {}).slice(0, 4)
                .map(([ext]) => {
                    const e = ext.replace('.', '');
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

    window.__openSubject = function (name) {
        switchNav('materials');
        showFilesView(name);
    };

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
                全部 <span class="folder-chip-count">${data.root_file_count + data.folders.reduce((a, f) => a + f.file_count, 0)}</span>
            </div>`;
            html += data.folders.map(f =>
                `<div class="folder-chip" onclick="__setSubCat('${escapeAttr(f.path)}')">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
                    ${esc(f.path)} <span class="folder-chip-count">${f.file_count}</span>
                </div>`
            ).join('');
            foldersRow.innerHTML = html;
        } catch (e) {
            foldersRow.style.display = 'none';
        }
    }

    window.__setSubCat = function (sc) {
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
            document.querySelector('#filesSection .file-table-wrap').style.display = data.items.length ? '' : 'none';
            renderPagination(data.page, data.pages, pagination, '__goPage');
        } catch (e) {
            console.error(e);
        }
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
        } catch (e) {
            console.error(e);
        }
    }

    // ── 论坛 ──────────────────────────────────
    async function loadForumPosts() {
        const q = forumSearchInput ? forumSearchInput.value.trim() : '';
        let url = `${API}/forum/posts?page=${forumPage}&size=20`;
        if (q) {
            url += `&q=${encodeURIComponent(q)}`;
        }

        try {
            const res = await fetch(url);
            if (!res.ok) {
                forumList.innerHTML = '<div class="forum-empty">论坛加载失败</div>';
                forumPagination.innerHTML = '';
                return;
            }
            const data = await res.json();
            renderForumPosts(data.items || []);
            renderPagination(data.page, data.pages, forumPagination, '__goForumPage');
        } catch (e) {
            console.error(e);
            forumList.innerHTML = '<div class="forum-empty">论坛加载失败</div>';
            forumPagination.innerHTML = '';
        }
    }

    async function publishPost() {
        if (!me.logged_in) {
            showToast('请先登录后再发布求助', 'error');
            return;
        }
        const content = (forumPostInput.value || '').trim();
        if (!content) {
            showToast('求助内容不能为空', 'error');
            return;
        }

        forumPostBtn.disabled = true;
        try {
            const res = await fetch(`${API}/forum/posts`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ content })
            });
            const data = await res.json();
            if (!res.ok) throw new Error(data.detail || data.msg || '发布失败');

            forumPostInput.value = '';
            forumPostCounter.textContent = '0 / 1000';
            showToast(data.msg || '发布成功', 'success');
            forumPage = 1;
            loadForumPosts();
        } catch (e) {
            showToast(e.message || '发布失败', 'error');
        } finally {
            forumPostBtn.disabled = false;
        }
    }

    window.__forumComment = async function (postId) {
        if (!me.logged_in) {
            showToast('请先登录后再评论', 'error');
            return;
        }
        const input = document.getElementById(`forumCommentInput_${postId}`);
        if (!input) return;
        const content = (input.value || '').trim();
        if (!content) {
            showToast('评论不能为空', 'error');
            return;
        }

        try {
            const res = await fetch(`${API}/forum/posts/${postId}/comments`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ content })
            });
            const data = await res.json();
            if (!res.ok) throw new Error(data.detail || data.msg || '评论失败');
            input.value = '';
            showToast(data.msg || '评论成功', 'success');
            loadForumPosts();
        } catch (e) {
            showToast(e.message || '评论失败', 'error');
        }
    };

    window.__forumDeletePost = async function (postId) {
        closeForumMenus();
        if (!confirm('确认删除该帖子及其所有评论？')) return;
        try {
            const res = await fetch(`${API}/forum/posts/${postId}`, { method: 'DELETE' });
            const data = await res.json();
            if (!res.ok) throw new Error(data.detail || data.msg || '删除失败');
            showToast(data.msg || '已删除', 'success');
            loadForumPosts();
        } catch (e) {
            showToast(e.message || '删除失败', 'error');
        }
    };

    window.__forumDeleteComment = async function (commentId) {
        closeForumMenus();
        if (!confirm('确认删除该评论？')) return;
        try {
            const res = await fetch(`${API}/forum/comments/${commentId}`, { method: 'DELETE' });
            const data = await res.json();
            if (!res.ok) throw new Error(data.detail || data.msg || '删除失败');
            showToast(data.msg || '已删除', 'success');
            loadForumPosts();
        } catch (e) {
            showToast(e.message || '删除失败', 'error');
        }
    };

    function renderForumPosts(posts) {
        if (!posts.length) {
            forumList.innerHTML = '<div class="forum-empty">还没有求助帖，发第一条试试吧。</div>';
            return;
        }

        forumList.innerHTML = posts.map(post => {
            const postMenu = post.can_delete
                ? `<div class="forum-menu-wrap">
                    <button class="forum-more-btn" type="button" onclick="__forumToggleMenu(event, 'forumPostMenu_${post.id}')" aria-label="帖子操作">⋯</button>
                    <div class="forum-pop-menu" id="forumPostMenu_${post.id}">
                        <button class="forum-pop-item danger" type="button" onclick="__forumDeletePost(${post.id})">删除帖子</button>
                    </div>
                </div>`
                : '';

            const commentsHtml = (post.comments || []).map(c => {
                const cMenu = c.can_delete
                    ? `<div class="forum-menu-wrap">
                        <button class="forum-more-btn forum-more-btn-sm" type="button" onclick="__forumToggleMenu(event, 'forumCommentMenu_${c.id}')" aria-label="评论操作">⋯</button>
                        <div class="forum-pop-menu" id="forumCommentMenu_${c.id}">
                            <button class="forum-pop-item danger" type="button" onclick="__forumDeleteComment(${c.id})">删除评论</button>
                        </div>
                    </div>`
                    : '';
                return `<div class="forum-comment-item">
                    <div class="forum-comment-meta">
                        <span>${esc(c.username)} · ${fmtDateTime(c.created_at)}</span>
                        ${cMenu}
                    </div>
                    <div class="forum-comment-content">${esc(c.content)}</div>
                </div>`;
            }).join('');

            const commentInput = me.logged_in
                ? `<div class="forum-comment-form">
                    <input id="forumCommentInput_${post.id}" maxlength="500" placeholder="写下你的建议或解决办法..." />
                    <button onclick="__forumComment(${post.id})">评论</button>
                </div>`
                : '<div class="forum-post-meta" style="margin-top:8px;">登录后可评论</div>';

            return `<article class="forum-post-card">
                <div class="forum-post-top">
                    <div>
                        <strong>${esc(post.username)}</strong>
                        <div class="forum-post-meta">${fmtDateTime(post.created_at)}</div>
                    </div>
                    ${postMenu}
                </div>
                <div class="forum-post-content">${esc(post.content)}</div>
                <div class="forum-comments">
                    ${commentsHtml || '<div class="forum-post-meta">暂无评论</div>'}
                    ${commentInput}
                </div>
            </article>`;
        }).join('');
    }

    function closeForumMenus() {
        document.querySelectorAll('.forum-pop-menu.open').forEach(el => el.classList.remove('open'));
    }

    window.__forumToggleMenu = function (event, menuId) {
        event.stopPropagation();
        const targetMenu = document.getElementById(menuId);
        if (!targetMenu) return;

        const isOpen = targetMenu.classList.contains('open');
        closeForumMenus();
        if (!isOpen) {
            targetMenu.classList.add('open');
        }
    };

    // ── 渲染文件行 ────────────────────────────
    function renderFileTable(items, tbody) {
        if (!items.length) {
            tbody.innerHTML = '';
            return;
        }
        tbody.innerHTML = items.map(f => {
            const ext = f.extension.replace('.', '');
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
        if (!items.length) {
            searchTableBody.innerHTML = '';
            return;
        }
        searchTableBody.innerHTML = items.map(f => {
            const ext = f.extension.replace('.', '');
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
        if (pages <= 1) {
            container.innerHTML = '';
            return;
        }
        let html = `<button class="page-btn" ${page <= 1 ? 'disabled' : ''} onclick="${fnName}(${page - 1})">‹</button>`;
        const range = getPageRange(page, pages);
        for (const p of range) {
            if (p === '...') html += '<span class="page-btn" style="border:none;pointer-events:none;">…</span>';
            else html += `<button class="page-btn ${p === page ? 'active' : ''}" onclick="${fnName}(${p})">${p}</button>`;
        }
        html += `<button class="page-btn" ${page >= pages ? 'disabled' : ''} onclick="${fnName}(${page + 1})">›</button>`;
        container.innerHTML = html;
    }

    function getPageRange(cur, total) {
        if (total <= 7) return Array.from({ length: total }, (_, i) => i + 1);
        if (cur <= 4) return [1, 2, 3, 4, 5, '...', total];
        if (cur >= total - 3) {
            const a = [1, '...'];
            for (let i = total - 4; i <= total; i += 1) a.push(i);
            return a;
        }
        return [1, '...', cur - 1, cur, cur + 1, '...', total];
    }

    window.__goPage = function (p) {
        currentPage = p;
        loadFiles();
        window.scrollTo({ top: 200, behavior: 'smooth' });
    };
    window.__searchGoPage = function (p) {
        searchPage = p;
        doSearch(searchInput.value.trim());
    };
    window.__goForumPage = function (p) {
        forumPage = p;
        loadForumPosts();
        window.scrollTo({ top: 0, behavior: 'smooth' });
    };

    // ── 工具函数 ──────────────────────────────
    function esc(s) {
        const d = document.createElement('div');
        d.textContent = s || '';
        return d.innerHTML;
    }
    function escapeAttr(s) {
        return (s || '').replace(/'/g, "\\'").replace(/"/g, '&quot;');
    }
    function fmtDate(iso) {
        if (!iso) return '';
        const d = new Date(iso);
        return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    }
    function fmtDateTime(iso) {
        if (!iso) return '';
        const d = new Date(iso.includes(' ') ? iso.replace(' ', 'T') : iso);
        if (Number.isNaN(d.getTime())) return iso;
        const y = d.getFullYear();
        const m = String(d.getMonth() + 1).padStart(2, '0');
        const day = String(d.getDate()).padStart(2, '0');
        const h = String(d.getHours()).padStart(2, '0');
        const mi = String(d.getMinutes()).padStart(2, '0');
        return `${y}-${m}-${day} ${h}:${mi}`;
    }
    function formatNoticeDate(s) {
        if (!s) return '';
        const t = s.includes(' ') ? s.replace(' ', 'T') : s;
        const d = new Date(t);
        if (Number.isNaN(d.getTime())) return '';
        return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    }
    function getExtColor(ext) {
        const map = {
            pdf: '#e74c3c',
            doc: '#2b79c2',
            docx: '#2b79c2',
            zip: '#f39c12',
            rar: '#8e44ad',
            '7z': '#8e44ad',
            ppt: '#e67e22',
            pptx: '#e67e22',
            xls: '#27ae60',
            xlsx: '#27ae60',
            txt: '#95a5a6',
            md: '#95a5a6',
            csv: '#95a5a6'
        };
        return map[(ext || '').toLowerCase()] || '#7f8c8d';
    }
    function showToast(msg, type) {
        let c = document.querySelector('.toast-container');
        if (!c) {
            c = document.createElement('div');
            c.className = 'toast-container';
            document.body.appendChild(c);
        }
        const t = document.createElement('div');
        t.className = `toast ${type || 'success'}`;
        t.textContent = msg;
        c.appendChild(t);
        setTimeout(() => t.remove(), 3000);
    }
})();