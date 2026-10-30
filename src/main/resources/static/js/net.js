(() => {
  'use strict';

  const RETRYABLE_STATUS = new Set([408, 425, 429, 500, 502, 503, 504]);
  const DEFAULT_DELAYS = [1200, 3000, 6000];
  const MAX_RETRY_AFTER_MS = 15000;
  const OFFLINE_WAIT_MS = 15000;

  function isRetryableStatus(status) {
    return RETRYABLE_STATUS.has(status);
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

  window.GeometryNet = Object.freeze({
    fetchWithRetry,
    isRetryableStatus
  });
})();
