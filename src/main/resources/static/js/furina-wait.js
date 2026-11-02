/*
 * 「芙宁娜来回走动」等待动画。
 *
 * 只在 AI 还没有返回可展示正文时显示：识别题目、分步讲解、追问回复都复用
 * 同一个轻量组件。精灵图来自 furina-dsh-pet（见 assets/furina-walk.LICENSE.txt），
 * 帧动画完全由 CSS steps() 驱动，这里只负责插入、淡出和清理。
 */
(() => {
  'use strict';

  const HOST_CLASS = 'furina-wait-host';
  const LEAVE_MS = 220;
  const records = new WeakMap();
  const active = new Set();

  function buildOverlay() {
    const overlay = document.createElement('div');
    overlay.className = 'furina-wait';
    overlay.setAttribute('data-furina-wait', '');
    overlay.setAttribute('aria-hidden', 'true');

    const walker = document.createElement('div');
    walker.className = 'furina-wait__walker';

    // 两帧精灵叠在一起，由 CSS 在走到边界时切换朝向。
    const right = document.createElement('span');
    right.className = 'furina-wait__sprite furina-wait__sprite--right';

    const left = document.createElement('span');
    left.className = 'furina-wait__sprite furina-wait__sprite--left';

    walker.append(right, left);
    overlay.appendChild(walker);
    return overlay;
  }

  function destroy(record) {
    if (record.timer) {
      window.clearTimeout(record.timer);
      record.timer = 0;
    }
    if (record.overlay.parentNode) {
      record.overlay.parentNode.removeChild(record.overlay);
    }
    active.delete(record);
    if (records.get(record.container) === record) {
      records.delete(record.container);
    }
    record.container.classList.remove(HOST_CLASS);
  }

  /**
   * 在 container 的正文底部显示等待动画。重复调用是幂等的。
   */
  function show(container) {
    if (!container) return;

    const current = records.get(container);
    if (current) {
      if (current.overlay.parentNode === container && !current.leaving) return;
      destroy(current);
    }

    const overlay = buildOverlay();
    container.classList.add(HOST_CLASS);
    container.appendChild(overlay);

    const record = { container, overlay, timer: 0, leaving: false };
    records.set(container, record);
    active.add(record);
  }

  /**
   * 收到第一段正文、请求失败或结束时调用。默认先淡出再移除；
   * immediate 用于容器马上要被完整重绘的场景。
   */
  function stop(container, options = {}) {
    if (!container) return;

    const record = records.get(container);
    if (!record) {
      container.classList.remove(HOST_CLASS);
      return;
    }

    if (record.timer) return;

    if (options.immediate) {
      destroy(record);
      return;
    }

    record.leaving = true;
    record.overlay.classList.add('is-leaving');
    record.timer = window.setTimeout(() => destroy(record), LEAVE_MS);
  }

  /**
   * 页面切换、组件销毁时兜底清理，避免残留节点或定时器。
   */
  function stopAll() {
    Array.from(active).forEach(destroy);
  }

  window.GeometryFurinaWait = Object.freeze({
    show,
    stop,
    stopAll
  });

  window.addEventListener('pagehide', stopAll);
})();
