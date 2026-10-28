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

  function renderMarkdown(text, container) {
    if (!container) return;
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

    const isStandalone = (start, end) => {
      const lineStart = source.lastIndexOf('\n', start - 1) + 1;
      const lineEnd = source.indexOf('\n', end);
      const before = source.slice(lineStart, start);
      const after = source.slice(end, lineEnd === -1 ? source.length : lineEnd);
      return /^\s*$/.test(before) && /^\s*$/.test(after);
    };

    const findClosing = (needle, from) => {
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
    };

    const findInlineDollar = (from) => {
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
        const close = findClosing('\\]', index + 2);
        if (close !== -1) {
          addMath(source.slice(index + 2, close), true, isStandalone(index, close + 2));
          index = close + 2;
          continue;
        }
      }

      if (source.startsWith('\\(', index)) {
        const close = findClosing('\\)', index + 2);
        if (close !== -1) {
          addMath(source.slice(index + 2, close), false);
          index = close + 2;
          continue;
        }
      }

      if (source.startsWith('$$', index)) {
        const close = findClosing('$$', index + 2);
        if (close !== -1) {
          addMath(source.slice(index + 2, close), true, isStandalone(index, close + 2));
          index = close + 2;
        } else {
          output += '$$';
          index += 2;
        }
        continue;
      }

      if (source[index] === '$') {
        const close = findInlineDollar(index + 1);
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
          const close = findClosing(endToken, index + environment[0].length);
          if (close !== -1) {
            addMath(source.slice(index, close + endToken.length), true,
              isStandalone(index, close + endToken.length));
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

  function createMathNode(segment) {
    const wrapper = document.createElement('span');
    wrapper.className = segment.display ? 'math-display' : 'math-inline';
    wrapper.setAttribute('title', segment.latex);

    if (window.katex && typeof window.katex.render === 'function') {
      try {
        window.katex.render(segment.latex, wrapper, {
          displayMode: segment.display,
          throwOnError: false,
          strict: 'ignore',
          trust: false
        });
        return wrapper;
      } catch (error) {
        wrapper.classList.add('math-error');
      }
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
    scheduleFit: scheduleFitDisplayMath
  };
})();
