'use strict';
const api = require('./api');
const draftStore = require('./budget-draft');
const format = require('./format');
const CATEGORY_NAMES = { BODY: '主体类', EXTERIOR: '外装类' };
// 行级数量对用户公开（源自用户自己的参数，2026-09-29 决策 B）；单位口径与「我的当地单价」页一致
const UNIT_LABELS = { SQM: 'm²', METER: '米', PIECE: '个', SET: '套', HOUSEHOLD: '户', ITEM: '项' };
const ICONS = { FOUNDATION: 'view-module', STRUCTURE: 'home', ROOF: 'home', DECORATION: 'component-layout', DOORS_WINDOWS: 'archway', WALL_PAINT: 'brush', CULTURE_STONE: 'view-module', LIGHTING: 'lightbulb', WATERPROOF_LIGHTNING: 'secured', INSURANCE: 'secured' };
const STATUS = { PRICED: '已计价', MISSING_PRICE: '待补价', MISSING_QUANTITY: '待补量', MISSING_BOTH: '待补配置/量价', EXCLUDED: '本次未包含' };
const EXPLANATIONS = { FOUNDATION: '包含所选基础施工及材料', STRUCTURE: '包含所选主体结构施工', ROOF: '包含所选屋面做法' };
function safeCents(value) { return Number.isSafeInteger(value) && value >= 0; }
function cents(value) { return safeCents(value) && value <= 10000000000; }
function yuan(value) { return safeCents(value) ? Math.floor(value / 100) + '.' + String(value % 100).padStart(2, '0') : '—'; }
function wan(value) {
  if (!safeCents(value)) return '—';
  const rounded = Math.floor(value / 10000) + (value % 10000 >= 5000 ? 1 : 0);
  return Math.floor(rounded / 100) + '.' + String(rounded % 100).padStart(2, '0');
}
function amount(value, exact) { return (exact ? yuan(value) : wan(value)) + (exact ? '元' : '万'); }
function errorText(error) { return error && (error.msg || error.message) || '预算读取失败，请重试'; }
function text(value) { return typeof value === 'string' ? value : ''; }
function signedCents(value) { return Number.isSafeInteger(value) && value >= -10000000000 && value <= 10000000000; }

