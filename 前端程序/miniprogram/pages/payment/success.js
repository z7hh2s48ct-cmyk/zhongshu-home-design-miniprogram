'use strict';

const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');

protectedPage({
  data: { price: 0, base: 0, bonus: 0, total: 0,
          orderId: '', paymentState: 'PENDING', fulfillmentState: 'NOT_READY',
          paymentStateText: '处理中', polling: true },
  onLoad(options) {
    this.setData({
      price: options.price || 0, base: options.base || 0,
      bonus: options.bonus || 0, total: options.total || 0,
      orderId: options.orderId || ''
    });
    if (this.data.orderId) this.poll();
    else this.setData({ polling: false, paymentStateText: '订单信息缺失' });
  },
  onUnload() { this.setData({ polling: false }); },
  // 状态以服务端 payment_state + fulfillment_state 为准，不信页面参数
  applyOrder(order) {
    const payment = order.paymentState || 'UNKNOWN';
    const fulfillment = order.fulfillmentState || 'NOT_READY';
    let text = '处理中';
    if (payment === 'SUCCEEDED' && fulfillment === 'CREDITED') text = '已完成';
    else if (payment === 'SUCCEEDED') text = '已支付，到账确认中';
    else if (payment === 'UNKNOWN') text = '支付结果确认中';
    else if (payment === 'FAILED') text = '支付失败';
    this.setData({ paymentState: payment, fulfillmentState: fulfillment, paymentStateText: text });
    return payment === 'SUCCEEDED' && fulfillment === 'CREDITED';
  },
  poll() {
    if (!this.data.polling) return;
    const self = this;
    api.getRechargeOrder(this.data.orderId).then(function (order) {
      const done = self.applyOrder(order);
      if (done || order.paymentState === 'FAILED') {
        self.setData({ polling: false });
      } else if (self.data.polling) {
        setTimeout(function () { self.poll(); }, 2000);
      }
    }).catch(function () {
      self.setData({ polling: false, paymentStateText: '查询失败，请稍后刷新' });
    });
  },
  backAi() { wx.switchTab({ url: '/pages/ai-design/index' }); },
  viewPoints() { wx.switchTab({ url: '/pages/profile/index' }); }
});
