'use strict';

const draftStore = require('./budget-draft');
const api = require('./api');

const STANDARD = [
  ['FOUNDATION', 'BODY', '地基基础', '选择基础形式'],
  ['STRUCTURE', 'BODY', '主体结构', '选择结构形式'],
  ['ROOF', 'BODY', '屋面结构', '选择屋面做法'],
  ['DECORATION', 'EXTERIOR', '装饰构件', '选择装饰风格'],
  ['DOORS_WINDOWS', 'EXTERIOR', '门窗', '选择门窗配置'],
  ['WALL_PAINT', 'EXTERIOR', '外墙漆', '选择外墙工艺'],
  ['CULTURE_STONE', 'EXTERIOR', '文化石', '选择文化石规格'],
  ['LIGHTING', 'EXTERIOR', '灯具', '选择灯光配置'],
  ['WATERPROOF_LIGHTNING', 'EXTERIOR', '防水防雷', '选择安全配置'],
  ['INSURANCE', 'EXTERIOR', '保险', '选择工程保险'],
];
const REQUIRED_GROUPS = { DOORS_WINDOWS: ['DOOR', 'WINDOW'], WATERPROOF_LIGHTNING: ['WATERPROOF', 'LIGHTNING'] };
const GROUP_NAMES = { DOOR: '门', WINDOW: '窗', WATERPROOF: '防水', LIGHTNING: '防雷',
  WASHER: '洗墙灯', STRIP: '灯带', WALL_LAMP: '壁灯' };

function catalog(response) {
  if (!response || !Array.isArray(response.items)) throw Error('预算选项目录格式无效，请重试');
  const ids = new Set();
  return response.items.filter(item => ['STANDARD', 'CUSTOM_TEMPLATE'].includes(item.source))
    .map(item => {
      if (!draftStore.id(item.itemId) || !['BODY', 'EXTERIOR'].includes(item.category)
          || typeof item.code !== 'string' || typeof item.name !== 'string' || !Array.isArray(item.options)) throw Error('预算选项目录不完整');
      return { itemId: item.itemId, code: item.code, category: item.category, name: item.name, source: item.source,
        options: item.options.map(option => {
          if (!draftStore.id(option.optionId) || ids.has(option.optionId) || typeof option.label !== 'string'
              || typeof option.selectionGroup !== 'string' || !option.selectionGroup
              || !['AVAILABLE', 'MISSING_PRICE'].includes(option.availability)) throw Error('预算选项无效，请重试');
          ids.add(option.optionId);
          return { optionId: option.optionId, code: option.code, label: option.label,
            selectionGroup: option.selectionGroup, availability: option.availability };
        }) };
    });
}

function reconcile(draft, items) {
  const byId = new Map();
  items.forEach(item => item.options.forEach(option => byId.set(option.optionId, { item, option })));
  const groups = new Set();
  const ids = Array.isArray(draft.selections) ? draft.selections : [];
  const selections = ids.filter(value => {
    const selected = byId.get(value);
    if (!selected) return false;
    const key = selected.item.itemId + ':' + selected.option.selectionGroup;
    if (groups.has(key)) return false;
    groups.add(key);
    return true;
  });
  return Object.assign({}, draft, { selections,
    selectionResetReason: selections.length !== ids.length ? 'OPTION_UNAVAILABLE' : draft.selectionResetReason });
}

function choose(draft, items, itemId, group, optionId) {
  const item = items.find(value => value.itemId === itemId);
  if (!item) throw Error('该预算项已不可选，请刷新');
  if (optionId && !item.options.some(option => option.optionId === optionId && option.selectionGroup === group)) throw Error('该配置已失效，请重选');
  const groupIds = item.options.filter(option => option.selectionGroup === group).map(option => option.optionId);
  const selections = (draft.selections || []).filter(value => !groupIds.includes(value));
  if (optionId) selections.push(optionId);
  return Object.assign({}, draft, { selections });
}

