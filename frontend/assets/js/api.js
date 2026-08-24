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
      chat:       '/api/assistant/chat'
    }
  };

  /* 通用请求函数：mock 模式下走本地数据，否则走真实 fetch */
  function request(url, options) {
    options = options || {};
    if (config.useMock) {
      // mock 模式不会真正走这里，各方法内已直接返回 mock Promise
      return Promise.resolve(null);
    }
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

  /* ==================================================================
     登录
     ================================================================== */
  function login(username, password, role) {
    if (config.useMock) {
      var users = {
        'farmer':   { name: '张老三', roleName: '农户',       role: 'farmer' },
        'admin':    { name: '李四',   roleName: '农场管理员', role: 'admin' },
        'sysadmin': { name: '王五',   roleName: '系统管理员', role: 'sysadmin' }
      };
      var u = users[role] || users.farmer;
      return mockDelay({ code: 0, msg: 'ok', data: { token: 'mock-token-' + role, name: u.name, roleName: u.roleName, role: u.role } }, 600);
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
  function getPlots() {
    if (config.useMock) return mockDelay({ code: 0, data: window.MOCK.plots });
    return request(config.endpoints.plots);
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
  function getThresholds() {
    if (config.useMock) return mockDelay({ code: 0, data: window.MOCK.thresholds });
    return request(config.endpoints.thresholds);
  }

  function saveThresholds(data) {
    if (config.useMock) {
      window.MOCK.thresholds.humidityMin = data.humidityMin;
      window.MOCK.thresholds.tempMax = data.tempMax;
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
    if (config.useMock) {
      var arr = window.MOCK.alarms;
      for (var i = 0; i < arr.length; i++) {
        if (arr[i].id === alarmId) { arr[i].status = status; break; }
      }
      return mockDelay({ code: 0 }, 300);
    }
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
    if (config.useMock) return mockDelay({ code: 0 }, 300);
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
     农事建议（根据实时数据动态生成）
     ================================================================== */
  function getAdvice() {
    if (config.useMock) return mockDelay({ code: 0, data: window.MOCK.getAdvice() }, 300);
    return request(config.endpoints.advice);
  }

  /* ==================================================================
     智能问答
     ================================================================== */
  function getChatReply(question) {
    if (config.useMock) {
      var reply = window.MOCK.getChatReply(question);
      return mockDelay({ code: 0, data: reply }, 800);
    }
    return request(config.endpoints.chat, { method: 'POST', data: { question: question } });
  }

  return {
    config: config,
    login: login,
    register: register,
    getPlots: getPlots,
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
    getAdvice: getAdvice,
    getChatReply: getChatReply
  };
})();
