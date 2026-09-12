'use strict';
const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const http = require('../../utils/request');
const view = require('../../utils/record-view');

protectedPage({
  data: { price: '—', base: '—', bonus: '—', total: '—', orderId: '', paymentState: '', fulfillmentState: '',
    paymentStateText: '正在查询', paymentTitle: '订单详情', paymentHint: '到账结果以服务端确认为准', polling: false, paying: false, canPay: false, error: '' },
  onLoad(options) { this.setData({ orderId: view.id(options.orderId) || '' }); },
  onShow() { this.refresh(); },
  onHide() { this.stop(); },
  onUnload() { this.stop(); this._token = null; },
  stop() { this._seq = (this._seq || 0) + 1; clearTimeout(this._timer); this.setData({ polling: false, paying: false }); },
  current(seq) {
    if (seq !== this._seq) return false;
    if (this._token && (http.isSameSession ? http.isSameSession(this._token) : this._token === http.getToken())) return true;
    this.stop();
    this.setData({ canPay: false, price: '—', base: '—', bonus: '—', total: '—', paymentState: '', fulfillmentState: '', paymentStateText: '身份已变化', paymentTitle: '订单详情', paymentHint: '', error: '请重新登录后读取订单' });
    return false;
  },
  refresh() {
    this.stop(); this._token = http.getToken(); this._attempts = 0;
    this.setData({ canPay: false, price: '—', base: '—', bonus: '—', total: '—', paymentState: '', fulfillmentState: '', paymentStateText: '正在查询', paymentTitle: '订单详情', paymentHint: '到账结果以服务端确认为准', error: '' });
    if (!this.data.orderId || !this._token) { this.setData({ error: '订单信息缺失或登录已失效，请从充值记录重新进入' }); return; }
    this.setData({ polling: true }); return this.poll();
  },
  applyOrder(order) {
    if (!order || order.orderId !== this.data.orderId) throw Error('订单数据不匹配');
    const refunded = order.refund && order.refund.channelState === 'SUCCEEDED';
    const credited = !order.refund && order.paymentState === 'SUCCEEDED' && order.fulfillmentState === 'CREDITED';
    const refundHint = order.refund ? '退款 ¥' + view.amount(order.refund.amountCents) + ' · ' + (order.refund.reason || '未填写退款原因') : '';
    const base = Number.isSafeInteger(order.basePoints) && order.basePoints >= 0 ? order.basePoints : null;
    const bonus = Number.isSafeInteger(order.bonusPoints) && order.bonusPoints >= 0 ? order.bonusPoints : null;
    const points = base != null && bonus != null ? base + bonus : '—';
    this.setData({ price: view.amount(order.amountCents), base: base ?? '—', bonus: bonus ?? '—', total: credited ? points : '—',
      paymentState: order.paymentState, fulfillmentState: order.fulfillmentState,
      canPay: !order.refund && ['CREATED', 'PENDING'].includes(order.paymentState),
      paymentStateText: view.payment(order), paymentTitle: credited ? '支付成功 · 已到账' : view.payment(order),
      paymentHint: refundHint || (credited ? points + ' 设计点已到账' : '未确认到账前，请勿重复下单') });
    return refunded || credited || (order.refund && order.refund.channelState === 'FAILED') || ['FAILED', 'CLOSED', 'CANCELLED', 'REFUNDED'].includes(order.paymentState);
  },
  resumePayment() {
    if (!this.data.canPay || this.data.paying || !this.current(this._seq)) return;
    this.stop();
    const seq = this._seq;
    this.setData({ paying: true, error: '' });
    return api.getPayParams(this.data.orderId).then(params => {
      if (!this.current(seq)) return;
      const keys = ['timeStamp', 'nonceStr', 'package', 'signType', 'paySign'];
      if (!params || keys.some(key => typeof params[key] !== 'string' || !params[key])) throw Error('支付参数尚未就绪，请刷新原订单后重试');
      wx.requestPayment(Object.assign({}, params, {
        success: () => { if (this.current(seq)) this.refresh(); },
        fail: () => { if (this.current(seq)) { this.setData({ paying: false }); this.refresh(); } }
      }));
    }).catch(error => {
      if (this.current(seq)) this.setData({ paying: false, error: view.errorText(error) });
    });
  },
  poll() {
    if (!this.data.polling) return;
    const seq = this._seq; this._attempts++;
    return api.getRechargeOrder(this.data.orderId).then(order => {
      if (!this.current(seq)) return;
      const done = this.applyOrder(order);
      if (done || this._attempts >= 10) this.setData({ polling: false });
      else this._timer = setTimeout(() => { if (this.current(seq)) this.poll(); }, 2000);
    }).catch(error => { if (this.current(seq)) this.setData({ polling: false, error: view.errorText(error) }); });
  },
  backAi() { wx.switchTab({ url: '/pages/ai-design/index' }); },
  viewPoints() { wx.switchTab({ url: '/pages/profile/index' }); },
  records() { wx.navigateTo({ url: '/pages/profile/records?type=orders' }); }
});
