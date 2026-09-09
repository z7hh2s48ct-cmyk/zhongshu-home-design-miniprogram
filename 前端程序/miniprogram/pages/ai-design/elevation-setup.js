'use strict';
const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const assets = require('../../utils/assets');

// 立面参数以稳定编码入库（配置快照可复算），中文标签仅作展示
const STYLE_OPTIONS = [
  { label: '新中式', code: 'NEW_CHINESE' },
  { label: '现代', code: 'MODERN' },
  { label: '中式', code: 'CHINESE' },
  { label: '欧式', code: 'EUROPEAN' }
];
const ROOF_OPTIONS = [
  { label: '坡屋顶', code: 'GABLE_ROOF' },
  { label: '平屋顶', code: 'FLAT_ROOF' }
];
const WALL_OPTIONS = [
  { label: '米白真石漆', code: 'WHITE_STUCCO' },
  { label: '浅灰石材', code: 'GREY_STONE' },
  { label: '白色涂料', code: 'WHITE_COAT' }
];
const ACCENT_OPTIONS = [
  { label: '深木色', code: 'DEEP_WOOD' },
  { label: '暖灰色', code: 'WARM_GREY' },
  { label: '黑色', code: 'BLACK' }
];

protectedPage({
  data: {
    styleOptions: STYLE_OPTIONS, roofOptions: ROOF_OPTIONS,
    wallOptions: WALL_OPTIONS, accentOptions: ACCENT_OPTIONS,
    style: 'NEW_CHINESE', roof: 'GABLE_ROOF', wall: 'WHITE_STUCCO', accent: 'DEEP_WOOD',
    count: 2, creating: false, points: '—',
    flatLabel: '', flatImageUrl: ''
  },
  onShow() {
    const global = getApp().globalData;
    this.setData({ flatLabel: global.flatLabel || '已选平面方案' });
    this.refreshPoints();
    require('../../utils/generation-price').refresh(this, 'ELEVATION');
    this.loadFlatImage();
  },
  refreshPoints() {
    const self = this;
    api.getPointAccount().then(function (account) {
      self.setData({ points: account.availablePoints });
    }).catch(function () { /* 静默保留占位 */ });
  },
  loadFlatImage() {
    const assetId = getApp().globalData.selectedFlatAssetId;
    if (!assetId) return;
    const self = this;
    assets.fetchAssetDataUrl(assetId).then(function (url) {
      self.setData({ flatImageUrl: url });
    }).catch(function () { /* 保留占位 */ });
  },
  choose(e) {
    const field = e.currentTarget.dataset.field;
    this.setData({ [field]: e.currentTarget.dataset.value });
  },
  selectCount(e) { this.setData({ count: Number(e.currentTarget.dataset.count) }); require('../../utils/generation-price').refresh(this, 'ELEVATION'); },
  generate() {
    if (this.data.creating) return;
    const projectId = getApp().globalData.projectId;
    if (!projectId) { wx.showToast({ title: '请先完成平面设计', icon: 'none' }); return; }
    const self = this;
    self.setData({ creating: true });
    const idemKey = 'elev-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10);
    const config = {
      count: self.data.count,
      styleCode: self.data.style,
      roofType: self.data.roof,
      material: self.data.wall,
      color: self.data.accent
    };
    getApp().globalData.elevationConfig = config;
    require('../../utils/generation-price').confirm('ELEVATION', self.data.count).then(function (price) {
      config.priceConfirmation = price;
      return api.createElevationJob(projectId, config, idemKey);
    }).then(function (job) {
      getApp().globalData.jobId = job.jobId;
      wx.redirectTo({ url: '/pages/ai-design/generating?stage=elevation&count=' + config.count });
    }).catch(function (err) {
      if (!err || !err.cancelled) wx.showToast({ title: (err && err.msg) || '创建立面任务失败', icon: 'none' });
      self.setData({ creating: false });
    });
  }
});
