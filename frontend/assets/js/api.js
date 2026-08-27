/* ==========================================================================
   智慧农业平台 — API 接口契约层（共享层）
   用法：页面里统一调用 window.API.xxx()，不要直接 fetch。

   对接后端时只需两步：
     1. 把 config.useMock 改为 false；
     2. 把 config.baseUrl 改成后端地址（如 'http://localhost:8080'）。
   接口路径已经按 config.endpoints 定义好，与后端约定一致即可。
   ========================================================================== */

window.API = (function () {

  var config = {
    baseUrl: '',            // 后端地址；由 WebServer 同源托管时留空，分开部署时填 'http://localhost:8080'
    useMock: false,         // false=走真实后端接口（数据存 MySQL）
    endpoints: {
      login:      '/api/auth/login',
      register:   '/api/auth/register',
      plots:      '/api/plots',
      plot:       '/api/plots/{plotId}',
      devices:    '/api/devices',
      realtime:   '/api/plots/{plotId}/realtime',
      history:    '/api/plots/{plotId}/history',
      control:    '/api/devices/{deviceId}/control',
      thresholds: '/api/thresholds',
      alarms:     '/api/alarms',
      controlLogs:'/api/control-logs',
      boardRefresh:'/api/board/refresh',
      registerRequests: '/api/register-requests',
      advice:     '/api/advice',
      weather:    '/api/weather',
      detectionRecords: '/api/camera/detection-records',
      detectionStatus:  '/api/camera/detection/status',
      detectionSettings:'/api/camera/detection/settings',
      chat:       '/api/assistant/chat',
      conversations: '/api/conversations',
      conversation:   '/api/conversations/{id}'
    }
  };

  var loaderState = {
    active: 0,
    showTimer: null,
    hideTimer: null,
    textTimer: null,
    shownAt: 0,
    messageIndex: 0,
    pageLoadUntil: Date.now() + 2600,
    messages: ['正在连接设备', '正在分析数据', '正在同步状态']
  };

  function loaderMessages(url, method) {
    if (url.indexOf('/api/devices') === 0 || url.indexOf('/api/board') === 0) {
      return ['正在连接设备', '正在同步设备状态', '正在读取实时数据'];
    }
    if (url.indexOf('/api/plots') === 0 || url.indexOf('/api/sensor-data') === 0) {
      return ['正在分析数据', '正在整理地块信息', '正在刷新监测指标'];
    }
    if (url.indexOf('/api/alarms') === 0 || url.indexOf('/api/thresholds') === 0) {
      return ['正在检查告警', '正在读取阈值配置', '正在同步处理状态'];
    }
    if (url.indexOf('/api/camera') === 0) {
      return ['正在连接摄像头', '正在加载识别状态', '正在同步视频记录'];
    }
    if (url.indexOf('/api/weather') === 0 || url.indexOf('/api/advice') === 0) {
      return ['正在分析数据', '正在生成农事建议', '正在同步天气信息'];
    }
    if (url.indexOf('/api/assistant') === 0) {
      return ['正在分析数据', '正在检索知识库', '正在生成回答'];
    }
    if (method !== 'GET') {
      return ['正在提交操作', '正在同步状态', '正在刷新数据'];
    }
    return ['正在加载数据', '正在分析数据', '正在同步状态'];
  }

  function ensureLoader() {
    var el = document.getElementById('pageLoader');
    if (el) return el;

    el = document.createElement('div');
    el.id = 'pageLoader';
    el.className = 'page-loader';
    el.setAttribute('aria-live', 'polite');
    el.innerHTML =
      '<div class="page-loader-panel">' +
        '<div class="page-loader-spinner"><span></span><span></span><span></span></div>' +
        '<div class="page-loader-text" id="pageLoaderText">正在加载数据</div>' +
      '</div>';
    document.body.appendChild(el);
    return el;
  }

  function setLoaderText() {
    var text = document.getElementById('pageLoaderText');
    if (!text) return;
    text.textContent = loaderState.messages[loaderState.messageIndex % loaderState.messages.length];
  }

  function showLoader(messages) {
    loaderState.messages = messages || loaderState.messages;
    loaderState.messageIndex = 0;
    var el = ensureLoader();
    setLoaderText();
    el.classList.add('show');
    loaderState.shownAt = Date.now();

    clearInterval(loaderState.textTimer);
    loaderState.textTimer = setInterval(function () {
      loaderState.messageIndex += 1;
      setLoaderText();
    }, 1400);
  }

  function showPageLoader(messages) {
    clearTimeout(loaderState.hideTimer);
    clearTimeout(loaderState.showTimer);
    loaderState.active = Math.max(loaderState.active, 1);
    showLoader(messages || ['正在打开页面', '正在准备数据', '正在同步状态']);
  }

  function beginLoader(url, method, enabled, immediate) {
    if (!enabled) return false;
    loaderState.active += 1;
    clearTimeout(loaderState.hideTimer);
    clearTimeout(loaderState.showTimer);
    showLoader(loaderMessages(url, method));
    return true;
  }

  function endLoader(started) {
    if (!started) return;
    loaderState.active = Math.max(0, loaderState.active - 1);
    if (loaderState.active > 0) return;

    clearTimeout(loaderState.showTimer);
    var el = document.getElementById('pageLoader');
    if (!el || !el.classList.contains('show')) return;

    var wait = Math.max(0, 420 - (Date.now() - loaderState.shownAt));
    loaderState.hideTimer = setTimeout(function () {
      if (loaderState.active > 0) return;
      el.classList.remove('show');
      clearInterval(loaderState.textTimer);
      loaderState.textTimer = null;
    }, wait);
  }

  /* 通用请求函数：mock 模式下走本地数据，否则走真实 fetch */
  function request(url, options) {
    options = options || {};
    if (config.useMock) {
      // mock 模式不会真正走这里，各方法内已直接返回 mock Promise
      return Promise.resolve(null);
    }
    var method = options.method || 'GET';
    var forceLoader = options.loader === true;
    var showGlobalLoader = forceLoader || (options.loader !== false && (Date.now() <= loaderState.pageLoadUntil || method !== 'GET'));
    var loaderStarted = beginLoader(url, method, showGlobalLoader, forceLoader);
    return fetch(config.baseUrl + url, {
      method: method,
      headers: { 'Content-Type': 'application/json' },
      body: options.data ? JSON.stringify(options.data) : undefined
    }).then(function (res) {
      endLoader(loaderStarted);
      return res.json();
    }, function (err) {
      endLoader(loaderStarted);
      throw err;
    });
  }

  /* 模拟延迟，让 mock 响应更接近真实网络 */
  function mockDelay(data, ms) {
    ms = ms || 400;
    return new Promise(function (resolve) {
      setTimeout(function () { resolve(JSON.parse(JSON.stringify(data))); }, ms);
    });
  }

  /* 当前角色：前端从 Layout 读取（登录后写入 localStorage）；管理类操作（增删地块）需 admin/sysadmin */
  function currentRole() {
    return (window.Layout && typeof Layout.getRole === 'function') ? Layout.getRole() : 'farmer';
  }

  /* ==================================================================
     登录
     ================================================================== */
  function login(username, password, role) {
    if (config.useMock) {
      var users = {
        'farmer':   { username: 'farmer01', name: '张老三', roleName: '农户',       role: 'farmer' },
        'admin':    { username: 'admin01',  name: '李四',   roleName: '农场管理员', role: 'admin' },
        'sysadmin': { username: 'sysadmin01', name: '王五', roleName: '系统管理员', role: 'sysadmin' }
      };
      var u = users[role] || users.farmer;
      return mockDelay({ code: 0, msg: 'ok', data: { token: 'mock-token-' + role, username: u.username, name: u.name, roleName: u.roleName, role: u.role } }, 600);
    }
    return request(config.endpoints.login, { method: 'POST', data: { username: username, password: password, role: role } });
  }

  /* 注册：前端仅预留接口，后端接入前 mock 返回占位提示 */
  function register(data) {
    if (config.useMock) return mockDelay({ code: 0, msg: '注册接口已预留，待后端接入', data: null }, 400);
    return request(config.endpoints.register, { method: 'POST', data: data });
  }

  /* ==================================================================
     地块
     ================================================================== */
  function getPlots(options) {
    if (config.useMock) return mockDelay({ code: 0, data: window.MOCK.plots });
    return request(config.endpoints.plots, options || {});
  }

  /* 新增地块；data: {name, crop, area, devices?:[{name,type,ip,port},...]}，devices 为可选绑定设备 */
  function addPlot(data) {
    if (config.useMock) {
      var id = 'P' + (100 + window.MOCK.plots.length + 1);
      var devs = data.devices || [];
      var plot = {
        id: id,
        name: data.name,
        crop: data.crop,
        area: data.area + '亩',
        temp: null,
        humidity: null,
        deviceCount: devs.length,
        onlineCount: devs.length
      };
      window.MOCK.plots.push(plot);
      devs.forEach(function (d) {
        window.MOCK.devices.push({
          id: 'D' + (100 + window.MOCK.devices.length + 1),
          name: d.name,
          type: d.type,
          plotId: id,
          plotName: data.name,
          ip: d.ip,
          port: d.port,
          protocol: d.protocol || null,
          username: d.username || null,
          password: d.password || null,
          online: true,
          controllable: d.type === '灌溉设备',
          running: false
        });
      });
      return mockDelay({ code: 0, data: plot }, 300);
    }
    var payload = data || {};
    payload.role = currentRole();   // 权限：仅管理员/系统管理员可新增地块
    return request(config.endpoints.plots, { method: 'POST', data: payload });
  }

  /* 删除地块（后端级联删除其设备与关联数据） */
  function deletePlot(plotId) {
    if (config.useMock) {
      var arr = window.MOCK.plots;
      for (var i = 0; i < arr.length; i++) {
        if (arr[i].id === plotId) { arr.splice(i, 1); break; }
      }
      window.MOCK.devices = window.MOCK.devices.filter(function (d) { return d.plotId !== plotId; });
      return mockDelay({ code: 0 }, 300);
    }
    return request(config.endpoints.plot.replace('{plotId}', plotId) + '?role=' + encodeURIComponent(currentRole()), { method: 'DELETE' });
  }

  /* ==================================================================
     设备
     ================================================================== */
  function getDevices() {
    if (config.useMock) return mockDelay({ code: 0, data: window.MOCK.devices });
    return request(config.endpoints.devices);
  }

  function addDevice(data) {
    if (config.useMock) {
      var d = {
        id: 'D' + (100 + window.MOCK.devices.length + 1),
        name: data.name,
        type: data.type,
        plotId: data.plotId,
        plotName: data.plotName,
        ip: data.ip,
        port: data.port,
        protocol: data.protocol || null,
        username: data.username || null,
        password: data.password || null,
        online: true,
        controllable: data.type === '灌溉设备',
        running: false
      };
      window.MOCK.devices.push(d);
      return mockDelay({ code: 0, data: d }, 300);
    }
    return request(config.endpoints.devices, { method: 'POST', data: data });
  }

  function unbindDevice(deviceId) {
    if (config.useMock) {
      var arr = window.MOCK.devices;
      for (var i = 0; i < arr.length; i++) {
        if (arr[i].id === deviceId) { arr.splice(i, 1); break; }
      }
      return mockDelay({ code: 0 }, 300);
    }
    return request(config.endpoints.devices + '/' + deviceId, { method: 'DELETE' });
  }

  /* ==================================================================
     实时数据 / 历史数据（供同伴页面使用）
     ================================================================== */
  function getRealtime(plotId) {
    if (config.useMock) return mockDelay({ code: 0, data: window.MOCK.getRealtime(plotId) }, 350);
    return request(config.endpoints.realtime.replace('{plotId}', plotId));
  }

  function getHistory(plotId, days, win) {
    if (config.useMock) return mockDelay({ code: 0, data: window.MOCK.getHistory(plotId, days) }, 500);
    // win 为短时窗口（1m/30m/24h）时走 ?window=，否则走 ?days=
    var url = win
      ? config.endpoints.history.replace('{plotId}', plotId) + '?window=' + win
      : config.endpoints.history.replace('{plotId}', plotId) + '?days=' + (days || 7);
    return request(url);
  }

  /* 板子手动刷新：立即读一次板子并写库，返回最新读数 */
  function refreshBoard() {
    if (config.useMock) return mockDelay({ code: 0, data: { temp: 30.5, humidity: 55, lux: 520, updatedAt: '模拟数据' } }, 300);
    return request(config.endpoints.boardRefresh, { method: 'POST' });
  }

  /* ==================================================================
     设备控制（灌溉开关）
     action: 'on' | 'off'
     ================================================================== */
  function controlIrrigation(deviceId, action) {
    if (config.useMock) {
      var arr = window.MOCK.devices;
      var dev = null;
      for (var i = 0; i < arr.length; i++) {
        if (arr[i].id === deviceId) { dev = arr[i]; break; }
      }
      if (dev) { dev.running = (action === 'on'); }
      return mockDelay({ code: 0, data: dev }, 600);
    }
    return request(config.endpoints.control.replace('{deviceId}', deviceId), {
      method: 'POST', data: { action: action }
    });
  }

  /* ==================================================================
     告警阈值
     ================================================================== */
  function getThresholds(filter) {
    if (config.useMock) return mockDelay({ code: 0, data: window.MOCK.thresholds });
    filter = filter || {};
    var qs = [];
    if (filter.plotId) qs.push('plotId=' + encodeURIComponent(filter.plotId));
    return request(config.endpoints.thresholds + (qs.length ? '?' + qs.join('&') : ''), filter.loader === true ? { loader: true } : undefined);
  }

  function saveThresholds(data, filter) {
    if (config.useMock) {
      window.MOCK.thresholds.humidityMin = data.humidityMin;
      window.MOCK.thresholds.humidityMax = data.humidityMax;
      window.MOCK.thresholds.tempMin = data.tempMin;
      window.MOCK.thresholds.tempMax = data.tempMax;
      window.MOCK.thresholds.luxMin = data.luxMin;
      window.MOCK.thresholds.luxMax = data.luxMax;
      return mockDelay({ code: 0 }, 300);
    }
    filter = filter || {};
    var qs = [];
    if (filter.plotId) qs.push('plotId=' + encodeURIComponent(filter.plotId));
    return request(config.endpoints.thresholds + (qs.length ? '?' + qs.join('&') : ''), { method: 'PUT', data: data });
  }

  /* ==================================================================
     告警记录
     ================================================================== */
  function getAlarms(filter) {
    if (config.useMock) return mockDelay({ code: 0, data: window.MOCK.alarms });
    filter = filter || {};
    var qs = [];
    if (filter.plotId) qs.push('plotId=' + encodeURIComponent(filter.plotId));
    return request(config.endpoints.alarms + (qs.length ? '?' + qs.join('&') : ''), filter.loader === true ? { loader: true } : undefined);
  }

  function updateAlarmStatus(alarmId, status, data) {
    data = data || {};
    if (config.useMock) {
      var arr = window.MOCK.alarms;
      for (var i = 0; i < arr.length; i++) {
        if (arr[i].id === alarmId) {
          arr[i].status = status;
          arr[i].handler = data.handler || arr[i].handler || '演示用户';
          arr[i].handledAt = data.handledAt || arr[i].handledAt || new Date().toLocaleString('zh-CN', { hour12: false }).slice(0, 16);
          arr[i].handleLog = data.handleLog || arr[i].handleLog || '';
          break;
        }
      }
      return mockDelay({ code: 0 }, 300);
    }
    return request(config.endpoints.alarms + '/' + alarmId, {
      method: 'PUT',
      data: {
        status: status,
        handler: data.handler,
        handleLog: data.handleLog
      }
    });
  }

  /* ==================================================================
     注册申请审核（管理员）
     ================================================================== */
  function getRegisterRequests() {
    if (config.useMock) return mockDelay({ code: 0, data: window.MOCK.registerRequests || [] });
    return request(config.endpoints.registerRequests);
  }

  /* 审核：status='已通过'|'已拒绝'；通过可带 name（显示名），拒绝可带 rejectReason */
  function reviewRegisterRequest(id, status, extra) {
    extra = extra || {};
    if (config.useMock) return mockDelay({ code: 0 }, 300);
    var data = { status: status };
    if (extra.name) data.name = extra.name;
    if (extra.rejectReason) data.rejectReason = extra.rejectReason;
    return request(config.endpoints.registerRequests + '/' + id, { method: 'PUT', data: data });
  }

  /* ==================================================================
     控制日志
     ================================================================== */
  function getControlLogs(filter) {
    if (config.useMock) return mockDelay({ code: 0, data: window.MOCK.controlLogs }, 400);
    filter = filter || {};
    var qs = [];
    if (filter.limit) qs.push('limit=' + encodeURIComponent(filter.limit));
    return request(config.endpoints.controlLogs + (qs.length ? '?' + qs.join('&') : ''));
  }

  /* ==================================================================
     摄像头人体识别
     ================================================================== */
  function getDetectionRecords(filter) {
    filter = filter || {};
    if (config.useMock) return mockDelay({ code: 0, data: window.MOCK.detectionRecords || [] }, 300);
    var qs = [];
    if (filter.deviceId) qs.push('deviceId=' + encodeURIComponent(filter.deviceId));
    if (filter.date) qs.push('date=' + encodeURIComponent(filter.date));
    return request(config.endpoints.detectionRecords + (qs.length ? '?' + qs.join('&') : ''));
  }

  function getDetectionRecord(id) {
    if (config.useMock) {
      var list = window.MOCK.detectionRecords || [];
      var found = null;
      for (var i = 0; i < list.length; i++) {
        if (String(list[i].id) === String(id)) { found = list[i]; break; }
      }
      return mockDelay({ code: found ? 0 : 1, data: found, msg: found ? 'ok' : '识别记录不存在' }, 200);
    }
    return request(config.endpoints.detectionRecords + '/' + encodeURIComponent(id));
  }

  function getDetectionStatus(deviceId) {
    if (config.useMock) {
      return mockDelay({ code: 0, data: { deviceId: deviceId, enabled: true, confidenceThreshold: 0.5, cooldownSeconds: 30, preSeconds: 5, postSeconds: 10 } }, 200);
    }
    var url = config.endpoints.detectionStatus + (deviceId ? '?deviceId=' + encodeURIComponent(deviceId) : '');
    return request(url);
  }

  function saveDetectionSettings(data) {
    if (config.useMock) return mockDelay({ code: 0, data: data }, 250);
    return request(config.endpoints.detectionSettings, { method: 'POST', data: data });
  }

  /* ==================================================================
     农事建议（根据实时数据动态生成）
     ================================================================== */
  function getAdvice() {
    if (config.useMock) return mockDelay({ code: 0, data: window.MOCK.getAdvice() }, 300);
    return request(config.endpoints.advice);
  }

  /* ==================================================================
     天气预报（后端接和风天气；未配 Key 或调用失败时降级为 mock）
     ================================================================== */
  function getWeather() {
    if (config.useMock) return mockDelay({ code: 0, data: window.MOCK.weather }, 300);
    return request(config.endpoints.weather).then(function (res) {
      // 后端未配置 / 调用失败时回退到本地模拟天气，保证板块始终有内容
      if (res && res.code === 0 && res.data) return res;
      return { code: 0, data: window.MOCK.weather };
    });
  }

  /* ==================================================================
     智能问答
     ================================================================== */
  /* 多轮对话：user 用户名，messages 整段历史 [{role,content},...]，conversationId 当前会话（无则新建） */
  function getChatReply(user, messages, conversationId) {
    if (config.useMock) {
      // mock 模式取最后一条用户消息做关键词匹配
      var q = '';
      for (var i = messages.length - 1; i >= 0; i--) {
        if (messages[i].role === 'user') { q = messages[i].content; break; }
      }
      var reply = window.MOCK.getChatReply(q);
      reply.conversationId = conversationId || 999001;
      return mockDelay({ code: 0, data: reply }, 800);
    }
    return request(config.endpoints.chat, {
      method: 'POST',
      data: { user: user, conversationId: conversationId, messages: messages }
    });
  }

  /* 当前用户的对话历史列表（按用户隔离） */
  function getConversations(user) {
    if (config.useMock) return mockDelay({ code: 0, data: window.MOCK.conversations || [] }, 300);
    return request(config.endpoints.conversations + '?user=' + encodeURIComponent(user));
  }

  /* 加载某次对话的完整上下文（消息列表） */
  function getConversationMessages(conversationId, user) {
    if (config.useMock) {
      var arr = window.MOCK.conversations || [];
      var c = null;
      for (var i = 0; i < arr.length; i++) {
        if (arr[i].id === conversationId) { c = arr[i]; break; }
      }
      return mockDelay({ code: 0, data: c || { id: conversationId, title: '', messages: [] } }, 300);
    }
    return request(config.endpoints.conversation.replace('{id}', conversationId) + '?user=' + encodeURIComponent(user));
  }

  /* 删除某次对话 */
  function deleteConversation(conversationId, user) {
    if (config.useMock) {
      window.MOCK.conversations = (window.MOCK.conversations || []).filter(function (c) { return c.id !== conversationId; });
      return mockDelay({ code: 0 }, 300);
    }
    return request(config.endpoints.conversation.replace('{id}', conversationId) + '?user=' + encodeURIComponent(user), { method: 'DELETE' });
  }

  return {
    config: config,
    showPageLoader: showPageLoader,
    login: login,
    register: register,
    getPlots: getPlots,
    addPlot: addPlot,
    deletePlot: deletePlot,
    getDevices: getDevices,
    addDevice: addDevice,
    unbindDevice: unbindDevice,
    getRealtime: getRealtime,
    getHistory: getHistory,
    controlIrrigation: controlIrrigation,
    getThresholds: getThresholds,
    saveThresholds: saveThresholds,
    getAlarms: getAlarms,
    updateAlarmStatus: updateAlarmStatus,
    getRegisterRequests: getRegisterRequests,
    reviewRegisterRequest: reviewRegisterRequest,
    getControlLogs: getControlLogs,
    refreshBoard: refreshBoard,
    getDetectionRecords: getDetectionRecords,
    getDetectionRecord: getDetectionRecord,
    getDetectionStatus: getDetectionStatus,
    saveDetectionSettings: saveDetectionSettings,
    getAdvice: getAdvice,
    getWeather: getWeather,
    getChatReply: getChatReply,
    getConversations: getConversations,
    getConversationMessages: getConversationMessages,
    deleteConversation: deleteConversation
  };
})();
