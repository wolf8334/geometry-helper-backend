(() => {
  'use strict';

  const API_HISTORY = new URL('api/history', document.baseURI).toString();
  const DEFAULT_PAGE_SIZE = 10;
  const ALLOWED_PAGE_SIZES = new Set([10, 20, 50]);

  const $ = (id) => document.getElementById(id);
  const dom = {
    filterForm: $('filterForm'),
    fromDate: $('fromDate'),
    toDate: $('toDate'),
    keyword: $('keyword'),
    followupFilter: $('followupFilter'),
    pageSize: $('pageSize'),
    searchButton: $('searchButton'),
    resetButton: $('resetButton'),
    historySummary: $('historySummary'),
    historyAlert: $('historyAlert'),
    historyList: $('historyList'),
    historyEmpty: $('historyEmpty'),
    historyLoading: $('historyLoading'),
    historyLoadingText: $('historyLoadingText'),
    historyPager: $('historyPager'),
    prevPageButton: $('prevPageButton'),
    nextPageButton: $('nextPageButton'),
    pageIndicator: $('pageIndicator'),
    imageDialog: $('historyImageDialog'),
    closeImageButton: $('closeHistoryImageButton'),
    dialogImage: $('historyDialogImage')
  };

  const state = {
    page: 1,
    size: DEFAULT_PAGE_SIZE,
    total: 0,
    items: [],
    loading: false,
    requestToken: 0,
    retryTimer: null,
    previewTrigger: null
  };

  function init() {
    readQueryState();
    bindEvents();
    loadHistory();
  }

  function bindEvents() {
    dom.filterForm.addEventListener('submit', (event) => {
      event.preventDefault();
      state.page = 1;
      loadHistory();
    });

    dom.filterForm.addEventListener('reset', () => {
      window.setTimeout(() => {
        state.page = 1;
        loadHistory();
      }, 0);
    });

    dom.pageSize.addEventListener('change', () => {
      state.page = 1;
      loadHistory();
    });

    dom.prevPageButton.addEventListener('click', () => {
      if (state.page <= 1 || state.loading) return;
      state.page -= 1;
      loadHistory();
    });

    dom.nextPageButton.addEventListener('click', () => {
      if (state.loading || state.page >= totalPages()) return;
      state.page += 1;
      loadHistory();
    });

    dom.closeImageButton.addEventListener('click', closeImagePreview);
    dom.imageDialog.addEventListener('click', (event) => {
      if (event.target === dom.imageDialog) {
        closeImagePreview();
        return;
      }

      if (
        event.target instanceof Element
        && (
          event.target.classList.contains('preview-dialog__canvas')
          || event.target.classList.contains('preview-dialog__inner')
        )
      ) {
        closeImagePreview();
      }
    });
    dom.imageDialog.addEventListener('close', restoreImagePreviewFocus);
  }

  function readQueryState() {
    const params = new URLSearchParams(window.location.search);
    dom.fromDate.value = params.get('from') || '';
    dom.toDate.value = params.get('to') || '';
    dom.keyword.value = params.get('keyword') || '';
    dom.followupFilter.value = params.get('followup') || '';

    const parsedSize = Number(params.get('size'));
    state.size = ALLOWED_PAGE_SIZES.has(parsedSize) ? parsedSize : DEFAULT_PAGE_SIZE;
    dom.pageSize.value = String(state.size);

    const parsedPage = Number(params.get('page'));
    state.page = Number.isInteger(parsedPage) && parsedPage > 0 ? parsedPage : 1;
  }

  async function loadHistory() {
    clearHistoryRetry();
    const token = state.requestToken + 1;
    state.requestToken = token;
    state.loading = true;
    dom.historyLoadingText.textContent = '正在加载历史记录…';
    setLoading(true);
    clearError();

    const params = buildQueryParams();
    const url = `${API_HISTORY}?${params.toString()}`;
    window.history.replaceState(null, '', `${window.location.pathname}?${params.toString()}`);
    let transientFailure = false;

    try {
      const response = await window.GeometryNet.fetchWithRetry(url, {
        headers: { Accept: 'application/json' },
        cache: 'no-store'
      }, {
        maxAttempts: 4,
        onRetry: ({ attempt }) => {
          if (token !== state.requestToken) return;
          dom.historyLoadingText.textContent = navigator.onLine
            ? `网络有点波动，正在第 ${attempt} 次自动重试…`
            : '网络已断开，联网后会自动重试…';
        }
      });
      const data = await readJson(response);

      if (token !== state.requestToken) return;
      if (!response.ok) {
        if (window.GeometryNet.isRetryableStatus(response.status)) {
          transientFailure = true;
          return;
        }
        showError(pickErrorMessage(response.status, data));
        return;
      }

      state.items = data && Array.isArray(data.items) ? data.items : [];
      state.total = data && Number.isFinite(data.total) ? data.total : 0;
      state.page = data && Number.isInteger(data.page) ? data.page : state.page;
      state.size = data && Number.isInteger(data.size) ? data.size : state.size;
      dom.pageSize.value = String(state.size);
      renderHistory();
    } catch (error) {
      if (token === state.requestToken) {
        transientFailure = true;
      }
    } finally {
      if (token === state.requestToken) {
        state.loading = false;
        setLoading(false);
        if (transientFailure) {
          showRetryNotice();
          scheduleHistoryRetry(token);
        } else if (dom.historyAlert.hidden) {
          updateSummary();
        }
      }
    }
  }

  function clearHistoryRetry() {
    if (state.retryTimer) {
      window.clearTimeout(state.retryTimer);
      state.retryTimer = null;
    }
  }

  function scheduleHistoryRetry(token) {
    clearHistoryRetry();
    state.retryTimer = window.setTimeout(() => {
      state.retryTimer = null;
      if (token !== state.requestToken || state.loading) return;
      loadHistory();
    }, 12000);
  }

  function showRetryNotice() {
    const message = navigator.onLine
      ? '网络有点慢，正在等待自动重试…'
      : '网络已断开，联网后会自动重试…';
    dom.historySummary.textContent = message;
    dom.historyLoadingText.textContent = message;
    dom.historyLoading.hidden = false;
    dom.historyList.hidden = true;
    dom.historyEmpty.hidden = true;
    dom.historyPager.hidden = true;
  }

  function buildQueryParams() {
    const params = new URLSearchParams();
    const from = dom.fromDate.value;
    const to = dom.toDate.value;
    const keyword = dom.keyword.value.trim();
    const followup = dom.followupFilter.value;
    const size = Number(dom.pageSize.value);

    if (from) params.set('from', from);
    if (to) params.set('to', to);
    if (keyword) params.set('keyword', keyword);
    if (followup === 'true' || followup === 'false') params.set('followup', followup);
    if (ALLOWED_PAGE_SIZES.has(size)) state.size = size;
    params.set('page', String(state.page));
    params.set('size', String(state.size));
    return params;
  }

  function renderHistory() {
    dom.historyList.replaceChildren();
    dom.historyAlert.hidden = true;

    if (!state.items.length) {
      dom.historyList.hidden = true;
      dom.historyEmpty.hidden = false;
      dom.historyPager.hidden = true;
      updateSummary();
      return;
    }

    dom.historyEmpty.hidden = true;
    dom.historyList.hidden = false;

    const fragment = document.createDocumentFragment();
    state.items.forEach((question) => {
      fragment.appendChild(createQuestionCard(question));
    });
    dom.historyList.appendChild(fragment);

    if (window.GeometryMarkdown) {
      window.GeometryMarkdown.scheduleFit(dom.historyList);
    }

    updatePager();
    updateSummary();
  }

  function createQuestionCard(question) {
    const card = document.createElement('article');
    card.className = 'card history-card';

    card.appendChild(createCardHead(question));
    const imageSection = createImageSection(question);
    if (imageSection) {
      card.appendChild(imageSection);
    }
    card.appendChild(createQuestionSection(question));
    card.appendChild(createPartsSection(question));
    card.appendChild(createFollowupSection(question));
    return card;
  }

  function createCardHead(question) {
    const head = document.createElement('header');
    head.className = 'history-card__head';

    const copy = document.createElement('div');
    const title = document.createElement('h2');
    title.className = 'history-card__title';
    title.textContent = `题目 #${question.id}`;

    const meta = document.createElement('p');
    meta.className = 'history-card__meta';
    const parts = [];

    const time = formatDate(question.createdAt);
    if (time) parts.push(time);
    if (question.hasImage) parts.push('已保存原图');
    if (Array.isArray(question.solutionParts) && question.solutionParts.length > 0) {
      parts.push(`已生成 ${question.solutionParts.length} 段`);
    }
    meta.textContent = parts.join(' · ');

    copy.append(title, meta);

    const badges = document.createElement('div');
    badges.className = 'history-card__badges';
    badges.appendChild(createChip(`用户 #${question.userId ?? 0}`));

    if (Number.isInteger(question.followupStep)) {
      badges.appendChild(createChip(`追问于第 ${question.followupStep} 段`, 'accent'));
    } else {
      badges.appendChild(createChip('暂无追问'));
    }

    head.append(copy, badges);
    return head;
  }

  function createImageSection(question) {
    if (!question.hasImage) return null;

    const section = document.createElement('section');
    section.className = 'history-section';

    const title = document.createElement('h3');
    title.className = 'history-section__title';
    title.textContent = '题目原图';

    const imageUrl = `${API_HISTORY}/${encodeURIComponent(String(question.id))}/image`;
    const previewButton = document.createElement('button');
    previewButton.type = 'button';
    previewButton.className = 'history-image';
    previewButton.setAttribute('aria-label', '查看题目原图大图');
    previewButton.addEventListener('click', () => {
      openImagePreview(imageUrl, previewButton);
    });

    const image = document.createElement('img');
    image.src = imageUrl;
    image.alt = '题目原图';
    image.loading = 'lazy';
    image.decoding = 'async';
    image.addEventListener('error', () => {
      section.hidden = true;
    });

    previewButton.appendChild(image);
    section.append(title, previewButton);
    return section;
  }

  function openImagePreview(url, trigger) {
    if (!url || dom.imageDialog.open) return;

    state.previewTrigger = trigger || document.activeElement;
    dom.dialogImage.src = url;
    document.body.classList.add('dialog-open');

    if (typeof dom.imageDialog.showModal === 'function') {
      dom.imageDialog.showModal();
    } else {
      dom.imageDialog.setAttribute('open', '');
    }

    dom.closeImageButton.focus();
  }

  function closeImagePreview() {
    if (dom.imageDialog.open) {
      dom.imageDialog.close();
      return;
    }

    dom.imageDialog.removeAttribute('open');
    restoreImagePreviewFocus();
  }

  function restoreImagePreviewFocus() {
    document.body.classList.remove('dialog-open');
    dom.dialogImage.removeAttribute('src');

    if (state.previewTrigger && typeof state.previewTrigger.focus === 'function') {
      state.previewTrigger.focus();
    }

    state.previewTrigger = null;
  }

  function createQuestionSection(question) {
    const section = document.createElement('section');
    section.className = 'history-section';

    const title = document.createElement('h3');
    title.className = 'history-section__title';
    title.textContent = '题目复述';

    const body = document.createElement('div');
    body.className = 'markdown history-question';
    renderMarkdown(question.questionText || '（未保存题目复述）', body);

    section.append(title, body);
    return section;
  }

  function createPartsSection(question) {
    const section = document.createElement('section');
    section.className = 'history-section';

    const title = document.createElement('h3');
    title.className = 'history-section__title';
    title.textContent = '分步讲解';
    section.appendChild(title);

    const parts = Array.isArray(question.solutionParts) ? question.solutionParts : [];
    if (!parts.length) {
      section.appendChild(createMutedText('暂无讲解记录。'));
      return section;
    }

    const list = document.createElement('div');
    list.className = 'history-parts';

    parts.forEach((part, index) => {
      const details = document.createElement('details');
      details.className = 'history-part';
      details.open = index === 0;

      const summary = document.createElement('summary');
      summary.className = 'history-part__summary';
      summary.textContent = partLabel(part.part);

      const body = document.createElement('div');
      body.className = 'markdown history-part__body';
      renderMarkdown(part.content || '', body);

      details.addEventListener('toggle', () => {
        if (details.open && window.GeometryMarkdown) {
          window.GeometryMarkdown.scheduleFit(details);
        }
      });

      details.append(summary, body);
      list.appendChild(details);
    });

    section.appendChild(list);
    return section;
  }

  function createFollowupSection(question) {
    const section = document.createElement('section');
    section.className = 'history-section';

    const title = document.createElement('h3');
    title.className = 'history-section__title';
    title.textContent = Number.isInteger(question.followupStep)
      ? `追问记录 · 第 ${question.followupStep} 段`
      : '追问记录';
    section.appendChild(title);

    const followups = Array.isArray(question.followups) ? question.followups : [];
    if (!followups.length) {
      section.appendChild(createMutedText('暂无追问。'));
      return section;
    }

    const list = document.createElement('div');
    list.className = 'history-followups';

    followups.forEach((message) => {
      const item = document.createElement('article');
      const isAssistant = message.role === 'assistant';
      item.className = `history-followup history-followup--${isAssistant ? 'assistant' : 'user'}`;

      const meta = document.createElement('div');
      meta.className = 'history-followup__meta';
      const step = Number.isInteger(message.stepNo) ? ` · 第 ${message.stepNo} 段` : '';
      const time = formatDate(message.createdAt);
      meta.textContent = `${isAssistant ? '老师' : '我'}${step}${time ? ` · ${time}` : ''}`;

      const bubble = document.createElement('div');
      bubble.className = 'history-followup__bubble';
      if (isAssistant) {
        bubble.classList.add('markdown');
        renderMarkdown(message.content || '', bubble);
      } else {
        bubble.textContent = message.content || '';
      }

      item.append(meta, bubble);
      list.appendChild(item);
    });

    section.appendChild(list);
    return section;
  }

  function createChip(text, modifier = '') {
    const chip = document.createElement('span');
    chip.className = modifier ? `history-chip history-chip--${modifier}` : 'history-chip';
    chip.textContent = text;
    return chip;
  }

  function createMutedText(text) {
    const element = document.createElement('p');
    element.className = 'history-muted';
    element.textContent = text;
    return element;
  }

  function renderMarkdown(text, container) {
    if (window.GeometryMarkdown) {
      window.GeometryMarkdown.render(text, container);
      return;
    }
    container.textContent = text;
  }

  function partLabel(part) {
    const labels = ['第一段', '第二段', '第三段'];
    return labels[part - 1] || `第 ${part} 段`;
  }

  function updateSummary() {
    if (state.loading) {
      dom.historySummary.textContent = '正在加载历史记录…';
      return;
    }

    if (state.total === 0) {
      dom.historySummary.textContent = '没有符合条件的记录';
      return;
    }

    const total = totalPages();
    dom.historySummary.textContent = `共 ${state.total} 道题 · 第 ${state.page} / ${total} 页`;
  }

  function updatePager() {
    const total = totalPages();
    dom.historyPager.hidden = total <= 1;
    dom.prevPageButton.disabled = state.page <= 1;
    dom.nextPageButton.disabled = state.page >= total;
    dom.pageIndicator.textContent = `第 ${state.page} / ${total} 页`;
  }

  function totalPages() {
    return Math.max(1, Math.ceil(state.total / Math.max(1, state.size)));
  }

  function setLoading(loading) {
    dom.historyLoading.hidden = !loading;
    dom.searchButton.disabled = loading;
    dom.prevPageButton.disabled = loading || state.page <= 1;
    dom.nextPageButton.disabled = loading || state.page >= totalPages();

    if (loading) {
      dom.historyList.hidden = true;
      dom.historyEmpty.hidden = true;
      dom.historyPager.hidden = true;
    }
  }

  function showError(message) {
    dom.historyAlert.textContent = message;
    dom.historyAlert.hidden = false;
    dom.historyList.hidden = true;
    dom.historyEmpty.hidden = true;
    dom.historyPager.hidden = true;
    dom.historySummary.textContent = '加载失败';
  }

  function clearError() {
    dom.historyAlert.textContent = '';
    dom.historyAlert.hidden = true;
  }

  function pickErrorMessage(status, data) {
    const apiMessage = data && typeof data.msg === 'string' ? data.msg.trim() : '';
    if (apiMessage) return apiMessage;

    switch (status) {
      case 400:
        return '查询条件有误，请检查日期范围后重试。';
      case 404:
        return '没有找到对应的历史记录。';
      default:
        return '加载历史记录失败，请稍后重试。';
    }
  }

  async function readJson(response) {
    const text = await response.text();
    if (!text) return null;

    try {
      return JSON.parse(text);
    } catch (error) {
      return null;
    }
  }

  function formatDate(value) {
    if (!value) return '';

    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '';

    return new Intl.DateTimeFormat('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false
    }).format(date);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init, { once: true });
  } else {
    init();
  }
})();
