'use strict';
const drafts = require('./budget-draft');
const sha = require('./sha256');
function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === 'object') {
    const result = {};
    Object.keys(value).sort().forEach(key => { if (value[key] !== undefined) result[key] = canonical(value[key]); });
    return result;
  }
  return value;
}
function storageKey(slot) {
  const scope = drafts.sessionScope();
  if (!scope) throw { msg: '请先登录' };
  return 'zs_generation_attempt_v1:' + scope + ':' + slot;
}
function read(slot) { return wx.getStorageSync(storageKey(slot)) || null; }
function assertCurrent(attempt) {
  if (!attempt || attempt.scope !== drafts.sessionScope()) throw { code: 'SESSION_CHANGED', msg: '登录身份已变化，请重新进入' };
}
function save(slot, attempt) {
  assertCurrent(attempt);
  wx.setStorageSync(storageKey(slot), attempt); return attempt;
}
function begin(slot, payload) {
  const text = JSON.stringify(canonical(payload));
  const fingerprint = sha.sha256Hex(new Uint8Array(Array.from(unescape(encodeURIComponent(text)), char => char.charCodeAt(0))));
  const previous = read(slot);
  if (previous) {
    if (previous.fingerprint !== fingerprint) throw { msg: '上次生成结果尚未确认，请恢复原输入继续重试' };
    return previous;
  }
  return save(slot, { scope: drafts.sessionScope(), state: 'DRAFT', fingerprint, payload: canonical(payload), key: 'generation-' + Date.now() + '-' + Math.random().toString(36).slice(2, 12) });
}
function complete(slot, attempt) {
  if (attempt) {
    assertCurrent(attempt);
    const saved = read(slot);
    if (saved && saved.key !== attempt.key) return;
  }
  wx.removeStorageSync(storageKey(slot));
}
function submitted(slot, attempt) { attempt.state = 'SUBMITTED'; return save(slot, attempt); }
function failed(slot, attempt, error) {
  assertCurrent(attempt);
  if (attempt.state === 'DRAFT' || (error && error.cancelled && attempt.state === 'REJECTED_PRICE')) {
    complete(slot, attempt); return true;
  }
  if (error && Number(error.code) === 1072000001) {
    // The transaction explicitly rejected this quote. Keep the operation identity and intent,
    // allow a fresh confirmation, and never reinterpret a transport timeout as rejection.
    delete attempt.confirmedPrice; attempt.state = 'REJECTED_PRICE'; save(slot, attempt);
  }
  return false;
}
module.exports = { begin, read, save, complete, assertCurrent, submitted, failed };
