const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../miniprogram');
const projectId = '9007199254740993', budgetId = '9007199254740995', revisionId = '9007199254740997';
const plain = value => JSON.parse(JSON.stringify(value));
const flush = () => new Promise(resolve => setImmediate(resolve));
const event = dataset => ({ currentTarget: { dataset } });
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const fixture = (extra = {}) => ({ budgetId, revisionId, model: 'ITEMIZED_V1', projectId, projectName: '泉州住宅', schemeName: '方案版本 2', resultVersionId: '9007199254740999', regionName: '福建 · 泉州',
  inputSummary: { footprintArea: '120', floorCount: 2, roofArea: '112', buildingArea: '240', regionCode: 'FJ_QZ' },
  completeness: 'COMPLETE', pricedSubtotalCents: 48202000, totalCents: 48202000, categoryTotals: { BODY: 33000000, EXTERIOR: 15202000 },
  items: [{ itemId: '11', itemCode: 'FOUNDATION', category: 'BODY', publicName: '地基基础', source: 'STANDARD', completeness: 'COMPLETE', pricedSubtotalCents: 33000000, amountCents: 33000000,
    lines: [{ lineId: '21', optionLabel: '整板基础', status: 'PRICED', amountCents: 33000000 }] },
  { itemId: '12', itemCode: 'DOORS_WINDOWS', category: 'EXTERIOR', publicName: '门窗', source: 'STANDARD', completeness: 'COMPLETE', pricedSubtotalCents: 15202000, amountCents: 15202000,
    lines: [{ lineId: '22', optionLabel: '门', status: 'PRICED', amountCents: 15202000 }, { lineId: '23', optionLabel: '窗', status: 'PRICED', amountCents: 0 }] }],
  missingItems: [], warnings: [], disclaimer: '仅供参考，不构成报价或结算依据', createdAt: '2026-09-08T10:00:00Z', saved: false, ...extra });
const legacy = () => ({ estimateId: budgetId, model: 'LEGACY_RANGE', projectId, totalMinCents: 30000000, totalMaxCents: 50000000,
  inputSnapshot: { buildingArea: 240, structureType: 'FRAME', materialGrade: 'A', unitPrice: 'secret' }, createdAt: 1788861600000, disclaimer: '历史参考区间' });
const quote = (extra = {}) => ({ quoteId: '9007199254741011', quoteVersion: 2, budgetId, revisionId, projectId, projectName: '泉州住宅', schemeName: '方案版本 2', regionName: '福建 · 泉州',
  status: 'PUBLISHED', current: true, calculatedTotalCents: 48202000, adjustmentCents: 180000, finalPriceCents: 48382000,
  categoryTotals: { BODY: 33000000, EXTERIOR: 15202000 }, items: fixture().items, publishedAt: '2026-09-08T12:00:00Z', withdrawnAt: null,
  disclaimer: '正式报价以本版本金额及已列范围为准', ...extra });

function runtime(overrides = {}) {
  let token = 'user-A-session', definition;
  const storage = { v12Authorized: true }, calls = [], modules = new Map();
  const api = { getBudgetEstimate: async (id, revision) => { calls.push(['get', id, revision]); return fixture(); },
    getBudgetQuotes: async id => { calls.push(['quotes', id]); return []; },
    saveBudgetEstimate: async (id, key) => { calls.push(['save', id, key]); return { budgetId: id, saved: true }; },
    getBudgetHistory: async (id, options) => { calls.push(['history', id, plain(options)]); return { list: [fixture({ saved: true })], nextCursor: null }; }, ...overrides };
  const wx = { getStorageSync: key => storage[key], setStorageSync: (key, value) => { storage[key] = plain(value); },
    navigateTo: options => calls.push(['navigateTo', options]), redirectTo: options => calls.push(['redirectTo', options]),
    switchTab: options => calls.push(['switchTab', options]), navigateBack: options => calls.push(['navigateBack', options]),
    showToast: options => calls.push(['toast', options]), showModal: options => calls.push(['modal', options]) };
  function load(relative) {
    const filename = path.resolve(root, relative);
    if (modules.has(filename)) return modules.get(filename).exports;
    const module = { exports: {} }; modules.set(filename, module);
    vm.runInNewContext(fs.readFileSync(filename, 'utf8'), { module, wx, console, getApp: () => ({ homeSeen: true, globalData: { budgetEstimate: { poisoned: true } } }), getCurrentPages: () => [],
      Page: value => { definition = value; }, require(specifier) {
        if (specifier.endsWith('/api')) return api;
        if (specifier.endsWith('/request')) return { getToken: () => token };
        return load(path.resolve(path.dirname(filename), specifier + '.js'));
      } }, { filename }); return module.exports;
  }
  return { calls, api, storage, view: load('utils/budget-view.js'), token(value) { token = value; },
    page(name = 'result', options = { budgetId }) {
      // Reopening a page creates a new instance, even when CommonJS dependencies are cached.
      modules.delete(path.resolve(root, 'pages/budget/' + name + '.js')); load('pages/budget/' + name + '.js');
      const page = { ...definition, route: 'pages/budget/' + name, options, data: plain(definition.data), setData(values) { Object.assign(this.data, values); } };
      page.onLoad(options); page.onShow(); return page;
    } };
}

