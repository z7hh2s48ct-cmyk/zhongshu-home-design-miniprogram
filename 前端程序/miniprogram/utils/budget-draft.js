'use strict';

const { getToken } = require('./request');
const { sha256Hex } = require('./sha256');

// Local editing state only; never a saved budget or a pricing snapshot.
const FIELDS = ['regionCode', 'footprintArea', 'floorCount', 'buildingArea', 'roofArea'];
const EDITABLE = ['regionCode', 'footprintArea', 'floorCount', 'roofArea'];
const SOURCE_NAMES = ['PROJECT', 'DERIVED', 'USER_OVERRIDE', 'ADMIN_OVERRIDE'];

const SCOPE_KEY = 'zs_draft_scope';

function sessionScope() {
  // P2-C（报告 15）：草稿作用域改绑本地稳定标识——access token 会静默刷新轮换，
  // 旧实现按 token 摘要定作用域，刷新后旧草稿永远读不到（用户配到一半全丢）。
  // 现改为首次使用时生成的稳定随机标识：token 刷新不影响，登出/会话失效
  // （request.js clearTokens）时清除，账号切换自然隔离。
  try {
    if (!getToken()) return null;
    let scope = wx.getStorageSync(SCOPE_KEY);
    if (typeof scope !== 'string' || !/^[0-9a-f]{64}$/.test(scope)) {
      const bytes = new Uint8Array(32);
      for (let index = 0; index < bytes.length; index++) bytes[index] = Math.floor(Math.random() * 256);
      scope = sha256Hex(bytes);
      wx.setStorageSync(SCOPE_KEY, scope);
    }
    return scope;
  } catch (error) { return null; }
}

function id(value) {
  if (typeof value !== 'string' || !/^[1-9][0-9]{0,18}$/.test(value)
      || (value.length === 19 && value > '9223372036854775807')) return null;
  return value;
}

function area(value) {
  if (typeof value !== 'string' || !/^(0|[1-9][0-9]*)(\.[0-9]{1,4})?$/.test(value) || value.length > 20) return null;
  const parts = value.split('.');
  const scaled = Number(parts[0]) * 10000 + Number((parts[1] || '').padEnd(4, '0'));
  return scaled > 0 && scaled <= 10000000000 ? scaled : null;
}

function areaText(scaled) {
  return (Math.floor(scaled / 10000) + '.' + String(scaled % 10000).padStart(4, '0')).replace(/\.?0+$/, '');
}

function floor(value) { return /^(?:[1-9]|1[0-9]|20)$/.test(String(value)) ? Number(value) : null; }

function publicValues(values) {
  const result = {};
  FIELDS.forEach(function (key) {
    const value = (values || {})[key];
    if (key === 'floorCount') result[key] = floor(value);
    else if (key === 'regionCode') result[key] = typeof value === 'string' && /^[A-Za-z0-9_-]{1,32}$/.test(value) ? value : null;
    else result[key] = area(value) == null ? null : areaText(area(value));
  });
  return result;
}

function storageKey(projectId, resultVersionId, scope) {
  if (!id(projectId) || (resultVersionId != null && !id(resultVersionId))) throw new Error('项目或方案编号无效');
  if (!scope || scope !== sessionScope()) throw new Error('登录身份已变化，请重新导入参数');
  return 'zs_budget_draft_v2:' + scope + ':' + projectId + ':' + (resultVersionId || 'project');
}

function create(response) {
  const scope = sessionScope();
  storageKey(response.projectId, response.resultVersionId, scope);
  const sources = {};
  FIELDS.forEach(function (key) {
    const value = (response.sources || {})[key];
    if (SOURCE_NAMES.includes(value)) sources[key] = value;
  });
  return { schema: 2, sessionScope: scope, projectId: response.projectId, resultVersionId: response.resultVersionId || null,
    imported: publicValues(response.importedValues), importedSources: sources,
    requirementSnapshotIds: (response.requirementSnapshotIds || []).filter(value => id(value)),
    overrides: {}, selections: [], selectionResetReason: '', generationAttempt: null };
}