// Display projection only; no internal quantity/price/rule object enters page data or storage.
function publicBudget(value) {
  if (!value || !draftStore.id(value.budgetId || value.estimateId) || !draftStore.id(value.projectId)) throw Error('预算数据格式不正确');
  if (value.model === 'LEGACY_RANGE') {
    if (!safeCents(value.totalMinCents) || !safeCents(value.totalMaxCents) || value.totalMinCents > value.totalMaxCents) throw Error('旧预算区间不正确');
    const raw = value.inputSnapshot || {};
    return { budgetId: value.estimateId || value.budgetId, model: value.model, projectId: value.projectId, projectName: '历史区间预算', schemeName: '', resultVersionId: null, regionName: text(raw.regionCode),
      totalMinCents: value.totalMinCents, totalMaxCents: value.totalMaxCents, inputSummary: { buildingArea: raw.buildingArea, structureType: text(raw.structureType), materialGrade: text(raw.materialGrade) },
      createdAt: value.createdAt, disclaimer: text(value.disclaimer), saved: false, items: [], missingItems: [], warnings: [] };
  }
  if (value.model !== 'ITEMIZED_V1' || !draftStore.id(value.revisionId) || !['COMPLETE', 'INCOMPLETE'].includes(value.completeness) || !cents(value.pricedSubtotalCents)
      || (value.completeness === 'COMPLETE' ? !cents(value.totalCents) || value.totalCents !== value.pricedSubtotalCents : value.totalCents !== null)
      || !value.categoryTotals || !cents(value.categoryTotals.BODY) || !cents(value.categoryTotals.EXTERIOR)
      || value.categoryTotals.BODY + value.categoryTotals.EXTERIOR !== value.pricedSubtotalCents || !Array.isArray(value.items)) throw Error('分项预算数据不完整');
  const summary = {};
  ['regionCode', 'footprintArea', 'floorCount', 'buildingArea', 'roofArea'].forEach(key => { if (value.inputSummary && value.inputSummary[key] != null) summary[key] = value.inputSummary[key]; });
  const items = value.items.map(item => {
    if (!Object.prototype.hasOwnProperty.call(CATEGORY_NAMES, item.category) || !['COMPLETE', 'INCOMPLETE'].includes(item.completeness)
        || !cents(item.pricedSubtotalCents) || (item.amountCents !== null && !cents(item.amountCents)) || !Array.isArray(item.lines)) throw Error('预算明细数据不完整');
    return { itemId: item.itemId, itemCode: text(item.itemCode), category: item.category, publicName: text(item.publicName), source: text(item.source),
      completeness: item.completeness, pricedSubtotalCents: item.pricedSubtotalCents, amountCents: item.amountCents,
      lines: item.lines.map(line => {
        if (!Object.prototype.hasOwnProperty.call(STATUS, line.status) || (line.amountCents !== null && !cents(line.amountCents))) throw Error('预算子项数据不完整');
        if (line.quantity != null && typeof line.quantity !== 'string') throw Error('预算子项数量不正确');
        if (line.unit != null && typeof line.unit !== 'string') throw Error('预算子项单位不正确');
        return { lineId: text(line.lineId), optionLabel: text(line.optionLabel), status: line.status, amountCents: line.amountCents,
          quantity: text(line.quantity), unit: text(line.unit) };
      }) };
  });
  return { budgetId: value.budgetId, revisionId: value.revisionId, model: value.model, projectId: value.projectId, projectName: text(value.projectName),
    resultVersionId: value.resultVersionId == null ? null : draftStore.id(value.resultVersionId), schemeName: text(value.schemeName), regionName: text(value.regionName), inputSummary: summary,
    completeness: value.completeness, pricedSubtotalCents: value.pricedSubtotalCents, totalCents: value.totalCents,
    categoryTotals: { BODY: value.categoryTotals.BODY, EXTERIOR: value.categoryTotals.EXTERIOR }, items,
    missingItems: (value.missingItems || []).filter(v => typeof v === 'string'), warnings: (value.warnings || []).filter(v => typeof v === 'string'),
    disclaimer: text(value.disclaimer), createdAt: value.createdAt, saved: value.saved === true };
}
// Quote projection deliberately accepts only the owner-visible contract; internal reasons and line inputs are dropped.
function publicQuote(value) {
  if (!value || !draftStore.id(value.quoteId) || !draftStore.id(value.budgetId) || !draftStore.id(value.revisionId) || !draftStore.id(value.projectId)
      || !Number.isInteger(value.quoteVersion) || value.quoteVersion < 1 || !['PUBLISHED', 'WITHDRAWN'].includes(value.status) || typeof value.current !== 'boolean'
      || !cents(value.calculatedTotalCents) || !signedCents(value.adjustmentCents) || !cents(value.finalPriceCents)
      || value.finalPriceCents !== value.calculatedTotalCents + value.adjustmentCents || !value.categoryTotals
      || !cents(value.categoryTotals.BODY) || !cents(value.categoryTotals.EXTERIOR) || !Array.isArray(value.items)) throw Error('正式报价数据不完整');
  const items = value.items.map(item => {
    if (!Object.prototype.hasOwnProperty.call(CATEGORY_NAMES, item.category) || !['COMPLETE', 'INCOMPLETE'].includes(item.completeness)
        || !cents(item.pricedSubtotalCents) || (item.amountCents !== null && !cents(item.amountCents)) || !Array.isArray(item.lines)) throw Error('正式报价明细不完整');
    return { itemId: item.itemId == null ? null : draftStore.id(item.itemId), itemCode: text(item.itemCode), category: item.category, publicName: text(item.publicName), source: text(item.source),
      completeness: item.completeness, pricedSubtotalCents: item.pricedSubtotalCents, amountCents: item.amountCents,
      lines: item.lines.map(line => {
        if (!draftStore.id(line.lineId) || !Object.prototype.hasOwnProperty.call(STATUS, line.status) || (line.amountCents !== null && !cents(line.amountCents))) throw Error('正式报价子项不完整');
        return { lineId: line.lineId, optionLabel: text(line.optionLabel), status: line.status, amountCents: line.amountCents };
      }) };
  });
  return { quoteId: value.quoteId, quoteVersion: value.quoteVersion, budgetId: value.budgetId, revisionId: value.revisionId, projectId: value.projectId,
    projectName: text(value.projectName), schemeName: text(value.schemeName), regionName: text(value.regionName), status: value.status, current: value.current,
    calculatedTotalCents: value.calculatedTotalCents, adjustmentCents: value.adjustmentCents, finalPriceCents: value.finalPriceCents,
    categoryTotals: { BODY: value.categoryTotals.BODY, EXTERIOR: value.categoryTotals.EXTERIOR }, items,
    publishedAt: value.publishedAt || null, withdrawnAt: value.withdrawnAt || null, disclaimer: text(value.disclaimer) };
}
function quoteView(quote, exact) {
  const signed = quote.adjustmentCents > 0 ? '+' : quote.adjustmentCents < 0 ? '−' : '';
  return { quoteAmountText: exact ? yuan(quote.finalPriceCents) : wan(quote.finalPriceCents), quoteAmountUnit: exact ? '元' : '万元',
    quoteExactText: yuan(quote.finalPriceCents) + '元', quoteCalculatedText: yuan(quote.calculatedTotalCents) + '元',
    quoteAdjustmentText: signed + yuan(Math.abs(quote.adjustmentCents)) + '元' };
}
function viewModel(estimate, category, exact) {
  const title = category ? CATEGORY_NAMES[category] + '明细' : '参考预算';
  if (estimate.model === 'LEGACY_RANGE') return { title, legacy: true, amountLabel: '历史参考区间', amountText: (exact ? yuan : wan)(estimate.totalMinCents) + '–' + (exact ? yuan : wan)(estimate.totalMaxCents), amountUnit: exact ? '元' : '万元',
    exactText: yuan(estimate.totalMinCents) + '–' + yuan(estimate.totalMaxCents) + '元', groups: [], categoryRows: [],
    basis: [{ label: '建筑面积', value: estimate.inputSummary.buildingArea ? estimate.inputSummary.buildingArea + ' m²' : '未记录' },
      { label: '结构形式', value: format.structureLabel(estimate.inputSummary.structureType) || '未记录' }, { label: '材料等级', value: estimate.inputSummary.materialGrade || '未记录' }],
    warningText: '区间测算记录仅供历史查询，不回填分项或推算中值。' };
  const groups = estimate.items.filter(item => !category || item.category === category).map(item => ({
    ...item, icon: ICONS[item.itemCode] || 'file', custom: item.source !== 'STANDARD',
    amountText: item.amountCents == null ? (item.completeness === 'INCOMPLETE' ? '待补齐' : '未计入') : amount(item.amountCents, exact),
    description: item.lines.map(line => line.optionLabel || STATUS[line.status]).join(' + '),
    explanation: EXPLANATIONS[item.itemCode] || '按本次所选配置计入，具体范围以正式报价为准', subtotalText: amount(item.pricedSubtotalCents, true),
    lines: item.lines.map(line => ({ ...line, statusLabel: STATUS[line.status],
      amountText: line.amountCents == null ? STATUS[line.status] : amount(line.amountCents, true),
      quantityText: line.quantity ? line.quantity + (UNIT_LABELS[line.unit] || line.unit || '') : '' }))
  }));
  const complete = estimate.completeness === 'COMPLETE';
  const shownAmount = category ? estimate.categoryTotals[category] : complete ? estimate.totalCents : estimate.pricedSubtotalCents;
  const s = estimate.inputSummary;
  return { title, legacy: false, categoryTitle: CATEGORY_NAMES[category] || '', amountLabel: (category ? CATEGORY_NAMES[category] : '参考预算') + (complete ? (category ? '合计' : '总额') : '已计价小计'),
    amountText: exact ? yuan(shownAmount) : wan(shownAmount), amountUnit: exact ? '元' : '万元', exactText: yuan(shownAmount) + '元', groups,
    categoryRows: Object.keys(CATEGORY_NAMES).map(code => ({ code, name: CATEGORY_NAMES[code], icon: code === 'BODY' ? 'home' : 'archway',
      description: code === 'BODY' ? '地基基础 · 主体结构 · 屋面结构' : '装饰构件 · 门窗 · 外墙漆等7项', amountText: amount(estimate.categoryTotals[code], exact), incomplete: !complete })),
    basis: [{ label: '占地面积', value: s.footprintArea ? s.footprintArea + ' m²' : '待补齐' }, { label: '建筑层数', value: s.floorCount ? s.floorCount + '层' : '待补齐' },
      { label: '屋顶面积', value: s.roofArea ? s.roofArea + ' m²' : '待补齐' }, { label: '建造地区', value: estimate.regionName || '待补齐' }],
    warningText: complete ? '' : '此预算尚不完整，当前仅展示已计价小计；待补项和参数须核定后才能形成完整报价。' };
}

