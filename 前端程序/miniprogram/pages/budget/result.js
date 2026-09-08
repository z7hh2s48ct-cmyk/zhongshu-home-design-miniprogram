'use strict';
const { protectedPage } = require('../../utils/access');
const format = require('../../utils/format');

protectedPage({
  data: { estimate: null, rangeText: '—', unitText: '', basis: [], costs: [], disclaimer: '' },
  onLoad() {
    const estimate = getApp().globalData.budgetEstimate;
    if (!estimate) {
      wx.showToast({ title: '请先生成预算', icon: 'none' });
      setTimeout(function () { wx.navigateBack(); }, 1000);
      return;
    }
    this.renderEstimate(estimate);
  },
  renderEstimate(estimate) {
    const snapshot = estimate.inputSnapshot || {};
    const area = Number(snapshot.buildingArea) || 0;
    const rangeText = format.centsToWan(estimate.totalMinCents) + '–' + format.centsToWan(estimate.totalMaxCents);
    let unitText = '';
    if (area > 0 && estimate.totalMinCents) {
      const unitMin = Math.round(estimate.totalMinCents / (100 * area));
      const unitMax = Math.round(estimate.totalMaxCents / (100 * area));
      unitText = '参考单方造价 ' + unitMin + '–' + unitMax + ' 元/m²';
    }
    const costs = (estimate.breakdown || []).map(function (item) {
      return {
        name: item.name || item.stage || '费用项',
        range: format.centsToWan(item.minCents) + '–' + format.centsToWan(item.maxCents)
      };
    });
    const basis = [
      { name: '建筑面积', value: area ? area + ' m²' : '—' },
      { name: '结构形式', value: format.structureLabel(snapshot.structureType) || '—' },
      { name: '材料等级', value: (snapshot.materialGrade || '—') + ' 档' }
    ];
    this.setData({
      estimate: estimate,
      rangeText: rangeText,
      unitText: unitText,
      costs: costs,
      basis: basis,
      disclaimer: estimate.disclaimer || '不含土地、软装、报建及特殊地基处理'
    });
  },
  save() { wx.showToast({ title: '预算单已保存', icon: 'success' }); },
  adjust() { wx.navigateBack(); }
});
