/*
 * Shared Markdown + KaTeX renderer.
 *
 * Math is extracted into private-use placeholder tokens before Markdown is
 * parsed. KaTeX renders those tokens after DOMPurify has sanitized the HTML,
 * because KaTeX relies on inline styles that DOMPurify would otherwise strip.
 */
(() => {
  'use strict';

  const SANITIZE_CONFIG = {
    USE_PROFILES: { html: true },
    FORBID_TAGS: [
      'script',
      'style',
      'iframe',
      'object',
      'embed',
      'form',
      'input',
      'button',
      'textarea',
      'select',
      'option',
      'img',
      'video',
      'audio',
      'canvas',
      'picture',
      'source',
      'track',
      'svg',
      'math',
      'link',
      'meta',
      'base'
    ],
    FORBID_ATTR: [
      'style',
      'src',
      'srcset',
      'poster',
      'background',
      'action',
      'formaction',
      'ping',
      'xlink:href'
    ],
    ALLOW_DATA_ATTR: false
  };

  const MATH_TOKEN_START = '\uE000';
  const MATH_TOKEN_END = '\uE001';
  const MATH_TOKEN_SPLIT_PATTERN = /(\uE000\d+\uE001)/;

  let mathFitFrame = 0;

  const STREAM_RENDER_INTERVAL_MS = 80;
  const STREAM_PENDING_MATH_LIMIT = 240;
  const KATEX_CACHE_LIMIT = 400;
  const katexCache = new Map();
  const streamStates = new WeakMap();

  function renderMarkdown(text, container) {
    if (!container) return;
    stopStreaming(container);
    container.classList.remove('markdown--plain');

    if (!window.marked || !window.DOMPurify) {
      container.textContent = text;
      container.classList.add('markdown--plain');
      return;
    }

    try {
      const extracted = extractMath(text);
      const html = window.marked.parse(extracted.text, { gfm: true, breaks: true });
      const clean = window.DOMPurify.sanitize(html, SANITIZE_CONFIG);
      container.innerHTML = clean;
      enhanceRenderedContent(container);
      renderMathTokens(container, extracted.segments);
      scheduleFitDisplayMath(container);
    } catch (error) {
      container.textContent = text;
      container.classList.add('markdown--plain');
    }
  }

  function scheduleFitDisplayMath(root) {
    if (!root) return;

    if (mathFitFrame) {
      window.cancelAnimationFrame(mathFitFrame);
    }
    mathFitFrame = window.requestAnimationFrame(() => {
      mathFitFrame = 0;
      fitDisplayMath(root);
    });
  }

  /**
   * 手机窄屏下，过长的块级公式只缩小字号，不创建滚动容器。
   * 先恢复 CSS 字号再测量，避免窗口缩放后字号越缩越小。
   */
  function fitDisplayMath(root) {
    root.querySelectorAll('.math-display .katex-display > .katex').forEach((formula) => {
      formula.style.removeProperty('font-size');

      const wrapper = formula.closest('.math-display');
      const available = wrapper ? wrapper.clientWidth : 0;
      if (available <= 0) return;

      const width = formula.getBoundingClientRect().width;
      if (!Number.isFinite(width) || width <= available + 1) return;

      const baseSize = Number.parseFloat(window.getComputedStyle(formula).fontSize);
      if (!Number.isFinite(baseSize) || baseSize <= 0) return;

      const nextSize = Math.max(9, baseSize * ((available - 1) / width));
      formula.style.fontSize = `${nextSize.toFixed(2)}px`;
    });
  }

  function enhanceRenderedContent(root) {
    root.querySelectorAll('a').forEach((link) => {
      const href = link.getAttribute('href');

      if (!href || !href.trim()) {
        link.replaceWith(document.createTextNode(link.textContent || ''));
        return;
      }

      link.target = '_blank';
      link.rel = 'noopener noreferrer';
    });

    root.querySelectorAll('table').forEach((table) => {
      const wrapper = document.createElement('div');
      wrapper.className = 'table-scroll';
      wrapper.tabIndex = 0;
      wrapper.setAttribute('role', 'region');
      wrapper.setAttribute('aria-label', '表格，可横向滚动');
      table.parentNode.insertBefore(wrapper, table);
      wrapper.appendChild(table);
    });

    root.querySelectorAll('pre').forEach((pre) => {
      pre.tabIndex = 0;
      pre.setAttribute('aria-label', '代码块，可横向滚动');
    });
  }

  function isStandalone(source, start, end) {
    const lineStart = source.lastIndexOf('\n', start - 1) + 1;
    const lineEnd = source.indexOf('\n', end);
    const before = source.slice(lineStart, start);
    const after = source.slice(end, lineEnd === -1 ? source.length : lineEnd);
    return /^\s*$/.test(before) && /^\s*$/.test(after);
  }

  function findClosing(source, needle, from) {
    let cursor = from;
    while (cursor <= source.length - needle.length) {
      const found = source.indexOf(needle, cursor);
      if (found === -1) return -1;

      let slashCount = 0;
      for (let position = found - 1; position >= 0 && source[position] === '\\'; position -= 1) {
        slashCount += 1;
      }
      if (slashCount % 2 === 0) return found;
      cursor = found + needle.length;
    }
    return -1;
  }

  function findInlineDollar(source, from) {
    for (let cursor = from; cursor < source.length; cursor += 1) {
      if (source[cursor] === '\n') return -1;
      if (source[cursor] !== '$') continue;

      let slashCount = 0;
      for (let position = cursor - 1; position >= 0 && source[position] === '\\'; position -= 1) {
        slashCount += 1;
      }
      if (slashCount % 2 === 0) return cursor;
    }
    return -1;
  }

  function extractMath(text) {
    const source = String(text || '').replace(/\r\n?/g, '\n');
    const segments = [];
    let output = '';
    let index = 0;

    const addMath = (body, display, standalone = false) => {
      const latex = body.trim();
      if (!latex) return false;

      const token = `${MATH_TOKEN_START}${segments.length}${MATH_TOKEN_END}`;
      segments.push({ latex, display });
      output += standalone ? `\n\n${token}\n\n` : token;
      return true;
    };

    while (index < source.length) {
      const atLineStart = index === 0 || source[index - 1] === '\n';

      // Keep fenced code blocks verbatim so dollar signs inside code are not
      // treated as math delimiters.
      if (atLineStart) {
        const fenceMatch = /^ {0,3}(`{3,}|~{3,})/.exec(source.slice(index));
        if (fenceMatch) {
          const markerChar = fenceMatch[1][0];
          const markerLength = fenceMatch[1].length;
          const openingLineEnd = source.indexOf('\n', index);
          let end = source.length;
          let lineStart = openingLineEnd === -1 ? source.length : openingLineEnd + 1;

          while (lineStart < source.length) {
            const lineEnd = source.indexOf('\n', lineStart);
            const line = source.slice(lineStart, lineEnd === -1 ? source.length : lineEnd);
            const closingFence = new RegExp(`^ {0,3}${markerChar}{${markerLength},}\\s*$`);

            if (closingFence.test(line)) {
              end = lineEnd === -1 ? source.length : lineEnd + 1;
              break;
            }
            if (lineEnd === -1) break;
            lineStart = lineEnd + 1;
          }

          output += source.slice(index, end);
          index = end;
          continue;
        }
      }

      // Keep inline code spans verbatim as well.
      if (source[index] === '`') {
        let tickCount = 1;
        while (source[index + tickCount] === '`') tickCount += 1;

        const fence = '`'.repeat(tickCount);
        const close = source.indexOf(fence, index + tickCount);
        if (close !== -1) {
          output += source.slice(index, close + tickCount);
          index = close + tickCount;
          continue;
        }
      }

      if (source[index] === '\\' && source[index + 1] === '$') {
        output += '\\$';
        index += 2;
        continue;
      }

      if (source.startsWith('\\[', index)) {
        const close = findClosing(source, '\\]', index + 2);
        if (close !== -1) {
          addMath(source.slice(index + 2, close), true, isStandalone(source, index, close + 2));
          index = close + 2;
          continue;
        }
      }

      if (source.startsWith('\\(', index)) {
        const close = findClosing(source, '\\)', index + 2);
        if (close !== -1) {
          addMath(source.slice(index + 2, close), false);
          index = close + 2;
          continue;
        }
      }

      if (source.startsWith('$$', index)) {
        const close = findClosing(source, '$$', index + 2);
        if (close !== -1) {
          addMath(source.slice(index + 2, close), true, isStandalone(source, index, close + 2));
          index = close + 2;
        } else {
          output += '$$';
          index += 2;
        }
        continue;
      }

      if (source[index] === '$') {
        const close = findInlineDollar(source, index + 1);
        if (close !== -1) {
          const body = source.slice(index + 1, close);
          const nextCharacter = source[close + 1];

          // Avoid treating common currency text such as "$5 ... $10" as math.
          if (body.trim() && !/^\s|\s$/.test(body)
              && !(nextCharacter && /\d/.test(nextCharacter))) {
            addMath(body, false);
            index = close + 1;
            continue;
          }
        }
      }

      if (source.startsWith('\\begin{', index)) {
        const environment = /^\\begin\{([A-Za-z*]+)\}/.exec(source.slice(index));
        if (environment) {
          const endToken = `\\end{${environment[1]}}`;
          const close = findClosing(source, endToken, index + environment[0].length);
          if (close !== -1) {
            addMath(source.slice(index, close + endToken.length), true,
              isStandalone(source, index, close + endToken.length));
            index = close + endToken.length;
            continue;
          }
        }
      }

      output += source[index];
      index += 1;
    }

    return { text: output, segments };
  }

  /**
   * 流式渲染时只输出已经闭合的公式。
   *
   * <p>模型还没写完的公式（例如只收到一个开头的 `$`）先留在缓冲区里，
   * 不把半截 LaTeX 当普通文字显示；等收尾符号到达后再整段交给 KaTeX。
   * 返回的 parts 按原文顺序排列，文本与公式交替出现。</p>
   */
  function extractStreamingParts(text) {
    const source = String(text || '').replace(/\r\n?/g, '\n');
    const parts = [];
    let textBuffer = '';
    let trimNextNewline = false;
    let index = 0;

    const flushText = () => {
      if (!textBuffer) return;
      parts.push({ type: 'text', value: textBuffer });
      textBuffer = '';
    };

    const pushText = (value) => {
      if (!value) return;
      if (trimNextNewline) {
        trimNextNewline = false;
        if (value.startsWith('\n')) {
          value = value.slice(1);
        }
        if (!value) return;
      }
      textBuffer += value;
    };

    const addMath = (body, display, standalone = false) => {
      const latex = body.trim();
      if (!latex) return false;
      if (standalone) {
        textBuffer = textBuffer.replace(/\n$/, '');
        trimNextNewline = true;
      }
      flushText();
      parts.push({ type: 'math', segment: { latex, display } });
      return true;
    };

    while (index < source.length) {
      const atLineStart = index === 0 || source[index - 1] === '\n';

      // 代码块里的美元符号不是公式，原样输出。
      if (atLineStart) {
        const fenceMatch = /^ {0,3}(`{3,}|~{3,})/.exec(source.slice(index));
        if (fenceMatch) {
          const markerChar = fenceMatch[1][0];
          const markerLength = fenceMatch[1].length;
          const openingLineEnd = source.indexOf('\n', index);
          let end = source.length;
          let lineStart = openingLineEnd === -1 ? source.length : openingLineEnd + 1;

          while (lineStart < source.length) {
            const lineEnd = source.indexOf('\n', lineStart);
            const line = source.slice(lineStart, lineEnd === -1 ? source.length : lineEnd);
            const closingFence = new RegExp(`^ {0,3}${markerChar}{${markerLength},}\\s*$`);

            if (closingFence.test(line)) {
              end = lineEnd === -1 ? source.length : lineEnd + 1;
              break;
            }
            if (lineEnd === -1) break;
            lineStart = lineEnd + 1;
          }

          pushText(source.slice(index, end));
          index = end;
          continue;
        }
      }

      // 行内代码同理。
      if (source[index] === '`') {
        let tickCount = 1;
        while (source[index + tickCount] === '`') tickCount += 1;

        const fence = '`'.repeat(tickCount);
        const close = source.indexOf(fence, index + tickCount);
        if (close !== -1) {
          pushText(source.slice(index, close + tickCount));
          index = close + tickCount;
          continue;
        }
      }

      if (source[index] === '\\' && source[index + 1] === '$') {
        pushText('$');
        index += 2;
        continue;
      }

      if (source.startsWith('\\[', index)) {
        const close = findClosing(source, '\\]', index + 2);
        if (close === -1) break;
        addMath(source.slice(index + 2, close), true, isStandalone(source, index, close + 2));
        index = close + 2;
        continue;
      }

      if (source.startsWith('\\(', index)) {
        const close = findClosing(source, '\\)', index + 2);
        if (close === -1) break;
        addMath(source.slice(index + 2, close), false);
        index = close + 2;
        continue;
      }

      if (source.startsWith('$$', index)) {
        const close = findClosing(source, '$$', index + 2);
        if (close === -1) break;
        addMath(source.slice(index + 2, close), true, isStandalone(source, index, close + 2));
        index = close + 2;
        continue;
      }

      if (source[index] === '$') {
        const close = findInlineDollar(source, index + 1);
        if (close !== -1) {
          const body = source.slice(index + 1, close);
          const nextCharacter = source[close + 1];

          if (body.trim() && !/^\s|\s$/.test(body)
              && !(nextCharacter && /\d/.test(nextCharacter))) {
            addMath(body, false);
            index = close + 1;
            continue;
          }

          pushText('$');
          index += 1;
          continue;
        }

        // 行内公式不能跨行；如果本行没有收尾的 $，就按普通美元符号处理。
        const rest = source.slice(index + 1);
        if (rest.includes('\n') || rest.length > STREAM_PENDING_MATH_LIMIT) {
          pushText('$');
          index += 1;
          continue;
        }

        // 公式还没写完，先不显示这几字符，等下一次增量。
        break;
      }

      if (source.startsWith('\\begin{', index)) {
        const environment = /^\\begin\{([A-Za-z*]+)\}/.exec(source.slice(index));
        if (environment) {
          const endToken = `\\end{${environment[1]}}`;
          const close = findClosing(source, endToken, index + environment[0].length);
          if (close === -1) break;
          addMath(source.slice(index, close + endToken.length), true,
            isStandalone(source, index, close + endToken.length));
          index = close + endToken.length;
          continue;
        }
      }

      pushText(source[index]);
      index += 1;
    }

    flushText();
    return parts;
  }

  function streamingKey(parts) {
    let key = '';
    parts.forEach((part) => {
      if (part.type === 'text') {
        key += `T${part.value.length}:${part.value.slice(-24)}`;
        return;
      }
      key += `M${part.segment.display ? 'D' : 'I'}:${part.segment.latex}`;
    });
    return key;
  }

  function katexMarkup(segment) {
    const key = `${segment.display ? 'D' : 'I'}\u0000${segment.latex}`;
    if (katexCache.has(key)) return katexCache.get(key);

    let markup = null;
    if (window.katex && typeof window.katex.renderToString === 'function') {
      try {
        markup = window.katex.renderToString(segment.latex, {
          displayMode: segment.display,
          throwOnError: false,
          strict: 'ignore',
          trust: false
        });
      } catch (error) {
        markup = null;
      }
    }

    if (katexCache.size >= KATEX_CACHE_LIMIT) katexCache.clear();
    katexCache.set(key, markup);
    return markup;
  }

  /**
   * 结束某个容器的流式状态。
   *
   * <p>提交后会用完整 Markdown 重绘同一个容器；如果此时还有 80ms 合并窗口里
   * 的定时器，它会把旧的纯文本盖回最终结果，所以重绘前必须先取消。</p>
   */
  function stopStreaming(container) {
    if (!container) return;
    const state = streamStates.get(container);
    if (!state) return;
    if (state.timer) {
      window.clearTimeout(state.timer);
      state.timer = 0;
    }
    streamStates.delete(container);
  }

  /**
   * 流式更新容器内容：正文按纯文本增量显示，公式在闭合后立即用 KaTeX 排版。
   * 80ms 合并一次渲染，避免每个 token 都重建 DOM。
   */
  function streamWaitingOverlay(container) {
    return container.querySelector(':scope > [data-furina-wait]');
  }

  /**
   * 只替换正文节点，保留等待动画元素。
   * 不能把动画元素摘下来再插回去，否则 CSS animation 会重新计时。
   */
  function replaceStreamingChildren(container, fragment) {
    const waiting = streamWaitingOverlay(container);
    Array.from(container.childNodes).forEach((node) => {
      if (node !== waiting) container.removeChild(node);
    });
    if (!fragment) return;
    if (waiting) {
      container.insertBefore(fragment, waiting);
    } else {
      container.appendChild(fragment);
    }
  }

  function renderStreaming(container, text) {
    if (!container) return;

    let state = streamStates.get(container);
    if (!state) {
      state = { text: '', key: null, lastDraw: 0, timer: 0 };
      streamStates.set(container, state);
    }

    state.text = String(text || '');

    if (!state.text) {
      if (state.timer) {
        window.clearTimeout(state.timer);
        state.timer = 0;
      }
      state.key = null;
      container.classList.add('markdown', 'markdown--plain');
      replaceStreamingChildren(container, null);
      return;
    }

    if (state.timer) return;

    const wait = Math.max(0, STREAM_RENDER_INTERVAL_MS - (Date.now() - state.lastDraw));
    if (wait === 0) {
      drawStreaming(container, state);
      return;
    }

    state.timer = window.setTimeout(() => {
      state.timer = 0;
      drawStreaming(container, state);
    }, wait);
  }

  function drawStreaming(container, state) {
    state.lastDraw = Date.now();

    const parts = extractStreamingParts(state.text);
    const key = streamingKey(parts);
    if (key === state.key && container.childNodes.length > 0) return;
    state.key = key;

    const fragment = document.createDocumentFragment();
    parts.forEach((part) => {
      if (part.type === 'text') {
        fragment.appendChild(document.createTextNode(part.value));
        return;
      }
      fragment.appendChild(createMathNode(part.segment));
    });

    // 等待动画节点不属于 Markdown 内容，只替换正文并保留它，避免动画重启动。
    container.classList.add('markdown', 'markdown--plain');
    replaceStreamingChildren(container, fragment);
    scheduleFitDisplayMath(container);
  }

  function createMathNode(segment) {
    const wrapper = document.createElement('span');
    wrapper.className = segment.display ? 'math-display' : 'math-inline';
    wrapper.setAttribute('title', segment.latex);

    // 流式渲染会反复重建同一个公式，缓存 KaTeX 的 HTML 结果。
    const markup = katexMarkup(segment);
    if (markup != null) {
      wrapper.innerHTML = markup;
      return wrapper;
    }

    if (window.katex) {
      wrapper.classList.add('math-error');
    }

    wrapper.textContent = segment.display ? `$$${segment.latex}$$` : `$${segment.latex}$`;
    return wrapper;
  }

  function renderMathTokens(root, segments) {
    if (!root || !segments.length) return;

    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    const textNodes = [];
    let node = walker.nextNode();

    while (node) {
      if (node.nodeValue && node.nodeValue.indexOf(MATH_TOKEN_START) !== -1) {
        const parent = node.parentElement;
        if (!parent || !parent.closest('code, pre')) {
          textNodes.push(node);
        }
      }
      node = walker.nextNode();
    }

    textNodes.forEach((textNode) => {
      const parts = textNode.nodeValue.split(MATH_TOKEN_SPLIT_PATTERN);
      if (parts.length === 1) return;

      const fragment = document.createDocumentFragment();
      parts.forEach((part, partIndex) => {
        if (partIndex % 2 === 0) {
          if (part) fragment.appendChild(document.createTextNode(part));
          return;
        }

        const segment = segments[Number(part.slice(1, -1))];
        if (!segment) {
          fragment.appendChild(document.createTextNode(part));
          return;
        }
        fragment.appendChild(createMathNode(segment));
      });

      if (textNode.parentNode) {
        textNode.parentNode.replaceChild(fragment, textNode);
      }
    });
  }

  window.GeometryMarkdown = {
    render: renderMarkdown,
    renderStreaming,
    stopStreaming,
    scheduleFit: scheduleFitDisplayMath
  };
})();
