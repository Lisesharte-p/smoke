/* ==========================================================================
   智慧农业平台 — 布局注入层（共享层）
   功能：动态渲染侧边栏（含完整 8 项导航）+ 顶栏，避免每个页面复制粘贴。

   ★ 与同伴对接的约定（重要）★
   侧边栏导航在这里统一配置，href 指向约定的页面文件名。
   如果同伴的页面文件名改了，只需改下面 NAV 里的 href 这一处即可，页面不用动。
   同伴的 4 个页面：index.html / monitoring.html / history.html / login.html

   每个页面 body 上要写 data-page 属性，用于高亮当前导航项 + 顶栏标题：
       <body data-page="control">
   ========================================================================== */

window.Layout = (function () {

  /* ---------- 导航配置（全部 8 项，含同伴页面） ---------- */
  var NAV = [
    {
      group: '概览',
      items: [
        { key: 'dashboard',  label: '数据总览', icon: '📊', href: 'index.html' }
      ]
    },
    {
      group: '数据监测',
      items: [
        { key: 'monitoring', label: '数据监测', icon: '📡', href: 'monitoring.html' },
        { key: 'history',    label: '历史趋势', icon: '📈', href: 'history.html' }
      ]
    },
    {
      group: '设备与告警',
      items: [
        { key: 'control',    label: '设备控制', icon: '🎛️', href: 'control.html' },
        { key: 'alarm',      label: '告警管理', icon: '🔔', href: 'alarm.html' }
      ]
    },
    {
      group: '智能与系统',
      items: [
        { key: 'assistant',  label: '智能问答', icon: '💬', href: 'assistant.html' },
        { key: 'devices',    label: '设备管理', icon: '🗂️', href: 'devices.html' },
        { key: 'review',     label: '注册审核', icon: '📝', href: 'review.html' }
      ]
    }
  ];

  /* ---------- 顶栏标题映射 ---------- */
  var TITLES = {
    dashboard:  '数据总览',
    monitoring: '数据监测',
    history:    '历史趋势',
    control:    '设备控制',
    alarm:      '告警管理',
    assistant:  '智能问答',
    devices:    '设备管理',
    review:     '注册审核'
  };

  /* ---------- 角色权限配置：每个角色可访问的导航项 key ---------- */
  var ROLE_NAV = {
    farmer:   ['dashboard', 'monitoring', 'history', 'control', 'alarm', 'assistant'],
    admin:    ['dashboard', 'monitoring', 'history', 'control', 'alarm', 'assistant', 'devices', 'review'],
    sysadmin: ['dashboard', 'monitoring', 'history', 'control', 'alarm', 'assistant', 'devices', 'review']
  };

  /* 文件名 → 页面 key，用于隐藏当前角色无权限访问的页内跳转链接 */
  var KEY_BY_HREF = {};
  NAV.forEach(function (g) {
    g.items.forEach(function (it) { KEY_BY_HREF[it.href] = it.key; });
  });

  /* ---------- 用户信息（登录页写入，其余页面读取） ---------- */
  var STORAGE_KEY = 'agri_user';

  function getUser() {
    try {
      return JSON.parse(localStorage.getItem(STORAGE_KEY)) || { name: '张老三', roleName: '农户', role: 'farmer' };
    } catch (e) {
      return { name: '张老三', roleName: '农户', role: 'farmer' };
    }
  }

  function setUser(u) {
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify(u)); } catch (e) { /* 存储不可用时静默降级 */ }
  }

  /* 是否已登录（localStorage 里存在用户信息） */
  function isLoggedIn() {
    try { return !!localStorage.getItem(STORAGE_KEY); } catch (e) { return false; }
  }

  /* 当前角色：farmer 农户 / admin 农场管理员 / sysadmin 系统管理员 */
  function getRole() {
    return getUser().role || 'farmer';
  }

  /* 当前角色是否有权访问某个页面（key 对应 NAV 里的 item.key） */
  function canAccess(key) {
    var allowed = ROLE_NAV[getRole()] || ROLE_NAV.farmer;
    return allowed.indexOf(key) !== -1;
  }

  /* 退出登录：清除本地登录态并回到登录页（切换账户） */
  function logout() {
    try { localStorage.removeItem(STORAGE_KEY); } catch (e) {}
    location.href = 'login.html';
  }

  /* ---------- 渲染侧边栏 ---------- */
  function renderSidebar(activeKey) {
    var allowed = ROLE_NAV[getRole()] || ROLE_NAV.farmer;
    var html = '<div class="sidebar-brand">智慧农业平台</div>';
    html += '<nav class="sidebar-nav">';

    NAV.forEach(function (group) {
      // 按当前角色过滤导航项，整组被过滤掉时不渲染该分组标题
      var items = group.items.filter(function (item) {
        return allowed.indexOf(item.key) !== -1;
      });
      if (!items.length) return;

      html += '<div class="nav-group-label">' + group.group + '</div>';
      items.forEach(function (item) {
        var active = item.key === activeKey ? ' active' : '';
        html += '<a class="nav-item' + active + '" href="' + item.href + '">' + item.label + '</a>';
      });
    });

    html += '</nav>';
    html += '<div class="sidebar-footer">智慧农业平台 v1.0</div>';
    document.getElementById('sidebar').innerHTML = html;
  }

  /* ---------- 渲染顶栏 ---------- */
  function renderTopbar(pageKey) {
    var u = getUser();
    var title = TITLES[pageKey] || '智慧农业平台';
    var html = '<div class="topbar-left">' +
               '<span class="topbar-title">' + title + '</span>' +
               '<span class="topbar-breadcrumb">首页 / ' + title + '</span>' +
               '</div>';
    html += '<div class="topbar-right">' +
            '<span class="topbar-clock" id="topbarClock">--:--:--</span>' +
            '<span class="topbar-user"><span class="avatar">' + (u.name || '农').charAt(0) + '</span>' +
            u.roleName + ' · ' + u.name + '</span>' +
            '<a class="topbar-logout" id="logoutBtn" href="javascript:;" title="退出并切换账户">切换账户</a>' +
            '</div>';
    document.getElementById('topbar').innerHTML = html;
  }

  /* ---------- 时钟 ---------- */
  function startClock() {
    function tick() {
      var el = document.getElementById('topbarClock');
      if (!el) return;
      var d = new Date();
      function p(n) { return (n < 10 ? '0' : '') + n; }
      el.textContent = p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
    }
    tick();
    setInterval(tick, 1000);
  }

  /* ---------- 处理当前角色无权限访问的页内跳转链接 ----------
     · 操作按钮（.btn）：直接隐藏，避免农户看到无法使用的按钮
     · 数据卡片等（如 KPI 卡片）：保留内容但禁用跳转，农户仍可查看统计数据 */
  function hideRestrictedLinks() {
    var links = document.querySelectorAll('a[href]');
    for (var i = 0; i < links.length; i++) {
      var a = links[i];
      var key = KEY_BY_HREF[a.getAttribute('href')];
      if (!key || canAccess(key)) continue;

      if (/(^|\s)btn(\s|$)/.test(a.className)) {
        a.style.display = 'none';
      } else {
        a.removeAttribute('href');
        a.removeAttribute('title');
        a.style.pointerEvents = 'none';
        a.style.cursor = 'default';
      }
    }
  }

  /* ---------- 入口：页面加载时调用 Layout.init() ---------- */
  function init() {
    var pageKey = document.body.getAttribute('data-page') || '';

    // 1) 未登录 → 回登录页
    if (!isLoggedIn()) {
      location.replace('login.html');
      return;
    }

    // 2) 无权限访问当前页 → 回数据总览（所有角色均可访问）
    if (pageKey && !canAccess(pageKey)) {
      location.replace('index.html');
      return;
    }

    renderSidebar(pageKey);
    renderTopbar(pageKey);
    hideRestrictedLinks();
    startClock();

    // 绑定「切换账户」按钮
    var logoutBtn = document.getElementById('logoutBtn');
    if (logoutBtn) logoutBtn.addEventListener('click', logout);
  }

  return {
    NAV: NAV,
    TITLES: TITLES,
    init: init,
    getUser: getUser,
    setUser: setUser,
    getRole: getRole,
    canAccess: canAccess,
    isLoggedIn: isLoggedIn
  };
})();

/* ==========================================================================
   Toast 轻提示
   ========================================================================== */
window.Toast = (function () {
  function ensureWrap() {
    var wrap = document.getElementById('toastWrap');
    if (!wrap) {
      wrap = document.createElement('div');
      wrap.id = 'toastWrap';
      wrap.className = 'toast-wrap';
      document.body.appendChild(wrap);
    }
    return wrap;
  }

  var ICONS = { success: '✅', error: '❌', info: 'ℹ️' };

  function show(msg, type) {
    type = type || 'info';
    var wrap = ensureWrap();
    var el = document.createElement('div');
    el.className = 'toast ' + type;
    el.innerHTML = '<span>' + (ICONS[type] || 'ℹ️') + '</span><span>' + msg + '</span>';
    wrap.appendChild(el);
    setTimeout(function () {
      el.style.opacity = '0';
      el.style.transform = 'translateX(120%)';
      el.style.transition = 'all .3s ease';
      setTimeout(function () { el.remove(); }, 300);
    }, 2200);
  }

  return {
    show: show,
    success: function (m) { show(m, 'success'); },
    error: function (m) { show(m, 'error'); },
    info: function (m) { show(m, 'info'); }
  };
})();
