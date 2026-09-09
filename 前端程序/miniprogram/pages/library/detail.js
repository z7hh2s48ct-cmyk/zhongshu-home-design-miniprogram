'use strict';

const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const assets = require('../../utils/assets');
const format = require('../../utils/format');
const http = require('../../utils/request');

protectedPage({
  data: {
    caseDetail: null, loading: true, tab: 0, favorite: false, favoriteLoading: false, favoriteSaving: false, favoriteError: false,
    drawings: [], hero: { assetId: '', url: '', status: 'missing' }, detailError: false
  },
  onLoad(options) {
    this._token = http.getToken(); this._closed = false;
    const caseId = options && options.id;
    this._caseId = caseId;
    if (!caseId) {
      wx.showToast({ title: '请从户型库选择案例', icon: 'none' });
      wx.navigateBack({ fail: function () { wx.switchTab({ url: '/pages/library/index' }); } });
      return;
    }
    this.loadDetail(caseId);
    this.loadFavoriteState(caseId);
  },
  onUnload() { this._closed = true; },
  current() { return !this._closed && !!this._token && (http.isSameSession ? http.isSameSession(this._token) : this._token === http.getToken()); },
  loadDetail(caseId) {
    const self = this;
    const sequence = this._detailSequence = (this._detailSequence || 0) + 1;
    this.setData({ loading: true, detailError: false });
    return api.getCaseDetail(caseId).then(function (detail) {
      if (!self.current() || sequence !== self._detailSequence) return;
      const parameters = detail.parameters || {};
      const style = format.styleLabel(parameters.styleCode);
      self.setData({
        loading: false,
        caseDetail: {
          id: detail.caseId, title: detail.title, description: detail.description,
          sourceLabel: (detail.sourceType === 'AI' ? 'AI案例' : '公司案例') + (style ? ' · ' + style : ''),
          meta: (parameters.buildingArea || '—') + 'm² / ' + (parameters.floorCount || '—') + '层 / 面宽'
            + (parameters.faceWidth || '—') + 'm / 进深' + (parameters.depth || '—') + 'm',
          canDesign: (detail.allowedActions || []).includes('DESIGN_WITH'),
          designUnavailableReason: detail.designUnavailableReason || '该案例暂无有效的设计参考授权',
          coverAssetId: detail.coverAssetId,
          floorPlanAssetIds: detail.floorPlanAssetIds || [],
          elevationAssetId: detail.elevationAssetId
        }
      });
      self.loadTabImages(detail, sequence);
    }).catch(function () {
      if (!self.current() || sequence !== self._detailSequence) return;
      self.setData({ loading: false, detailError: true });
      wx.showToast({ title: '加载失败', icon: 'none' });
    });
  },
  retryDetail() { return this.loadDetail(this._caseId); },
  loadTabImages(detail, sequence) {
    const plans = Array.isArray(detail.floorPlans) ? detail.floorPlans
      : (detail.floorPlanAssetIds || []).map(assetId => ({ assetId }));
    const drawings = [{ assetId: detail.elevationAssetId, label: '立面效果' }].concat(plans.map((plan, index) => ({
      assetId: plan.assetId,
      label: detail.sourceType !== 'AI' && plan.floorNo != null ? plan.floorNo + '层平面'
        : plans.length === 1 ? '平面方案' : '平面图' + (index + 1)
    }))).map(item => Object.assign(item, { url: '', status: item.assetId ? 'loading' : 'missing' }));
    const hero = { assetId: detail.coverAssetId, url: '', status: detail.coverAssetId ? 'loading' : 'missing' };
    this.setData({ drawings, hero, tab: 0 });
    this.loadImage('hero', hero.assetId, sequence);
    drawings.forEach((item, index) => this.loadImage('drawings[' + index + ']', item.assetId, sequence));
  },
  loadImage(path, assetId, sequence) {
    if (!assetId) return;
    this.setData({ [path + '.status']: 'loading' });
    return assets.fetchAssetDataUrl(assetId).then(url => {
      if (this.current() && sequence === this._detailSequence)
        this.setData({ [path + '.url']: url, [path + '.status']: 'ready' });
    }).catch(() => {
      if (this.current() && sequence === this._detailSequence) this.setData({ [path + '.status']: 'error' });
    });
  },
  retryImage(e) {
    const index = e.currentTarget.dataset.index;
    const item = index === 'hero' ? this.data.hero : this.data.drawings[Number(index)];
    if (item && item.status === 'error') return this.loadImage(index === 'hero' ? 'hero' : 'drawings[' + Number(index) + ']', item.assetId, this._detailSequence);
  },
  imageError(e) {
    const index = e.currentTarget.dataset.index;
    this.setData({ [(index === 'hero' ? 'hero' : 'drawings[' + Number(index) + ']') + '.status']: 'error' });
  },
  loadFavoriteState(caseId, cursor) {
    const self = this;
    if (!self.current()) return;
    if (!cursor) this._favoriteCursors = new Set();
    self.setData({ favoriteLoading: true });
    return api.listFavorites(cursor || null, 50).then(function (page) {
      if (!self.current()) return;
      if (!page || !Array.isArray(page.list)) throw Error('收藏数据异常');
      const ids = page.list.map(function (c) { return String(c.caseId); });
      if (ids.includes(String(caseId))) self.setData({ favorite: true, favoriteLoading: false });
      else if (page.nextCursor) {
        if (self._favoriteCursors.has(page.nextCursor)) throw Error('收藏分页异常');
        self._favoriteCursors.add(page.nextCursor);
        return self.loadFavoriteState(caseId, page.nextCursor);
      }
      else self.setData({ favorite: false, favoriteLoading: false });
    }).catch(function () { if (self.current()) self.setData({ favoriteLoading: false, favoriteError: true }); });
  },
  selectTab(e) { this.setData({ tab: Number(e.currentTarget.dataset.index) }); },
  toggleFavorite() {
    const detail = this.data.caseDetail;
    if (!this.current() || !detail || this.data.favoriteLoading || this.data.favoriteSaving) return;
    if (this.data.favoriteError) { this.setData({ favoriteError: false }); this.loadFavoriteState(detail.id); wx.showToast({ title: '正在重新确认收藏状态，请稍后重试', icon: 'none' }); return; }
    const next = !this.data.favorite;
    const self = this;
    self.setData({ favoriteSaving: true });
    return (next ? api.favorite(detail.id) : api.unfavorite(detail.id)).then(function (saved) {
      if (!self.current()) return;
      if (saved !== true) throw Error('收藏状态未保存，请重试');
      self.setData({ favorite: next });
      wx.showToast({ title: next ? '已收藏' : '已取消收藏', icon: 'success' });
    }).catch(function (err) {
      if (self.current()) wx.showToast({ title: (err && (err.msg || err.message)) || '操作失败', icon: 'none' });
    }).then(function () { if (self.current()) self.setData({ favoriteSaving: false }); });
  },
  useHouse() {
    const detail = this.data.caseDetail;
    if (!this.current() || !detail) return;
    if (!detail.canDesign) { wx.showToast({ title: detail.designUnavailableReason, icon: 'none' }); return; }
    // switchTab 无法携带 query，经全局数据把参考案例带给 AI 设计页
    getApp().globalData.refCase = { caseId: detail.id, title: detail.title };
    wx.switchTab({ url: '/pages/ai-design/index' });
  }
});
