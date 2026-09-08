'use strict';

const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const assets = require('../../utils/assets');
const format = require('../../utils/format');

protectedPage({
  data: {
    caseDetail: null, loading: true, tab: 0, favorite: false,
    tabImages: ['', '', '']
  },
  onLoad(options) {
    const caseId = options && options.id;
    if (!caseId) {
      wx.showToast({ title: '请从户型库选择案例', icon: 'none' });
      wx.navigateBack({ fail: function () { wx.switchTab({ url: '/pages/library/index' }); } });
      return;
    }
    this.loadDetail(caseId);
    this.loadFavoriteState(caseId);
  },
  loadDetail(caseId) {
    const self = this;
    api.getCaseDetail(caseId).then(function (detail) {
      const style = format.styleLabel(detail.parameters.styleCode);
      self.setData({
        loading: false,
        caseDetail: {
          id: detail.caseId, title: detail.title, description: detail.description,
          sourceLabel: (detail.sourceType === 'AI' ? 'AI案例' : '公司案例') + (style ? ' · ' + style : ''),
          meta: (detail.parameters.buildingArea || '—') + 'm² / ' + (detail.parameters.floorCount || '—') + '层 / 面宽'
            + (detail.parameters.faceWidth || '—') + 'm / 进深' + (detail.parameters.depth || '—') + 'm',
          coverAssetId: detail.coverAssetId,
          floorPlanAssetIds: detail.floorPlanAssetIds || [],
          elevationAssetId: detail.elevationAssetId
        }
      });
      self.loadTabImages(detail);
    }).catch(function () {
      self.setData({ loading: false });
      wx.showToast({ title: '加载失败', icon: 'none' });
    });
  },
  // 立面→一层平面→二层平面；资产取不到时保留内置手绘图兜底
  loadTabImages(detail) {
    const self = this;
    const sources = [detail.elevationAssetId].concat((detail.floorPlanAssetIds || []).slice(0, 2));
    sources.forEach(function (assetId, index) {
      if (!assetId) return;
      assets.fetchAssetDataUrl(assetId).then(function (url) {
        const update = {};
        update['tabImages[' + index + ']'] = url;
        self.setData(update);
      }).catch(function () { /* 保留兜底图 */ });
    });
  },
  loadFavoriteState(caseId) {
    const self = this;
    api.listFavorites(null, 50).then(function (page) {
      const ids = (page.list || []).map(function (c) { return String(c.caseId); });
      self.setData({ favorite: ids.indexOf(String(caseId)) >= 0 });
    }).catch(function () { /* 查询失败按未收藏处理 */ });
  },
  selectTab(e) { this.setData({ tab: Number(e.currentTarget.dataset.index) }); },
  toggleFavorite() {
    const detail = this.data.caseDetail;
    if (!detail) return;
    const next = !this.data.favorite;
    const self = this;
    (next ? api.favorite(detail.id) : api.unfavorite(detail.id)).then(function () {
      self.setData({ favorite: next });
      wx.showToast({ title: next ? '已收藏' : '已取消收藏', icon: 'success' });
    }).catch(function (err) {
      wx.showToast({ title: (err && err.msg) || '操作失败', icon: 'none' });
    });
  },
  useHouse() {
    const detail = this.data.caseDetail;
    if (!detail) return;
    // switchTab 无法携带 query，经全局数据把参考案例带给 AI 设计页
    getApp().globalData.refCase = { caseId: detail.id, title: detail.title };
    wx.switchTab({ url: '/pages/ai-design/index' });
  }
});
