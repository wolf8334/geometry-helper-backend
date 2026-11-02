(() => {
  'use strict';

  const RETRYABLE_STATUS = new Set([408, 425, 429, 500, 502, 503, 504]);
  const DEFAULT_DELAYS = [1200, 3000, 6000];
  const MAX_RETRY_AFTER_MS = 15000;
  const OFFLINE_WAIT_MS = 15000;

  function isRetryableStatus(status) {
    return RETRYABLE_STATUS.has(status);
  }

  class StreamError extends Error {
    constructor(message, options = {}) {
      super(message || '请求失败，请稍后重试');
      this.name = 'StreamError';
      this.retryable = options.retryable !== false;
      this.status = Number.isInteger(options.status) ? options.status : 0;
      this.committed = Boolean(options.committed);
      this.commit = options.commit || null;
    }
  }

  function defaultStatusMessage(status) {
    switch (status) {
      case 400:
        return '请求有误，请检查后重试。';
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
        return '请求超时，请稍后重试。';
      default:
        return status >= 500
          ? '服务暂时不可用，请稍后重试。'
          : '请求失败，请检查网络后重试。';
    }
  }

  async function readErrorPayload(response) {
    try {
      const text = await response.text();
      if (!text) return null;
      return JSON.parse(text);
    } catch (error) {
      return null;
    }
  }

  function parseRetryAfter(response) {
    if (!response || !response.headers) return 0;

    const value = response.headers.get('Retry-After');
    if (!value) return 0;

    const seconds = Number(value);
    if (Number.isFinite(seconds) && seconds >= 0) {
      return Math.min(seconds * 1000, MAX_RETRY_AFTER_MS);
    }

    const timestamp = Date.parse(value);
    if (Number.isNaN(timestamp)) return 0;
    return Math.min(Math.max(0, timestamp - Date.now()), MAX_RETRY_AFTER_MS);
  }

  function wait(ms) {
    return new Promise((resolve) => {
      window.setTimeout(resolve, ms);
    });
  }

  function waitForRetry(ms) {
    if (navigator.onLine) return wait(ms);

    return new Promise((resolve) => {
      let settled = false;
      const finish = () => {
        if (settled) return;
        settled = true;
        window.clearTimeout(timer);
        window.removeEventListener('online', finish);
        resolve();
      };
      const timer = window.setTimeout(finish, Math.max(ms, OFFLINE_WAIT_MS));
      window.addEventListener('online', finish, { once: true });
    });
  }

  function cancelBody(response) {
    if (!response || !response.body || typeof response.body.cancel !== 'function') {
      return Promise.resolve();
    }
    return response.body.cancel().catch(() => {});
  }

  async function fetchWithRetry(input, init = {}, options = {}) {
    const maxAttempts = Math.max(
      1,
      Number.isInteger(options.maxAttempts) ? options.maxAttempts : 3
    );
    const delays = Array.isArray(options.delays) && options.delays.length > 0
      ? options.delays
      : DEFAULT_DELAYS;
    const onRetry = typeof options.onRetry === 'function' ? options.onRetry : null;
    let attempt = 0;

    while (attempt < maxAttempts) {
      try {
        const response = await fetch(input, init);
        attempt += 1;

        if (!isRetryableStatus(response.status) || attempt >= maxAttempts) {
          return response;
        }

        const delayMs = Math.max(
          delays[Math.min(attempt - 1, delays.length - 1)],
          parseRetryAfter(response)
        );

        if (onRetry) {
          try {
            await onRetry({ attempt, response, error: null, delayMs });
          } catch (error) {
            // 状态提示失败不应该影响请求重试。
          }
        }

        await cancelBody(response);
        await waitForRetry(delayMs);
      } catch (error) {
        if (error && error.name === 'AbortError') throw error;

        attempt += 1;
        if (attempt >= maxAttempts) throw error;

        const delayMs = delays[Math.min(attempt - 1, delays.length - 1)];
        if (onRetry) {
          try {
            await onRetry({ attempt, response: null, error, delayMs });
          } catch (retryError) {
            // 状态提示失败不应该影响请求重试。
          }
        }

        await waitForRetry(delayMs);
      }
    }

    throw new Error('请求重试次数已用尽');
  }

  async function consumeSse(response, handlers) {
    if (!response.body || typeof response.body.getReader !== 'function') {
      throw new StreamError('当前浏览器不支持流式响应，请升级浏览器后重试', {
        retryable: false,
        status: response.status
      });
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let eventName = 'message';
    let dataLines = [];
    let committed = false;
    let commitPayload = null;
    let serverError = null;

    const notify = (name, payload) => {
      const callback = handlers[name];
      if (typeof callback !== 'function') return;
      try {
        callback(payload);
      } catch (error) {
        // 页面渲染异常不应该影响已经收到的数据。
      }
    };

    const dispatch = () => {
      const name = eventName || 'message';
      const raw = dataLines.join('\n');
      let payload = null;

      if (raw) {
        try {
          payload = JSON.parse(raw);
        } catch (error) {
          payload = null;
        }
      }

      eventName = 'message';
      dataLines = [];

      if (name === 'delta') {
        const text = payload && typeof payload.text === 'string' ? payload.text : '';
        if (text) notify('onDelta', { text });
        return;
      }

      // [Claude 添加] 模型思考期间的进度，只用于显示，不参与最终结果。
      if (name === 'thinking') {
        const text = payload && typeof payload.text === 'string' ? payload.text : '';
        if (text) notify('onThinking', { text });
        return;
      }

      if (name === 'meta') {
        notify('onMeta', payload);
        return;
      }

      if (name === 'commit') {
        committed = true;
        commitPayload = payload;
        notify('onCommit', payload);
        return;
      }

      if (name === 'error') {
        serverError = {
          message: payload && typeof payload.message === 'string' && payload.message.trim()
            ? payload.message.trim()
            : '服务暂时不可用，请稍后重试。',
          retryable: !(payload && payload.retryable === false)
        };
        return;
      }

      // done 以及未知事件都不需要额外处理。
    };

    const processLine = (line) => {
      if (line === '') {
        dispatch();
        return;
      }
      if (line.startsWith(':')) return;

      const colon = line.indexOf(':');
      const field = colon === -1 ? line : line.slice(0, colon);
      let value = colon === -1 ? '' : line.slice(colon + 1);
      if (value.startsWith(' ')) value = value.slice(1);

      if (field === 'event') {
        eventName = value;
      } else if (field === 'data') {
        dataLines.push(value);
      }
    };

    const drainLines = (flush) => {
      let index = buffer.indexOf('\n');
      while (index !== -1) {
        let line = buffer.slice(0, index);
        if (line.endsWith('\r')) line = line.slice(0, -1);
        buffer = buffer.slice(index + 1);
        processLine(line);
        index = buffer.indexOf('\n');
      }

      if (flush && buffer.length > 0) {
        const line = buffer.endsWith('\r') ? buffer.slice(0, -1) : buffer;
        buffer = '';
        processLine(line);
      }
    };

    let readError = null;

    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        drainLines(false);
      }
      buffer += decoder.decode();
      drainLines(true);
      if (dataLines.length > 0) dispatch();
    } catch (error) {
      readError = error;
    } finally {
      try {
        reader.releaseLock();
      } catch (error) {
        // 释放失败不影响已经读取的数据。
      }
    }

    // 已经落库的结果优先：即使 done 事件、连接尾部或读取过程丢失，
    // 也按成功处理，避免前端重试后重复生成。
    if (committed) {
      return { committed: true, commit: commitPayload };
    }

    if (readError) {
      throw readError;
    }

    if (serverError) {
      throw new StreamError(serverError.message, {
        retryable: serverError.retryable,
        status: response.status
      });
    }

    throw new StreamError('连接中断，请稍后重试。', {
      retryable: true,
      status: response.status
    });
  }

  async function streamRequest(input, init = {}, options = {}) {
    const maxAttempts = Math.max(
      1,
      Number.isInteger(options.maxAttempts) ? options.maxAttempts : 3
    );
    const delays = Array.isArray(options.delays) && options.delays.length > 0
      ? options.delays
      : DEFAULT_DELAYS;
    const onRetry = typeof options.onRetry === 'function' ? options.onRetry : null;
    const onAttemptStart = typeof options.onAttemptStart === 'function'
      ? options.onAttemptStart
      : null;
    const handlers = {
      onDelta: typeof options.onDelta === 'function' ? options.onDelta : null,
      onThinking: typeof options.onThinking === 'function' ? options.onThinking : null,
      onMeta: typeof options.onMeta === 'function' ? options.onMeta : null,
      onCommit: typeof options.onCommit === 'function' ? options.onCommit : null
    };

    let lastError = null;

    for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
      if (onAttemptStart) {
        try {
          onAttemptStart({ attempt });
        } catch (error) {
          // 渲染复位失败不应该影响请求。
        }
      }

      try {
        const headers = new Headers(init.headers || {});
        headers.set('Accept', 'text/event-stream, application/json');

        const response = await fetch(input, {
          ...init,
          headers
        });

        if (!response.ok) {
          const payload = await readErrorPayload(response);
          const apiMessage = payload && typeof payload.msg === 'string'
            ? payload.msg.trim()
            : '';
          throw new StreamError(apiMessage || defaultStatusMessage(response.status), {
            retryable: isRetryableStatus(response.status),
            status: response.status
          });
        }

        return await consumeSse(response, handlers);
      } catch (error) {
        if (error && error.name === 'AbortError') throw error;

        const streamError = error instanceof StreamError
          ? error
          : new StreamError('网络连接中断，请稍后重试。', { retryable: true });

        lastError = streamError;

        if (!streamError.retryable || attempt >= maxAttempts) {
          throw streamError;
        }

        const delayMs = delays[Math.min(attempt - 1, delays.length - 1)];

        if (onRetry) {
          try {
            await onRetry({ attempt, error: streamError, delayMs });
          } catch (retryError) {
            // 状态提示失败不应该影响请求重试。
          }
        }

        await waitForRetry(delayMs);
      }
    }

    throw lastError || new StreamError('请求失败，请稍后重试。', { retryable: true });
  }

  window.GeometryNet = Object.freeze({
    fetchWithRetry,
    streamRequest,
    isRetryableStatus,
    StreamError
  });
})();
