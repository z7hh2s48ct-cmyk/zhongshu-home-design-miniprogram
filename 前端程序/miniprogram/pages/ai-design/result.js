'use strict';
const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const http = require('../../utils/request');
const assets = require('../../utils/assets');
const view = require('../../utils/record-view');

protectedPage({
  data: { tab: 0, versions: [], latest: null, versionLabel: '', imageUrl: '', flatImageUrl: '', loading: false, error: '', imageError: '', readOnly: false },
  onLoad(options) {
    this._projectId = view.id(options.projectId || getApp().globalData.projectId);
    this._versionId = options.resultVersionId == null ? null : view.id(options.resultVersionId);
    this._invalidVersion = options.resultVersionId != null && !this._versionId;
    this.loadVersions();
  },
  onUnload() { this._seq = (this._seq || 0) + 1; this._token = null; },
  onShow() { if (!(http.isSameSession ? http.isSameSession(this._token) : this._token === http.getToken())) this.loadVersions(); },
  current(seq) {
    if (seq !== this._seq) return false;
    if (this._token && (http.isSameSession ? http.isSameSession(this._token) : this._token === http.getToken())) return true;
    this.setData({ latest: null, versions: [], imageUrl: '', flatImageUrl: '', loading: false, error: '登录身份已变化，请重新进入' });
    return false;
  },
  loadVersions() {
    const seq = this._seq = (this._seq || 0) + 1; this._token = http.getToken();
    this.setData({ loading: false, error: '', latest: null, versions: [], imageUrl: '', flatImageUrl: '', imageError: '' });
    if (!this._projectId || this._invalidVersion || !this._token) { this.setData({ error: '方案信息无效，请从「我的方案」重新进入' }); return; }
    this.setData({ loading: true });
    return api.getResultVersions(this._projectId).then(page => {
      if (!this.current(seq)) return;
      if (!page || !Array.isArray(page.list)) throw Error('方案版本读取失败');
      const list = page.list;
      const selected = this._versionId ? list.find(item => item.versionId === this._versionId)
        : list.find(item => item.superseded === false) || list[0];
      if (!selected || !view.id(selected.versionId)) throw Error('该方案版本不可用或尚未完成');
      this.setData({ versions: list, latest: selected, loading: false, versionLabel: '版本 v' + selected.version,
        readOnly: selected.superseded === true });
      return this.loadImages();
    }).catch(error => { if (this.current(seq)) this.setData({ loading: false, error: view.errorText(error) }); });
  },
  loadImages() {
    if (!this.current(this._seq) || !this.data.latest) return;
    const seq = this._seq, selected = this.data.latest;
    this.setData({ imageError: '' });
    return Promise.all([['imageUrl', selected.selectedElevationAssetId], ['flatImageUrl', selected.selectedFlatAssetId]].map(([key, assetId]) => {
      if (!view.id(assetId)) { this.setData({ imageError: '本版本图片暂不可用' }); return; }
      return assets.fetchProfileAvatar(assetId).then(url => { if (this.current(seq)) this.setData({ [key]: url }); })
        .catch(() => { if (this.current(seq)) this.setData({ imageError: '部分图片加载失败，可重试' }); });
    }));
  },
  selectTab(event) { this.setData({ tab: Number(event.currentTarget.dataset.index) === 1 ? 1 : 0 }); },
  budget() {
    if (this.current(this._seq) && this.data.latest) wx.navigateTo({ url: '/pages/budget/input?projectId=' + this._projectId + '&resultVersionId=' + this.data.latest.versionId });
  },
  publish() {
    if (!this.current(this._seq) || !this.data.latest || this.data.readOnly) return;
    Object.assign(getApp().globalData, { projectId: this._projectId, resultVersionId: this.data.latest.versionId });
    wx.navigateTo({ url: '/pages/ai-design/publish' });
  },
  save() { wx.navigateTo({ url: '/pages/profile/records?type=projects' }); },
  adjust() { this.regenerate(); },
  regenerate() {
    if (!this.current(this._seq) || !this.data.latest || this.data.readOnly || this.data.regenerating) return;
    const seq = this._seq;
    wx.showModal({ title: '重新生成方案', content: '将创建新的立面任务并消耗设计点，原方案保留。是否继续？',
      success: result => {
        if (!result.confirm || !this.current(seq) || this.data.regenerating) return;
        this.setData({ regenerating: true });
        this._revisionKey = this._revisionKey || 'revision-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10);
        api.createRevisionRequest(this._projectId, { reason: '用户发起重新生成', count: 2 }, this._revisionKey).then(vo => {
          if (!this.current(seq)) return;
          if (!vo || !view.id(vo.jobId)) throw Error('任务编号无效');
          Object.assign(getApp().globalData, { projectId: this._projectId, jobId: vo.jobId });
          wx.redirectTo({ url: '/pages/ai-design/generating?stage=elevation&count=2' });
        }).catch(error => { if (this.current(seq)) wx.showToast({ title: view.errorText(error), icon: 'none' }); })
          .then(() => { if (this.current(seq)) this.setData({ regenerating: false }); });
      }
    });
  }
});
