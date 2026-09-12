'use strict';
const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const assets = require('../../utils/assets');

protectedPage({
  data: { selected: -1, elevations: [], projectId: null, loading: true, submitting: false, flatLabel: '' },
  onLoad(query) {
    const projectId = require('../../utils/record-view').id(query && query.projectId || getApp().globalData.projectId);
    if (!projectId) {
      wx.showToast({ title: '请先完成平面设计', icon: 'none' });
      wx.switchTab({ url: '/pages/ai-design/index' });
      return;
    }
    this.setData({ projectId: projectId, flatLabel: getApp().globalData.flatLabel || '' });
    this.loadCandidates();
  },
  loadCandidates() {
    const self = this;
    const jobId = getApp().globalData.jobId;
    // 立面候选同样拉式晋升；项目下候选含平面任务的历史候选，按 jobId 过滤出本阶段候选
    api.getProject(this.data.projectId, jobId).then(function (project) {
      const list = (project.candidates || [])
        .filter(function (c) { return !jobId || String(c.jobId) === String(jobId); })
        .map(function (c, i) {
        return {
          id: c.candidateId,
          letter: String.fromCharCode(65 + i),
          name: '方案 ' + String.fromCharCode(65 + i),
          slot: c.slotNo || (i + 1),
          assetId: c.assetId,
          image: ''
        };
      });
      self.setData({ elevations: list, loading: false });
      list.forEach(function (item, i) {
        if (!item.assetId) return;
        assets.fetchAssetDataUrl(item.assetId).then(function (url) {
          const update = {};
          update['elevations[' + i + '].image'] = url;
          self.setData(update);
        }).catch(function () { /* 候选图加载失败保留占位 */ });
      });
    }).catch(function (err) {
      self.setData({ loading: false });
      wx.showToast({ title: (err && err.msg) || '立面候选加载失败', icon: 'none' });
    });
  },
  select(e) { this.setData({ selected: Number(e.currentTarget.dataset.index) }); },
  confirm() {
    const sel = this.data.elevations[this.data.selected];
    if (!sel) { wx.showToast({ title: '请先选择方案', icon: 'none' }); return; }
    if (this.data.submitting) return;
    const self = this;
    self.setData({ submitting: true });
    // 选定立面即生成最终结果版本（服务端要求 jobId+candidateId），返回 resultVersionId 供结果页/投稿/预算引用
    api.selectElevation(this.data.projectId, getApp().globalData.jobId, sel.id).then(function (vo) {
      const global = getApp().globalData;
      global.selectedElevationId = sel.id;
      global.selectedElevationAssetId = sel.assetId;
      global.elevationLabel = sel.name;
      global.resultVersionId = (vo && vo.resultVersionId) ? vo.resultVersionId : null;
      wx.redirectTo({ url: '/pages/ai-design/result' });
    }).catch(function (err) {
      self.setData({ submitting: false });
      wx.showToast({ title: (err && err.msg) || '选定失败，请重试', icon: 'none' });
    });
  },
  regenerate() {
    if (this._regenerating) return;
    if (!getApp().globalData.elevationConfig) {
      wx.navigateTo({ url: '/pages/ai-design/elevation-setup' });
      return;
    }
    this._regenerating = true;
    const self = this;
    // 复用立面设置页采集的参数，数量以当前候选数为准
    const config = Object.assign({}, getApp().globalData.elevationConfig,
      { count: this.data.elevations.length || 2 });
    const idemKey = 'elev-regen-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10);
    require('../../utils/generation-price').confirm('ELEVATION', config.count).then(function (price) {
      config.priceConfirmation = price;
      return api.createElevationJob(self.data.projectId, config, idemKey);
    }).then(function (job) {
      getApp().globalData.jobId = job.jobId;
      wx.redirectTo({ url: '/pages/ai-design/generating?stage=elevation&count=' + (config.count || 2) });
    }).catch(function (err) {
      if (!err || !err.cancelled) wx.showToast({ title: (err && err.msg) || '重新生成失败', icon: 'none' });
      self._regenerating = false;
    });
  }
});
