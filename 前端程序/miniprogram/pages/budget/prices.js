'use strict';
const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');

// T14 我的当地单价：账号覆盖价跟账号永久生效，只影响本人新测算；金额一律以分提交服务端。
const UNIT_LABELS = { SQM: '平方米', METER: '米', PIECE: '个', SET: '套', HOUSEHOLD: '户', ITEM: '项' };
const MAX_YUAN = 1000000; // 与服务端 1～1 亿分上限一致

function yuanText(cents) {
  return (cents / 100).toFixed(2);
}

function toView(data) {
  return (data.groups || []).map(group => ({
    itemId: group.itemId,
    itemName: group.itemName,
    category: group.category,
    options: (group.options || []).map(option => ({
      optionId: option.optionId,
      label: option.label,
      unitLabel: UNIT_LABELS[option.unit] || option.unit || '',
      baselineText: option.baselinePriceCents == null ? '待补价' : '¥' + yuanText(option.baselinePriceCents),
      myText: option.myPriceCents == null ? '' : '¥' + yuanText(option.myPriceCents),
      overridden: option.priceSource === 'ACCOUNT_OVERRIDE',
      missing: option.priceSource === 'MISSING',
      reason: option.reason || ''
    }))
  })).filter(group => group.options.length);
}

protectedPage({
  data: {
    loading: false, error: '', regions: [], regionNames: [], regionIndex: -1, regionCode: '',
    groups: [], overriddenCount: 0,
    editOptionId: null, inputValue: '', saving: false, resetting: false
  },
  onLoad(options) {
    this._initialRegion = options && options.regionCode ? decodeURIComponent(options.regionCode) : '';
    this.load();
  },
  load() {
    if (this.data.loading) return;
    this.setData({ loading: true, error: '' });
    api.getBudgetRegions().then(regions => {
      if (!regions.length) { this.setData({ loading: false, groups: [], error: '暂无已启用地区，请等待平台配置基准价格。' }); return; }
      const names = regions.map(item => item.name);
      let index = regions.findIndex(item => item.code === (this._initialRegion || regions[0].code));
      if (index < 0) index = 0;
      this.setData({ regions, regionNames: names, regionIndex: index, regionCode: regions[index].code });
      return this.loadPrices(regions[index].code);
    }).catch(error => {
      this.setData({ loading: false, error: (error && (error.msg || error.message)) || '地区加载失败，请重试' });
    });
  },
  loadPrices(regionCode) {
    return api.getMyPrices(regionCode).then(data => {
      const groups = toView(data);
      this.setData({ loading: false, groups, overriddenCount: groups.reduce((sum, g) => sum + g.options.filter(o => o.overridden).length, 0) });
    }).catch(error => {
      this.setData({ loading: false, error: (error && (error.msg || error.message)) || '单价加载失败，请重试' });
    });
  },
  onRegionChange(event) {
    const index = Number(event.detail.value);
    const region = this.data.regions[index];
    if (!region || region.code === this.data.regionCode) return;
    this.cancelEdit();
    this.setData({ regionIndex: index, regionCode: region.code, error: '' });
    this.setData({ loading: true });
    this.loadPrices(region.code);
  },
  startEdit(event) {
    const optionId = event.currentTarget.dataset.optionId;
    const current = this.findOption(optionId);
    if (!current || this.data.saving) return;
    this.setData({ editOptionId: optionId, inputValue: current.overridden ? current.myText.replace('¥', '') : current.baselineText === '待补价' ? '' : current.baselineText.replace('¥', '') });
  },
  cancelEdit() { this.setData({ editOptionId: null, inputValue: '' }); },
  onPriceInput(event) { this.setData({ inputValue: event.detail.value }); },
  confirmEdit(event) {
    const optionId = event.currentTarget.dataset.optionId;
    const raw = (this.data.inputValue || '').trim();
    if (!/^\d+(\.\d{1,2})?$/.test(raw) || parseFloat(raw) <= 0 || parseFloat(raw) > MAX_YUAN) {
      wx.showToast({ title: '请输入 0.01～100万 的单价', icon: 'none' });
      return;
    }
    const cents = Math.round(parseFloat(raw) * 100);
    if (this.data.saving) return;
    this.setData({ saving: true });
    api.setMyPrice(optionId, this.data.regionCode, cents).then(() => this.loadPrices(this.data.regionCode)).then(() => {
      this.setData({ saving: false, editOptionId: null, inputValue: '' });
      wx.showToast({ title: '已保存，仅影响你的新测算', icon: 'none' });
    }).catch(error => {
      this.setData({ saving: false });
      wx.showToast({ title: (error && (error.msg || error.message)) || '保存失败，请重试', icon: 'none' });
    });
  },
  reset(event) {
    const optionId = event.currentTarget.dataset.optionId;
    if (this.data.resetting) return;
    wx.showModal({
      title: '恢复默认价',
      content: '恢复后你的预算测算将使用平台基准价，已保存的预算不变。',
      confirmText: '恢复默认',
      success: result => {
        if (!result.confirm) return;
        this.setData({ resetting: true });
        api.resetMyPrice(optionId, this.data.regionCode).then(() => this.loadPrices(this.data.regionCode)).then(() => {
          this.setData({ resetting: false });
          wx.showToast({ title: '已恢复基准价', icon: 'none' });
        }).catch(error => {
          this.setData({ resetting: false });
          wx.showToast({ title: (error && (error.msg || error.message)) || '恢复失败，请重试', icon: 'none' });
        });
      }
    });
  },
  retry() { this.cancelEdit(); this.load(); },
  findOption(optionId) {
    for (const group of this.data.groups) {
      const hit = group.options.find(item => item.optionId === optionId);
      if (hit) return hit;
    }
    return null;
  }
});
