'use strict';
const api = require('./api');

function quote(stage, count, options) {
  const resolution = options && options.resolution || '4K';
  return api.getGenerationQuote(stage, count, resolution).then(value => {
    if (!value || value.stage !== stage || Number(value.count) !== count || value.resolution !== resolution || !value.ruleId
      || value.usageProduct !== 'AI_PROMPT' || !value.usageRuleId
      || !Number.isSafeInteger(Number(value.usageRuleVersion)) || Number(value.usageRuleVersion) < 1
      || !Number.isSafeInteger(Number(value.usagePointCost)) || Number(value.usagePointCost) < 1
      || !Number.isSafeInteger(Number(value.totalPointCost)) || Number(value.totalPointCost) < 0) {
      throw { msg: '未取得有效报价，请重试' };
    }
    return value;
  });
}

function refresh(page, stage) {
  const seq = page._quoteSeq = (page._quoteSeq || 0) + 1;
  page.setData({ priceText: '正在获取报价…', quoteLoading: true, quoteReady: false, quoteError: '' });
  // 各档位单价（2K/4K 每张分）非阻断并行拉取：供档位卡片展示，失败保留占位文案
  ['2K', '4K'].forEach(resolution => {
    quote(stage, page.data.count, { resolution }).then(value => {
      if (page._quoteSeq !== seq) return;
      page.setData({ ['quoteByResolution.' + resolution]: value.totalPointCost + ' 点' });
    }).catch(() => {});
  });
  return quote(stage, page.data.count, page.data).then(value => {
    if (page._quoteSeq === seq) page.setData({ priceText: '出图 ' + value.totalPointCost + ' 点，提示词 ' + value.usagePointCost + ' 点', quoteLoading: false, quoteReady: true, quoteError: '' });
  }).catch(() => {
    if (page._quoteSeq === seq) page.setData({ priceText: '报价暂不可用', quoteLoading: false, quoteReady: false, quoteError: '报价读取失败，请重试' });
  });
}

function confirm(stage, count, description, options) {
  const http = require('./request');
  const token = http.captureSession ? http.captureSession() : http.getToken();
  return quote(stage, count, options).then(value => new Promise((resolve, reject) => {
    wx.showModal({ title: '确认生成', content: '出图 ' + value.totalPointCost + ' 点；提示词模型调用 ' + value.usagePointCost + ' 点。' + (description || ''),
      success: result => {
        if (!result.confirm) { reject({ cancelled: true }); return; }
        if (!http.isSameSession(token)) { reject({ msg: '登录身份已变化，请重试' }); return; }
        resolve({ ruleId: String(value.ruleId), ruleVersion: value.ruleVersion,
          usageConfirmation: { product: value.usageProduct, ruleId: String(value.usageRuleId), ruleVersion: value.usageRuleVersion } });
      }, fail: reject });
  }));
}
module.exports = { quote, refresh, confirm };
