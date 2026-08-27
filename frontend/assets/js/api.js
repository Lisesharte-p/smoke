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
    useMock: false,         // 默认真实接口；模拟模式由 URL / 本地设置显式开启
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

  /* ---------- 模拟器模式：真实接口为默认，显式开启后所有请求留在浏览器 ---------- */
  var MODE_KEY = 'agri_simulator_mode';
  var queryMode = new URLSearchParams(window.location.search).get('simulator');
  if (queryMode === '1' || queryMode === '0') {
    try { localStorage.setItem(MODE_KEY, queryMode === '1' ? '1' : '0'); } catch (e) {}
  }
  try { config.useMock = localStorage.getItem(MODE_KEY) === '1'; } catch (e) { config.useMock = queryMode === '1'; }

  function isSimulatorMode() { return !!config.useMock; }

  function setSimulatorMode(enabled) {
    config.useMock = !!enabled;
    try { localStorage.setItem(MODE_KEY, config.useMock ? '1' : '0'); } catch (e) {}
  }

  function resetSimulator() {
    if (window.MOCK && typeof window.MOCK.reset === 'function') window.MOCK.reset();
  }

  function simulatorCameraSource() {
    return 'assets/pvz/images/Backgroundfull.png';
  }

  /* 通用请求函数：模拟模式下硬性阻断，避免未来新增接口意外访问后端 */
  function request(url, options) {
    options = options || {};
    if (isSimulatorMode()) return Promise.reject(new Error('模拟模式已阻断后端请求'));
    return fetch(config.baseUrl + url, {
      method: options.method || 'GET',
      headers: { 'Content-Type': 'application/json' },
      body: options.data ? JSON.stringify(options.data) : undefined
    }).then(function (res) {
      return res.json();
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
  function login(username, password) {
    if (config.useMock) {
      var u = window.MOCK.login(username, password);
      if (!u) return mockDelay({ code: 1, msg: '账号或密码错误', data: null }, 400);
      return mockDelay({ code: 0, msg: 'ok', data: { token: 'simulator-token-' + u.username, username: u.username, name: u.name, roleName: u.roleName, role: u.role } }, 400);
    }
    return request(config.endpoints.login, { method: 'POST', data: { username: username, password: password } });
  }

  /* 注册：模拟模式创建待审核申请；真实模式保持原接口 */
  function register(data) {
    if (config.useMock) {
      var req = window.MOCK.register(data);
      return mockDelay(req.error ? { code: 1, msg: req.error, data: null } : { code: 0, msg: '注册申请已提交，请等待管理员审核', data: req }, 300);
    }
    return request(config.endpoints.register, { method: 'POST', data: data });
  }

  /* ==================================================================
     地块
     ================================================================== */
  function getPlots() {
    if (config.useMock) return mockDelay({ code: 0, data: window.MOCK.plots });
    return request(config.endpoints.plots);
  }

  /* 新增地块；data: {name, crop, area, devices?:[{name,type,ip,port},...]}，devices 为可选绑定设备 */
  function addPlot(data) {
    if (config.useMock) return mockDelay({ code: 0, data: window.MOCK.savePlot(data) }, 300);
    var payload = data || {};
    payload.role = currentRole();   // 权限：仅管理员/系统管理员可新增地块
    return request(config.endpoints.plots, { method: 'POST', data: payload });
  }

  /* 删除地块（后端级联删除其设备与关联数据） */
  function deletePlot(plotId) {
    if (config.useMock) return mockDelay({ code: window.MOCK.removePlot(plotId) ? 0 : 1, msg: '地块不存在' }, 300);
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
    if (config.useMock) return mockDelay({ code: 0, data: window.MOCK.saveDevice(data) }, 300);
    return request(config.endpoints.devices, { method: 'POST', data: data });
  }

  function unbindDevice(deviceId) {
    if (config.useMock) return mockDelay({ code: window.MOCK.removeDevice(deviceId) ? 0 : 1, msg: '设备不存在' }, 300);
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
    if (config.useMock) return mockDelay({ code: 0, data: window.MOCK.getHistory(plotId, days, win) }, 500);
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
      var operator = window.Layout && Layout.getUser ? (Layout.getUser().roleName + '·' + Layout.getUser().name) : '模拟用户';
      var dev = window.MOCK.setIrrigation(deviceId, action, operator);
      return mockDelay(dev ? { code: 0, data: dev } : { code: 1, msg: '设备不存在', data: null }, 450);
    }
    return request(config.endpoints.control.replace('{deviceId}', deviceId), {
      method: 'POST', data: { action: action }
    });
  }

  /* ==================================================================
     告警阈值
     ================================================================== */
  function getThresholds() {
    if (config.useMock) return mockDelay({ code: 0, data: window.MOCK.thresholds });
    return request(config.endpoints.thresholds);
  }

  function saveThresholds(data) {
    if (config.useMock) {
      window.MOCK.setThresholds(data);
      return mockDelay({ code: 0 }, 300);
    }
    return request(config.endpoints.thresholds, { method: 'PUT', data: data });
  }

  /* ==================================================================
     告警记录
     ================================================================== */
  function getAlarms() {
    if (config.useMock) return mockDelay({ code: 0, data: window.MOCK.alarms });
    return request(config.endpoints.alarms);
  }

  function updateAlarmStatus(alarmId, status) {
    if (config.useMock) return mockDelay({ code: window.MOCK.setAlarmStatus(alarmId, status) ? 0 : 1, msg: '告警不存在' }, 300);
    return request(config.endpoints.alarms + '/' + alarmId, { method: 'PUT', data: { status: status } });
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
    if (config.useMock) {
      var reviewer = window.Layout && Layout.getUser ? Layout.getUser().name : '模拟管理员';
      var request = window.MOCK.reviewRequest(id, status, extra, reviewer);
      return mockDelay(request ? { code: 0, data: request } : { code: 1, msg: '申请不存在或已审核' }, 300);
    }
    var data = { status: status };
    if (extra.name) data.name = extra.name;
    if (extra.rejectReason) data.rejectReason = extra.rejectReason;
    return request(config.endpoints.registerRequests + '/' + id, { method: 'PUT', data: data });
  }

  /* ==================================================================
     控制日志
     ================================================================== */
  function getControlLogs() {
    if (config.useMock) return mockDelay({ code: 0, data: window.MOCK.controlLogs }, 400);
    return request(config.endpoints.controlLogs);
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
    if (config.useMock) return mockDelay({ code: 0, data: window.MOCK.getDetection(deviceId) }, 200);
    var url = config.endpoints.detectionStatus + (deviceId ? '?deviceId=' + encodeURIComponent(deviceId) : '');
    return request(url);
  }

  function saveDetectionSettings(data) {
    if (config.useMock) return mockDelay({ code: 0, data: window.MOCK.saveDetection(data) }, 250);
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
      var conv = window.MOCK.saveConversation(user, messages, conversationId, reply);
      reply.conversationId = conv.id;
      return mockDelay({ code: 0, data: reply }, 500);
    }
    return request(config.endpoints.chat, {
      method: 'POST',
      data: { user: user, conversationId: conversationId, messages: messages }
    });
  }

  /* 当前用户的对话历史列表（按用户隔离） */
  function getConversations(user) {
    if (config.useMock) return mockDelay({ code: 0, data: window.MOCK.conversations.filter(function (c) { return c.user === user; }) }, 300);
    return request(config.endpoints.conversations + '?user=' + encodeURIComponent(user));
  }

  /* 加载某次对话的完整上下文（消息列表） */
  function getConversationMessages(conversationId, user) {
    if (config.useMock) {
      var c = window.MOCK.conversations.filter(function (x) { return String(x.id) === String(conversationId) && x.user === user; })[0];
      return mockDelay({ code: c ? 0 : 1, data: c || null, msg: c ? 'ok' : '对话不存在' }, 300);
    }
    return request(config.endpoints.conversation.replace('{id}', conversationId) + '?user=' + encodeURIComponent(user));
  }

  /* 删除某次对话 */
  function deleteConversation(conversationId, user) {
    if (config.useMock) return mockDelay({ code: window.MOCK.deleteConversation(conversationId, user) ? 0 : 1, msg: '对话不存在' }, 300);
    return request(config.endpoints.conversation.replace('{id}', conversationId) + '?user=' + encodeURIComponent(user), { method: 'DELETE' });
  }

  return {
    config: config,
    isSimulatorMode: isSimulatorMode,
    setSimulatorMode: setSimulatorMode,
    resetSimulator: resetSimulator,
    getSimulatorCameraSource: simulatorCameraSource,
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
