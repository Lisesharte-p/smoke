/* ==========================================================================
   智慧农业平台 — 动画工具层（共享层，所有页面引用）
   提供：数字滚动 / 数值闪烁 / 进场 stagger / 按钮涟漪 / 逐字打字
   用法：页面里先引入本文件，再调用 window.Animate.xxx
   ========================================================================== */

window.Animate = (function () {

  /* ---------- 缓动：easeOutCubic（滚动/淡入都用它，收尾柔和） ---------- */
  function easeOutCubic(t) {
    return 1 - Math.pow(1 - t, 3);
  }

  /* ---------- 数字滚动 count-up ----------
     el      目标 DOM 元素
     target  目标数值
     opts    { duration 时长(ms), decimals 小数位数, suffix 后缀文本 } */
  function countUp(el, target, opts) {
    if (!el) return;
    opts = opts || {};
    var duration = opts.duration || 900;
    var decimals = (opts.decimals == null) ? 0 : opts.decimals;
    var suffix = opts.suffix || '';
    var startTime = null;

    function step(ts) {
      if (!startTime) startTime = ts;
      var p = Math.min((ts - startTime) / duration, 1);
      var val = target * easeOutCubic(p);
      el.textContent = val.toFixed(decimals) + suffix;
      if (p < 1) {
        requestAnimationFrame(step);
      } else {
        el.textContent = target.toFixed(decimals) + suffix;
      }
    }
    requestAnimationFrame(step);
  }

  /* ---------- 数值刷新闪烁（实时数据更新时调用，重复触发） ---------- */
  function flash(el) {
    if (!el) return;
    el.classList.remove('anim-flash');
    void el.offsetWidth; // 强制重排，让动画可重复触发
    el.classList.add('anim-flash');
  }

  /* ---------- 进场 stagger：给容器内子元素按顺序加 anim-in ----------
     container  容器元素
     opts       { selector 子元素选择器, step 相邻延迟ms, max 最大延迟ms } */
  function staggerIn(container, opts) {
    if (!container) return;
    opts = opts || {};
    var selector = opts.selector || ':scope > *';
    var step = opts.step || 60;
    var max = opts.max || 600;

    var items = container.querySelectorAll(selector);
    for (var i = 0; i < items.length; i++) {
      items[i].classList.add('anim-in');
      items[i].style.animationDelay = Math.min(i * step, max) + 'ms';
    }
  }

  /* ---------- 全页自动进场：扫描 .content 下的主要静态区块 ----------
     动态渲染的元素（如 plot-card）需在渲染后另行调用 staggerIn。 */
  function autoStagger() {
    var content = document.querySelector('.content');
    if (!content) return;
    var targets = content.querySelectorAll(
      '.kpi-grid > *, .plot-grid > *, .section-title, .metric-big, .card, .alert-banner'
    );
    for (var i = 0; i < targets.length; i++) {
      var el = targets[i];
      if (el.classList.contains('anim-in')) continue;
      el.classList.add('anim-in');
      el.style.animationDelay = Math.min(i * 50, 500) + 'ms';
    }
  }

  /* ---------- 按钮涟漪（全局事件委托，一次绑定全站生效） ---------- */
  function initRipple() {
    document.addEventListener('click', function (e) {
      var btn = e.target && e.target.closest ? e.target.closest('.btn') : null;
      if (!btn) return;
      var rect = btn.getBoundingClientRect();
      var size = Math.max(rect.width, rect.height);
      var span = document.createElement('span');
      span.className = 'ripple';
      span.style.width = size + 'px';
      span.style.height = size + 'px';
      span.style.left = (e.clientX - rect.left - size / 2) + 'px';
      span.style.top = (e.clientY - rect.top - size / 2) + 'px';
      btn.appendChild(span);
      setTimeout(function () { span.remove(); }, 600);
    });
  }

  /* ---------- 逐字打字（聊天机器人回复用） ----------
     el    目标元素
     text  完整文本
     opts  { speed 每字间隔ms, onDone 结束回调 } */
  function typewrite(el, text, opts) {
    if (!el) return;
    opts = opts || {};
    var speed = opts.speed || 28;
    var onDone = opts.onDone || null;
    var onTick = opts.onTick || null;
    var render = opts.render || null; // 可选：把每帧文本渲染为 HTML（如 markdown）
    var i = 0;
    if (render) {
      el.innerHTML = '';
    } else {
      el.textContent = '';
    }
    function tick() {
      if (i <= text.length) {
        var partial = text.slice(0, i);
        if (render) {
          el.innerHTML = render(partial);
        } else {
          el.textContent = partial;
        }
        if (onTick) onTick();
        i++;
        setTimeout(tick, speed);
      } else if (onDone) {
        onDone();
      }
    }
    tick();
  }

  /* ---------- 页面加载时自动：涟漪委托 + 静态区块进场 ---------- */
  function ready(fn) {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', fn);
    } else {
      fn();
    }
  }
  ready(function () {
    initRipple();
    autoStagger();
  });

  return {
    countUp: countUp,
    flash: flash,
    staggerIn: staggerIn,
    autoStagger: autoStagger,
    typewrite: typewrite,
    easeOutCubic: easeOutCubic
  };
})();