function read(projectId, resultVersionId) {
  const scope = sessionScope();
  const saved = wx.getStorageSync(storageKey(projectId, resultVersionId, scope));
  if (!saved || saved.schema !== 2 || saved.sessionScope !== scope || saved.projectId !== projectId || saved.resultVersionId !== (resultVersionId || null)) return null;
  const draft = create({ projectId, resultVersionId, importedValues: saved.imported, sources: saved.importedSources,
    requirementSnapshotIds: saved.requirementSnapshotIds });
  EDITABLE.forEach(function (key) {
    if (Object.prototype.hasOwnProperty.call(saved.overrides || {}, key)) draft.overrides[key] = saved.overrides[key];
  });
  draft.selections = Array.isArray(saved.selections) ? [...new Set(saved.selections.filter(value => id(value)))] : [];
  draft.selectionResetReason = ['REGION_CHANGED', 'OPTION_UNAVAILABLE'].includes(saved.selectionResetReason) ? saved.selectionResetReason : '';
  const pricing = saved.usageConfirmation;
  if (pricing && pricing.product === 'BUDGET_ESTIMATE' && id(pricing.ruleId)
      && Number.isSafeInteger(pricing.ruleVersion) && pricing.ruleVersion > 0
      && Number.isSafeInteger(saved.usagePointCost) && saved.usagePointCost > 0) {
    draft.usageConfirmation = { product: pricing.product, ruleId: pricing.ruleId, ruleVersion: pricing.ruleVersion };
    draft.usagePointCost = saved.usagePointCost;
  }
  if (saved.generationAttempt && typeof saved.generationAttempt.signature === 'string'
      && saved.generationAttempt.signature === JSON.stringify(requestBody(draft))
      && /^[A-Za-z0-9_-]{1,64}$/.test(saved.generationAttempt.key || '')) {
    draft.generationAttempt = { signature: saved.generationAttempt.signature, key: saved.generationAttempt.key };
  }
  return draft;
}

function write(draft) { wx.setStorageSync(storageKey(draft.projectId, draft.resultVersionId, draft.sessionScope), draft); }

function edit(draft, field, value) {
  if (!EDITABLE.includes(field)) throw new Error('此参数不可编辑');
  const overrides = Object.assign({}, draft.overrides);
  overrides[field] = value === '' ? null : value;
  const next = Object.assign({}, draft, { overrides });
  if (field === 'regionCode' && value !== values(draft).regionCode) {
    next.selections = [];
    next.selectionResetReason = 'REGION_CHANGED';
  }
  return next;
}

function restore(draft) {
  const next = Object.assign({}, draft, { overrides: {} });
  if (values(draft).regionCode !== draft.imported.regionCode) {
    next.selections = [];
    next.selectionResetReason = 'REGION_CHANGED';
  }
  return next;
}

function requestBody(draft) {
  const inputOverrides = {};
  EDITABLE.forEach(function (key) {
    if (Object.prototype.hasOwnProperty.call(draft.overrides || {}, key)) inputOverrides[key] = draft.overrides[key];
  });
  const body = { resultVersionId: draft.resultVersionId, inputOverrides,
    optionIds: [...new Set((Array.isArray(draft.selections) ? draft.selections : []).filter(value => id(value)))].sort(),
    requirementSnapshotIds: draft.requirementSnapshotIds.slice() };
  if (draft.usageConfirmation) body.usageConfirmation = draft.usageConfirmation;
  return body;
}

function prepareGeneration(draft) {
  const body = requestBody(draft);
  const signature = JSON.stringify(body);
  const previous = draft.generationAttempt;
  const key = previous && previous.signature === signature ? previous.key
    : 'budget-' + Date.now() + '-' + Math.random().toString(36).slice(2, 12) + '-' + Math.random().toString(36).slice(2, 8);
  return { body, key, draft: Object.assign({}, draft, { generationAttempt: { signature, key } }) };
}

function values(draft) {
  const current = Object.assign({}, draft.imported, draft.overrides);
  const footprint = area(current.footprintArea);
  const floors = floor(current.floorCount);
  // Only derived area follows edits. Actual project area stays independent.
  if (!draft.imported.buildingArea || draft.importedSources.buildingArea === 'DERIVED') {
    current.buildingArea = footprint && floors && footprint * floors <= 10000000000 ? areaText(footprint * floors) : null;
  }
  return current;
}

function validate(draft, regions) {
  const current = values(draft);
  const errors = {};
  if (area(current.footprintArea) == null) errors.footprintArea = '请输入大于0且不超过100万㎡的面积，最多4位小数';
  if (!floor(current.floorCount)) errors.floorCount = '请选择1至4层';
  if (area(current.roofArea) == null) errors.roofArea = '请填写实际屋顶面积，最多4位小数';
  if (!current.regionCode || !regions.some(region => region.code === current.regionCode)) errors.regionCode = '请选择已启用的建造地区';
  const expected = area(current.footprintArea) * floor(current.floorCount);
  const hasActual = draft.imported.buildingArea && draft.importedSources.buildingArea !== 'DERIVED';
  const needsReview = !!hasActual && expected > 0 && expected !== area(draft.imported.buildingArea);
  return { errors, needsReview, valid: Object.keys(errors).length === 0 };
}

function query(draft) {
  return '?projectId=' + encodeURIComponent(draft.projectId)
    + (draft.resultVersionId ? '&resultVersionId=' + encodeURIComponent(draft.resultVersionId) : '');
}

module.exports = { id, area, create, read, write, edit, restore, values, validate, query, sessionScope, requestBody, prepareGeneration };
