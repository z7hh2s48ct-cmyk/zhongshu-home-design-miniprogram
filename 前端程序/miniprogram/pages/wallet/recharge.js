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
      // T13-24：真实支付通道下发 payParams 时拉起微信支付；Stub 通道无该字段，直接进入查单页
      if (order && order.payParams && typeof wx.requestPayment === 'function') {
        self.invokePayment(order, order.payParams);
        return;
      }
      self.toSuccessPage(order);
    }).catch(function (err) {
      wx.showToast({ title: (err && err.msg) || '创建订单失败', icon: 'none' });
      self.setData({ paying: false });
    });
  },
  /**
   * T13-24：拉起微信支付 + fail 分支区分 cancel / 签名失效 / 网络错误。
   * cancel 与签名失效允许「继续支付」重领 payParams（禁止重复建单，同 orderNo 幂等）；
   * 网络错误提示重试。success 回调只跳查单页，禁止直接加点（分派表 §8 红线 3）。
   */
  invokePayment(order, payParams) {
    const self = this;
    wx.requestPayment(Object.assign({}, payParams, {
      success: function () { self.toSuccessPage(order); },
      fail: function (res) {
        self.setData({ paying: false });
        const errMsg = (res && res.errMsg) || '';
        // 用户主动取消：允许继续支付（重领 payParams）
        if (errMsg.indexOf('cancel') !== -1) {
          self.offerResumePayment(order);
          return;
        }
        // 签名失效 / 支付参数过期：允许继续支付（重领 payParams）
        if (errMsg.indexOf('sign') !== -1 || errMsg.indexOf('signature') !== -1
            || errMsg.indexOf('过期') !== -1 || errMsg.indexOf('失效') !== -1) {
          wx.showToast({ title: '支付参数已过期，请重新发起', icon: 'none' });
          self.offerResumePayment(order);
          return;
        }
        // 网络错误 / 其他：提示重试，不自动重领（避免循环）
        wx.showToast({ title: '支付未完成，请从充值记录继续', icon: 'none' });
      }
    }));
  },
  /**
   * T13-24：恢复流程——弹窗询问是否继续支付，确认后重领 payParams 再次拉起。
   * 禁止重复建单：走 GET /recharge-orders/{orderId}/pay-params 独立端点，不重新 createOrder。
   */
  offerResumePayment(order) {
    const self = this;
    wx.showModal({
      title: '继续支付',
      content: '订单尚未完成支付，是否继续？',
      confirmText: '继续支付',
      cancelText: '稍后再说',
      success: function (modalRes) {
        if (!modalRes.confirm) return;
        self.setData({ paying: true });
        api.getPayParams(order.orderId).then(function (params) {
          if (params && typeof wx.requestPayment === 'function') {
            self.invokePayment(order, params);
          } else {
            self.setData({ paying: false });
            self.toSuccessPage(order);
          }
        }).catch(function () {
          self.setData({ paying: false });
          wx.showToast({ title: '订单状态已变更，请查看充值记录', icon: 'none' });
        });
      }
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
