'use strict';

const { safeTarget, navigate, returnHome, refreshFromServer } = require('../../utils/access');
const http = require('../../utils/request');
const api = require('../../utils/api');

Page({
  data: { code: '', accessReady: false, loading: false },
  onLoad(options) {
    if (!getApp().homeSeen) { returnHome(); return; }
    try {
      const redirect = options.redirect || '';
      this.target = safeTarget(redirect.startsWith('/') ? redirect : decodeURIComponent(redirect));
    } catch (e) {
      this.target = '/pages/home/index';
    }
    this.setData({ accessReady: true });
  },
  onInput(event) { this.setData({ code: event.detail.value.trim() }); },

  ensureLoggedIn() {
    if (http.getToken()) return Promise.resolve();
    return new Promise(function (resolve, reject) {
      wx.login({ success: function (res) { resolve(res.code); }, fail: function () { reject({ msg: '微信登录失败' }); } });
    }).then(function (code) { return api.login(code); })
      .then(function (data) { http.setTokens(data.accessToken, data.refreshToken); });
  },

  // 激活成功统一收口：先写状态再导航，写入失败必须留在本页
  activate(target) {
    try {
      wx.setStorageSync('v12Authorized', true);
    } catch (e) {
      wx.showToast({ title: '激活状态写入失败，请重试', icon: 'none' });
      return;
    }
    refreshFromServer();
    wx.showToast({ title: '激活成功', icon: 'success' });
    navigate(target || '/pages/home/index', true);
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

  continueBrowsing() { wx.navigateBack({ fail: returnHome }); },
  contact() { wx.showToast({ title: '请联系授权管理员', icon: 'none' }); }
});
