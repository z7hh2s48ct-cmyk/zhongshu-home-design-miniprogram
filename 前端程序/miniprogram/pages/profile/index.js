'use strict';

const { isAuthorized, openFeature, openActivation } = require('../../utils/access');
const api = require('../../utils/api');
const unread = require('../../utils/unread-count');
const http = require('../../utils/request');
const assets = require('../../utils/assets');

// 「我的」是公开基础页：未激活展示访客态，不虚构余额与统计；
// 业务动作（充值/消息）走统一门禁，激活管理在本页完成。
Page({
  data: {
    nickname: '', avatar: '', authorized: false,
    availablePoints: 0, reservedPoints: 0,
    projectCount: 0, publishedCount: 0, unreadCount: 0
  },
  onShow() { this.load(); },
  onUnload() { this._loadId = (this._loadId || 0) + 1; },
  load() {
    // 本地授权快照先行渲染，服务端状态回来后纠正
    this.setData({ authorized: isAuthorized(), unreadCount: unread.getUnreadCount() });
    this.loadUnreadCount();
    const self = this;
    const token = http.getToken();
    const loadId = this._loadId = (this._loadId || 0) + 1;
    const current = () => self._loadId === loadId && (http.isSameSession ? http.isSameSession(token) : http.getToken() === token);
    if (!token) {
      this.setData({ nickname: '', avatar: '', availablePoints: 0, reservedPoints: 0, projectCount: 0, publishedCount: 0 });
      return;
    }
    api.getProfile().then(function (p) {
      if (!current()) return;
      self.setData({
        nickname: p.nickname || '用户',
        avatar: p.avatar || '',
        authorized: p.accessGrantStatus === 'ACTIVE',
        projectCount: (p.stats && p.stats.projectCount) || 0,
        publishedCount: (p.stats && p.stats.publishedCount) || 0
      });
      if (p.avatarAssetId) assets.fetchProfileAvatar(p.avatarAssetId).then(avatar => {
        if (current()) self.setData({ avatar });
      }).catch(() => {});
    }).catch(function () { /* 静默：保留本地快照 */ });
    api.getPointAccount().then(function (a) {
      if (!current()) return;
      self.setData({ availablePoints: a.availablePoints || 0, reservedPoints: a.reservedPoints || 0 });
    }).catch(function () { /* 静默 */ });
  },
  loadUnreadCount() {
    unread.refreshUnreadCount()
      .then((count) => this.setData({ unreadCount: count }))
      .catch(function () { /* 静默：保留最近一次已知数量 */ });
  },
  openAuthorization() {
    if (this.data.authorized) {
      wx.showModal({
        title: '授权信息', content: '当前账号已激活，可正常使用全部功能。',
        showCancel: false, confirmText: '知道了', confirmColor: '#7b5532'
      });
      return;
    }
    openActivation('/pages/profile/index');
  },
  openRecharge() { openFeature('/pages/wallet/recharge'); },
  openMessages() { openFeature('/pages/messagecenter/messagecenter'); },
  openEdit() { openFeature('/pages/profile/edit'); },
  openServices() { openFeature('/pages/profile/services'); },
  openRecords(event) {
    const type = event.currentTarget.dataset.type;
    if (['projects', 'submissions', 'favorites', 'orders'].includes(type)) openFeature('/pages/profile/records?type=' + type);
  },
  contact() { wx.showToast({ title: '请联系授权管理员开通', icon: 'none' }); }
});