// Three budget reading screens share authorized ID/revision loading, not an in-memory-only result.
function budgetPage(category) {
  return {
    data: { title: category ? CATEGORY_NAMES[category] + '明细' : '参考预算', category: category || '', loading: false, error: '', saving: false, saveError: '', estimate: null,
      groups: [], categoryRows: [], basis: [], exact: false, expanded: {}, legacy: false, quoteLoading: false, quoteError: '', quoteStatusText: '', quote: null },
    onLoad(options) {
      this._budgetId = draftStore.id(options.budgetId || options.estimateId);
      this._revisionId = options.revisionId == null ? null : draftStore.id(options.revisionId);
      if (!this._budgetId || (options.revisionId != null && !this._revisionId)) { this._budgetId = null; this.setData({ error: '预算编号无效，请从项目历史重新进入' }); return; }
      this.load();
    },
    onShow() { if (this._scope && this._scope !== draftStore.sessionScope()) { this.clearSession(); this.load(); } },
    onUnload() { this._sequence = (this._sequence || 0) + 1; this._scope = null; },
    clearSession() {
      this._sequence = (this._sequence || 0) + 1; this._saveKey = null;
      this.setData({ loading: false, saving: false, estimate: null, groups: [], categoryRows: [], basis: [], expanded: {}, saveError: '', quoteLoading: false, quoteError: '', quoteStatusText: '', quote: null, error: '登录身份已变化，请重新读取预算' });
    },
    current() { if (!this._scope || this._scope !== draftStore.sessionScope()) { this.clearSession(); return false; } return true; },
    load() {
      if (!this._budgetId || this.data.loading) return;
      const scope = draftStore.sessionScope(); if (!scope) { this.clearSession(); return; }
      this._scope = scope; const seq = this._sequence = (this._sequence || 0) + 1;
      this.setData({ loading: true, error: '', estimate: null, groups: [], categoryRows: [], basis: [], quoteLoading: false, quoteError: '', quoteStatusText: '', quote: null });
      api.getBudgetEstimate(this._budgetId, this._revisionId).then(response => {
        if (seq !== this._sequence || !this.current()) return;
        const estimate = publicBudget(response);
        if (estimate.budgetId !== this._budgetId || (this._revisionId && estimate.revisionId !== this._revisionId) || (category && estimate.model !== 'ITEMIZED_V1')) throw Error('预算版本不匹配');
        this.setData({ estimate, loading: false, ...viewModel(estimate, category, this.data.exact) });
        if (!category && estimate.model === 'ITEMIZED_V1') this.loadQuote(estimate.projectId, seq);
      }).catch(error => { if (seq === this._sequence && this.current()) this.setData({ loading: false, error: errorText(error) }); });
    },
    loadQuote(projectId, seq) {
      this.setData({ quoteLoading: true, quoteError: '', quoteStatusText: '' });
      api.getBudgetQuotes(projectId).then(response => {
        if (seq !== this._sequence || !this.current()) return;
        if (!Array.isArray(response)) throw Error('正式报价列表格式不正确');
        const quotes = response.map(publicQuote);
        if (quotes.some(quote => quote.projectId !== projectId)) throw Error('正式报价项目不匹配');
        const latest = quotes.find(quote => quote.current && quote.budgetId === this._budgetId);
        const quote = latest && latest.status === 'PUBLISHED' && latest.budgetId === this._budgetId ? latest : null;
        this.setData({ quoteLoading: false, quote, quoteStatusText: latest && latest.status === 'WITHDRAWN' ? '当前正式报价已撤回' : '', ...(quote ? quoteView(quote, this.data.exact) : {}) });
      }).catch(error => { if (seq === this._sequence && this.current()) this.setData({ quoteLoading: false, quoteError: errorText(error) }); });
    },
    toggleExact() { if (this.current() && this.data.estimate) { const exact = !this.data.exact; this.setData({ exact, ...viewModel(this.data.estimate, category, exact), ...(this.data.quote ? quoteView(this.data.quote, exact) : {}) }); } },
    expand(event) { if (this.current()) { const key = event.currentTarget.dataset.index; this.setData({ expanded: { ...this.data.expanded, [key]: !this.data.expanded[key] } }); } },
    detail(event) {
      if (!this.current() || !this.data.estimate || this.data.legacy) return;
      const target = { BODY: 'body-detail', EXTERIOR: 'exterior-detail' }[event.currentTarget.dataset.category]; if (!target) return;
      wx.navigateTo({ url: '/pages/budget/' + target + '?budgetId=' + this._budgetId + '&revisionId=' + this.data.estimate.revisionId,
        fail: () => wx.showToast({ title: '明细打开失败，请重试', icon: 'none' }) });
    },
    save() {
      if (!this.current() || this.data.saving || !this.data.estimate || this.data.legacy || this.data.estimate.saved) return;
      const scope = this._scope, seq = this._sequence;
      this._saveKey = this._saveKey || 'budget-save-' + Date.now() + '-' + Math.random().toString(36).slice(2, 12);
      this.setData({ saving: true, saveError: '' });
      api.saveBudgetEstimate(this._budgetId, this._saveKey).then(result => {
        if (seq !== this._sequence || scope !== this._scope || !this.current()) return;
        if (!result || result.budgetId !== this._budgetId || result.saved !== true) throw Error('保存结果不正确，请重试');
        this.setData({ saving: false, estimate: { ...this.data.estimate, saved: true } }); wx.showToast({ title: '已保存到项目预算', icon: 'success' });
      }).catch(error => { if (seq === this._sequence && scope === this._scope && this.current()) this.setData({ saving: false, saveError: errorText(error) }); });
    },
    adjust() {
      if (this.current() && this.data.estimate) wx.navigateTo({ url: '/pages/budget/input' + draftStore.query(this.data.estimate), fail: () => wx.showToast({ title: '页面打开失败，请重试', icon: 'none' }) });
    },
    history() { if (this.current() && this.data.estimate) wx.navigateTo({ url: '/pages/budget/history?projectId=' + this.data.estimate.projectId }); },
    back() {
      if (this.current()) wx.navigateBack({ fail: () => wx.redirectTo({ url: '/pages/budget/result?budgetId=' + this._budgetId + (this._revisionId ? '&revisionId=' + this._revisionId : '') }) });
    }
  };
}
module.exports = { yuan, wan, amount, publicBudget, publicQuote, quoteView, viewModel, budgetPage, errorText };
