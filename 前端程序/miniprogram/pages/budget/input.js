'use strict';
const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const draftStore = require('../../utils/budget-draft');
const selection = require('../../utils/budget-selection');

protectedPage({
  data: { loading: false, error: '', noProject: false, draft: null, values: {}, regions: [], regionName: '待选择', sourceLabel: '', storageError: '', needsReview: false,
    bodyCount: 0, exteriorCount: 0, customCount: 0, pendingPriceCount: 0, catalogLoading: false, catalogError: '',
    catalogReady: false, selectionNotice: '', canGenerate: false, disabledReason: '', creating: false, generateError: '',
    importConflict: false, reimporting: false, importError: '' },
  onLoad(options) {
    const global = getApp().globalData || {};
    this._projectId = draftStore.id(options.projectId == null ? global.projectId : options.projectId);
    this._requestedVersionId = options.resultVersionId == null ? null : draftStore.id(options.resultVersionId);
    if (options.resultVersionId != null && !this._requestedVersionId) {
      this._projectId = null;
      this.setData({ error: '方案编号无效，请从设计项目重新进入' });
      return;
    }
    if (!this._projectId) { this.setData({ noProject: true }); return; }
    this.load();
  },
  onShow() {
    if (this._sessionScope && this._sessionScope !== draftStore.sessionScope()) {
      this.invalidateSession();
      this.load();
      return;
    }
    if (!this.data.draft || this.data.reimporting || this._confirmingImport) return;
    try {
      this.renderDraft(draftStore.read(this.data.draft.projectId, this.data.draft.resultVersionId) || this.data.draft);
      this.loadCatalog();
    }
    catch (error) { this.setData({ storageError: '本机草稿暂时无法读取，请重试' }); }
  },
  onUnload() {
    this._requestId = (this._requestId || 0) + 1;
    this._catalogRequestId = (this._catalogRequestId || 0) + 1;
    this._generationId = (this._generationId || 0) + 1;
    this._importId = (this._importId || 0) + 1;
    this._confirmingImport = false;
    this._sessionScope = null;
  },
  load() {
    if (this.data.loading || this.data.reimporting || this._confirmingImport || !this._projectId) return;
    const scope = draftStore.sessionScope();
    if (!scope) { this.invalidateSession(); return; }
    if (this._sessionScope !== scope) this.invalidateSession();
    this._sessionScope = scope;
    const requestId = this._requestId = (this._requestId || 0) + 1;
    this.setData({ loading: true, error: '', regionsError: '' });
    const regionRequest = api.getBudgetRegions().then(regions => ({ regions }))
      .catch(() => ({ regions: [], regionsError: '地区加载失败，可重试' }));
    // Server ownership is checked on entry before a local draft is shown.
    api.getProject(this._projectId).then(project => {
      if (!this.acceptResponse(scope, requestId)) return null;
      // Resolve once, including null. A network/catalog retry must not change the chosen scheme.
      if (!this._hasResolvedVersion) {
        this._loadedVersionId = this._requestedVersionId || draftStore.id(project.resultVersionId);
        this._hasResolvedVersion = true;
      }
      return Promise.all([api.getBudgetInputs(this._projectId, this._loadedVersionId), regionRequest]);
    }).then(result => {
      if (!result || !this.acceptResponse(scope, requestId)) return;
      const [response, regionResult] = result;
      if (response.projectId !== this._projectId || (response.resultVersionId || null) !== this._loadedVersionId) throw new Error('项目参数不匹配');
      const initial = draftStore.create(response);
      let draft = initial;
      let storageError = '';
      try { draft = draftStore.read(initial.projectId, initial.resultVersionId) || initial; draftStore.write(draft); }
      catch (error) { storageError = '本机草稿无法保存，请恢复存储后重试'; }
      this.setData(Object.assign({ loading: false, storageError }, regionResult));
      this.renderDraft(draft);
    }).catch(error => {
      if (this.acceptResponse(scope, requestId)) this.setData({ loading: false, error: (error && (error.msg || error.message)) || '项目参数加载失败，请重试' });
    });
  },
  invalidateSession() {
    this._requestId = (this._requestId || 0) + 1;
    this._catalogRequestId = (this._catalogRequestId || 0) + 1;
    this._generationId = (this._generationId || 0) + 1;
    this._importId = (this._importId || 0) + 1;
    this._confirmingImport = false;
    this._catalog = [];
    this._catalogRegion = null;
    this.setData({ loading: false, draft: null, values: {}, regions: [], regionName: '待选择', sourceLabel: '',
      storageError: '', regionsError: '', needsReview: false, error: '登录身份已变化，请重新导入参数',
      bodyCount: 0, exteriorCount: 0, customCount: 0, pendingPriceCount: 0, catalogLoading: false, catalogReady: false,
      catalogError: '', selectionNotice: '', canGenerate: false, disabledReason: '', creating: false, generateError: '',
      importConflict: false, reimporting: false, importError: '' });
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
    const region = this.data.regions.find(item => item.code === values.regionCode);
    const validation = draftStore.validate(draft, this.data.regions);
    const body = selection.summary(this._catalog || [], draft, 'BODY');
    const exterior = selection.summary(this._catalog || [], draft, 'EXTERIOR');
    const invalidProvided = Object.keys(validation.errors).some(key => key === 'regionCode' || (values[key] != null && values[key] !== ''));
    const canGenerate = !!region && !invalidProvided && !this.data.storageError && this.data.catalogReady && this._catalogRegion === values.regionCode;
    // 按钮不可用时把原因放在底栏，而不是让用户猜为什么灰
    let disabledReason = '';
    if (!region) disabledReason = invalidProvided ? '基础参数有误，请点「修改」更正后再生成' : '请先在「基础参数」中选择建造地区';
    else if (invalidProvided) disabledReason = '基础参数有误，请点「修改」更正后再生成';
    else if (this.data.storageError) disabledReason = '本机草稿不可用，请恢复存储后重试';
    else if (!this.data.catalogReady) disabledReason = this.data.catalogError ? '预算配置加载失败，请点上方「重试」' : '正在校验地区可选配置…';
    else if (this._catalogRegion !== values.regionCode) disabledReason = '正在同步当前地区的可选配置…';
    this.setData({ draft, values, regionName: region ? region.name : values.regionCode ? '原地区已不可选' : '待选择',
      sourceLabel: draft.resultVersionId ? '已关联设计方案' : '已关联设计项目', needsReview: validation.needsReview,
      bodyCount: body.completeCount, exteriorCount: exterior.completeCount,
      customCount: body.selectedCustomCount + exterior.selectedCustomCount,
      pendingPriceCount: body.pendingPriceCount + exterior.pendingPriceCount, selectionNotice: selection.selectionNotice(draft),
      canGenerate, disabledReason });
    if (this._catalogRegion !== values.regionCode && !this.data.catalogLoading) this.loadCatalog();
  },
  loadCatalog() {
    if (!this.currentSession() || !this.data.draft || this.data.creating || this.data.catalogLoading || this.data.reimporting || this._confirmingImport) return;
    const region = draftStore.values(this.data.draft).regionCode;
    if (!this.data.regions.some(item => item.code === region)) {
      this.setData({ catalogReady: false, canGenerate: false, disabledReason: '请先在「基础参数」中选择建造地区', catalogError: '请先选择已启用的建造地区' });
      return;
    }
    const scope = this._sessionScope;
    const requestId = this._catalogRequestId = (this._catalogRequestId || 0) + 1;
    this.setData({ catalogLoading: true, catalogReady: false, canGenerate: false, disabledReason: '正在校验地区可选配置…', catalogError: '' });
    api.getBudgetOptions(region).then(response => {
      if (requestId !== this._catalogRequestId) return;
      if (scope !== draftStore.sessionScope()) { this.invalidateSession(); return; }
      const items = selection.catalog(response);
      const current = draftStore.read(this.data.draft.projectId, this.data.draft.resultVersionId) || this.data.draft;
      if (draftStore.values(current).regionCode !== region) {
        this.setData({ catalogLoading: false }); this.renderDraft(current); return;
      }
      const draft = selection.reconcile(current, items);
      draftStore.write(draft);
      this._catalog = items;
      this._catalogRegion = region;
      this.setData({ catalogLoading: false, catalogReady: true });
      this.renderDraft(draft);
    }).catch(error => {
      if (requestId !== this._catalogRequestId) return;
      if (scope !== draftStore.sessionScope()) { this.invalidateSession(); return; }
      this.setData({ catalogLoading: false, catalogReady: false, canGenerate: false, disabledReason: '预算配置加载失败，请点上方「重试」',
        catalogError: (error && (error.msg || error.message)) || '配置加载失败，请重试' });
    });
  },
  modify() {
    if (!this.currentSession() || !this.data.draft || this.data.storageError || this.data.creating || this.data.reimporting || this._confirmingImport) return;
    wx.navigateTo({ url: '/pages/budget/parameters' + draftStore.query(this.data.draft), fail: () => wx.showToast({ title: '页面打开失败，请重试', icon: 'none' }) });
  },
  confirmReimport() {
    if (!this.currentSession() || !this.data.draft || this.data.loading || this.data.catalogLoading || this.data.creating || this.data.reimporting || this._confirmingImport) return;
    const scope = this._sessionScope;
    const importId = this._importId = (this._importId || 0) + 1;
    const { projectId, resultVersionId } = this.data.draft;
    this._confirmingImport = true;
    wx.showModal({ title: '重新导入当前参数？',
      content: '将读取当前项目及原方案版本的最新参数，重建这份本机草稿并清空参数修改和预算配置；不会修改设计项目、已存预算或其他草稿。',
      confirmText: '重新导入',
      success: result => {
        if (importId !== this._importId || !this.currentSession()) return;
        this._confirmingImport = false;
        if (!result.confirm) return;
        this.setData({ reimporting: true, importError: '' });
        // Do not consult the project's latest scheme or restore the stale local import.
        api.getBudgetInputs(projectId, resultVersionId).then(response => {
          if (importId !== this._importId) return;
          if (scope !== draftStore.sessionScope()) { this.invalidateSession(); return; }
          if (!response || response.projectId !== projectId || (response.resultVersionId || null) !== resultVersionId) throw Error('项目参数不匹配，原草稿已保留');
          const fresh = draftStore.create(response);
          // A successful owned read is required before replacing this single draft key.
          draftStore.write(fresh);
          this._catalog = [];
          this._catalogRegion = null;
          this.setData({ reimporting: false, importError: '', importConflict: false, generateError: '', storageError: '',
            catalogReady: false, catalogError: '' });
          this.renderDraft(fresh);
          wx.showToast({ title: '已重新导入，配置已清空', icon: 'none' });
        }).catch(error => {
          if (importId !== this._importId) return;
          if (scope !== draftStore.sessionScope()) { this.invalidateSession(); return; }
          this.setData({ reimporting: false, importError: ((error && (error.msg || error.message)) || '参数重新导入失败') + '；原草稿未替换，请重试' });
        });
      },
      fail: () => {
        if (importId !== this._importId || !this.currentSession()) return;
        this._confirmingImport = false;
        this.setData({ importError: '确认窗口未打开，原草稿未替换，请重试' });
      },
    });
  },
  selectProject() { wx.switchTab({ url: '/pages/ai-design/index' }); },
  // 首页预算入口无参进入且 globalData 已清空（如重启）时，引导从已有项目进入而不是只能重新生成
  openMyProjects() {
    wx.navigateTo({ url: '/pages/profile/records?type=projects', fail: () => wx.switchTab({ url: '/pages/profile/index' }) });
  },
  configuration(event) {
    if (!this.currentSession() || !this.data.draft || this.data.creating || this.data.storageError || this.data.reimporting || this._confirmingImport) return;
    const category = event && event.currentTarget.dataset.category;
    if (!['BODY', 'EXTERIOR'].includes(category)) return;
    const target = category === 'BODY' ? '/pages/budget/body' : '/pages/budget/exterior';
    wx.navigateTo({ url: target + draftStore.query(this.data.draft),
      fail: () => wx.showToast({ title: '页面打开失败，请重试', icon: 'none' }) });
  },
  history() {
    if (this.currentSession() && this.data.draft && !this.data.creating && !this.data.reimporting && !this._confirmingImport) wx.navigateTo({ url: '/pages/budget/history?projectId=' + encodeURIComponent(this.data.draft.projectId) });
  },
  generate() {
    if (!this.currentSession() || !this.data.draft || this.data.creating || this.data.loading || this.data.catalogLoading || this.data.reimporting || this._confirmingImport) return;
    let attempt;
    try {
      const current = draftStore.read(this.data.draft.projectId, this.data.draft.resultVersionId) || this.data.draft;
      this.renderDraft(current);
      if (!this.data.canGenerate) { this.setData({ generateError: '请检查地区、基础参数和配置加载状态' }); return; }
      attempt = draftStore.prepareGeneration(current);
      // Persist the operation key before sending; ambiguous failures reuse this exact request.
      draftStore.write(attempt.draft);
    } catch (error) { this.setData({ generateError: '本机草稿无法保存，尚未发起预算，请重试' }); return; }
    const scope = this._sessionScope;
    const generationId = this._generationId = (this._generationId || 0) + 1;
    this.setData({ draft: attempt.draft, creating: true, generateError: '' });
    api.createItemizedBudget(attempt.draft.projectId, attempt.body, attempt.key).then(response => {
      if (generationId !== this._generationId) return;
      if (scope !== draftStore.sessionScope()) { this.invalidateSession(); return; }
      if (!response || !draftStore.id(response.budgetId)) throw Error('预算响应无有效编号，请重试');
      wx.navigateTo({ url: '/pages/budget/result?budgetId=' + encodeURIComponent(response.budgetId),
        success: () => {
          if (scope !== draftStore.sessionScope() || generationId !== this._generationId) return;
          this.setData({ creating: false });
          // A completed navigation ends this operation. A later explicit calculation can use new prices.
          try {
            const saved = draftStore.read(attempt.draft.projectId, attempt.draft.resultVersionId);
            if (saved && saved.generationAttempt && saved.generationAttempt.key === attempt.key) {
              draftStore.write(Object.assign({}, saved, { generationAttempt: null }));
            }
          } catch (error) { this.setData({ storageError: '测算已生成，本机操作记录未更新，请检查存储后重试' }); }
        },
        fail: () => {
          if (scope === draftStore.sessionScope() && generationId === this._generationId) {
            this.setData({ creating: false, generateError: '预算已生成，页面打开失败；重试会读取同一份预算' });
          }
        } });
    }).catch(error => {
      if (generationId !== this._generationId) return;
      if (scope !== draftStore.sessionScope()) { this.invalidateSession(); return; }
      this.setData({ creating: false, generateError: (error && (error.msg || error.message)) || '预算生成失败，请重试',
        importConflict: !!error && String(error.code) === '1099000001' });
    });
  },
  legacy() {
    if (this.currentSession() && this.data.draft && !this.data.creating && !this.data.reimporting && !this._confirmingImport) wx.navigateTo({ url: '/pages/budget/legacy' + draftStore.query(this.data.draft) });
  },
});
