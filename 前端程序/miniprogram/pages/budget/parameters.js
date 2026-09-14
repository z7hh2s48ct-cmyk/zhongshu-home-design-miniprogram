'use strict';
const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const draftStore = require('../../utils/budget-draft');

protectedPage({
  data: { loading: false, error: '', draft: null, values: {}, regions: [], regionNames: [], regionIndex: -1, regionName: '请选择',
    floorNames: Array.from({ length: 20 }, (_, index) => (index + 1) + '层'), floorIndex: -1,
    errors: {}, needsReview: false, storageError: '', saving: false },
  onLoad(options) {
    this._projectId = draftStore.id(options.projectId);
    this._versionId = options.resultVersionId == null ? null : draftStore.id(options.resultVersionId);
    if (!this._projectId || (options.resultVersionId != null && !this._versionId)) {
      this._projectId = null;
      this.setData({ error: '请从设计项目的快速预算页进入' });
      return;
    }
    this.load();
  },
  onShow() {
    if (this._sessionScope && this._sessionScope !== draftStore.sessionScope()) {
      this.invalidateSession();
      this.load();
    }
  },
  onUnload() { this._requestId = (this._requestId || 0) + 1; this._sessionScope = null; },
  load() {
    if (this.data.loading || !this._projectId) return;
    const scope = draftStore.sessionScope();
    if (!scope) { this.invalidateSession(); return; }
    if (this._sessionScope !== scope) this.invalidateSession();
    this._sessionScope = scope;
    const requestId = this._requestId = (this._requestId || 0) + 1;
    this.setData({ loading: true, error: '', regionsError: '' });
    const regionRequest = api.getBudgetRegions().then(regions => ({ regions }))
      .catch(() => ({ regions: [], regionsError: '地区加载失败，请重试' }));
    Promise.all([api.getBudgetInputs(this._projectId, this._versionId), regionRequest]).then(([response, regionResult]) => {
      if (!this.acceptResponse(scope, requestId)) return;
      if (response.projectId !== this._projectId || (response.resultVersionId || null) !== this._versionId) throw new Error('项目参数不匹配');
      let draft = draftStore.create(response);
      let storageError = '';
      try { draft = draftStore.read(this._projectId, this._versionId) || draft; draftStore.write(draft); }
      catch (error) { storageError = '本机草稿无法保存，请恢复存储后重试'; }
      this.setData(Object.assign({ loading: false, storageError, regionNames: regionResult.regions.map(item => item.name) }, regionResult));
      this.renderDraft(draft);
    }).catch(error => {
      if (this.acceptResponse(scope, requestId)) this.setData({ loading: false, error: (error && (error.msg || error.message)) || '参数加载失败，请重试' });
    });
  },
  invalidateSession() {
    this._requestId = (this._requestId || 0) + 1;
    this.setData({ loading: false, saving: false, draft: null, values: {}, regions: [], regionNames: [], regionIndex: -1,
      regionName: '请选择', floorIndex: -1, errors: {}, needsReview: false, storageError: '', regionsError: '',
      error: '登录身份已变化，请重新导入参数' });
  },
  acceptResponse(scope, requestId) {
    if (requestId !== this._requestId) return false;
    if (!scope || scope !== draftStore.sessionScope()) { this.invalidateSession(); return false; }
    return true;
  },
  currentSession() {
    if (!this._sessionScope || this._sessionScope !== draftStore.sessionScope()) { this.invalidateSession(); return false; }
    return true;
  },
  renderDraft(draft) {
    if (!this.currentSession() || draft.sessionScope !== this._sessionScope) return;
    const values = draftStore.values(draft);
    const regionIndex = this.data.regions.findIndex(item => item.code === values.regionCode);
    this.setData({ draft, values, regionIndex, regionName: regionIndex >= 0 ? this.data.regions[regionIndex].name : values.regionCode ? '原地区已不可选' : '请选择',
      floorIndex: values.floorCount ? Number(values.floorCount) - 1 : -1,
      needsReview: draftStore.validate(draft, this.data.regions).needsReview });
  },
  updateDraft(draft) {
    if (!this.currentSession() || draft.sessionScope !== this._sessionScope) return;
    let storageError = '';
    try { draftStore.write(draft); }
    catch (error) { storageError = '修改尚未保存：本机存储不可用，请重试'; }
    this.setData({ storageError, errors: {} });
    this.renderDraft(draft);
  },
  onAreaInput(event) { if (this.currentSession() && this.data.draft) this.updateDraft(draftStore.edit(this.data.draft, event.currentTarget.dataset.field, event.detail.value)); },
  onFloorChange(event) { if (this.currentSession() && this.data.draft) this.updateDraft(draftStore.edit(this.data.draft, 'floorCount', Number(event.detail.value) + 1)); },
  onRegionChange(event) {
    if (!this.currentSession() || !this.data.draft) return;
    const region = this.data.regions[Number(event.detail.value)];
    if (region) {
      const changed = region.code !== draftStore.values(this.data.draft).regionCode;
      this.updateDraft(draftStore.edit(this.data.draft, 'regionCode', region.code));
      if (changed) wx.showToast({ title: '地区已变更，请重新选择预算配置', icon: 'none' });
    }
  },
  restore() { if (this.currentSession() && this.data.draft) this.updateDraft(draftStore.restore(this.data.draft)); },
  // T14：跳转我的当地单价（把基准价调成当地成本价，跟账号永久生效）
  openMyPrices() {
    const regionCode = this.data.draft ? draftStore.values(this.data.draft).regionCode : '';
    wx.navigateTo({ url: '/pages/budget/prices' + (regionCode ? '?regionCode=' + encodeURIComponent(regionCode) : '') });
  },
  save() {
    if (!this.currentSession() || this.data.saving || !this.data.draft) return;
    const validation = draftStore.validate(this.data.draft, this.data.regions);
    this.setData({ errors: validation.errors, needsReview: validation.needsReview });
    if (!validation.valid) { wx.showToast({ title: '请检查基础参数', icon: 'none' }); return; }
    this.setData({ saving: true });
    try { draftStore.write(this.data.draft); }
    catch (error) { this.setData({ saving: false, storageError: '参数未保存：本机存储不可用，请重试' }); return; }
    // Commits local parameter editing, not a backend budget save.
    wx.navigateBack({ fail: () => wx.redirectTo({ url: '/pages/budget/input' + draftStore.query(this.data.draft) }),
      complete: () => this.setData({ saving: false }) });
  },
});
