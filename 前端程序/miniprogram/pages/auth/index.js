'use strict';

const { safeTarget, navigate, returnHome, refreshFromServer } = require('../../utils/access');
const http = require('../../utils/request');
const api = require('../../utils/api');

const HOME = '/pages/home/index';

Page({
  data: { code: '', accessReady: false, loading: false },
  onLoad(options) {
    if (!getApp().homeSeen) { returnHome(); return; }
    try {
      const redirect = options.redirect || '';
      this.target = safeTarget(redirect.startsWith('/') ? redirect : decodeURIComponent(redirect));
    } catch (e) {
      this.target = HOME;
    }
    this._leftPage = false;
    this.setData({ accessReady: true });
    // 已激活账号进入本页时静默恢复会话；不阻断手动输入授权码
    this._recovery = this.recoverSession();
  },
  onUnload() { this._leftPage = true; },
  onInput(event) { this.setData({ code: event.detail.value.trim() }); },

  // 内部识别微信并登录：合并并发在途登录——恢复与手动兑换共享同一次 wx.login，杜绝重复登录
  // 与会话代际冲突（第二次登录响应会被请求层判为 SESSION_CHANGED 而破坏首次兑换）；force 时忽略
  // 既有令牌强制重新登录（会话过期恢复用）；无论成败都释放在途，失败后允许重试。
  ensureLoggedIn(force) {
    if (!force && http.getToken()) return Promise.resolve();
    if (this._loginFlight) return this._loginFlight;
    const self = this;
    const flight = new Promise(function (resolve, reject) {
      wx.login({ success: function (res) { resolve(res.code); }, fail: function () { reject({ msg: '微信登录失败' }); } });
    }).then(function (code) { return api.login(code); })
      .then(function (data) { http.setTokens(data.accessToken, data.refreshToken); });
    this._loginFlight = flight.then(
      function () { self._loginFlight = null; },
      function (err) { self._loginFlight = null; throw err; }
    );
    return this._loginFlight;
  },

  // 会话恢复：进入本页先内部识别微信并查服务端授权真值（getAccessGrant，实时重判 restricted，
  // 不依赖本地快照）；ACTIVE 则静默恢复使用、无需输码。身份过期（401）时强制重登（force，忽略
  // 既有令牌）再查一次；本次请求因并发操作改变的会话代际被判 SESSION_CHANGED 时，按当前令牌/在途
  // 登录复用或正常登录（非 force）后重查一次、绝不在此主动清会话（否则会作废在途重试登录）；其余
  // 失败（网络/服务端错误）静默降级到手动输码。恢复全程绝不弹错误、不清用户已输入的码、不阻断
  // 手动路径；离页后页面自身回调不再导航、不再发起授权查询（请求层 showActivation 对已在途授权查询
  // 迟到 401 的拉回属全局副作用，本项不改 request.js、未闭环，延后 T13-08）。
  recoverSession() {
    if (this.recovering || this.activated || this._leftPage) return Promise.resolve();
    this.recovering = true;
    const self = this;
    return self._recoverOnce(false)
      .catch(function (err) {
        if (self._leftPage || self.activated) return undefined;
        // 会话失效分两类，恢复层均不在此主动清会话。
        //   401（身份过期）→ _recoverOnce(true)：force 忽略既有令牌强制重登再查一次授权。请求层对 401 清会话
        //     有两条路径：①刷新失败分支——refreshError.code===401 且会话在等待期未变（request.js:115
        //     getToken()===token 且代际未变）→ clearTokens+showActivation；②刷新成功后重放请求又 401（已 retried、
        //     有令牌，request.js:121）→ 同样 clearTokens+showActivation。不清会话的 401：①刷新等待期会话已变
        //     （新令牌已建立，line 115 判否）→ 抛 401、保留新令牌；②通过前置会话检查后 401 来自登录/刷新端点
        //     （request.js:112）→ 原样抛出、不清除现有会话状态（会话于请求期间已变则先进入 :99-105 检查：命中 :100-103 同会话刷新轮换例外则重放一次，否则转 SESSION_CHANGED）。
        //     showActivation 是否重定向还取决于当前是否已在激活页（request.js:59）。无论哪条，恢复层都不主动 clearTokens。
        //   SESSION_CHANGED（代际被并发操作改变：可能已建立更新会话、也可能已被清除）→ _recoverOnce(false)：
        //     非 force，按当前令牌/在途登录复用或正常登录后重查一次，绝不主动 clearTokens——否则会二次 bump
        //     代际、作废并发在途的重试登录，令其响应同样被判 SESSION_CHANGED、令牌终空、兑换与导航零次
        //     （round-3 修复 P1）。
        // 两者都只重试一次，二次失败静默降级；网络/服务端错误直接静默降级到手动输码。
        if (err && err.code === 401) return self._recoverOnce(true).catch(function () { return undefined; });
        if (err && err.code === 'SESSION_CHANGED') return self._recoverOnce(false).catch(function () { return undefined; });
        return undefined;
      })
      .then(function () { self.recovering = false; });
  },

  _recoverOnce(force) {
    const self = this;
    return self.ensureLoggedIn(force)
      .then(function () {
        // 登录落定后、发起授权查询前复查生命周期：用户已在登录阶段离页则不再发起 getAccessGrant，
        // 收窄「离页后请求层迟到 401 触发 showActivation 把用户拉回」的窗口（round-3 修复 P2）；
        // 但 grant 已在途后离页的迟到 401 拉回属 request.js 全局副作用，本项未闭环、延后 T13-08。
        if (self._leftPage || self.activated) return null;
        return api.getAccessGrant();
      })
      .then(function (grant) {
        if (!self._leftPage && !self.activated && grant && grant.status === 'ACTIVE') {
          self.activate(self.target, { silent: true });
        }
      });
  },

  // 激活成功统一收口：先写授权状态再导航。写入失败留在本页可重试（silent 时不弹 toast，供静默恢复用）；
  // 并发/重入由 _activating 守卫，导航成功后才置终态 activated，导航失败则释放以允许本页重试导航。
  activate(target, options) {
    const silent = !!(options && options.silent);
    if (this.activated || this._activating) return;
    this._activating = true;
    const self = this;
    try {
      wx.setStorageSync('v12Authorized', true);
    } catch (e) {
      self._activating = false;
      if (!silent) wx.showToast({ title: '激活状态写入失败，请重试', icon: 'none' });
      return;
    }
    refreshFromServer();
    if (!silent) wx.showToast({ title: '激活成功', icon: 'success' });
    navigate(target || HOME, true, {
      success: function () { self.activated = true; self._activating = false; },
      // 导航失败释放守卫以允许本页重试；手动路径补回 access.navigate 原 navigationFailed 的失败提示，
      // 静默恢复路径保持不弹（round-3 修复 P2：fail 回调曾吞掉手动兑换的导航失败反馈）。
      fail: function () {
        self._activating = false;
        if (!silent) wx.showToast({ title: '页面打开失败，请重试', icon: 'none' });
      }
    });
  },

  verify() {
    if (!this.data.accessReady || this.data.loading) return;
    const code = this.data.code;
    if (!code) { wx.showToast({ title: '请输入授权码', icon: 'none' }); return; }
    const self = this;
    self.setData({ loading: true });
    return self.ensureLoggedIn()
      .then(function () { return api.redeemAccessCode(code); })
      .then(function (grant) {
        if (!grant || grant.status !== 'ACTIVE') throw { msg: '授权未生效，请重试或联系管理员' };
        self.activate(self.target);
      })
      .catch(function (err) {
        wx.showToast({ title: (err && err.msg) || '兑换失败', icon: 'none' });
      })
      .then(function () { self.setData({ loading: false }); });
  },

  continueBrowsing() { this._leftPage = true; wx.navigateBack({ fail: returnHome }); },
  contact() { wx.showToast({ title: '请联系授权管理员', icon: 'none' }); }
});
