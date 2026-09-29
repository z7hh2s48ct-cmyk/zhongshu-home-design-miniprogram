'use strict';
const api = require('./api');

function quote(stage, count, options) {
  const resolution = options && options.resolution || '2K';
  return api.getGenerationQuote(stage, count, resolution).then(value => {
    if (!value || value.stage !== stage || Number(value.count) !== count || value.resolution !== resolution || !value.ruleId
      || !Number.isSafeInteger(Number(value.totalPointCost)) || Number(value.totalPointCost) < 0) {
      throw { msg: '未取得有效报价，请重试' };
    }
    return value;
  });
}

function refresh(page, stage) {
  const seq = page._quoteSeq = (page._quoteSeq || 0) + 1;
  page.setData({ priceText: '正在获取报价…', quoteLoading: true, quoteReady: false, quoteError: '' });
  return quote(stage, page.data.count, page.data).then(value => {
    if (page._quoteSeq === seq) page.setData({ priceText: '单个 ' + value.unitPointCost + ' 点，合计 ' + value.totalPointCost + ' 设计点', quoteLoading: false, quoteReady: true, quoteError: '' });
  }).catch(() => {
    if (page._quoteSeq === seq) page.setData({ priceText: '报价暂不可用', quoteLoading: false, quoteReady: false, quoteError: '报价读取失败，请重试' });
  });
}

function confirm(stage, count, description, options) {
  const http = require('./request');
  const token = http.getToken();
  return quote(stage, count, options).then(value => new Promise((resolve, reject) => {
    wx.showModal({ title: '确认生成', content: '生成 ' + count + ' 个方案，本次消耗 ' + value.totalPointCost + ' 设计点。' + (description || ''),
      success: result => {
        if (!result.confirm) { reject({ cancelled: true }); return; }
        if (!http.isSameSession(token)) { reject({ msg: '登录身份已变化，请重试' }); return; }
        resolve({ ruleId: String(value.ruleId), ruleVersion: value.ruleVersion });
      }, fail: reject });
  }));
}
module.exports = { quote, refresh, confirm };
