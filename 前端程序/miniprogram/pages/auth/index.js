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
  // 手动路径；离页后页面自身回调不再导航、不再发起授权查询；且授权查询以 silent 发起（T13-08 承 P2#1
  // 闭环），即便 grant 已在途后离页、迟到 401 也只同步会话状态、不触发请求层 showActivation 把用户拉回。
  recoverSession() {
    if (this.recovering || this.activated || this._leftPage) return Promise.resolve();
    this.recovering = true;
    const self = this;
    return self._recoverOnce(false)
      .catch(function (err) {
        if (self._leftPage || self.activated) return undefined;
        // 会话失效分两类，恢复层均不在此主动清会话。
        //   401（身份过期）→ _recoverOnce(true)：force 忽略既有令牌强制重登再查一次授权。请求层（request.js）对 401 的清会话
        //     分布在三条失效路径——①「刷新失败分支」（refreshSession 的 rejection：refreshError.code===401 且会话在等待期未变
        //     getToken()===token、代际未变 → clearTokens）；②「重放终态分支」（刷新成功后重放又 401：已 retried、有令牌 → clearTokens）；
        //     ③「源于 401 的 SESSION_CHANGED 分支」（前台 401 迟到于会话已被并发清除 → 不再清、仅补导航）。拦截器确认 401 失效时由
        //     invalidateSession 置位 pendingInvalidationEpoch 事件（记录被失效会话的 epoch；唯它置位，setTokens 新登录、clearTokens 登出各自复位为 null），三条路径的前台
        //     导航统一经 foregroundInvalidateNav(silent, token, epoch, code) 四重门裁决：前台（!silent）&& 令牌快照非空（token，本请求确属某会话）
        //     && 本响应为 401（code，排除迟到 200/500/SESSION_CHANGED）&& epoch 归属匹配（pendingInvalidationEpoch===epoch，本请求确属「被失效」的那个会话，排除跨会话/新登录/登出），才 showActivation 恰一次（消费即复位去重），silent 从不触发。
        //     本恢复层以 { silent: true } 发起授权查询（见 _recoverOnce），故上述路径只同步会话状态（clearTokens、v12Authorized 落盘）、不触发
        //     showActivation 全局拉回（T13-08 承 P2#1 闭环；round-4 跨会话竞态修复见 foregroundInvalidateNav 四重门 + sessionEpoch 归属；round-5 于刷新成功回调（successCb）重放前增 sessionEpoch 守卫——刷新成功→重放系独立微任务，此窗口内登录/登出致 epoch 变则不重放、转 SESSION_CHANGED）。不清会话的 401：①刷新等待期会话已变（新令牌已建立，「刷新失败分支」clearTokens 守卫
        //     getToken()===token 判否）→ 抛 401、保留新令牌；②通过前置会话检查后 401 来自登录/刷新端点（url===REFRESH_URL 或
        //     /auth/wechat-login）→ 原样抛出、不清会话（会话于请求期间已变则先经「重放/SESSION_CHANGED 分支」：命中同会话刷新轮换
        //     例外 lastRotation 则重放一次，否则转 SESSION_CHANGED）。showActivation 是否真重定向还取决于当前是否已在激活页（见其内
        //     current.route!=='pages/auth/index' 判据）。无论哪条，恢复层都不主动 clearTokens。
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
        // 登录落定后、发起授权查询前复查生命周期：用户已在登录阶段离页则不再发起 getAccessGrant（round-3
        // 修复 P2，收窄窗口）；授权查询再以 silent 发起（T13-08 承 P2#1 闭环），grant 已在途后离页的迟到 401
        // 只同步会话状态、不再触发请求层 showActivation 全局拉回。
        if (self._leftPage || self.activated) return null;
        return api.getAccessGrant({ silent: true });
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
