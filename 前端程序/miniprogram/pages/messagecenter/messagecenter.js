'use strict';

const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const format = require('../../utils/format');

protectedPage({
  data: { messages: [], unread: 0, loading: true },
  onShow() { this.load(); },
  load() {
    const self = this;
    api.listMessages(null, 50).then(function (page) {
      self.setData({
        messages: ((page && page.list) || []).map(function (m) {
          return { id: m.messageId, type: m.messageType, title: m.title,
                   content: m.content, read: m.read, time: format.shortTime(m.sentAt) };
        }),
        loading: false
      });
    }).catch(function () {
      self.setData({ loading: false });
      wx.showToast({ title: '消息加载失败', icon: 'none' });
    });
    api.getUnreadCount().then(function (count) {
      self.setData({ unread: count || 0 });
    }).catch(function () { /* 静默 */ });
  },
  onTap(e) {
    const id = e.currentTarget.dataset.id;
    const index = this.data.messages.findIndex(function (m) { return m.id === id; });
    if (index < 0) return;
    const message = this.data.messages[index];
    if (message.read) return;
    // 本地先行置已读并扣减角标，回执失败不打断（下次进入按服务端为准）
    const update = {};
    update['messages[' + index + '].read'] = true;
    this.setData(update);
    this.setData({ unread: Math.max((this.data.unread || 0) - 1, 0) });
    api.markMessageRead(id).then(function () { /* 已读回执 */ }).catch(function () { /* 静默 */ });
  }
});
