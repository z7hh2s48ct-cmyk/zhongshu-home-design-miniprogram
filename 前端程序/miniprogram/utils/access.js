'use strict';

const api = require('./api');

const HOME = '/pages/home/index';
const AUTH = '/pages/auth/index';
const PUBLIC_PAGES = [HOME, '/pages/profile/index', '/pages/profile/services'];
const TAB_PAGES = [HOME, '/pages/profile/index', '/pages/library/index', '/pages/ai-design/index'];
const BUSINESS_PAGES = [
  '/pages/profile/records', '/pages/profile/record',
  '/pages/profile/edit',
  '/pages/library/index', '/pages/library/detail', '/pages/ai-design/index',
  '/pages/ai-design/generating', '/pages/ai-design/plane-select',
  '/pages/ai-design/elevation-setup', '/pages/ai-design/elevation-select',
  '/pages/ai-design/result', '/pages/ai-design/publish',
  '/pages/budget/input', '/pages/budget/parameters', '/pages/budget/legacy',
  '/pages/budget/body', '/pages/budget/exterior', '/pages/budget/history',
  '/pages/budget/result', '/pages/budget/body-detail', '/pages/budget/exterior-detail', '/pages/budget/prices', '/pages/wallet/recharge',
  '/pages/payment/success', '/pages/messagecenter/messagecenter'
];
let prompting = false;

function navigationFailed() {
  wx.showToast({ title: '页面打开失败，请重试', icon: 'none' });
}

// 授权判定以服务端 GET /access-grant 为准（refreshFromServer 启动/兑换后刷新），
// 本地缓存只是两次刷新之间的同步快照，判权本身不产生网络等待。
function isAuthorized() {
  try {
    return wx.getStorageSync('v12Authorized') === true;
  } catch (error) {
    console.warn('[access] 无法读取授权状态', error);
    return false;
  }
}

function setAuthorized(value) {
  try { wx.setStorageSync('v12Authorized', value === true); } catch (error) { /* 存储不可用时判权保持保守 */ }
}

// 启动、兑换成功后调用；测试环境无 wx.request 时静默跳过。
function refreshFromServer() {
  if (typeof wx.request !== 'function') return Promise.resolve();
  if (!require('./request').getToken()) return Promise.resolve();
  // silent：后台对账只同步授权快照，绝不让在途请求的迟到 401/受限触发全局跳转把用户从当前页拉回激活页
  // （承 T13-07 P2#1）；导航由页面级门禁 protectedPage/checkPage 在用户进入或交互时驱动。
  return api.getAccessGrant({ silent: true })
    .then((grant) => setAuthorized(!!grant && grant.status === 'ACTIVE'))
    .catch(() => { /* 刷新失败保持现有快照，待下次进入页面重试 */ });
}

function safeTarget(value) {
  const target = typeof value === 'string' ? value : HOME;
  const route = target.split('?')[0];
  if (![...PUBLIC_PAGES, ...BUSINESS_PAGES].includes(route)) return HOME;
  return TAB_PAGES.includes(route) ? route : target;
}

function navigate(url, replace = false, handlers = {}) {
  const method = TAB_PAGES.includes(url.split('?')[0]) ? 'switchTab' : replace ? 'redirectTo' : 'navigateTo';
  wx[method]({ url, success: handlers.success, fail: handlers.fail || navigationFailed });
}

function openActivation(target = HOME) {
  wx.navigateTo({ url: `${AUTH}?redirect=${encodeURIComponent(safeTarget(target))}`, fail: navigationFailed });
}

function promptActivation(target) {
  if (prompting) return;
  prompting = true;
  wx.showModal({
    title: '激活后使用此功能',
    content: '首页可继续浏览，使用此功能需要先激活。',
    cancelText: '继续浏览',
    confirmText: '去激活',
    confirmColor: '#7b5532',
    success(result) {
      if (result.confirm) openActivation(target);
    },
    fail: navigationFailed,
    complete() { prompting = false; }
  });
}

function leaveRestrictedPage(page, target) {
  page.setData({ accessReady: false });
  if (page._returningHome) return;
  page._returningHome = true;
  wx.switchTab({
    url: HOME,
    success() { if (target) promptActivation(target); },
    fail() { page._returningHome = false; navigationFailed(); }
  });
}

function requireActivation(action, target = HOME) {
  if (isAuthorized()) { action(); return; }
  const pages = getCurrentPages();
  const current = pages[pages.length - 1];
  if (current?.route === 'pages/profile/index') current.setData({ authorized: false });
  if (current && BUSINESS_PAGES.includes(`/${current.route}`)) leaveRestrictedPage(current, target);
  else promptActivation(target);
}

function openFeature(target) {
  const url = safeTarget(target);
  if (PUBLIC_PAGES.includes(url)) navigate(url);
  else requireActivation(() => navigate(url), url);
}

function returnHome() {
  wx.switchTab({ url: HOME, fail: navigationFailed });
}

function checkPage(page, fromAction = false) {
  // 激活是资金前置条件，任何入口都必须满足；"先看过首页"只对普通入口生效——
  // 分享/扫码直达属于用户手持明确目标，视为已满足该引导前置（见 app.js deepLinkEntry）。
  const app = getApp();
  const allowed = isAuthorized() && (app.homeSeen || app.deepLinkEntry);
  if (allowed) {
    page.setData({ accessReady: true });
    page._returningHome = false;
  } else {
    const query = Object.entries(page.options || {}).map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value)}`).join('&');
    const target = `/${page.route}${query ? `?${query}` : ''}`;
    leaveRestrictedPage(page, fromAction ? target : null);
  }
  return allowed;
}

// 入口、直达链接、回到前台和业务点击共享检查，避免漏掉某一条路径。
function protectedPage(definition) {
  const guarded = { ...definition, data: { ...definition.data, accessReady: false } };
  for (const [name, handler] of Object.entries(definition)) {
    if (typeof handler !== 'function' || ['onLoad', 'onShow', 'onHide', 'onUnload'].includes(name)) continue;
    guarded[name] = function (...args) {
      if (checkPage(this, true)) return handler.apply(this, args);
    };
  }
  guarded.onLoad = function (options) {
    if (checkPage(this) && definition.onLoad) definition.onLoad.call(this, options);
  };
  guarded.onShow = function () {
    if (checkPage(this) && definition.onShow) definition.onShow.call(this);
  };
  Page(guarded);
}

module.exports = {
  isAuthorized, setAuthorized, refreshFromServer,
  safeTarget, navigate, openActivation, requireActivation, openFeature, returnHome, protectedPage
};
