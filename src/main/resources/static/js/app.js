(() => {
  'use strict';

  const MAX_FILE_SIZE = 10 * 1024 * 1024;
  const ALLOWED_TYPES = new Set([
    'image/jpeg',
    'image/jpg',
    'image/png',
    'image/webp',
    'image/gif'
  ]);
  const ALLOWED_EXTENSIONS = /\.(jpe?g|png|webp|gif)$/i;
  const API_PING = new URL('api/ping', document.baseURI).toString();
  const API_SOLVE = new URL('api/solve', document.baseURI).toString();
  const API_HISTORY = new URL('api/history', document.baseURI).toString();
  const API_FOLLOWUP = new URL('api/followup', document.baseURI).toString();
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

  // Math is extracted into private-use placeholder tokens before Markdown is
  // parsed. KaTeX renders those tokens after DOMPurify has sanitized the HTML,
  // because KaTeX relies on inline styles that DOMPurify would otherwise strip.
  const MATH_TOKEN_START = '\uE000';
  const MATH_TOKEN_END = '\uE001';
  const MATH_TOKEN_SPLIT_PATTERN = /(\uE000\d+\uE001)/;

  const $ = (id) => document.getElementById(id);
  const dom = {
    offlineBar: $('offlineBar'),
    serviceStatus: $('serviceStatus'),
    serviceStatusText: $('serviceStatusText'),
    serviceStatusTime: $('serviceStatusTime'),
    installButton: $('installButton'),
    uploadCard: $('uploadCard'),
    uploadStateBadge: $('uploadStateBadge'),
    inlineError: $('inlineError'),
    chooser: $('chooser'),
    cameraButton: $('cameraButton'),
    albumButton: $('albumButton'),
    cameraInput: $('cameraInput'),
    albumInput: $('albumInput'),
    previewPanel: $('previewPanel'),
    previewButton: $('previewButton'),
    previewImage: $('previewImage'),
    fileSize: $('fileSize'),
    userNote: $('userNote'),
    reselectButton: $('reselectButton'),
    uploadButton: $('uploadButton'),
    uploadButtonText: $('uploadButtonText'),
    nextQuestionButton: $('nextQuestionButton'),
    reselectInput: $('reselectInput'),
    uploadProgress: $('uploadProgress'),
    progressTitle: $('progressTitle'),
    progressHint: $('progressHint'),
    elapsedTime: $('elapsedTime'),
    confirmSection: $('confirmSection'),
    confirmTitle: $('confirmTitle'),
    confirmBadge: $('confirmBadge'),
    confirmMeta: $('confirmMeta'),
    confirmAlert: $('confirmAlert'),
    confirmMarkdown: $('confirmMarkdown'),
    confirmActions: $('confirmActions'),
    startSolveButton: $('startSolveButton'),
    retryAnalysisButton: $('retryAnalysisButton'),
    confirmReselectButton: $('confirmReselectButton'),
    resultSection: $('resultSection'),
    resultTitle: $('resultTitle'),
    resultBadge: $('resultBadge'),
    resultMeta: $('resultMeta'),
    resultAlert: $('resultAlert'),
    markdownResult: $('markdownResult'),
    emptyResult: $('emptyResult'),
    errorResult: $('errorResult'),
    resultActions: $('resultActions'),
    continueSolveButton: $('continueSolveButton'),
    continueSolveText: $('continueSolveText'),
    retryButton: $('retryButton'),

    followupButton: $('followupButton'),
    followupCount: $('followupCount'),
    followupDialog: $('followupDialog'),
    closeFollowupButton: $('closeFollowupButton'),
    followupMeta: $('followupMeta'),
    followupStatus: $('followupStatus'),
    followupMessages: $('followupMessages'),
    followupEmpty: $('followupEmpty'),
    followupForm: $('followupForm'),
    followupInput: $('followupInput'),
    followupStepHint: $('followupStepHint'),
    followupSubmit: $('followupSubmit'),
    followupError: $('followupError'),

    previewDialog: $('previewDialog'),
    closePreviewButton: $('closePreviewButton'),
    dialogImage: $('dialogImage'),
    toast: $('toast')
  };

  const state = {
    file: null,
    objectUrl: null,
    imageLoaded: false,
    uploading: false,
    uploadStartedAt: 0,
    elapsedTimer: null,
    uploadOutcome: null,
    answerEmpty: false,
    analysis: '',
    solveParts: [],
    solvePart: 0,
    solveTotal: 3,
    solveDone: false,
    questionId: null,
    maxViewedPart: 0,
    followupMessages: [],
    followupLimit: 3,
    followupLoaded: false,
    followupLoading: false,
    followupSending: false,
    followupLoadToken: 0,
    followupSendToken: 0,
    followupRetryTimer: null,
    followupNoticeTimer: null,
    followupTrigger: null,
    retryCount: 0,
    loadToken: 0,
    installPrompt: null,
    previewTrigger: null,
    serviceAbort: null,
    serviceChecking: false,
    toastTimer: null
  };

  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  let mathFitFrame = 0;

  function init() {
    bindEvents();
    syncOnlineState();
    setUploadState('idle');
    setupInstallPrompt();
    registerServiceWorker();

    if (navigator.onLine) {
      checkService();
    }
  }

  function bindEvents() {
    dom.cameraButton.addEventListener('click', () => {
      if (state.uploading) return;
      triggerFileInput(dom.cameraInput);
    });

    dom.albumButton.addEventListener('click', () => {
      if (state.uploading) return;
      triggerFileInput(dom.albumInput);
    });

    dom.cameraInput.addEventListener('change', handleFileInputChange);
    dom.albumInput.addEventListener('change', handleFileInputChange);
    dom.reselectInput.addEventListener('change', handleFileInputChange);

    dom.reselectButton.addEventListener('click', triggerReselect);
    dom.confirmReselectButton.addEventListener('click', triggerReselect);
    dom.uploadButton.addEventListener('click', analyzeCurrent);
    dom.nextQuestionButton.addEventListener('click', () => {
      window.location.reload();
    });
    dom.retryButton.addEventListener('click', retryFromResult);
    dom.startSolveButton.addEventListener('click', startSolving);
    dom.continueSolveButton.addEventListener('click', startSolving);
    dom.retryAnalysisButton.addEventListener('click', retryAnalysis);

    dom.previewButton.addEventListener('click', openPreview);
    dom.closePreviewButton.addEventListener('click', closePreview);
    dom.previewDialog.addEventListener('click', (event) => {
      if (event.target === dom.previewDialog) {
        closePreview();
        return;
      }

      if (
        event.target instanceof Element
        && (
          event.target.classList.contains('preview-dialog__canvas')
          || event.target.classList.contains('preview-dialog__inner')
        )
      ) {
        closePreview();
      }
    });
    dom.previewDialog.addEventListener('close', restorePreviewFocus);

    dom.followupButton.addEventListener('click', openFollowup);
    dom.closeFollowupButton.addEventListener('click', closeFollowup);
    dom.followupDialog.addEventListener('click', (event) => {
      if (event.target === dom.followupDialog) {
        closeFollowup();
      }
    });
    dom.followupDialog.addEventListener('close', restoreFollowupFocus);
    dom.followupForm.addEventListener('submit', sendFollowup);
    dom.followupInput.addEventListener('keydown', (event) => {
      if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
        event.preventDefault();
        dom.followupForm.requestSubmit();
      }
    });

    bindDropZone();

    window.addEventListener('online', () => {
      syncOnlineState();
      showToast('网络已恢复');
      checkService();
    });

    window.addEventListener('offline', () => {
      syncOnlineState();
      showToast('网络已断开');
    });

    window.addEventListener('resize', () => {
      scheduleFitDisplayMath(dom.markdownResult);
      scheduleFitDisplayMath(dom.followupMessages);
    });

    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState !== 'visible') return;
      syncOnlineState();
      if (navigator.onLine) {
        checkService();
      }
    });
  }

  function bindDropZone() {
    const zone = dom.chooser;

    ['dragenter', 'dragover'].forEach((type) => {
      zone.addEventListener(type, (event) => {
        if (state.uploading) return;
        event.preventDefault();
        if (event.dataTransfer) {
          event.dataTransfer.dropEffect = 'copy';
        }
        zone.classList.add('is-dragging');
      });
    });

    ['dragleave', 'dragend'].forEach((type) => {
      zone.addEventListener(type, () => {
        zone.classList.remove('is-dragging');
      });
    });

    zone.addEventListener('drop', (event) => {
      event.preventDefault();
      zone.classList.remove('is-dragging');

      if (state.uploading) {
        showToast('正在识别中，请稍候');
        return;
      }

      const files = event.dataTransfer && event.dataTransfer.files;
      if (!files || files.length === 0) return;

      if (files.length > 1) {
        showToast('每次只能上传一张图片，已选择第一张');
      }

      handleFile(files[0]);
    });

    document.addEventListener('dragover', (event) => {
      event.preventDefault();
    });

    document.addEventListener('drop', (event) => {
      const target = event.target;
      if (!(target instanceof Element) || !target.closest('.chooser')) {
        event.preventDefault();
      }
    });
  }

  function handleFileInputChange(event) {
    const input = event.currentTarget;
    const file = input.files && input.files[0];
    input.value = '';

    if (file) {
      handleFile(file);
    }
  }

  function triggerFileInput(input) {
    if (state.uploading) return;
    input.value = '';
    input.click();
  }

  function triggerReselect() {
    if (state.uploading) return;
    triggerFileInput(dom.reselectInput);
  }

  function handleFile(file) {
    if (!file) return;

    if (state.uploading) {
      showToast('正在识别中，请稍候');
      return;
    }

    clearInlineError();
    const validationMessage = validateFile(file);

    if (validationMessage) {
      showInlineError(validationMessage);
      return;
    }

    selectFile(file);
  }

  function validateFile(file) {
    if (!file.size) {
      return '图片文件为空，请重新选择。';
    }

    if (file.size > MAX_FILE_SIZE) {
      return '图片不能超过 10MB，请压缩后重试。';
    }

    const type = (file.type || '').toLowerCase();
    const hasAllowedType = ALLOWED_TYPES.has(type);
    const hasAllowedExtension = ALLOWED_EXTENSIONS.test(file.name || '');

    if (!hasAllowedType && !(type === '' && hasAllowedExtension)) {
      return '仅支持 JPG、JPEG、PNG、WebP、GIF 图片。';
    }

    return '';
  }

  function selectFile(file) {
    if (state.objectUrl) {
      URL.revokeObjectURL(state.objectUrl);
    }

    state.file = file;
    state.objectUrl = URL.createObjectURL(file);
    state.imageLoaded = false;
    state.uploadOutcome = null;
    state.analysis = '';
    state.questionId = null;
    state.retryCount = 0;
    resetSolveState();
    state.loadToken += 1;
    const loadToken = state.loadToken;

    clearConfirmation();
    clearResult();
    clearInlineError();
    dom.userNote.disabled = false;
    dom.chooser.hidden = true;
    dom.previewPanel.hidden = false;
    dom.fileSize.textContent = formatBytes(file.size);
    dom.dialogImage.removeAttribute('src');

    dom.previewImage.onload = () => {
      if (loadToken !== state.loadToken) return;
      state.imageLoaded = true;
      clearInlineError();
      setUploadState('preview');
      updateUploadAvailability();
    };

    dom.previewImage.onerror = () => {
      if (loadToken !== state.loadToken) return;
      state.imageLoaded = false;
      setUploadState('image-error');
      showInlineError('图片加载失败，请重新选择。');
      updateUploadAvailability();
    };

    dom.previewImage.src = state.objectUrl;
    setUploadState('image-loading');
    updateUploadAvailability();

    window.setTimeout(() => {
      dom.previewButton.focus({ preventScroll: true });
    }, 0);
  }

  function setUploadState(name) {
    const badges = {
      'idle': ['等待选择', 'state-badge--idle'],
      'image-loading': ['读取中', 'state-badge--loading'],
      'preview': ['已选图', 'state-badge--ready'],
      'uploading': ['识别中', 'state-badge--loading'],
      'retrying': ['重新识别中', 'state-badge--loading'],
      'confirm': ['待核对', 'state-badge--loading'],
      'solving': ['讲解中', 'state-badge--loading'],
      'part': ['待继续', 'state-badge--ready'],
      'success': ['已完成', 'state-badge--success'],
      'error': ['处理失败', 'state-badge--error'],
      'image-error': ['图片异常', 'state-badge--error']
    };
    const badge = badges[name] || badges.idle;
    const busy = name === 'uploading' || name === 'retrying' || name === 'solving';
    const progress = {
      uploading: ['正在识别，请稍候…', '正在读取题目和补充说明，请不要关闭页面。'],
      retrying: ['正在重新识别，请稍候…', '正在重新阅读图片并生成新的题目复述。'],
      solving: ['请稍候…', '正在准备内容，请不要关闭页面。']
    }[name];

    dom.uploadStateBadge.textContent = badge[0];
    dom.uploadStateBadge.className = `state-badge ${badge[1]}`;
    dom.uploadCard.setAttribute('aria-busy', busy ? 'true' : 'false');
    dom.uploadProgress.hidden = !progress;
    if (progress) {
      dom.progressTitle.textContent = progress[0];
      dom.progressHint.textContent = progress[1];
    }
    dom.uploadButtonText.textContent = name === 'solving'
      ? '讲解中…'
      : (name === 'uploading' || name === 'retrying' ? '识别中…' : '上传图片');
    dom.reselectButton.disabled = busy;
    dom.confirmReselectButton.disabled = busy;
    updateUploadAvailability();
  }

  function updateUploadAvailability() {
    const online = navigator.onLine;
    const busy = state.uploading;
    const canAnalyze = Boolean(state.file && state.imageLoaded && !busy && online && !state.analysis);
    const canSolve = Boolean(
      state.file && state.imageLoaded && !busy && online
      && state.analysis && (state.uploadOutcome !== true || state.answerEmpty)
    );
    const canContinue = Boolean(
      state.file && state.imageLoaded && !busy && online
      && state.analysis && !state.solveDone
    );

    dom.uploadButton.disabled = !canAnalyze;
    dom.retryButton.disabled = !(canAnalyze || canSolve);
    dom.reselectButton.disabled = busy;
    dom.reselectButton.hidden = Boolean(state.analysis);
    dom.confirmReselectButton.disabled = busy;
    dom.startSolveButton.disabled = !canSolve;
    dom.continueSolveButton.disabled = !canContinue;
    dom.retryAnalysisButton.disabled = busy || state.retryCount >= 1
      || !state.analysis || state.uploadOutcome === true;
    dom.userNote.disabled = busy || Boolean(state.analysis);
    updateFollowupButton();

    if (!online) {
      dom.uploadButton.title = '当前网络不可用';
    } else if (!state.file) {
      dom.uploadButton.title = '请先选择图片';
    } else if (!state.imageLoaded) {
      dom.uploadButton.title = '图片尚未加载完成';
    } else if (state.analysis) {
      dom.uploadButton.title = '请先在下方核对题目复述';
    } else {
      dom.uploadButton.title = '';
    }
  }

  function syncOnlineState() {
    const online = navigator.onLine;
    dom.offlineBar.hidden = online;

    if (!online) {
      if (state.serviceAbort) {
        state.serviceAbort.abort();
      }
      state.serviceChecking = false;
      setServiceStatus('error', '服务不可用', '');
    }

    updateUploadAvailability();
  }

  async function checkService() {
    if (!navigator.onLine) {
      setServiceStatus('error', '服务不可用', '');
      return;
    }

    if (state.serviceChecking) return;

    state.serviceChecking = true;
    setServiceStatus('checking', '检查服务中…', '');

    const controller = new AbortController();
    state.serviceAbort = controller;
    const timer = window.setTimeout(() => controller.abort(), 8000);

    try {
      const response = await fetch(API_PING, {
        cache: 'no-store',
        headers: { Accept: 'application/json' },
        signal: controller.signal
      });

      if (!response.ok) {
        throw new Error('Ping failed');
      }

      const data = await response.json().catch(() => null);
      const message = data && typeof data.msg === 'string' && data.msg.trim()
        ? data.msg.trim()
        : '服务正常';
      const time = data && data.timestamp ? formatServerTime(data.timestamp) : '';

      setServiceStatus('ok', message, time);
    } catch (error) {
      setServiceStatus('error', '服务不可用', '');
    } finally {
      window.clearTimeout(timer);
      if (state.serviceAbort === controller) {
        state.serviceAbort = null;
      }
      state.serviceChecking = false;
    }
  }

  function setServiceStatus(kind, text, time) {
    dom.serviceStatus.className = `status-pill status-pill--${kind}`;
    dom.serviceStatusText.textContent = text;
    dom.serviceStatusTime.textContent = time ? `· ${time}` : '';
    dom.serviceStatusTime.hidden = !time;
    dom.serviceStatus.title = time ? `${text} · 服务时间 ${time}` : text;
  }

  function formatServerTime(value) {
    const match = String(value).match(/(\d{2}:\d{2}(?::\d{2})?)/);
    return match ? match[1] : '';
  }

  function ensureReady() {
    if (state.uploading) return false;

    if (!state.file) {
      showInlineError('请先拍照或选择图片。');
      return false;
    }

    if (!state.imageLoaded) {
      showInlineError('图片尚未加载完成，请稍候或重新选择。');
      return false;
    }

    if (!navigator.onLine) {
      showInlineError('当前网络不可用，请联网后重试。');
      return false;
    }

    clearInlineError();
    return true;
  }

  async function analyzeCurrent() {
    if (!ensureReady()) return;
    state.retryCount = 0;
    await runPhase('analyze');
  }

  async function retryAnalysis() {
    if (state.uploading) return;
    if (state.retryCount >= 1) {
      showConfirmAlert('已重新识别一次，仍不正确。请重新选择图片后重试。', 'error');
      updateUploadAvailability();
      return;
    }
    if (!ensureReady()) return;

    state.retryCount += 1;
    const ok = await runPhase('retry');
    if (!ok) {
      state.retryCount = Math.max(0, state.retryCount - 1);
      updateUploadAvailability();
    }
  }

  async function startSolving() {
    if (!state.analysis) {
      showConfirmAlert('请先核对题目复述。');
      return;
    }
    if (state.solveDone) {
      showToast('本题讲解已完成');
      return;
    }
    if (!ensureReady()) return;

    const part = state.solvePart + 1;
    const stage = part === 1 ? 'confirm' : 'continue';
    await runPhase(stage, { part });
  }

  function retryFromResult() {
    if (state.analysis) {
      startSolving();
    } else {
      analyzeCurrent();
    }
  }

  async function runPhase(stage, options = {}) {
    if (state.uploading) return false;

    const isSolve = stage === 'confirm' || stage === 'continue';
    const requestedPart = Number.isInteger(options.part)
      ? options.part
      : (stage === 'confirm' ? 1 : 0);
    const totalParts = Number.isInteger(state.solveTotal) ? state.solveTotal : 3;

    state.uploading = true;
    state.uploadOutcome = null;
    clearInlineError();

    if (isSolve) {
      if (requestedPart > 1 && state.solveParts.length > 0) {
        clearResultFeedback();
        renderSolveLoading(totalParts);
      } else {
        clearResult();
      }
    } else {
      // 重新识别时立刻清掉上一次的错误提示和旧结果。
      clearResult();
      hideConfirmAlert();
    }

    setUploadState(isSolve ? 'solving' : (stage === 'retry' ? 'retrying' : 'uploading'));
    startElapsedTimer();
    let transientFailure = false;

    try {
      const formData = new FormData();
      formData.append('file', state.file, state.file.name || 'geometry-image');
      formData.append('stage', stage);

      const note = dom.userNote.value.trim();
      if (note) {
        formData.append('message', note);
      }
      if (state.questionId && stage !== 'analyze') {
        formData.append('questionId', String(state.questionId));
      }
      if (isSolve) {
        formData.append('analysis', state.analysis);
        formData.append('part', String(requestedPart));
        const history = buildSolveHistory();
        if (history) {
          formData.append('history', history);
        }
      }

      const response = await window.GeometryNet.fetchWithRetry(API_SOLVE, {
        method: 'POST',
        body: formData,
        headers: { Accept: 'application/json' },
        cache: 'no-store'
      }, {
        maxAttempts: 3,
        onRetry: ({ attempt }) => showAutoRetryStatus(stage, attempt)
      });

      const data = await readJson(response);

      if (!response.ok) {
        if (window.GeometryNet.isRetryableStatus(response.status)) {
          transientFailure = true;
          handleTransientFailure(stage);
          return false;
        }
        handlePhaseFailure(pickErrorMessage(response.status, data), stage);
        return false;
      }

      if (data && Number.isInteger(data.questionId)) {
        state.questionId = data.questionId;
      }

      if (isSolve) {
        const answer = data && typeof data.answer === 'string' ? data.answer.trim() : '';
        if (!answer) {
          handlePhaseFailure('系统未返回本段讲解，请重试。', stage);
          return false;
        }

        const part = data && Number.isInteger(data.part) ? data.part : requestedPart;
        const total = data && Number.isInteger(data.totalParts) ? data.totalParts : totalParts;
        const done = Boolean(data && data.done) || part >= total;
        const message = data && typeof data.msg === 'string' && data.msg.trim()
          ? data.msg.trim()
          : (done ? '分步讲解已全部完成' : '已生成新内容，先想一想，再继续');

        storeSolvePart(part, answer);
        state.uploadOutcome = true;
        state.answerEmpty = false;
        renderSolvePart(message, part, total, done);
        return true;
      }

      const analysis = data && typeof data.analysis === 'string' ? data.analysis.trim() : '';
      if (!analysis) {
        handlePhaseFailure('系统未返回题目复述，请重试。', stage);
        return false;
      }

      const message = data && typeof data.msg === 'string' && data.msg.trim()
        ? data.msg.trim()
        : '题目复述完成，请核对';

      state.uploadOutcome = null;
      renderConfirmation(message, analysis, stage === 'retry');
      return true;
    } catch (error) {
      if (error && error.name === 'AbortError') {
        handlePhaseFailure(
          isSolve ? '讲解生成超时，请稍后重试。' : '识别超时，请稍后重试。',
          stage
        );
        return false;
      }

      transientFailure = true;
      handleTransientFailure(stage);
      return false;
    } finally {
      state.uploading = false;
      stopElapsedTimer();

      if (isSolve) {
        if (state.uploadOutcome === true) {
          setUploadState(state.solveDone ? 'success' : 'part');
        } else if (transientFailure) {
          setUploadState(state.solveParts.some(Boolean) ? 'part' : 'confirm');
        } else {
          setUploadState('error');
        }
      } else if (state.analysis) {
        setUploadState('confirm');
      } else if (transientFailure) {
        setUploadState('preview');
      } else if (state.uploadOutcome === false) {
        setUploadState('error');
      } else {
        setUploadState('preview');
      }
      updateUploadAvailability();
    }
  }

  function handlePhaseFailure(message, stage) {
    state.uploadOutcome = false;

    if (stage === 'retry' && state.analysis) {
      showConfirmAlert(message);
      scrollToElement(dom.confirmSection);
      return;
    }

    if (stage === 'confirm' || stage === 'continue') {
      renderError(message, '讲解失败', state.solveParts.length > 0);
    } else {
      renderError(message, '识别失败');
    }
  }

  function showAutoRetryStatus(stage, attempt) {
    const offline = !navigator.onLine;
    const message = offline
      ? '网络已断开，联网后会自动继续…'
      : `网络有点波动，正在第 ${attempt} 次自动重试…`;

    dom.progressTitle.textContent = message;
    dom.progressHint.textContent = offline
      ? '恢复网络后会自动重新发起请求，请不要关闭页面。'
      : '请求没有成功时会自动重试，请不要关闭页面。';

    if (
      (stage === 'confirm' || stage === 'continue')
      && !dom.resultSection.hidden
    ) {
      dom.resultAlert.hidden = false;
      dom.resultAlert.className = 'result-alert result-alert--info';
      dom.resultAlert.textContent = message;
    }
  }

  function handleTransientFailure(stage) {
    state.uploadOutcome = null;
    state.answerEmpty = false;

    if (stage === 'confirm' || stage === 'continue') {
      if (state.solveParts.some(Boolean)) {
        restoreSolveResult();
      } else {
        clearResult();
      }
    } else {
      clearResult();
    }

    showToast(navigator.onLine ? '网络有点慢，稍后再试一次' : '网络已断开，联网后再试');
  }

  function restoreSolveResult() {
    dom.resultSection.hidden = false;
    dom.resultSection.removeAttribute('aria-busy');
    dom.resultTitle.textContent = state.solveDone ? '讲解完成' : '分步讲解';
    dom.resultBadge.textContent = state.solveDone
      ? '完成'
      : `第 ${state.solvePart}/${state.solveTotal} 段`;
    dom.resultBadge.className = 'state-badge state-badge--success';
    dom.resultMeta.textContent = state.solveDone
      ? `讲解完成 · ${formatNow()}`
      : `第 ${state.solvePart} / ${state.solveTotal} 段 · ${formatNow()}`;
    dom.resultAlert.hidden = true;
    dom.resultAlert.textContent = '';
    dom.resultAlert.className = 'result-alert';
    dom.emptyResult.hidden = true;
    dom.errorResult.hidden = true;
    renderSolveParts({ openOnlyPart: state.solvePart });
    dom.resultActions.hidden = false;
    dom.continueSolveButton.hidden = state.solveDone;
    dom.continueSolveButton.disabled = false;
    dom.continueSolveText.textContent = '继续下一段';
    dom.retryButton.hidden = true;
    updateUploadAvailability();
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

  function pickErrorMessage(status, data) {
    const apiMessage = data && typeof data.msg === 'string' ? data.msg.trim() : '';
    if (apiMessage) return apiMessage;

    switch (status) {
      case 400:
        return '请求有误，请重新选择图片后重试。';
      case 413:
        return '图片不能超过 10MB，请压缩后重试。';
      case 415:
        return '图片格式不支持，请选择 JPG、PNG、WebP 或 GIF 图片。';
      case 422:
        return '图片无法识别，请重新选择一张清晰的图片。';
      case 502:
      case 503:
        return '服务暂时不可用，请稍后重试。';
      case 504:
        return '识别超时，请稍后重试。';
      default:
        return status >= 500
          ? '服务暂时不可用，请稍后重试。'
          : '上传失败，请检查网络后重试。';
    }
  }

  function renderConfirmation(message, analysis, isRetry) {
    state.analysis = analysis;
    state.uploadOutcome = null;
    state.answerEmpty = false;
    resetSolveState();

    dom.confirmSection.hidden = false;
    dom.confirmTitle.textContent = isRetry ? '请再次核对题目复述' : '题目复述';
    dom.confirmBadge.textContent = isRetry ? '已重新识别' : '待核对';
    dom.confirmBadge.className = 'state-badge state-badge--loading';
    dom.confirmMeta.textContent = `${isRetry ? '重新识别' : '识别'}完成 · ${formatNow()}`;
    dom.confirmMarkdown.hidden = false;
    dom.confirmActions.hidden = false;
    renderMarkdown(analysis, dom.confirmMarkdown);

    if (isRetry) {
      showConfirmAlert('已重新识别一次，请再次核对；如仍不正确，请重新选择图片。', 'info');
    } else {
      hideConfirmAlert();
    }

    updateUploadAvailability();
    scrollToElement(dom.confirmSection);
  }

  function clearConfirmation() {
    dom.confirmSection.hidden = true;
    dom.confirmTitle.textContent = '题目复述';
    dom.confirmBadge.textContent = '待核对';
    dom.confirmBadge.className = 'state-badge state-badge--loading';
    dom.confirmMeta.textContent = '';
    dom.confirmMarkdown.hidden = true;
    dom.confirmMarkdown.innerHTML = '';
    dom.confirmMarkdown.classList.remove('markdown--plain');
    dom.confirmActions.hidden = false;
    hideConfirmAlert();
  }

  function showConfirmAlert(message, type = 'info') {
    dom.confirmAlert.hidden = false;
    dom.confirmAlert.className = `result-alert result-alert--${type}`;
    dom.confirmAlert.textContent = message;
  }

  function hideConfirmAlert() {
    dom.confirmAlert.hidden = true;
    dom.confirmAlert.textContent = '';
    dom.confirmAlert.className = 'result-alert';
  }

  function resetSolveState() {
    if (dom.followupDialog && dom.followupDialog.open) {
      closeFollowup();
    }
    state.solveParts = [];
    state.solvePart = 0;
    state.solveTotal = 3;
    state.solveDone = false;
    state.maxViewedPart = 0;
    state.followupMessages = [];
    state.followupLimit = 3;
    state.followupLoaded = false;
    state.followupLoading = false;
    state.followupSending = false;
    state.followupLoadToken += 1;
    state.followupSendToken += 1;
    updateFollowupFormState();
    setFollowupStatus('');
    clearFollowupError();
    updateFollowupButton();
  }

  function buildSolveHistory() {
    return state.solveParts
      .map((answer, index) => ({ answer, index }))
      .filter((item) => Boolean(item.answer))
      .map((item) => `【第 ${item.index + 1} 段】\n${item.answer}`)
      .join('\n\n');
  }

  function storeSolvePart(part, answer) {
    const index = Math.max(0, part - 1);
    state.solveParts[index] = answer;
    state.solveParts.length = Math.max(state.solveParts.length, part);
    state.solvePart = Math.max(state.solvePart, part);
    state.maxViewedPart = Math.max(state.maxViewedPart, part);
  }

  function solvePartLabel(part) {
    const labels = ['第一段', '第二段', '第三段'];
    return labels[part - 1] || `第 ${part} 段`;
  }

  function renderSolveParts(options = {}) {
    const parts = state.solveParts
      .map((answer, index) => ({ answer, index, part: index + 1 }))
      .filter((item) => Boolean(item.answer));

    if (!parts.length) {
      dom.markdownResult.hidden = true;
      dom.markdownResult.replaceChildren();
      return;
    }

    const openOnlyPart = Number.isInteger(options.openOnlyPart)
      ? options.openOnlyPart
      : 0;

    // 普通重绘保留用户手动收起/展开的状态；继续生成时只展开最新一段。
    const previousParts = new Set();
    const openParts = new Set();
    dom.markdownResult.querySelectorAll('.solve-part').forEach((node) => {
      const part = Number(node.dataset.part);
      if (!Number.isInteger(part)) return;
      previousParts.add(part);
      if (node.open) openParts.add(part);
    });

    const fragment = document.createDocumentFragment();
    parts.forEach(({ answer, part }) => {
      const details = document.createElement('details');
      details.className = 'solve-part';
      details.dataset.part = String(part);
      details.open = openOnlyPart
        ? part === openOnlyPart
        : (!previousParts.has(part) || openParts.has(part));
      if (details.open) {
        state.maxViewedPart = Math.max(state.maxViewedPart, part);
      }

      const summary = document.createElement('summary');
      summary.className = 'solve-part__summary';
      summary.title = '点击展开或收起';

      const title = document.createElement('span');
      title.className = 'solve-part__title';
      title.textContent = solvePartLabel(part);

      const stateHint = document.createElement('span');
      stateHint.className = 'solve-part__state';
      stateHint.setAttribute('aria-hidden', 'true');

      const body = document.createElement('div');
      body.className = 'markdown solve-part__body';

      summary.append(title, stateHint);
      details.append(summary, body);
      details.addEventListener('toggle', () => {
        if (details.open) {
          state.maxViewedPart = Math.max(state.maxViewedPart, part);
          updateFollowupButton();
          updateFollowupStepHint();
          scheduleFitDisplayMath(details);
        }
      });

      fragment.appendChild(details);
      renderMarkdown(answer, body);
    });

    dom.markdownResult.replaceChildren(fragment);
    dom.markdownResult.hidden = false;
    updateFollowupButton();
    scheduleFitDisplayMath(dom.markdownResult);
  }

  function renderSolveLoading(total) {
    state.solveTotal = total;
    dom.resultSection.hidden = false;
    dom.resultSection.setAttribute('aria-busy', 'true');
    dom.resultTitle.textContent = '分步讲解';
    dom.resultBadge.textContent = '请稍候';
    dom.resultBadge.className = 'state-badge state-badge--loading';
    dom.resultMeta.textContent = '';

    dom.resultAlert.hidden = false;
    dom.resultAlert.className = 'result-alert result-alert--info';
    dom.resultAlert.textContent = '请稍候…';

    dom.emptyResult.hidden = true;
    dom.errorResult.hidden = true;
    renderSolveParts({ openOnlyPart: state.solvePart });

    dom.resultActions.hidden = false;
    dom.continueSolveButton.hidden = false;
    dom.continueSolveButton.disabled = true;
    dom.continueSolveText.textContent = '请稍候…';
    dom.retryButton.hidden = true;

    scrollToElement(dom.resultSection);
  }

  function renderSolvePart(message, part, total, done) {
    state.uploadOutcome = true;
    state.answerEmpty = false;
    state.solvePart = part;
    state.solveTotal = total;
    state.solveDone = done;
    state.maxViewedPart = Math.max(state.maxViewedPart, part);

    dom.resultSection.hidden = false;
    dom.resultSection.removeAttribute('aria-busy');
    dom.resultTitle.textContent = done ? '讲解完成' : '分步讲解';
    dom.resultBadge.textContent = done ? '完成' : `第 ${part}/${total} 段`;
    dom.resultBadge.className = 'state-badge state-badge--success';
    dom.resultMeta.textContent = done
      ? `讲解完成 · ${formatNow()}`
      : `第 ${part} / ${total} 段 · ${formatNow()}`;

    dom.confirmTitle.textContent = '已确认的题目复述';
    dom.confirmBadge.textContent = '已确认';
    dom.confirmBadge.className = 'state-badge state-badge--success';
    dom.confirmActions.hidden = true;

    dom.resultAlert.hidden = false;
    dom.resultAlert.className = 'result-alert result-alert--success';
    dom.resultAlert.textContent = message;

    dom.emptyResult.hidden = true;
    dom.errorResult.hidden = true;
    renderSolveParts({ openOnlyPart: part });

    dom.resultActions.hidden = false;
    dom.continueSolveButton.hidden = done;
    dom.continueSolveButton.disabled = false;
    dom.continueSolveText.textContent = done ? '已完成' : '继续下一段';
    dom.retryButton.hidden = true;

    updateUploadAvailability();
    scrollToElement(dom.resultSection);
  }

  function renderError(message, title = '识别失败', preserveAnswer = false) {
    state.uploadOutcome = false;
    state.answerEmpty = false;
    dom.resultSection.hidden = false;
    dom.resultSection.removeAttribute('aria-busy');
    dom.resultTitle.textContent = title;
    dom.resultBadge.textContent = '失败';
    dom.resultBadge.className = 'state-badge state-badge--error';
    dom.resultMeta.textContent = `${title} · ${formatNow()}`;

    dom.resultAlert.hidden = false;
    dom.resultAlert.className = 'result-alert result-alert--error';
    dom.resultAlert.textContent = message;

    if (preserveAnswer) {
      renderSolveParts();
    } else {
      dom.markdownResult.hidden = true;
      dom.markdownResult.replaceChildren();
    }

    dom.emptyResult.hidden = true;
    dom.errorResult.hidden = false;
    dom.resultActions.hidden = false;
    dom.continueSolveButton.hidden = true;
    dom.continueSolveButton.disabled = false;
    dom.continueSolveText.textContent = '继续讲解';
    dom.retryButton.hidden = false;

    updateUploadAvailability();
    scrollToElement(dom.resultSection);
  }

  function clearResultFeedback() {
    dom.resultAlert.hidden = true;
    dom.resultAlert.textContent = '';
    dom.resultAlert.className = 'result-alert';
    dom.errorResult.hidden = true;
  }

  function clearResult() {
    dom.resultSection.hidden = true;
    dom.resultSection.removeAttribute('aria-busy');
    dom.resultTitle.textContent = '分步讲解';
    dom.resultBadge.textContent = '成功';
    dom.resultBadge.className = 'state-badge state-badge--success';
    dom.resultMeta.textContent = '';
    dom.resultAlert.hidden = true;
    dom.resultAlert.textContent = '';
    dom.resultAlert.className = 'result-alert';
    dom.markdownResult.hidden = true;
    dom.markdownResult.replaceChildren();
    dom.emptyResult.hidden = true;
    dom.errorResult.hidden = true;
    dom.resultActions.hidden = true;
    dom.continueSolveButton.hidden = true;
    dom.continueSolveButton.disabled = false;
    dom.continueSolveText.textContent = '继续讲解';
    dom.retryButton.hidden = false;
    state.uploadOutcome = null;
    state.answerEmpty = false;
  }

  function renderMarkdown(text, container = dom.markdownResult) {
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

  function scrollToElement(element) {
    if (!element) return;
    window.requestAnimationFrame(() => {
      element.scrollIntoView({
        behavior: reduceMotion.matches ? 'auto' : 'smooth',
        block: 'start'
      });
    });
  }

  function startElapsedTimer() {
    stopElapsedTimer();
    state.uploadStartedAt = Date.now();
    updateElapsed();
    state.elapsedTimer = window.setInterval(updateElapsed, 1000);
  }

  function updateElapsed() {
    const seconds = Math.max(0, Math.floor((Date.now() - state.uploadStartedAt) / 1000));
    const minutes = Math.floor(seconds / 60);
    const remainder = seconds % 60;
    dom.elapsedTime.textContent = `${String(minutes).padStart(2, '0')}:${String(remainder).padStart(2, '0')}`;
  }

  function stopElapsedTimer() {
    if (state.elapsedTimer) {
      window.clearInterval(state.elapsedTimer);
      state.elapsedTimer = null;
    }
    dom.elapsedTime.textContent = '00:00';
  }

  function formatNow() {
    return new Intl.DateTimeFormat('zh-CN', {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false
    }).format(new Date());
  }

  function formatBytes(bytes) {
    if (!Number.isFinite(bytes) || bytes <= 0) return '0 KB';
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }

  function showInlineError(message) {
    dom.inlineError.textContent = message;
    dom.inlineError.hidden = false;

    // When reselecting from the result area, bring the persistent error into view.
    if (!dom.resultSection.hidden) {
      dom.uploadCard.scrollIntoView({
        behavior: reduceMotion.matches ? 'auto' : 'smooth',
        block: 'center'
      });
    }
  }

  function clearInlineError() {
    dom.inlineError.textContent = '';
    dom.inlineError.hidden = true;
  }

  function showToast(message) {
    if (state.toastTimer) {
      window.clearTimeout(state.toastTimer);
    }

    dom.toast.textContent = message;
    dom.toast.hidden = false;
    state.toastTimer = window.setTimeout(() => {
      dom.toast.hidden = true;
      state.toastTimer = null;
    }, 2600);
  }

  function openPreview() {
    if (!state.objectUrl || dom.previewDialog.open) return;

    state.previewTrigger = document.activeElement;
    dom.dialogImage.src = state.objectUrl;
    document.body.classList.add('dialog-open');

    if (typeof dom.previewDialog.showModal === 'function') {
      dom.previewDialog.showModal();
    } else {
      dom.previewDialog.setAttribute('open', '');
    }

    dom.closePreviewButton.focus();
  }

  function closePreview() {
    if (dom.previewDialog.open) {
      dom.previewDialog.close();
      return;
    }

    dom.previewDialog.removeAttribute('open');
    restorePreviewFocus();
  }

  function restorePreviewFocus() {
    document.body.classList.remove('dialog-open');
    dom.dialogImage.removeAttribute('src');

    if (state.previewTrigger && typeof state.previewTrigger.focus === 'function') {
      state.previewTrigger.focus();
    }

    state.previewTrigger = null;
  }

  function updateFollowupButton() {
    if (!dom.followupButton) return;

    const hasPart = state.solveParts.some((answer) => Boolean(answer));
    dom.followupButton.hidden = !(state.questionId && hasPart);

    const answerCount = state.followupMessages
      .filter((message) => message.role === 'assistant').length;
    dom.followupCount.hidden = answerCount === 0;
    dom.followupCount.textContent = answerCount > 99 ? '99+' : String(answerCount);
    updateFollowupStepHint();
  }

  function followupStep() {
    const generated = state.solvePart || 0;
    const viewed = Math.max(state.maxViewedPart || 0, generated);
    if (viewed < 1) return 0;
    const total = state.solveTotal || 0;
    return total > 0 ? Math.min(viewed, total) : viewed;
  }

  function followupLimit() {
    return Number.isInteger(state.followupLimit)
      ? Math.max(0, state.followupLimit)
      : 3;
  }

  function followupQuestionCount() {
    return state.followupMessages
      .filter((message) => message.role === 'user').length;
  }

  function followupLimitReached() {
    const limit = followupLimit();
    return limit === 0 || followupQuestionCount() >= limit;
  }

  function followupLimitMessage() {
    const limit = followupLimit();
    return limit === 0
      ? '当前未开启追问功能。'
      : `每道题最多追问 ${limit} 次，已达到上限。`;
  }

  function updateFollowupStepHint() {
    if (!dom.followupStepHint) return;

    const limit = followupLimit();
    const remaining = Math.max(0, limit - followupQuestionCount());
    if (limit === 0) {
      dom.followupStepHint.textContent = '当前未开启追问';
      return;
    }
    if (remaining === 0) {
      dom.followupStepHint.textContent = `每道题最多追问 ${limit} 次，已用完`;
      return;
    }

    const step = followupStep();
    const stepText = step > 0
      ? `记录为在展开第 ${step} 段之后的追问`
      : '请先展开一段讲解';
    dom.followupStepHint.textContent = `${stepText} · 剩余 ${remaining}/${limit} 次`;
  }

  function updateFollowupMeta(maxSolvedPart) {
    if (!dom.followupMeta) return;

    const step = followupStep();
    const solved = Number.isInteger(maxSolvedPart) ? maxSolvedPart : (state.solvePart || 0);
    const limit = followupLimit();
    const limitText = limit === 0
      ? '未开启追问'
      : `追问 ${followupQuestionCount()}/${limit}`;
    dom.followupMeta.textContent = `当前已展开到第 ${step || 0} 段 · 已生成 ${solved} 段 · ${limitText}`;
  }

  function updateFollowupFormState() {
    if (!dom.followupInput || !dom.followupSubmit) return;

    const limit = followupLimit();
    const reached = followupLimitReached();
    if (reached) {
      dom.followupInput.disabled = true;
      dom.followupSubmit.disabled = true;
      dom.followupSubmit.textContent = limit === 0 ? '未开启追问' : '已达上限';
    } else {
      dom.followupInput.disabled = false;
      dom.followupSubmit.disabled = state.followupSending || state.followupLoading;
      dom.followupSubmit.textContent = state.followupSending ? '发送中…' : '发送追问';
    }

    updateFollowupStepHint();
  }

  async function openFollowup() {
    if (!state.questionId || dom.followupDialog.open) return;

    state.followupTrigger = document.activeElement;
    document.body.classList.add('dialog-open');
    if (typeof dom.followupDialog.showModal === 'function') {
      dom.followupDialog.showModal();
    } else {
      dom.followupDialog.setAttribute('open', '');
    }

    clearFollowupError();
    updateFollowupMeta();
    updateFollowupFormState();
    if (!state.followupLoaded && !state.followupLoading) {
      await loadFollowupThread();
    } else {
      renderFollowupMessages();
    }

    if (dom.followupDialog.open && !state.followupSending && !dom.followupInput.disabled) {
      dom.followupInput.focus();
    }
  }

  function closeFollowup() {
    state.followupLoadToken += 1;
    state.followupLoading = false;
    clearFollowupRetry();
    clearFollowupNotice();

    if (dom.followupDialog.open) {
      dom.followupDialog.close();
      return;
    }

    dom.followupDialog.removeAttribute('open');
    restoreFollowupFocus();
  }

  function restoreFollowupFocus() {
    document.body.classList.remove('dialog-open');

    if (state.followupTrigger && typeof state.followupTrigger.focus === 'function') {
      state.followupTrigger.focus();
    }

    state.followupTrigger = null;
  }

  async function loadFollowupThread() {
    if (!state.questionId) return;

    clearFollowupRetry();
    const token = state.followupLoadToken + 1;
    state.followupLoadToken = token;
    state.followupLoading = true;
    setFollowupStatus('正在加载之前的追问…');
    dom.followupEmpty.hidden = true;
    dom.followupMessages.replaceChildren();
    updateFollowupFormState();
    let transientFailure = false;

    try {
      const response = await window.GeometryNet.fetchWithRetry(
        `${API_HISTORY}/${encodeURIComponent(String(state.questionId))}`,
        {
          headers: { Accept: 'application/json' },
          cache: 'no-store'
        },
        {
          maxAttempts: 4,
          onRetry: () => {
            if (token !== state.followupLoadToken) return;
            setFollowupStatus(
              navigator.onLine
                ? '网络有点波动，正在自动重新加载追问…'
                : '网络已断开，联网后会自动重新加载追问…'
            );
          }
        }
      );
      const data = await readJson(response);

      if (token !== state.followupLoadToken) return;
      if (!response.ok) {
        if (window.GeometryNet.isRetryableStatus(response.status)) {
          transientFailure = true;
          renderFollowupMessages();
          return;
        }
        showFollowupError(pickErrorMessage(response.status, data));
        return;
      }

      const followups = data && Array.isArray(data.followups) ? data.followups : [];
      state.followupMessages = followups;
      state.followupLoaded = true;

      if (data && Number.isInteger(data.maxFollowupQuestions)) {
        state.followupLimit = Math.max(0, data.maxFollowupQuestions);
      }
      if (data && Number.isInteger(data.followupStep)) {
        state.maxViewedPart = Math.max(state.maxViewedPart, data.followupStep);
      }

      updateFollowupMeta(data && data.maxSolvedPart);
      updateFollowupFormState();
      renderFollowupMessages();
      updateFollowupButton();
    } catch (error) {
      if (token === state.followupLoadToken) {
        transientFailure = true;
        renderFollowupMessages();
      }
    } finally {
      if (token === state.followupLoadToken) {
        state.followupLoading = false;
        if (transientFailure) {
          setFollowupStatus(
            navigator.onLine
              ? '网络有点慢，正在等待自动重试…'
              : '网络已断开，联网后会自动重试…'
          );
          scheduleFollowupThreadRetry(token);
        } else {
          setFollowupStatus('');
        }
        updateFollowupFormState();
      }
    }
  }

  function clearFollowupRetry() {
    if (state.followupRetryTimer) {
      window.clearTimeout(state.followupRetryTimer);
      state.followupRetryTimer = null;
    }
  }

  function scheduleFollowupThreadRetry(token) {
    clearFollowupRetry();
    state.followupRetryTimer = window.setTimeout(() => {
      state.followupRetryTimer = null;
      if (
        token !== state.followupLoadToken
        || !dom.followupDialog.open
        || state.followupSending
      ) {
        return;
      }
      loadFollowupThread();
    }, 12000);
  }

  function renderFollowupMessages() {
    const messages = state.followupMessages;
    dom.followupMessages.replaceChildren();
    dom.followupMessages.hidden = messages.length === 0;
    dom.followupEmpty.hidden = messages.length > 0;

    if (!messages.length) return;

    const fragment = document.createDocumentFragment();
    messages.forEach((message) => {
      const isAssistant = message.role === 'assistant';
      const wrapper = document.createElement('article');
      wrapper.className = `followup-message followup-message--${isAssistant ? 'assistant' : 'user'}`;

      const meta = document.createElement('div');
      meta.className = 'followup-message__meta';
      const label = isAssistant ? '老师' : '我';
      const step = Number.isInteger(message.stepNo) ? ` · 第 ${message.stepNo} 段` : '';
      const time = formatHistoryTime(message.createdAt);
      meta.textContent = `${label}${step}${time ? ` · ${time}` : ''}`;

      const bubble = document.createElement('div');
      bubble.className = 'followup-message__bubble';
      if (isAssistant) {
        bubble.classList.add('markdown');
        renderMarkdown(message.content || '', bubble);
      } else {
        bubble.textContent = message.content || '';
      }

      wrapper.append(meta, bubble);
      fragment.appendChild(wrapper);
    });

    dom.followupMessages.appendChild(fragment);
    scheduleFitDisplayMath(dom.followupMessages);
    scrollFollowupToBottom();
  }

  async function sendFollowup(event) {
    event.preventDefault();
    if (state.followupSending) return;
    if (state.followupLoading) {
      showFollowupError('正在加载之前的追问，请稍候。');
      return;
    }
    if (followupLimitReached()) {
      showFollowupError(followupLimitMessage());
      updateFollowupFormState();
      return;
    }

    const message = dom.followupInput.value.trim();
    if (!message) {
      showFollowupError('请输入追问内容。');
      dom.followupInput.focus();
      return;
    }
    if (!state.questionId) {
      showFollowupError('题目记录尚未创建，请重新识别后再追问。');
      return;
    }

    const step = followupStep();
    if (step < 1) {
      showFollowupError('请先展开一段讲解后再追问。');
      return;
    }

    const questionId = state.questionId;
    const sendToken = state.followupSendToken + 1;
    state.followupSendToken = sendToken;
    state.followupSending = true;
    updateFollowupFormState();
    clearFollowupError();
    clearFollowupNotice();
    setFollowupStatus('老师正在思考…');
    let transientFailure = false;

    try {
      const response = await window.GeometryNet.fetchWithRetry(API_FOLLOWUP, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'application/json'
        },
        body: JSON.stringify({
          questionId,
          message,
          step
        }),
        cache: 'no-store'
      }, {
        maxAttempts: 3,
        onRetry: () => {
          if (sendToken !== state.followupSendToken) return;
          setFollowupStatus(
            navigator.onLine
              ? '网络有点波动，正在自动重试…'
              : '网络已断开，联网后会自动重试…'
          );
        }
      });
      const data = await readJson(response);

      if (sendToken !== state.followupSendToken) return;
      if (!response.ok) {
        if (window.GeometryNet.isRetryableStatus(response.status)) {
          transientFailure = true;
          return;
        }
        showFollowupError(pickErrorMessage(response.status, data));
        return;
      }

      const answer = data && typeof data.answer === 'string' ? data.answer.trim() : '';
      if (!answer) {
        showFollowupError('系统未返回追问回复，请重试。');
        return;
      }

      const now = new Date().toISOString();
      const answeredStep = data && Number.isInteger(data.step) ? data.step : step;
      state.followupMessages.push({
        id: Date.now(),
        role: 'user',
        content: message,
        stepNo: answeredStep,
        modelName: '',
        createdAt: now
      });
      state.followupMessages.push({
        id: Date.now() + 1,
        role: 'assistant',
        content: answer,
        stepNo: answeredStep,
        modelName: data && data.modelName ? data.modelName : '',
        createdAt: data && data.createdAt ? data.createdAt : now
      });
      state.followupLoaded = true;

      if (data && Number.isInteger(data.followupStep)) {
        state.maxViewedPart = Math.max(state.maxViewedPart, data.followupStep);
      }

      dom.followupInput.value = '';
      renderFollowupMessages();
      updateFollowupMeta();
      updateFollowupButton();
      updateFollowupFormState();
    } catch (error) {
      if (sendToken === state.followupSendToken) {
        transientFailure = true;
      }
    } finally {
      if (sendToken === state.followupSendToken) {
        state.followupSending = false;
        updateFollowupFormState();
        if (transientFailure) {
          showFollowupNotice(
            navigator.onLine
              ? '网络有点慢，请稍后再发送一次'
              : '网络已断开，联网后再发送'
          );
        } else {
          setFollowupStatus('');
        }
      }
    }
  }

  function setFollowupStatus(message) {
    dom.followupStatus.textContent = message || '';
    dom.followupStatus.hidden = !message;
  }

  function showFollowupError(message) {
    dom.followupError.textContent = message;
    dom.followupError.hidden = false;
  }

  function clearFollowupError() {
    dom.followupError.textContent = '';
    dom.followupError.hidden = true;
  }

  function showFollowupNotice(message) {
    clearFollowupNotice();
    setFollowupStatus(message);
    state.followupNoticeTimer = window.setTimeout(() => {
      state.followupNoticeTimer = null;
      if (dom.followupStatus.textContent === message) {
        setFollowupStatus('');
      }
    }, 5000);
  }

  function clearFollowupNotice() {
    if (state.followupNoticeTimer) {
      window.clearTimeout(state.followupNoticeTimer);
      state.followupNoticeTimer = null;
    }
  }

  function scrollFollowupToBottom() {
    window.requestAnimationFrame(() => {
      dom.followupMessages.scrollTop = dom.followupMessages.scrollHeight;
    });
  }

  function formatHistoryTime(value) {
    if (!value) return '';

    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '';

    return new Intl.DateTimeFormat('zh-CN', {
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false
    }).format(date);
  }

  function setupInstallPrompt() {
    window.addEventListener('beforeinstallprompt', (event) => {
      event.preventDefault();
      state.installPrompt = event;
      dom.installButton.hidden = false;
    });

    dom.installButton.addEventListener('click', async () => {
      if (!state.installPrompt) return;

      const prompt = state.installPrompt;
      state.installPrompt = null;
      dom.installButton.hidden = true;

      prompt.prompt();

      try {
        await prompt.userChoice;
      } catch (error) {
        // The browser may reject userChoice if the prompt was already handled.
      }
    });

    window.addEventListener('appinstalled', () => {
      state.installPrompt = null;
      dom.installButton.hidden = true;
      showToast('已安装到设备');
    });

    if (
      window.matchMedia('(display-mode: standalone)').matches
      || window.navigator.standalone === true
    ) {
      dom.installButton.hidden = true;
    }
  }

  function registerServiceWorker() {
    if (!('serviceWorker' in navigator)) return;

    window.addEventListener('load', () => {
      navigator.serviceWorker.register('sw.js').catch(() => {
        // Offline support is optional; never interrupt the main upload flow.
      });
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init, { once: true });
  } else {
    init();
  }
})();