function row(item, selectedIds, hint) {
  const selected = item.options.filter(option => selectedIds.includes(option.optionId));
  const groups = selected.map(option => option.selectionGroup);
  const required = REQUIRED_GROUPS[item.code];
  const complete = required ? required.every(group => groups.includes(group)) : selected.length > 0;
  return Object.assign({}, item, { hint: hint || '选择补充项目配置', complete,
    pendingPrice: selected.some(option => option.availability === 'MISSING_PRICE'),
    selectedLabel: selected.map(option => option.label).join(' + ') || (item.options.length ? '请选择' : '待配置'),
    partial: !complete && selected.length > 0 });
}

function summary(items, draft, category) {
  const selectedIds = draft.selections || [];
  const standardRows = STANDARD.filter(entry => entry[1] === category).map(entry => {
    const actual = items.find(item => item.source === 'STANDARD' && item.code === entry[0] && item.category === category);
    return row(actual || { itemId: null, code: entry[0], category, name: entry[2], source: 'STANDARD', options: [] }, selectedIds, entry[3]);
  });
  const customRows = items.filter(item => item.source === 'CUSTOM_TEMPLATE' && item.category === category).map(item => row(item, selectedIds));
  return { standardRows, customRows, completeCount: standardRows.filter(item => item.complete).length, totalCount: standardRows.length,
    selectedCustomCount: customRows.filter(item => item.complete).length,
    pendingPriceCount: standardRows.concat(customRows).filter(item => item.pendingPrice).length };
}

function groupChoices(item, draft) {
  const names = [...new Set(item.options.map(option => option.selectionGroup))];
  (REQUIRED_GROUPS[item.code] || []).forEach(name => { if (!names.includes(name)) names.push(name); });
  return names.map(name => {
    const options = item.options.filter(option => option.selectionGroup === name).map(option => Object.assign({}, option, {
      selected: (draft.selections || []).includes(option.optionId),
      displayLabel: option.label + (option.availability === 'MISSING_PRICE' ? '（待补价）' : '')
    }));
    return { name, label: GROUP_NAMES[name] || (names.length === 1 ? '配置选项' : name),
      options };
  });
}

function selectionNotice(draft) {
  return draft.selectionResetReason === 'REGION_CHANGED' ? '建造地区已修改，原预算配置已清空，请重新选择。'
    : draft.selectionResetReason === 'OPTION_UNAVAILABLE' ? '部分选项已停用或不再公开，已移除失效选择，请重新核对。' : '';
}

