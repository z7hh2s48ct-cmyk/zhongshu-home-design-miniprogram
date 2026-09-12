'use strict';
const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const assets = require('../../utils/assets');

protectedPage({
  data: { selected: -1, candidates: [], projectId: null, loading: true, submitting: false },
  onLoad(query) {
    const projectId = require('../../utils/record-view').id(query && query.projectId || getApp().globalData.projectId);
    if (!projectId) {
      wx.showToast({ title: '请先创建设计任务', icon: 'none' });
      wx.switchTab({ url: '/pages/ai-design/index' });
      return;
    }
    this.setData({ projectId: projectId });
    this.loadCandidates();
  },
  loadCandidates() {
    const self = this;
    const jobId = getApp().globalData.jobId;
    // 候选是拉式晋升的：带 jobId 查询项目详情，后端先把该任务已接受结果落为候选再下发；
    // 项目下候选含历史任务（平面+立面），必须按 jobId 过滤出本阶段候选
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
      self.setData({ candidates: list, loading: false });
      list.forEach(function (item, i) {
        if (!item.assetId) return;
        assets.fetchAssetDataUrl(item.assetId).then(function (url) {
          const update = {};
          update['candidates[' + i + '].image'] = url;
          self.setData(update);
        }).catch(function () { /* 候选图加载失败保留占位 */ });
      });
    }).catch(function (err) {
      self.setData({ loading: false });
      wx.showToast({ title: (err && err.msg) || '候选加载失败', icon: 'none' });
    });
  },
  select(e) { this.setData({ selected: Number(e.currentTarget.dataset.index) }); },
  confirm() {
    const sel = this.data.candidates[this.data.selected];
    if (!sel) { wx.showToast({ title: '请先选择方案', icon: 'none' }); return; }
    if (this.data.submitting) return;
    const self = this;
    self.setData({ submitting: true });
    // 选定必须落库（服务端要求 jobId+candidateId）：立面任务要求引用已选平面候选
    api.selectFlat(this.data.projectId, getApp().globalData.jobId, sel.id).then(function () {
      const global = getApp().globalData;
      global.selectedCandidateId = sel.id;
      global.selectedFlatAssetId = sel.assetId;
      global.flatLabel = sel.name;
      wx.redirectTo({ url: '/pages/ai-design/elevation-setup' });
    }).catch(function (err) {
      self.setData({ submitting: false });
      wx.showToast({ title: (err && err.msg) || '选定失败，请重试', icon: 'none' });
    });
  },
  regenerate() {
    if (this._regenerating) return;
    this._regenerating = true;
    const count = this.data.candidates.length || 2;
    const self = this;
    const idemKey = 'flat-regen-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10);
    require('../../utils/generation-price').confirm('FLAT', count).then(function (price) {
      return api.createFlatJob(self.data.projectId, count, idemKey, price);
    }).then(function (job) {
      getApp().globalData.jobId = job.jobId;
      wx.redirectTo({ url: '/pages/ai-design/generating?stage=plane&count=' + count });
    }).catch(function (err) {
      if (!err || !err.cancelled) wx.showToast({ title: (err && err.msg) || '重新生成失败', icon: 'none' });
      self._regenerating = false;
    });
  }
});
