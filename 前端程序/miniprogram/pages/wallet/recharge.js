"use strict";

const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const view = require('../../utils/record-view');

protectedPage({
  data: {
    selected: 0,
    packages: [],
    loading: true,
    points: '—',
    paying: false
  },
  onLoad() { this.loadPlans(); },
  onShow() { this.refreshPoints(); },
  refreshPoints() {
    const self = this;
    api.getPointAccount().then(function (account) {
      self.setData({ points: account.availablePoints });
    }).catch(function () { /* 静默保留占位 */ });
  },
  loadPlans() {
    var self = this;
    api.listRechargePlans().then(function (plans) {
      self.setData({
        packages: (plans || []).map(function (p, i) {
          return {
            price: p.amountCents / 100,
            base: p.basePoints,
            bonus: p.bonusPoints,
            total: p.basePoints + p.bonusPoints,
            planId: p.planId,
            recommended: p.recommended
          };
        }),
        loading: false,
        selected: 0
      });
    }).catch(function () {
      self.setData({ loading: false });
      wx.showToast({ title: '加载充值方案失败', icon: 'none' });
    });
  },
  selectPackage(event) { this.setData({ selected: Number(event.currentTarget.dataset.index) }); },
  openRecords() { wx.navigateTo({ url: '/pages/profile/records?type=orders' }); },
  pay() {
    const current = this.data.packages[this.data.selected];
    if (!current || !current.planId) return;
    // 涉及真实下单：点击防重 + 幂等键，快速连点不重复建单
    if (this.data.paying) return;
    const self = this;
    self.setData({ paying: true });
    const idemKey = 'recharge-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10);
    api.createRechargeOrder(current.planId, idemKey).then(function (order) {
      // P8B 真实支付通道下发 payParams 时拉起微信支付；Stub 通道无该字段，直接进入查单页
      if (order && order.payParams && typeof wx.requestPayment === 'function') {
        wx.requestPayment(Object.assign({}, order.payParams, {
          success: function () { self.toSuccessPage(order); },
          fail: function () {
            self.setData({ paying: false });
            wx.showToast({ title: '支付未完成', icon: 'none' });
          }
        }));
        return;
      }
      self.toSuccessPage(order);
    }).catch(function (err) {
      wx.showToast({ title: (err && err.msg) || '创建订单失败', icon: 'none' });
      self.setData({ paying: false });
    });
  },
  toSuccessPage(order) {
    this.setData({ paying: false });
    if (!order || !view.id(order.orderId)) { wx.showToast({ title: '订单未确认，请查看充值记录', icon: 'none' }); return; }
    wx.navigateTo({
      url: '/pages/payment/success?orderId=' + order.orderId,
      fail: function () { wx.showToast({ title: '订单已保存，请从充值记录查看', icon: 'none' }); }
    });
  }
});
