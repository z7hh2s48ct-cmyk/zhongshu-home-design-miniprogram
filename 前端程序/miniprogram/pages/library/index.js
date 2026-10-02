'use strict';

const { openFeature, protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const http = require('../../utils/request');
const assets = require('../../utils/assets');
const format = require('../../utils/format');

protectedPage({
  data: {
    cases: [], visibleCases: [], cursor: null, hasMore: true, loading: false,
    keyword: '', source: 0,
    filters: { sourceType: 'COMPANY', styleCode: '', floorCount: null },
    styleOptions: [
      { label: '全部', code: '' },
      { label: '新中式', code: 'NEW_CHINESE' },
      { label: '现代', code: 'MODERN' },
      { label: '中式', code: 'CHINESE' }
    ],
    floorOptions: [
      { label: '全部', count: null },
      { label: '一层', count: 1 },
      { label: '二层', count: 2 },
      { label: '三层', count: 3 }
    ]
  },
  onLoad() { this.loadPage(true); },
  onUnload() { this._unloaded = true; this._listSeq = (this._listSeq || 0) + 1; },
  onReachBottom() { if (this.data.hasMore && !this.data.loading) this.loadPage(false); },
  loadPage(reset) {
    if (this.data.loading && !reset) return;
    const seq = this._listSeq = (this._listSeq || 0) + (reset ? 1 : 0);
    const self = this;
    const session = http.captureSession ? http.captureSession() : http.getToken();
    const current = () => seq === self._listSeq && !self._unloaded && (http.isSameSession ? http.isSameSession(session) : session === http.getToken());
    const cursor = reset ? null : this.data.cursor;
    self.setData({ loading: true });
    const filters = this.data.filters;
    const params = {
      cursor: cursor, limit: 10,
      sourceType: filters.sourceType || undefined,
      styleCode: filters.styleCode || undefined,
      floorCount: filters.floorCount || undefined
    };
    return api.listCases(params).then(function (page) {
      if (!current()) return;
      const startIndex = reset ? 0 : self.data.cases.length;
      const newCases = (page.list || []).map(function (c) {
        const tag = format.styleLabel(c.styleCode);
        return {
          id: c.caseId, title: c.title, area: c.buildingArea,
          floors: c.floorCount, style: c.styleCode, tag: tag,
          coverAssetId: c.coverAssetId, image: '',
          meta: (c.buildingArea ? c.buildingArea + '㎡' : '') + (c.floorCount ? ' · ' + c.floorCount + '层' : '') + (tag ? ' · ' + tag : '')
        };
      });
      self.setData({
        cases: reset ? newCases : self.data.cases.concat(newCases),
        cursor: page.nextCursor,
        hasMore: !!page.nextCursor,
        loading: false
      });
      self.refreshVisible();
      newCases.forEach(function (item, i) {
        if (!item.coverAssetId) return;
        assets.fetchAssetDataUrl(item.coverAssetId).then(function (url) {
          // 回调期间可能已切换筛选/翻页：仅在索引仍有效且同一封面时回写
          if (!current()) return;
          const coverTarget = self.data.cases[startIndex + i];
          if (!coverTarget || coverTarget.coverAssetId !== item.coverAssetId) return;
          const update = {};
          update['cases[' + (startIndex + i) + '].image'] = url;
          self.setData(update);
          self.refreshVisible();
        }).catch(function () { /* 封面加载失败保留空态 */ });
      });
    }).catch(function () {
      if (!current()) return;
      self.setData({ loading: false });
      wx.showToast({ title: '加载失败', icon: 'none' });
    });
  },
  refreshVisible() {
    const keyword = (this.data.keyword || '').trim();
    const visible = !keyword ? this.data.cases : this.data.cases.filter(function (c) {
      return (c.title || '').indexOf(keyword) >= 0
        || (c.tag || '').indexOf(keyword) >= 0
        || String(c.area || '').indexOf(keyword) >= 0;
    });
    this.setData({ visibleCases: visible });
  },
  onSearch(e) {
    this.setData({ keyword: e.detail.value });
    this.refreshVisible();
  },
  selectSource(e) {
    const index = Number(e.currentTarget.dataset.index);
    if (index === this.data.source) return;
    this.setData({
      source: index,
      filters: Object.assign({}, this.data.filters, { sourceType: index === 1 ? 'AI' : 'COMPANY' }),
      cases: [], cursor: null, hasMore: true
    });
    this.loadPage(true);
  },
  onFilterChange(e) {
    const key = e.currentTarget.dataset.key;
    const value = e.currentTarget.dataset.value;
    if (value === undefined || this.data.filters[key] === value) return;
    // styleCode 用 ''、floorCount 用 null 表示「全部」，与选项数组严格相等以维持选中态
    this.setData({
      filters: Object.assign({}, this.data.filters, { [key]: value }),
      cases: [], cursor: null, hasMore: true
    });
    this.loadPage(true);
  },
  openDetail(e) { openFeature('/pages/library/detail?id=' + e.currentTarget.dataset.id); }
});
