'use strict';
const { isAuthorized, openFeature, openActivation } = require('../../utils/access');
Page({
  data: { authorized: false },
  onShow() { this.setData({ authorized: isAuthorized() }); },
  openEdit() { openFeature('/pages/profile/edit'); },
  openAuthorization() {
    if (!this.data.authorized) { openActivation('/pages/profile/services'); return; }
    wx.showModal({ title: '授权信息', content: '当前账号已激活，可正常使用全部功能。', showCancel: false, confirmColor: '#7b5532' });
  },
  showPlaceholder() { wx.showToast({ title: '功能建设中，敬请期待', icon: 'none' }); },
  contact() { wx.showToast({ title: '请联系授权管理员', icon: 'none' }); }
});
