'use strict';

const config = require('./config');

const TOKEN_KEY = 'zs_access_token';
const REFRESH_KEY = 'zs_refresh_token';
const REFRESH_URL = '/app-api/design/v1/auth/token-refresh';
const GRANT_REQUIRED = 1070001001;
let sessionGeneration = 0;
let refreshFlight = null;
let lastRotation = null;

function getToken() {
  try { return wx.getStorageSync(TOKEN_KEY) || ''; } catch (e) { return ''; }
}
function setTokens(access, refresh) {
  wx.setStorageSync(TOKEN_KEY, access || '');
  if (refresh) wx.setStorageSync(REFRESH_KEY, refresh);
  else wx.removeStorageSync(REFRESH_KEY);
  sessionGeneration++;
  lastRotation = null;
}
function clearTokens() {
  wx.removeStorageSync(TOKEN_KEY);
  wx.removeStorageSync(REFRESH_KEY);
  sessionGeneration++;
  lastRotation = null;
  try { wx.setStorageSync('v12Authorized', false); } catch (e) { /* 存储失败仍保持令牌清除 */ }
  if (typeof getApp === 'function') getApp().globalData = {};
}

function rawRequest(options) {
  return new Promise((resolve, reject) => {
    wx.request({
      url: config.apiBase + options.url,
      method: options.method || 'GET',
      data: options.data,
      header: Object.assign({
        'Content-Type': 'application/json',
        'tenant-id': String(config.tenantId)
      }, getToken() ? { 'Authorization': 'Bearer ' + getToken() } : {}, options.header || {}),
      success: resolve,
      fail: reject
    });
  });
}

function responseData(res) {
  const body = res.data;
  const code = res.statusCode === 401 ? 401 : res.statusCode === 403 ? 403
    : body && typeof body.code === 'number' ? body.code : res.statusCode >= 400 ? res.statusCode : 0;
  if (code !== 0) throw { code, msg: body && body.msg || '请求失败(' + code + ')' };
  return body && typeof body.code === 'number' ? body.data : body;
}

function showActivation() {
  const pages = getCurrentPages();
  const current = pages[pages.length - 1];
  if (!current || current.route !== 'pages/auth/index') wx.redirectTo({ url: '/pages/auth/index' });
}

function sessionChanged() { return { code: 'SESSION_CHANGED', msg: '登录身份已变化，请重新进入' }; }

function isSameSession(token) {
  return !!token && (getToken() === token || !!(lastRotation && lastRotation.access === token
    && lastRotation.nextAccess === getToken() && lastRotation.nextGeneration === sessionGeneration));
}

function refreshSession() {
  const generation = sessionGeneration, access = getToken();
  const refresh = wx.getStorageSync(REFRESH_KEY);
  if (!refresh) return Promise.reject({ code: 401, msg: '请重新登录' });
  if (refreshFlight && refreshFlight.generation === generation) return refreshFlight.promise;
  const flight = { generation };
  flight.promise = rawRequest({ url: REFRESH_URL, method: 'POST', data: { refreshToken: refresh } })
    .then(responseData).then(tokens => {
      if (sessionGeneration !== generation || getToken() !== access || wx.getStorageSync(REFRESH_KEY) !== refresh) throw sessionChanged();
      if (!tokens || !tokens.accessToken || !tokens.refreshToken || typeof tokens.restricted !== 'boolean') {
        throw { code: 401, msg: '会话刷新失败，请重新登录' };
      }
      setTokens(tokens.accessToken, tokens.refreshToken);
      lastRotation = { access, generation, nextAccess: tokens.accessToken, nextGeneration: sessionGeneration };
      wx.setStorageSync('v12Authorized', !tokens.restricted);
    }).finally(() => { if (refreshFlight === flight) refreshFlight = null; });
  refreshFlight = flight;
  return flight.promise;
}

// 业务码和 HTTP 401 使用同一刷新路径；资源 403 不清会话。
// 原请求最多重放一次，保留原始幂等键；身份改变后不重放旧用户操作。
function request(url, method, data, extraHeaders, retried) {
  const token = getToken(), generation = sessionGeneration;
  return rawRequest({ url, method, data, header: extraHeaders }).then(responseData).then(result => {
    const ownRotation = lastRotation && lastRotation.access === token && lastRotation.generation === generation
      && lastRotation.nextAccess === getToken() && lastRotation.nextGeneration === sessionGeneration;
    if ((getToken() !== token || sessionGeneration !== generation) && !ownRotation) throw sessionChanged();
    return result;
  }).catch(error => {
    if (getToken() !== token || sessionGeneration !== generation) {
      if (!retried && error.code === 401 && lastRotation && lastRotation.access === token
          && lastRotation.generation === generation && lastRotation.nextAccess === getToken()
          && lastRotation.nextGeneration === sessionGeneration) {
        return request(url, method, data, extraHeaders, true);
      }
      throw sessionChanged();
    }
    if (error.code === GRANT_REQUIRED) {
      wx.setStorageSync('v12Authorized', false);
      showActivation();
      throw error;
    }
    if (error.code !== 401 || url === REFRESH_URL || url.endsWith('/auth/wechat-login')) throw error;
    if (!retried && token) {
      return refreshSession().then(() => request(url, method, data, extraHeaders, true), refreshError => {
        if (refreshError.code === 401 && getToken() === token && sessionGeneration === generation) {
          clearTokens(); showActivation();
        }
        throw refreshError;
      });
    }
    if (token) { clearTokens(); showActivation(); }
    throw error;
  });
}

module.exports = {
  get: function (url, extraHeaders) { return request(url, 'GET', undefined, extraHeaders); },
  post: function (url, data, extraHeaders) { return request(url, 'POST', data, extraHeaders); },
  put: function (url, data, extraHeaders) { return request(url, 'PUT', data, extraHeaders); },
  del: function (url, data, extraHeaders) { return request(url, 'DELETE', data, extraHeaders); },
  patch: function (url, data, extraHeaders) { return request(url, 'PATCH', data, extraHeaders); },
  rawRequest: rawRequest,
  getToken: getToken,
  isSameSession: isSameSession,
  setTokens: setTokens,
  clearTokens: clearTokens,
  config: config
};
