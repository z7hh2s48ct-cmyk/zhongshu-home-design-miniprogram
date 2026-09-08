"use strict";

const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');

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
  openRecords() { wx.showToast({ title: '充值记录建设中，可在「我的」查看余额', icon: 'none' }); },
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
          success: function () { self.toSuccessPage(current, order); },
          fail: function () {
            self.setData({ paying: false });
            wx.showToast({ title: '支付未完成', icon: 'none' });
          }
        }));
        return;
      }
      self.toSuccessPage(current, order);
    }).catch(function (err) {
      wx.showToast({ title: (err && err.msg) || '创建订单失败', icon: 'none' });
      self.setData({ paying: false });
    });
  },
  toSuccessPage(current, order) {
    this.setData({ paying: false });
    wx.navigateTo({
      url: '/pages/payment/success?price=' + current.price.toFixed(2)
        + '&base=' + current.base + '&bonus=' + current.bonus
        + '&total=' + current.total + '&orderId=' + (order.orderId || '')
    });
  }
});
