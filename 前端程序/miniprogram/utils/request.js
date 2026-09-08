'use strict';

const config = require('./config');

const TOKEN_KEY = 'zs_access_token';
const REFRESH_KEY = 'zs_refresh_token';

function getToken() { return wx.getStorageSync(TOKEN_KEY) || ''; }
function setTokens(access, refresh) {
  wx.setStorageSync(TOKEN_KEY, access || '');
  if (refresh) wx.setStorageSync(REFRESH_KEY, refresh);
}
function clearTokens() {
  wx.removeStorageSync(TOKEN_KEY);
  wx.removeStorageSync(REFRESH_KEY);
}

function rawRequest(options) {
  return new Promise((resolve, reject) => {
    wx.request({
      url: config.apiBase + options.url,
      method: options.method || 'GET',
      data: options.data,
      header: Object.assign({
        'Content-Type': 'application/json'
      }, getToken() ? { 'Authorization': 'Bearer ' + getToken() } : {}, options.header || {}),
      success: resolve,
      fail: reject
    });
  });
}

/**
 * 统一请求：自动带 token、解析 CommonResult 壳、401 跳登录
 * @returns {Promise<any>} resolve(data) 或 reject({code, msg})
 */
function request(url, method, data, extraHeaders) {
  return rawRequest({ url, method, data, header: extraHeaders }).then(function (res) {
    if (res.statusCode === 401 || res.statusCode === 403) {
      clearTokens();
      try { wx.setStorageSync('v12Authorized', false); } catch (e) { /* 存储不可用时忽略 */ }
      // 已在激活页时不重复跳转，避免打断用户输入
      const pages = getCurrentPages();
      const current = pages[pages.length - 1];
      if (!current || current.route !== 'pages/auth/index') {
        wx.redirectTo({ url: '/pages/auth/index' });
      }
      return Promise.reject({ code: -1, msg: '请重新登录' });
    }
    if (res.statusCode >= 400) {
      var msg = (res.data && res.data.msg) || '请求失败(' + res.statusCode + ')';
      return Promise.reject({ code: res.data ? res.data.code : res.statusCode, msg: msg });
    }
    var body = res.data;
    if (body && typeof body.code === 'number') {
      if (body.code === 0) return body.data;
      return Promise.reject({ code: body.code, msg: body.msg || '操作失败' });
    }
    return body;
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
  setTokens: setTokens,
  clearTokens: clearTokens,
  config: config
};
