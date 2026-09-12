'use strict';
const api = require('./api');

function options(entry) {
  const result = [];
  if (!entry || typeof entry !== 'object') return result;
  if (typeof entry.phone === 'string' && /^\+?[0-9][0-9 -]{4,19}$/.test(entry.phone))
    result.push({ label: '拨打客服电话', type: 'phone', value: entry.phone.replace(/[ -]/g, '') });
  if (/^[A-Za-z0-9_-]{4,64}$/.test(entry.wecomCorpId || '') && /^https:\/\/work\.weixin\.qq\.com\/(kf|kfid)\/[^\s#]+$/.test(entry.wecomUrl || ''))
    result.push({ label: '企业微信客服', type: 'wecom', value: entry.wecomUrl, corpId: entry.wecomCorpId });
  if (typeof entry.helpUrl === 'string' && /^https:\/\/[A-Za-z0-9.-]+(?::443)?\/[^\s]*$/.test(entry.helpUrl))
    result.push({ label: '复制帮助链接', type: 'help', value: entry.helpUrl });
  return result;
}

function contact() {
  return api.getSupportEntry().then(entry => {
    const choices = options(entry);
    if (!choices.length) {
      wx.showModal({ title: '客服暂未开通', content: '当前没有可用的客服联系方式，请稍后再试。', showCancel: false });
      return;
    }
    wx.showActionSheet({ itemList: choices.map(item => item.label), success: result => {
      const choice = choices[result.tapIndex];
      if (!choice) return;
      const fail = () => wx.showToast({ title: '暂时无法打开，请重试或选择其他联系方式', icon: 'none' });
      if (choice.type === 'phone') wx.makePhoneCall({ phoneNumber: choice.value, fail });
      if (choice.type === 'help') wx.setClipboardData({ data: choice.value, fail });
      if (choice.type === 'wecom') {
        if (typeof wx.openCustomerServiceChat !== 'function') { fail(); return; }
        wx.openCustomerServiceChat({ corpId: choice.corpId, extInfo: { url: choice.value }, fail });
      }
    } });
  }).catch(() => wx.showToast({ title: '客服配置加载失败，请重试', icon: 'none' }));
}
module.exports = { contact, options };
