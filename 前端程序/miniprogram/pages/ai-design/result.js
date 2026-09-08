'use strict';
const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const assets = require('../../utils/assets');

protectedPage({
  data: { tab: 0, versions: [], latest: null, versionLabel: '', imageUrl: '', loading: true },
  onLoad() { this.loadVersions(); },
  loadVersions() {
    const self = this;
    const projectId = getApp().globalData.projectId;
    if (!projectId) {
      wx.showToast({ title: '请先完成方案设计', icon: 'none' });
      wx.switchTab({ url: '/pages/ai-design/index' });
      return;
    }
    api.getResultVersions(projectId).then(function (page) {
      const list = page.list || [];
      const latest = list.filter(function (v) { return !v.superseded; }).pop()
        || list[list.length - 1] || null;
      self.setData({
        versions: list, latest: latest, loading: false,
        versionLabel: latest ? '版本 v' + latest.version : '暂无结果版本',
        elevationLabel: getApp().globalData.elevationLabel || ''
      });
      self.loadImages();
    }).catch(function (err) {
      self.setData({ loading: false });
      wx.showToast({ title: (err && err.msg) || '结果加载失败', icon: 'none' });
    });
  },
  // 立面 Tab 用选定立面候选图，平面 Tab 用选定平面候选图（COS 后换签名 URL，取图入口不变）
  loadImages() {
    const self = this;
    const global = getApp().globalData;
    const elevationAssetId = global.selectedElevationAssetId;
    const flatAssetId = global.selectedFlatAssetId;
    const apply = function (key, assetId) {
      if (!assetId) return;
      assets.fetchAssetDataUrl(assetId).then(function (url) {
        self.setData({ [key]: url });
      }).catch(function () { /* 保留占位 */ });
    };
    apply('imageUrl', elevationAssetId || flatAssetId);
    apply('flatImageUrl', flatAssetId);
  },
  selectTab(e) { this.setData({ tab: Number(e.currentTarget.dataset.index) }); },
  budget() { wx.navigateTo({ url: '/pages/budget/input' }); },
  publish() { wx.navigateTo({ url: '/pages/ai-design/publish' }); },
  adjust() { wx.navigateBack(); },
  save() { wx.showToast({ title: '方案已保存', icon: 'success' }); },
  regenerate() {
    const projectId = getApp().globalData.projectId;
    if (!projectId) return;
    const idemKey = 'revision-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10);
    api.createRevisionRequest(projectId, { reason: '用户发起重新生成', count: 2 }, idemKey)
      .then(function (vo) {
        getApp().globalData.jobId = vo.jobId;
        wx.redirectTo({ url: '/pages/ai-design/generating?stage=plane&count=2' });
      }).catch(function (err) {
        wx.showToast({ title: (err && err.msg) || '发起调整失败', icon: 'none' });
      });
  }
});
