'use strict';
const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');

const GRADES = ['A', 'B', 'C']; // A 经济 / B 舒适 / C 品质（服务端计价口径）
const STRUCTURES = [
  { label: '砖混结构', code: 'BRICK' },
  { label: '框架结构', code: 'FRAME' },
  { label: '钢结构', code: 'STEEL' }
];

protectedPage({
  data: {
    standard: 1, interior: true, projectId: null, creating: false,
    buildingArea: '168',
    structure: STRUCTURES[1],
    structureNames: STRUCTURES.map(function (item) { return item.label; })
  },
  onLoad() { this.setData({ projectId: getApp().globalData.projectId }); },
  selectStandard(e) { this.setData({ standard: Number(e.currentTarget.dataset.index) }); },
  toggleInterior(e) { this.setData({ interior: e.detail.value }); },
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
    if (this.data.creating) return;
    const area = Number(this.data.buildingArea);
    if (!area || area <= 0) { wx.showToast({ title: '请输入正确的建筑面积', icon: 'none' }); return; }
    if (!this.data.projectId) { wx.showToast({ title: '请先完成方案设计', icon: 'none' }); return; }
    const self = this;
    self.setData({ creating: true });
    const versionId = getApp().globalData.resultVersionId;
    api.createBudgetEstimate(this.data.projectId, {
      regionCode: 'VAR1',
      structureType: this.data.structure.code,
      materialGrade: GRADES[this.data.standard] || 'B',
      buildingArea: Math.round(area),
      resultVersionId: versionId ? Number(versionId) : undefined
    }).then(function (estimate) {
      getApp().globalData.budgetEstimate = estimate;
      wx.redirectTo({ url: '/pages/budget/result' });
    }).catch(function (err) {
      wx.showToast({ title: (err && err.msg) || '预算测算失败', icon: 'none' });
      self.setData({ creating: false });
    });
  }
});
