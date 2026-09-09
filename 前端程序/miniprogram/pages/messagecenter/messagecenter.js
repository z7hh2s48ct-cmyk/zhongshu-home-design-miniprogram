'use strict';

const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const format = require('../../utils/format');
const unread = require('../../utils/unread-count');
const view = require('../../utils/record-view');

protectedPage({
  data: { messages: [], unread: 0, loading: true, nextCursor: null, loadingMore: false, error: '' },
  onShow() { this.load(); },
  onUnload() { this._seq = (this._seq || 0) + 1; },
  onReachBottom() { this.loadMore(); },
  load() {
    const self = this;
    this._seq = (this._seq || 0) + 1;
    const seq = this._seq;
    this.setData({ messages: [], nextCursor: null, loading: true, loadingMore: false, error: '' });
    const request = this.loadPage(null, seq);
    api.getUnreadCount().then(function (count) {
      if (self._seq !== seq) return;
      self.setData({ unread: unread.setUnreadCount(count) });
    }).catch(function () { /* 静默 */ });
    return request;
  },
  loadMore() {
    if (this.data.loading || this.data.loadingMore || !this.data.nextCursor) return;
    this.setData({ loadingMore: true, error: '' });
    return this.loadPage(this.data.nextCursor, this._seq);
  },
  loadPage(cursor, seq) {
    return api.listMessages(cursor, 50).then(page => {
      if (seq !== this._seq) return;
      const incoming = ((page && page.list) || []).map(m => ({
        id: m.messageId, type: m.messageType, title: m.title, content: m.content,
        bizType: m.bizType, bizId: m.bizId, read: m.read, time: format.shortTime(m.sentAt)
      }));
      const existing = cursor ? this.data.messages : [];
      const ids = new Set(existing.map(m => m.id));
      this.setData({ messages: existing.concat(incoming.filter(m => !ids.has(m.id))),
        nextCursor: page && page.nextCursor || null, loading: false, loadingMore: false });
    }).catch(() => {
      if (seq === this._seq) this.setData({ loading: false, loadingMore: false, error: '消息加载失败，请重试' });
    });
  },
  retry() { return this.data.nextCursor ? this.loadMore() : this.load(); },
  openBusiness(message) {
    const id = view.id(message.bizId);
    if (!id) return;
    let url;
    if (message.bizType === 'case_submission') url = '/pages/profile/record?type=submissions&id=' + id;
    if (message.bizType === 'design_project') url = '/pages/profile/record?type=projects&id=' + id;
    if (message.bizType === 'recharge_order') url = '/pages/payment/success?orderId=' + id;
    if (message.bizType === 'ai_job') {
      return api.getAiJob(id).then(job => {
        if (job && view.id(job.projectId)) wx.navigateTo({ url: '/pages/profile/record?type=projects&id=' + job.projectId });
      }).catch(error => wx.showToast({ title: view.errorText(error), icon: 'none' }));
    }
    if (url) wx.navigateTo({ url });
  },
  onTap(e) {
    const id = e.currentTarget.dataset.id;
    const index = this.data.messages.findIndex(function (m) { return m.id === id; });
    if (index < 0) return;
    const message = this.data.messages[index];
    this.openBusiness(message);
    if (message.read) return;
    // 本地先行置已读并扣减角标，回执失败不打断（下次进入按服务端为准）
    const update = {};
    update['messages[' + index + '].read'] = true;
    this.setData(update);
    this.setData({ unread: unread.decrementUnreadCount() });
    api.markMessageRead(id).then(function () { /* 已读回执 */ }).catch(function () { /* 静默 */ });
  }
});
