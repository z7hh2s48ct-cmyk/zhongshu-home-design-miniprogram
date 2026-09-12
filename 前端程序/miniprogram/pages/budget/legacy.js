'use strict';
const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const draftStore = require('../../utils/budget-draft');

const GRADES = ['A', 'B', 'C']; // A 经济 / B 舒适 / C 品质（服务端计价口径）
const STRUCTURES = [
  { label: '砖混结构', code: 'BRICK' },
  { label: '框架结构', code: 'FRAME' },
  { label: '钢结构', code: 'STEEL' }
];

protectedPage({
  data: {
    standard: 1, projectId: null, creating: false, loading: false, error: '',
    buildingArea: '',
    structure: STRUCTURES[1],
    structureNames: STRUCTURES.map(function (item) { return item.label; })
  },
  onLoad(options) {
    const projectId = draftStore.id(options.projectId);
    const resultVersionId = draftStore.id(options.resultVersionId);
    if (!projectId || (options.resultVersionId != null && !resultVersionId)) {
      this.setData({ error: '项目或方案编号无效，请从设计项目重新进入' });
      return;
    }
    this.setData({ projectId, resultVersionId });
    this.load();
  },
  load() {
    if (this.data.loading || !this.data.projectId) return;
    this.setData({ loading: true, error: '' });
    api.getBudgetInputs(this.data.projectId, this.data.resultVersionId).then(response => {
      this.setData({ loading: false, buildingArea: response.importedValues.buildingArea || '' });
    }).catch(error => this.setData({ loading: false, error: (error && error.msg) || '参数导入失败，请重试' }));
  },
  selectStandard(e) { this.setData({ standard: Number(e.currentTarget.dataset.index) }); },
  onAreaInput(e) { this.setData({ buildingArea: e.detail.value }); },
  chooseStructure() {
    const self = this;
    wx.showActionSheet({
      itemList: this.data.structureNames,
      success: function (res) {
        self.setData({ structure: STRUCTURES[res.tapIndex] || self.data.structure });
      },
      fail: function () { /* 取消选择 */ }
    });
  },
  generate() {
    if (this.data.creating || this.data.loading || this.data.error) return;
    const area = Number(this.data.buildingArea);
    if (!/^[1-9][0-9]{0,4}$/.test(this.data.buildingArea) || !Number.isInteger(area) || area > 10000) { wx.showToast({ title: '旧版面积须为1至10000的整数', icon: 'none' }); return; }
    if (!this.data.projectId) { wx.showToast({ title: '请从设计项目进入', icon: 'none' }); return; }
    const self = this;
    self.setData({ creating: true });
    const versionId = this.data.resultVersionId;
    api.createBudgetEstimate(this.data.projectId, {
      regionCode: 'VAR1',
      structureType: this.data.structure.code,
      materialGrade: GRADES[this.data.standard] || 'B',
      buildingArea: area,
      resultVersionId: versionId || undefined
    }).then(function (estimate) {
      if (!draftStore.id(estimate.estimateId)) throw new Error('预算编号无效，请重试');
      wx.redirectTo({ url: '/pages/budget/result?budgetId=' + encodeURIComponent(estimate.estimateId) });
    }).catch(function (err) {
      wx.showToast({ title: (err && err.msg) || '预算测算失败', icon: 'none' });
      self.setData({ creating: false });
    });
  }
});