// Both category pages share this exact flow; only their category and standard count differ.
function categoryPage(category) {
  return {
    data: { category, categoryTitle: category === 'BODY' ? '主体类' : '外装类', loading: false, error: '', draft: null,
      standardRows: [], customRows: [], completeCount: 0, totalCount: category === 'BODY' ? 3 : 7,
      pendingPriceCount: 0, storageError: '', notice: '', activeItem: null, activeGroups: [], regionMissing: false },
    onLoad(options) {
      this._projectId = draftStore.id(options.projectId);
      this._versionId = options.resultVersionId == null ? null : draftStore.id(options.resultVersionId);
      if (!this._projectId || (options.resultVersionId != null && !this._versionId)) {
        this._projectId = null; this.setData({ error: '请从快速预算页进入配置' }); return;
      }
      this.load();
    },
    onShow() {
      if (this._scope && this._scope !== draftStore.sessionScope()) { this.clearSession(); this.load(); }
      else if (this.data.draft) this.load();
    },
    onUnload() { this._requestId = (this._requestId || 0) + 1; this._scope = null; },
    clearSession() {
      this._requestId = (this._requestId || 0) + 1;
      this._catalog = [];
      this.setData({ loading: false, draft: null, standardRows: [], customRows: [], activeItem: null, activeGroups: [],
        completeCount: 0, pendingPriceCount: 0, notice: '', storageError: '', regionMissing: false, error: '登录身份已变化，请重新导入' });
    },
    currentSession() {
      if (!this._scope || this._scope !== draftStore.sessionScope()) { this.clearSession(); return false; }
      return true;
    },
    acceptResponse(scope, requestId) {
      if (requestId !== this._requestId) return false;
      if (scope !== draftStore.sessionScope()) { this.clearSession(); return false; }
      return true;
    },
    load() {
      if (!this._projectId || this.data.loading) return;
      const scope = draftStore.sessionScope();
      if (!scope) { this.clearSession(); return; }
      if (this._scope !== scope) this.clearSession();
      this._scope = scope;
      const requestId = this._requestId = (this._requestId || 0) + 1;
      this.setData({ loading: true, error: '', activeItem: null });
      Promise.all([api.getBudgetInputs(this._projectId, this._versionId), api.getBudgetRegions()]).then(([response, regions]) => {
        if (!this.acceptResponse(scope, requestId)) return null;
        if (response.projectId !== this._projectId || (response.resultVersionId || null) !== this._versionId) throw Error('项目参数不匹配');
        const draft = draftStore.read(this._projectId, this._versionId) || draftStore.create(response);
        const region = draftStore.values(draft).regionCode;
        if (!regions.some(item => item.code === region)) return { draft, items: [], regionMissing: true };
        return api.getBudgetOptions(region).then(response => ({ draft, items: catalog(response), regionMissing: false }));
      }).then(result => {
        if (!result || !this.acceptResponse(scope, requestId)) return;
        this._catalog = result.items;
        const draft = result.regionMissing ? result.draft : reconcile(result.draft, result.items);
        this.setData({ loading: false, regionMissing: result.regionMissing });
        this.persist(draft);
      }).catch(error => {
        if (this.acceptResponse(scope, requestId)) this.setData({ loading: false, error: (error && (error.msg || error.message)) || '配置加载失败，请重试' });
      });
    },
    persist(draft) {
      if (!this.currentSession() || draft.sessionScope !== this._scope) return;
      let storageError = '';
      try { draftStore.write(draft); } catch (error) { storageError = '配置未保存：本机存储不可用，请重试'; }
      const activeItem = this.data.activeItem;
      this.setData(Object.assign({ draft, storageError, notice: selectionNotice(draft),
        activeGroups: activeItem ? groupChoices(activeItem, draft) : [] }, summary(this._catalog, draft, category)));
    },
    openItem(event) {
      if (!this.currentSession() || !this.data.draft || this.data.regionMissing) return;
      const item = this._catalog.find(item => item.itemId === event.currentTarget.dataset.id);
      if (!item || !item.options.length) { wx.showToast({ title: '该项暂无可选配置，请管理员配置', icon: 'none' }); return; }
      this.setData({ activeItem: item, activeGroups: groupChoices(item, this.data.draft) });
    },
    chooseOption(event) {
      if (!this.currentSession() || !this.data.activeItem) return;
      const group = this.data.activeGroups.find(group => group.name === event.currentTarget.dataset.group);
      const option = group && group.options.find(option => option.optionId === event.currentTarget.dataset.optionId);
      if (!option) return;
      const singleGroup = this.data.activeGroups.length === 1;
      this.persist(choose(this.data.draft, this._catalog, this.data.activeItem.itemId, group.name, option.optionId));
      if (singleGroup && !this.data.storageError) this.closeItem();
    },
    closeItem() { this.setData({ activeItem: null, activeGroups: [] }); },
    keepSheet() {},
    parameters() {
      if (this.currentSession() && this.data.draft) wx.navigateTo({ url: '/pages/budget/parameters' + draftStore.query(this.data.draft) });
    },
    save() {
      if (!this.currentSession() || !this.data.draft || this.data.loading) return;
      this.persist(this.data.draft);
      if (this.data.storageError) return;
      wx.navigateBack({ fail: () => {
        if (this.currentSession() && this.data.draft) wx.redirectTo({ url: '/pages/budget/input' + draftStore.query(this.data.draft) });
      } });
    },
  };
}

module.exports = { catalog, reconcile, choose, summary, groupChoices, selectionNotice, categoryPage };
