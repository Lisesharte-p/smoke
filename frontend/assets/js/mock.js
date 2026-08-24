/* ==========================================================================
   智慧农业平台 — Mock 数据层
   说明：后端未就绪前，前端先用这份假数据模拟。后端就绪后，
         在 api.js 里把 USE_MOCK 改为 false 即可切换为真实接口。
   覆盖范围：地块 / 设备 / 实时数据 / 历史数据 / 告警 / 阈值 / 控制日志 / 智能问答
   ========================================================================== */

window.MOCK = (function () {

  /* ------------------------------------------------------------------
     地块数据（数据总览 / 数据监测用，供同伴页面调用）
     ------------------------------------------------------------------ */
  var plots = [
    { id: 'P001', name: '一号大棚', crop: '番茄',    area: '2.5亩', temp: 26.4, humidity: 62, deviceCount: 3, onlineCount: 3 },
    { id: 'P002', name: '二号大棚', crop: '黄瓜',    area: '1.8亩', temp: 24.1, humidity: 55, deviceCount: 3, onlineCount: 3 },
    { id: 'P003', name: '三号菜地', crop: '生菜',    area: '3.0亩', temp: 28.7, humidity: 41, deviceCount: 2, onlineCount: 1 },
    { id: 'P004', name: '四号果园', crop: '草莓',    area: '1.2亩', temp: 22.8, humidity: 68, deviceCount: 3, onlineCount: 3 }
  ];

  /* ------------------------------------------------------------------
     设备数据（设备管理 / 设备控制用）
     type: 土壤湿度 | 温度 | 灌溉设备
     controllable: 是否可远程控制（灌溉设备为 true）
     ------------------------------------------------------------------ */
  var devices = [
    { id: 'D001', name: '土壤湿度传感器-01', type: '土壤湿度',   plotId: 'P001', plotName: '一号大棚', online: true,  controllable: false },
    { id: 'D002', name: '温度传感器-01',     type: '温度',       plotId: 'P001', plotName: '一号大棚', online: true,  controllable: false },
    { id: 'D003', name: '灌溉电磁阀-01',     type: '灌溉设备',   plotId: 'P001', plotName: '一号大棚', online: true,  controllable: true,  running: false },
    { id: 'D004', name: '土壤湿度传感器-02', type: '土壤湿度',   plotId: 'P002', plotName: '二号大棚', online: true,  controllable: false },
    { id: 'D005', name: '温度传感器-02',     type: '温度',       plotId: 'P002', plotName: '二号大棚', online: true,  controllable: false },
    { id: 'D006', name: '灌溉电磁阀-02',     type: '灌溉设备',   plotId: 'P002', plotName: '二号大棚', ip: '192.168.70.167', port: 8888, online: true,  controllable: true,  running: true  },
    { id: 'D007', name: '土壤湿度传感器-03', type: '土壤湿度',   plotId: 'P003', plotName: '三号菜地', online: false, controllable: false },
    { id: 'D008', name: '灌溉电磁阀-03',     type: '灌溉设备',   plotId: 'P004', plotName: '四号果园', online: true,  controllable: true,  running: false }
  ];

  /* ------------------------------------------------------------------
     告警阈值
     ------------------------------------------------------------------ */
  var thresholds = {
    humidityMin: 40,   // 土壤湿度下限(%)
    tempMax: 35        // 温度上限(℃)
  };

  /* ------------------------------------------------------------------
     告警记录
     level: 严重 | 警告
     status: 未处理 | 已处理
     ------------------------------------------------------------------ */
  var alarms = [
    { id: 'A001', time: '2026-08-21 09:32', plotId: 'P003', plotName: '三号菜地', type: '土壤湿度过低', value: '38%', level: '警告', status: '未处理' },
    { id: 'A002', time: '2026-08-21 06:15', plotId: 'P001', plotName: '一号大棚', type: '温度过高',     value: '36.5℃', level: '严重', status: '已处理' },
    { id: 'A003', time: '2026-08-20 18:40', plotId: 'P003', plotName: '三号菜地', type: '设备离线',     value: '-',    level: '严重', status: '未处理' },
    { id: 'A004', time: '2026-08-20 11:03', plotId: 'P002', plotName: '二号大棚', type: '土壤湿度过低', value: '39%', level: '警告', status: '已处理' },
    { id: 'A005', time: '2026-08-19 22:18', plotId: 'P001', plotName: '一号大棚', type: '温度过高',     value: '35.8℃', level: '警告', status: '已处理' }
  ];

  /* ------------------------------------------------------------------
     控制日志（设备控制用）
     ------------------------------------------------------------------ */
  var controlLogs = [
    { id: 'L001', time: '2026-08-21 10:05', deviceId: 'D006', deviceName: '灌溉电磁阀-02', plotName: '二号大棚', action: '开启', result: '成功', operator: '农户·张老三' },
    { id: 'L002', time: '2026-08-21 08:30', deviceId: 'D003', deviceName: '灌溉电磁阀-01', plotName: '一号大棚', action: '关闭', result: '成功', operator: '农户·张老三' },
    { id: 'L003', time: '2026-08-20 19:12', deviceId: 'D003', deviceName: '灌溉电磁阀-01', plotName: '一号大棚', action: '开启', result: '成功', operator: '农户·张老三' },
    { id: 'L004', time: '2026-08-20 16:44', deviceId: 'D008', deviceName: '灌溉电磁阀-03', plotName: '四号果园', action: '开启', result: '失败', operator: '农场管理员·李四' }
  ];

  /* ------------------------------------------------------------------
     天气预报（数据总览用，模拟数据；后端就绪后接真实天气 API）
     ------------------------------------------------------------------ */
  var weather = {
    now: { icon: '☀️', text: '晴', temp: '26℃', humidity: '45%', wind: '东南风 2 级' },
    forecast: [
      { day: '今天', icon: '☀️', text: '晴',      high: 28, low: 18 },
      { day: '明天', icon: '⛅', text: '多云',    high: 25, low: 17 },
      { day: '后天', icon: '🌧️', text: '小雨',    high: 22, low: 16 },
      { day: '周六', icon: '🌤️', text: '晴间多云', high: 27, low: 19 },
      { day: '周日', icon: '☁️', text: '阴',      high: 24, low: 18 }
    ]
  };

  /* ------------------------------------------------------------------
     农事建议（数据总览用，模拟数据；可结合告警 / 阈值动态生成）
     ------------------------------------------------------------------ */
  var advice = [
    { icon: '💧', tag: '灌溉', text: '未来 48 小时有小雨，建议今日傍晚前完成本轮灌溉，雨后无需补浇。', href: 'control.html', action: '去灌溉' },
    { icon: '🌡️', tag: '通风', text: '明日午后气温偏高，一号、三号地块需加强通风降温。', href: 'monitoring.html', action: '看数据' },
    { icon: '⚠️', tag: '告警', text: '三号菜地土壤湿度仅 38%，低于阈值 40%，建议尽快补水。', href: 'alarm.html', action: '看告警' },
    { icon: '🐛', tag: '防病', text: '雨后湿度上升，番茄地块注意通风除湿，预防灰霉病。', href: null, action: '' }
  ];

  /* ------------------------------------------------------------------
     工具
     ------------------------------------------------------------------ */
  function rand(min, max) {
    return Math.round((min + Math.random() * (max - min)) * 10) / 10;
  }

  // 模拟网络延迟
  function delay(ms) {
    return new Promise(function (resolve) { setTimeout(resolve, ms); });
  }

  function nowTime() {
    var d = new Date();
    function p(n) { return (n < 10 ? '0' : '') + n; }
    return p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
  }

  function nowDate() {
    var d = new Date();
    function p(n) { return (n < 10 ? '0' : '') + n; }
    return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
  }

  /* ------------------------------------------------------------------
     实时数据（数据监测用，供同伴页面调用）
     返回某地块的当前温湿度
     ------------------------------------------------------------------ */
  function getRealtime(plotId) {
    var plot = plots.filter(function (p) { return p.id === plotId; })[0] || plots[0];
    // 轻微随机波动，模拟实时变化
    var temp = Math.round((plot.temp + (Math.random() * 0.6 - 0.3)) * 10) / 10;
    var humidity = Math.round((plot.humidity + (Math.random() * 1.4 - 0.7)) * 10) / 10;
    return {
      plotId: plot.id,
      plotName: plot.name,
      temp: temp,
      humidity: humidity,
      updatedAt: nowTime()
    };
  }

  /* ------------------------------------------------------------------
     历史数据（历史趋势用，供同伴页面调用）
     生成近 N 天、每天 4 个采样点（6/12/18/24 时）的温湿度
     ------------------------------------------------------------------ */
  function getHistory(plotId, days) {
    days = days || 7;
    var plot = plots.filter(function (p) { return p.id === plotId; })[0] || plots[0];
    var dates = [];
    var temp = [];
    var humidity = [];
    var lux = [];
    var baseTemp = plot.temp;
    var baseHum = plot.humidity;

    for (var i = days - 1; i >= 0; i--) {
      var d = new Date();
      d.setDate(d.getDate() - i);
      ['06:00', '12:00', '18:00', '24:00'].forEach(function (t, idx) {
        // 温度昼夜波动：午后高、凌晨低
        var tempWave = [ -2.1, 1.6, 1.0, -1.4 ][idx];
        var humWave  = [ 3.0, -2.0, -1.0, 2.5 ][idx];
        var luxWave  = [ 120, 380, 240, 30 ][idx]; // 亮度：中午强、夜间弱
        var day = (d.getMonth() + 1) + '/' + d.getDate();
        dates.push(day + ' ' + t);
        temp.push(Math.round((baseTemp + tempWave + (Math.random() * 1.2 - 0.6)) * 10) / 10);
        humidity.push(Math.round(Math.max(20, Math.min(90, baseHum + humWave + (Math.random() * 3 - 1.5))) * 10) / 10);
        lux.push(Math.round(Math.max(0, Math.min(1200, 480 + luxWave + (Math.random() * 60 - 30)))));
      });
    }
    return { dates: dates, temp: temp, humidity: humidity, lux: lux };
  }

  /* ------------------------------------------------------------------
     智能问答回复（智能体用）
     简单关键词匹配，返回灌溉建议
     ------------------------------------------------------------------ */
  function getChatReply(question) {
    var q = (question || '').replace(/[？?。.，,\s]/g, '');

    var replies = [
      { kw: ['浇水', '灌溉', '什么时候浇', '该不该浇'], answer: '根据当前土壤湿度数据，建议在清晨 6:00–8:00 或傍晚 18:00–20:00 灌溉，此时蒸发量小、水分利用率高。若土壤湿度低于 ' + thresholds.humidityMin + '%（当前阈值），请及时补水。', actions: [{ text: '去控制灌溉', href: 'control.html' }], sources: ['浇水的最佳时间', '如何判断该不该浇水', '远程灌溉控制操作'] },
      { kw: ['太干', '干旱', '缺水', '湿度低'], answer: '当前部分地块土壤湿度偏低，存在缺水风险。建议开启灌溉设备补水 20–30 分钟，并关注告警记录，避免作物因缺水萎蔫。', actions: [{ text: '查看告警', href: 'alarm.html' }], sources: ['土壤太干怎么办', '土壤湿度的适宜范围'] },
      { kw: ['阈值', '告警条件', '设置'], answer: '您可以在「告警管理」页设置土壤湿度下限和温度上限。当实测值越过阈值时，系统会自动触发告警并通知您。', actions: [{ text: '去设置阈值', href: 'alarm.html' }], sources: ['告警阈值如何设置', '如何避免告警误报'] },
      { kw: ['温度', '太热', '高温'], answer: '若大棚温度超过 ' + thresholds.tempMax + '℃（当前阈值），建议及时通风或开启遮阳。温度过高会影响作物生长，请留意实时温度曲线。', actions: [{ text: '查看实时数据', href: 'monitoring.html' }], sources: ['温度太高怎么降温', '大棚温度的适宜范围'] }
    ];

    for (var i = 0; i < replies.length; i++) {
      for (var j = 0; j < replies[i].kw.length; j++) {
        if (q.indexOf(replies[i].kw[j]) !== -1) {
          return replies[i];
        }
      }
    }
    return {
      answer: '我是智慧农业助手，可以为您提供灌溉建议和农事指导。您可以试试问我：「现在该浇水吗？」「土壤太干怎么办？」「如何设置告警阈值？」',
      actions: [],
      sources: []
    };
  }

  /* ------------------------------------------------------------------
     对外暴露
     ------------------------------------------------------------------ */
  return {
    plots: plots,
    devices: devices,
    thresholds: thresholds,
    alarms: alarms,
    controlLogs: controlLogs,
    weather: weather,
    advice: advice,
    getRealtime: getRealtime,
    getHistory: getHistory,
    getChatReply: getChatReply,
    delay: delay,
    nowTime: nowTime,
    nowDate: nowDate,
    rand: rand
  };
})();
