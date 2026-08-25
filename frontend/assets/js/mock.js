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
    humidityMax: 70,   // 土壤湿度上限(%)
    tempMin: 10,       // 温度下限(℃)
    tempMax: 35,       // 温度上限(℃)
    luxMin: 200,       // 亮度下限(lx)
    luxMax: 800        // 亮度上限(lx)
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
     农事建议（数据总览用）
     根据地块 / 设备 / 阈值 / 天气等数据动态生成，数据变化则建议随之变化。
     ------------------------------------------------------------------ */
  function getAdvice() {
    var list = [];

    // 未来几天是否有降雨 / 降雪
    var hasRain = weather.forecast.some(function (d) {
      return /雨|雷|雪/.test(d.text);
    });

    // 1. 天气提醒
    if (hasRain) {
      list.push({ icon: '🌧️', tag: '天气', text: '天气预报显示近期有降雨，可暂缓灌溉，雨后视土壤湿度再决定是否补水。', href: null, action: '' });
    }

    // 2. 灌溉建议：土壤湿度低于阈值的干旱地块
    var dry = plots.filter(function (p) { return thresholds.humidityMin !== null && p.humidity < thresholds.humidityMin; });
    if (dry.length) {
      var dryNames = dry.map(function (p) { return p.name; }).join('、');
      list.push({ icon: '💧', tag: '灌溉', text: dryNames + ' 土壤湿度低于阈值 ' + thresholds.humidityMin + '%，建议尽快补水。', href: 'control.html', action: '去灌溉' });
    }

    var wet = plots.filter(function (p) { return thresholds.humidityMax !== null && p.humidity > thresholds.humidityMax; });
    if (wet.length) {
      var wetNames = wet.map(function (p) { return p.name; }).join('、');
      list.push({ icon: '💦', tag: '排湿', text: wetNames + ' 土壤湿度高于阈值 ' + thresholds.humidityMax + '%，建议减少灌溉并加强通风排湿。', href: 'monitoring.html', action: '看数据' });
    }

    // 3. 温度建议：低温保温，高温通风
    var cold = plots.filter(function (p) { return thresholds.tempMin !== null && p.temp < thresholds.tempMin; });
    if (cold.length) {
      var coldNames = cold.map(function (p) { return p.name; }).join('、');
      list.push({ icon: '🧊', tag: '保温', text: coldNames + ' 温度低于阈值 ' + thresholds.tempMin + '℃，建议检查保温措施，必要时升温。', href: 'monitoring.html', action: '看数据' });
    }

    var hot = plots.filter(function (p) { return thresholds.tempMax !== null && p.temp > thresholds.tempMax; });
    if (hot.length) {
      var hotNames = hot.map(function (p) { return p.name; }).join('、');
      list.push({ icon: '🌡️', tag: '通风', text: hotNames + ' 温度超过 ' + thresholds.tempMax + '℃，建议加强通风降温。', href: 'monitoring.html', action: '看数据' });
    }

    // 4. 光照建议：亮度过高遮阳，亮度过低补光
    var highLux = plots.filter(function (p) { return thresholds.luxMax !== null && p.lux > thresholds.luxMax; });
    if (highLux.length) {
      var highLuxNames = highLux.map(function (p) { return p.name; }).join('、');
      list.push({ icon: '☀️', tag: '遮阳', text: highLuxNames + ' 亮度超过 ' + thresholds.luxMax + ' lx，建议适当遮阳，减少强光灼伤风险。', href: 'monitoring.html', action: '看数据' });
    }

    var lowLux = plots.filter(function (p) { return thresholds.luxMin !== null && p.lux < thresholds.luxMin; });
    if (lowLux.length) {
      var lowLuxNames = lowLux.map(function (p) { return p.name; }).join('、');
      list.push({ icon: '💡', tag: '补光', text: lowLuxNames + ' 亮度低于 ' + thresholds.luxMin + ' lx，建议检查遮挡情况，必要时开启补光。', href: 'monitoring.html', action: '看数据' });
    }

    // 5. 设备离线提醒
    var offline = devices.filter(function (d) { return !d.online; });
    if (offline.length) {
      list.push({ icon: '🔌', tag: '设备', text: '有 ' + offline.length + ' 台设备离线，请检查供电与网络连接。', href: 'devices.html', action: '去设备' });
    }

    // 6. 防病建议：高湿或雨后
    var humid = plots.filter(function (p) { return p.humidity > 70; });
    if (hasRain || humid.length) {
      list.push({ icon: '🐛', tag: '防病', text: '近期湿度偏高，注意通风除湿，预防灰霉病等病害。', href: null, action: '' });
    }

    // 兜底：数据均正常时给一条常规建议
    if (!list.length) {
      list.push({ icon: '✅', tag: '正常', text: '各地块温度、湿度、亮度均在正常范围，请保持当前管理节奏。', href: null, action: '' });
    }

    return list;
  }

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
  function isPredictionQuestion(q) {
    return /灌溉|浇水|补水|缺水|土壤湿度|墒情/.test(q) &&
           /预测|未来|三天|3天|该不该|是否应该|要不要|需不需要|多久|多长时间|几分钟|现在该/.test(q);
  }

  function clamp(v, min, max) {
    return Math.max(min, Math.min(max, v));
  }

  function sigmoid(x) {
    return 1 / (1 + Math.exp(-x));
  }

  function norm(value, center, scale) {
    if (value === null || value === undefined || isNaN(value)) return 0;
    return clamp((value - center) / scale, -1, 1);
  }

  function inputWeight(gate, row, col) {
    var n = (gate + 2) * 31 + (row + 3) * 17 + (col + 5) * 13;
    return ((n % 19) - 9) / 26;
  }

  function hiddenWeight(gate, row, col) {
    var n = (gate + 1) * 29 + (row + 7) * 11 + (col + 2) * 23;
    return ((n % 17) - 8) / 34;
  }

  function gruFeatures(temp, humidity, lux, humidityMin, trend) {
    return [
      norm(temp, 25, 12),
      norm(humidity, 50, 30),
      norm(lux, 500, 600),
      norm(humidityMin - humidity, 0, 30),
      norm(trend, 0, 8)
    ];
  }

  function gruStep(x, h) {
    var next = [];
    for (var row = 0; row < 6; row++) {
      var iz = 0, ir = 0, inn = 0, hz = 0, hr = 0;
      for (var i = 0; i < 5; i++) {
        iz += inputWeight(0, row, i) * x[i];
        ir += inputWeight(1, row, i) * x[i];
        inn += inputWeight(2, row, i) * x[i];
      }
      for (var j = 0; j < 6; j++) {
        hz += hiddenWeight(0, row, j) * h[j];
        hr += hiddenWeight(1, row, j) * h[j];
      }
      var z = sigmoid(iz + hz - 0.10);
      var r = sigmoid(ir + hr + 0.05);
      var hn = 0;
      for (var k = 0; k < 6; k++) hn += hiddenWeight(2, row, k) * (r * h[k]);
      var n = Math.tanh(inn + hn);
      next[row] = z * h[row] + (1 - z) * n;
    }
    return next;
  }

  function gruOutputDelta(h) {
    var out = [-0.70, 0.42, -0.35, 0.55, -0.28, 0.33];
    var y = -0.20;
    for (var i = 0; i < h.length; i++) y += out[i] * h[i];
    return Math.tanh(y) * 5.5;
  }

  function average(arr) {
    return arr.reduce(function (a, b) { return a + b; }, 0) / arr.length;
  }

  function predictionReply(question) {
    var q = (question || '').toUpperCase();
    var matched = plots.filter(function (p) {
      return q.indexOf(p.id.toUpperCase()) !== -1 ||
             q.indexOf(p.name.toUpperCase()) !== -1 ||
             q.indexOf(p.crop.toUpperCase()) !== -1;
    });
    var targets = matched.length ? matched : plots;
    var answer = '我根据最近几天的温度、土壤湿度和光照数据，做了未来 3 天的预测性灌溉判断。\n\n' +
      '当前采用的是**1 层 GRU 时间序列预测器**：输入温度、土壤湿度、光照和湿度变化特征，预测未来 3 天湿度；再由规则层给出是否灌溉和建议时长。\n';

    targets.forEach(function (p) {
      var hist = getHistory(p.id, 7);
      var recentHum = hist.humidity.slice(-12);
      var recentTemp = hist.temp.slice(-12);
      var recentLux = hist.lux.slice(-12);
      var current = p.humidity;
      var trendSum = 0;
      var weightSum = 0;
      for (var i = 1; i < recentHum.length; i++) {
        var w = i;
        trendSum += (recentHum[i] - recentHum[i - 1]) * w;
        weightSum += w;
      }
      var trend = weightSum ? trendSum / weightSum : -0.25;
      var avgTemp = average(recentTemp);
      var avgLux = average(recentLux);
      var evap = 0;
      if (avgTemp >= 35) evap -= 0.30;
      else if (avgTemp >= 30) evap -= 0.18;
      else if (avgTemp >= 26) evap -= 0.09;
      if (avgLux >= 900) evap -= 0.15;
      else if (avgLux >= 650) evap -= 0.09;

      var hidden = [0, 0, 0, 0, 0, 0];
      for (var t = 0; t < hist.humidity.length; t++) {
        var prev = t > 0 ? hist.humidity[t - 1] : current;
        hidden = gruStep(gruFeatures(hist.temp[t], hist.humidity[t], hist.lux[t], thresholds.humidityMin, hist.humidity[t] - prev), hidden);
      }

      var forecast = [];
      var h = current;
      for (var d = 0; d < 3; d++) {
        for (var step = 0; step < 4; step++) {
          var delta = clamp(gruOutputDelta(hidden) * 0.55 + trend * 0.35 + evap, -2, 0.8);
          h = clamp(h + delta, 5, 95);
          hidden = gruStep(gruFeatures(avgTemp, h, avgLux, thresholds.humidityMin, delta), hidden);
        }
        forecast.push(Math.round(h * 10) / 10);
      }
      var minHum = Math.min(forecast[0], forecast[1], forecast[2]);
      var should = current < thresholds.humidityMin || minHum < thresholds.humidityMin;
      var risk = minHum < thresholds.humidityMin - 8 ? '高' : (minHum < thresholds.humidityMin ? '中' : (minHum < thresholds.humidityMin + 4 ? '低' : '正常'));
      var deficit = Math.max(thresholds.humidityMin + 5 - Math.min(current, minHum), 1);
      var duration = Math.round(Math.max(10, Math.min(60, (8 + deficit * 2.2) * (0.8 + parseFloat(p.area) * 0.12))));
      var start = current < thresholds.humidityMin ? '现在或最近一个低蒸发时段（清晨/傍晚）' : (forecast[0] < thresholds.humidityMin ? '第 1 天清晨' : (forecast[1] < thresholds.humidityMin ? '第 2 天清晨' : '第 3 天清晨'));

      answer += '\n- **' + p.name + '（' + p.id + '，' + p.crop + '）**\n' +
        '  当前土壤湿度约 ' + current + '%，阈值为 ' + thresholds.humidityMin + '%。近几天平均温度 ' + avgTemp.toFixed(1) + '℃，光照 ' + avgLux.toFixed(0) + ' lx。\n' +
        '  未来 3 天预测湿度：第 1 天约 ' + forecast[0] + '%，第 2 天约 ' + forecast[1] + '%，第 3 天约 ' + forecast[2] + '%。\n';
      if (should) {
        answer += '  结论：**建议灌溉**，风险等级为**' + risk + '**。建议' + start + '灌溉 **' + duration + ' 分钟**。\n';
      } else {
        answer += '  结论：**未来 3 天暂不建议灌溉**，风险等级为**' + risk + '**。继续观察实时湿度即可。\n';
      }
    });

    answer += '\n提示：预测建议用于辅助决策，真正执行前建议再看一次实时湿度和设备在线状态。';
    return {
      answer: answer,
      actions: [{ text: '去控制灌溉', href: 'control.html' }, { text: '查看历史趋势', href: 'history.html' }],
      sources: ['历史温湿度光照数据', '1层GRU预测模型']
    };
  }

  function getChatReply(question) {
    var q = (question || '').replace(/[？?。.，,\s]/g, '');
    if (isPredictionQuestion(q)) return predictionReply(question);

    var replies = [
      { kw: ['浇水', '灌溉', '什么时候浇', '该不该浇'], answer: '根据当前土壤湿度数据，建议在**清晨 6:00–8:00** 或傍晚 18:00–20:00 灌溉，此时蒸发量小、水分利用率高。若土壤湿度低于 **' + thresholds.humidityMin + '%**（当前阈值），请及时补水。', actions: [{ text: '去控制灌溉', href: 'control.html' }], sources: ['浇水的最佳时间', '如何判断该不该浇水', '远程灌溉控制操作'] },
      { kw: ['太干', '干旱', '缺水', '湿度低'], answer: '当前部分地块土壤湿度偏低，存在**缺水风险**。建议开启灌溉设备补水 20–30 分钟，并关注告警记录，避免作物因缺水萎蔫。', actions: [{ text: '查看告警', href: 'alarm.html' }], sources: ['土壤太干怎么办', '土壤湿度的适宜范围'] },
      { kw: ['阈值', '告警条件', '设置'], answer: '您可以在「告警管理」页设置**土壤湿度下限**和**温度上限**。\n设置方式：进入告警页 → 点击「编辑阈值」→ 输入数值并保存。\n当前默认下限为 `' + thresholds.humidityMin + '%`、上限为 `' + thresholds.tempMax + '℃`，当实测值越过阈值时，系统会自动触发告警并通知您。', actions: [{ text: '去设置阈值', href: 'alarm.html' }], sources: ['告警阈值如何设置', '如何避免告警误报'] },
      { kw: ['温度', '太热', '高温'], answer: '若大棚温度超过 **' + thresholds.tempMax + '℃**（当前阈值），建议及时通风或开启遮阳。温度过高会影响作物生长，请留意实时温度曲线。', actions: [{ text: '查看实时数据', href: 'monitoring.html' }], sources: ['温度太高怎么降温', '大棚温度的适宜范围'] }
    ];

    for (var i = 0; i < replies.length; i++) {
      for (var j = 0; j < replies[i].kw.length; j++) {
        if (q.indexOf(replies[i].kw[j]) !== -1) {
          return replies[i];
        }
      }
    }
    return {
      answer: '我是智慧农业助手，可以为您提供灌溉建议和农事指导。\n您可以试试问我：\n· 「现在该浇水吗？」\n· 「土壤太干怎么办？」\n· 「如何设置告警阈值？」',
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
    getAdvice: getAdvice,
    getRealtime: getRealtime,
    getHistory: getHistory,
    getChatReply: getChatReply,
    delay: delay,
    nowTime: nowTime,
    nowDate: nowDate,
    rand: rand
  };
})();