test('金额按整数分展示：万元半入、精确两位、免费零及缺价不混淆', () => {
  const { view } = runtime();
  assert.equal(view.yuan(48202000), '482020.00'); assert.equal(view.wan(48202000), '48.20');
  assert.equal(view.wan(1004999), '1.00'); assert.equal(view.wan(1005000), '1.01');
  assert.equal(view.yuan(1), '0.01'); assert.equal(view.yuan(0), '0.00');
  for (const bad of [null, undefined, -1, 1.2, '100', Number.MAX_SAFE_INTEGER + 1]) assert.equal(view.yuan(bad), '—');
});
test('结果展示仅取公开字段：行级数量/单位随行公开（决策B），内部单价与工程量规则仍不进入页面', () => {
  const { view } = runtime(), raw = fixture();
  raw.unitPrice = 1; raw.inputSummary.quantities = { private: 1 }; raw.items[0].internalNote = 'secret';
  raw.items[0].lines[0].quantity = '120'; raw.items[0].lines[0].unit = 'SQM';
  const result = view.publicBudget(raw);
  assert.doesNotMatch(JSON.stringify(result), /unitPrice|quantities|internalNote|secret/);
  assert.equal(result.items[0].lines[0].quantity, '120');
  assert.equal(result.items[0].lines[0].unit, 'SQM');
  assert.equal(result.items[1].lines[1].amountCents, 0);
  assert.equal(view.viewModel(result, 'BODY', false).groups[0].lines[0].quantityText, '120m²');
  const bad = fixture(); bad.items[0].lines[0].quantity = 12;
  assert.throws(() => view.publicBudget(bad), /数量/);
});
test('转发卡片按万元命名并落首页；预算图仅完整预算可导（canvas 与按钮条件）', async () => {
  const r = runtime(), p = r.page('result'); await flush();
  const card = p.onShareAppMessage();
  assert.match(card.title, /参考预算约48\.20万/);
  assert.equal(card.path, '/pages/home/index');
  const wxml = fs.readFileSync(path.join(root, 'pages/budget/result.wxml'), 'utf8');
  assert.match(wxml, /bindtap="exportImage"/);
  assert.match(wxml, /wx:if="\{\{estimate\.completeness === 'COMPLETE'\}\}"/);
  assert.match(wxml, /<canvas type="2d" id="budgetCanvas"/);
  // 不完整预算：卡片退化为品牌名，页面给出补齐提示
  const incomplete = runtime({ getBudgetEstimate: async () => fixture({ completeness: 'INCOMPLETE', totalCents: null }) });
  const q = incomplete.page('result'); await flush();
  assert.doesNotMatch(q.onShareAppMessage().title, /48\.20/);
});
test('结果按URL预算和修订ID从后端读取，不依赖全局临时结果', async () => {
  const r = runtime(), p = r.page('result', { budgetId, revisionId }); await flush();
  assert.deepEqual(r.calls[0], ['get', budgetId, revisionId]); assert.equal(p.data.estimate.budgetId, budgetId);
  assert.equal(p.data.amountText, '48.20'); assert.equal(p.data.amountLabel, '参考预算总额');
  p.toggleExact(); assert.equal(p.data.amountText, '482020.00'); assert.equal(p.data.amountUnit, '元');
});
test('业主端只显示当前已发布的同预算报价，并沿用服务端公开预览字段', async () => {
  const r = runtime({ getBudgetQuotes: async id => { r.calls.push(['quotes', id]); return [quote({ internalReason: '内部让利审批' })]; } });
  const p = r.page(); await flush(); await flush();
  assert.deepEqual(r.calls.find(call => call[0] === 'quotes'), ['quotes', projectId]);
  assert.equal(p.data.quote.quoteId, '9007199254741011'); assert.equal(p.data.quoteAmountText, '48.38');
  assert.equal(p.data.quoteCalculatedText, '482020.00元'); assert.equal(p.data.quoteAdjustmentText, '+1800.00元');
  assert.doesNotMatch(JSON.stringify(p.data.quote), /internalReason|让利审批/);
  p.toggleExact(); assert.equal(p.data.quoteAmountText, '483820.00'); assert.equal(p.data.quoteAmountUnit, '元');
});
test('撤回的最新报价不回退显示旧报价，非法或跨项目报价不进入页面', async () => {
  const old = quote({ quoteId: '9007199254741010', quoteVersion: 1, current: false });
  const withdrawn = quote({ status: 'WITHDRAWN', current: true, withdrawnAt: '2026-09-08T13:00:00Z' });
  const r = runtime({ getBudgetQuotes: async () => [withdrawn, old] }), p = r.page(); await flush(); await flush();
  assert.equal(p.data.quote, null); assert.match(p.data.quoteStatusText, /已撤回/);
  const other = runtime({ getBudgetQuotes: async () => [quote({ projectId: '9007199254741999' })] }), page = other.page(); await flush(); await flush();
  assert.equal(page.data.quote, null); assert.match(page.data.quoteError, /不匹配/);
});
test('非法、大整数数值和不匹配预算修订不读取或展示他人结果', async () => {
  for (const options of [{}, { budgetId: '1/2' }, { budgetId: 9007199254740992 }, { budgetId, revisionId: 'x' }]) {
    const r = runtime(), p = r.page('result', options); await flush(); assert.equal(r.calls.length, 0); assert.equal(p.data.estimate, null); assert.ok(p.data.error);
  }
  for (const extra of [{ budgetId: '99' }, { revisionId: '99' }]) {
    const r = runtime({ getBudgetEstimate: async () => fixture(extra) }), p = r.page('result', { budgetId, revisionId }); await flush();
    assert.equal(p.data.estimate, null); assert.match(p.data.error, /不匹配/);
  }
});
test('待补预算只显示已计价小计和公开待补项，不伪装完整总额', async () => {
  const r = runtime({ getBudgetEstimate: async () => fixture({ completeness: 'INCOMPLETE', totalCents: null, missingItems: ['屋顶面积'] }) });
  const p = r.page(); await flush(); assert.match(p.data.amountLabel, /已计价小计/); assert.match(p.data.warningText, /尚不完整/); assert.equal(p.data.estimate.totalCents, null);
  assert.deepEqual(plain(p.data.estimate.missingItems), ['屋顶面积']);
});
test('结果数据非法或分类和总数不一致时明确报错而不显示假结果', async () => {
  for (const extra of [{ totalCents: null }, { categoryTotals: { BODY: 1, EXTERIOR: 2 } }, { completeness: 'INCOMPLETE', totalCents: 48202000 }]) {
    const r = runtime({ getBudgetEstimate: async () => fixture(extra) }), p = r.page(); await flush(); assert.equal(p.data.estimate, null); assert.ok(p.data.error);
  }
});
test('主体与外装明细保留预算修订，支持展开子项和精确零金额', async () => {
  const r = runtime(), p = r.page(); await flush(); p.detail(event({ category: 'EXTERIOR' }));
  assert.equal(r.calls.find(c => c[0] === 'navigateTo')[1].url, '/pages/budget/exterior-detail?budgetId=' + budgetId + '&revisionId=' + revisionId);
  const detail = r.page('exterior-detail', { budgetId, revisionId }); await flush();
  assert.equal(detail.data.groups.length, 1); assert.equal(detail.data.groups[0].lines[1].amountText, '0.00元');
  detail.expand(event({ index: 0 })); assert.equal(detail.data.expanded[0], true);
  const body = r.page('body-detail', { budgetId, revisionId }); await flush(); assert.equal(body.data.groups[0].itemCode, 'FOUNDATION');
});
test('后台公开自定义项按类别保留，缺量子项显示待补而不是零元', () => {
  const { view } = runtime(), raw = fixture({ completeness: 'INCOMPLETE', totalCents: null });
  raw.items[1].source = 'CUSTOM_TEMPLATE'; raw.items[1].completeness = 'INCOMPLETE'; raw.items[1].amountCents = null;
  raw.items[1].lines[1] = { lineId: '23', optionLabel: '补充门窗', status: 'MISSING_QUANTITY', amountCents: null };
  const detail = view.viewModel(view.publicBudget(raw), 'EXTERIOR', false);
  assert.equal(detail.groups[0].custom, true); assert.equal(detail.groups[0].amountText, '待补齐'); assert.equal(detail.groups[0].lines[1].amountText, '待补量');
});
test('保存阻止双击，失败后同键重试，只有服务端确认才显示已保存', async () => {
  const wait = deferred(), saves = [];
  const r = runtime({ saveBudgetEstimate: (id, key) => { saves.push([id, key]); return saves.length === 1 ? wait.promise : Promise.resolve({ budgetId: id, saved: true }); } });
  const p = r.page(); await flush(); p.save(); p.save(); assert.equal(saves.length, 1); assert.equal(p.data.estimate.saved, false);
  wait.reject(Error('网络断开')); await flush(); assert.equal(p.data.estimate.saved, false); assert.match(p.data.saveError, /网络断开/);
  p.save(); await flush(); assert.equal(saves[0][1], saves[1][1]); assert.equal(p.data.estimate.saved, true); p.save(); assert.equal(saves.length, 2);
});
test('重新进入读取持久化saved状态，错误保存回应不能触发成功提示', async () => {
  const r = runtime({ getBudgetEstimate: async () => fixture({ saved: true }) }), p = r.page(); await flush(); p.save(); assert.equal(r.calls.filter(c => c[0] === 'save').length, 0);
  const bad = runtime({ saveBudgetEstimate: async () => ({ budgetId: '99', saved: true }) }), page = bad.page(); await flush(); page.save(); await flush();
  assert.equal(page.data.estimate.saved, false); assert.ok(page.data.saveError); assert.equal(bad.calls.filter(c => c[0] === 'toast').length, 0);
});
test('账户切换清旧结果，过期读取与保存响应不能污染新会话', async () => {
  const wait = deferred(), r = runtime({ getBudgetEstimate: () => wait.promise }), p = r.page();
  r.token('user-B-session'); wait.resolve(fixture()); await flush(); assert.equal(p.data.estimate, null); assert.ok(p.data.error);
  r.api.getBudgetEstimate = async () => { throw Error('无权访问'); }; p.onShow(); await flush(); assert.equal(p.data.estimate, null); assert.match(p.data.error, /无权访问/);
  const save = deferred(), s = runtime({ saveBudgetEstimate: () => save.promise }), saved = s.page(); await flush(); saved.save(); s.token('user-C');
  save.resolve({ budgetId, saved: true }); await flush(); assert.equal(saved.data.estimate, null); assert.equal(s.calls.filter(c => c[0] === 'toast').length, 0);
});
test('真实激活守卫拒绝直达读取，卸载后异步响应不再更新页面', async () => {
  const r = runtime(); r.storage.v12Authorized = false; const p = r.page(); await flush();
  assert.equal(p.data.accessReady, false); assert.equal(r.calls.filter(c => c[0] === 'get').length, 0);
  const wait = deferred(), s = runtime({ getBudgetEstimate: () => wait.promise }), page = s.page(); page.onUnload(); wait.resolve(fixture()); await flush(); assert.equal(page.data.estimate, null);
});
test('旧区间按服务端原值展示，不伪造分项、中值或可保存状态', async () => {
  const r = runtime({ getBudgetEstimate: async () => legacy() }), p = r.page(); await flush();
  assert.equal(p.data.legacy, true); assert.equal(p.data.amountText, '30.00–50.00'); assert.equal(p.data.groups.length, 0); assert.doesNotMatch(JSON.stringify(p.data), /unitPrice|secret/);
  p.toggleExact(); assert.equal(p.data.amountText, '300000.00–500000.00'); assert.equal(p.data.amountUnit, '元');
  p.save(); p.detail(event({ category: 'BODY' })); assert.equal(r.calls.filter(c => ['save', 'navigateTo'].includes(c[0])).length, 0);
  const detail = r.page('body-detail', { budgetId }); await flush(); assert.equal(detail.data.estimate, null); assert.match(detail.data.error, /不匹配/);
});
test('旧区间金额不受新分项上限限制，但新分项仍拒绝超出边界', async () => {
  const r = runtime({ getBudgetEstimate: async () => ({ ...legacy(), totalMaxCents: 15000000000 }) }), p = r.page(); await flush();
  assert.equal(p.data.error, ''); assert.equal(p.data.amountText, '30.00–15000.00');
  assert.throws(() => r.view.publicBudget(fixture({ totalCents: 15000000000, pricedSubtotalCents: 15000000000 })));
});
test('项目历史默认已保存、支持新旧分页与按字符串ID去重', async () => {
  const calls = [], r = runtime({ getBudgetHistory: async (id, options) => {
    calls.push([id, plain(options)]); return options.savedOnly ? { list: [fixture({ saved: true })], nextCursor: null }
      : options.cursor ? { list: [legacy(), fixture({ budgetId: '9007199254740991' })], nextCursor: null } : { list: [legacy()], nextCursor: budgetId };
  } });
  const p = r.page('history', { projectId }); await flush(); assert.equal(calls[0][1].savedOnly, true); assert.equal(p.data.rows[0].saved, true);
  p.filter(event({ saved: 'false' })); await flush(); p.more(); await flush();
  assert.equal(calls[2][1].cursor, budgetId); assert.equal(p.data.rows.length, 2); assert.equal(p.data.rows[0].legacy, true); assert.equal(p.data.rows[0].amountText, '30.00–50.00万');
  p.open(event({ id: budgetId })); assert.match(r.calls.find(c => c[0] === 'navigateTo')[1].url, /result\?budgetId=9007199254740995$/);
});
test('历史筛选竞态丢弃旧响应，分页失败保留当前列表并可原游标重试', async () => {
  const wait = deferred(), r = runtime({ getBudgetHistory: async (id, options) => options.savedOnly ? wait.promise : { list: [fixture()], nextCursor: budgetId } });
  const p = r.page('history', { projectId }); p.filter(event({ saved: 'false' })); await flush(); wait.resolve({ list: [], nextCursor: null }); await flush(); assert.equal(p.data.rows.length, 1);
  r.api.getBudgetHistory = async () => { throw Error('网络断开'); }; p.more(); await flush(); assert.equal(p.data.rows.length, 1); assert.equal(p.data.nextCursor, budgetId); assert.match(p.data.error, /网络断开/);
  let cursor; r.api.getBudgetHistory = async (id, options) => { cursor = options.cursor; return { list: [], nextCursor: null }; }; p.retry(); await flush(); assert.equal(cursor, budgetId); assert.equal(p.data.error, '');
});
test('历史账户切换、无权项目和非法游标不得显示越权记录', async () => {
  const wait = deferred(), r = runtime({ getBudgetHistory: () => wait.promise }), p = r.page('history', { projectId });
  r.token('user-B'); wait.resolve({ list: [fixture({ saved: true })], nextCursor: null }); await flush(); assert.equal(p.data.rows.length, 0); assert.ok(p.data.error);
  for (const response of [{ list: [fixture({ saved: true, projectId: '2' })], nextCursor: null }, { list: [], nextCursor: 9007199254740992 }]) {
    const s = runtime({ getBudgetHistory: async () => response }), page = s.page('history', { projectId }); await flush(); assert.equal(page.data.rows.length, 0); assert.ok(page.data.error);
  }
});
test('预算页面动态图标均来自本地交付图标库', () => {
  const { view } = runtime(), raw = fixture();
  const codes = ['FOUNDATION', 'STRUCTURE', 'ROOF', 'DECORATION', 'DOORS_WINDOWS', 'WALL_PAINT', 'CULTURE_STONE', 'LIGHTING', 'WATERPROOF_LIGHTNING', 'INSURANCE', 'CUSTOM'];
  raw.items = codes.map(itemCode => ({ ...raw.items[0], itemCode }));
  const model = view.viewModel(view.publicBudget(raw), null, false), styles = fs.readFileSync(path.join(root, 'components/tdesign-miniprogram/icon/icon.wxss'), 'utf8');
  for (const row of model.groups.concat(model.categoryRows)) assert.ok(styles.includes('.t-icon-' + row.icon + ':before'), row.icon);
});
