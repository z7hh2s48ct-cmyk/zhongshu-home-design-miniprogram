'use strict';

const { openFeature } = require('../../utils/access');
const api = require('../../utils/api');
const assets = require('../../utils/assets');
const format = require('../../utils/format');

// 精选卡片内置默认占位（接口失败/未返回时兜底），接口数据回来后按位覆盖
const DEFAULT_FEATURED = [
  { id: null, title: '云栖雅院', meta: '建面 168㎡ · 2层 · 新中式', image: '/assets/v12/villa-classic.jpg' },
  { id: null, title: '清和小筑', meta: '建面 136㎡ · 2层 · 现代', image: '/assets/v12/villa-modern.jpg' }
];

Page({
  data: {
    featuredCases: DEFAULT_FEATURED,
    unreadCount: 0,
    accessGrantStatus: 'ANONYMOUS'
  },
  onShow() {
    getApp().homeSeen = true;
    this.loadHome();
  },
  loadHome() {
    const self = this;
    api.getHome().then(function (data) {
      if (!data) return;
      self.setData({
        unreadCount: data.unreadCount || 0,
        accessGrantStatus: data.accessGrantStatus || 'ANONYMOUS'
      });
      const featured = (data.featuredCases || []).slice(0, DEFAULT_FEATURED.length);
      featured.forEach(function (c, index) {
        self.setData({
          ['featuredCases[' + index + ']']: {
            id: c.caseId,
            title: c.title,
            meta: '建面 ' + (c.buildingArea || '—') + '㎡ · ' + (c.floorCount || '—') + '层 · ' + format.styleLabel(c.styleCode),
            image: assets.cachedAssetDataUrl(c.coverAssetId) || self.data.featuredCases[index].image,
            coverAssetId: c.coverAssetId
          }
        });
        if (c.coverAssetId) {
          assets.fetchAssetDataUrl(c.coverAssetId).then(function (url) {
            self.setData({ ['featuredCases[' + index + '].image']: url });
          }).catch(function () { /* 保留占位图 */ });
        }
      });
    }).catch(function () { /* 首页容错：保留默认精选 */ });
  },
  openAi() { openFeature('/pages/ai-design/index'); },
  openLibrary() { openFeature('/pages/library/index'); },
  openDetail(e) {
    const id = e && e.currentTarget && e.currentTarget.dataset ? e.currentTarget.dataset.id : null;
    openFeature('/pages/library/detail' + (id ? '?id=' + id : ''));
  },
  openBudget() { openFeature('/pages/budget/input'); }
});
