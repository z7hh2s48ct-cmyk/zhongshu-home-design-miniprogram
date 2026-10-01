const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../miniprogram');
const projectId = '9007199254740993';
const versionId = '9007199254740995';
const plain = value => JSON.parse(JSON.stringify(value));
const flush = () => new Promise(resolve => setImmediate(resolve));
const inputs = () => ({ projectId, resultVersionId: versionId, requirementSnapshotIds: ['9007199254740997'],
  importedValues: { regionCode: 'QZ', footprintArea: '120', floorCount: 2, buildingArea: '240', roofArea: '112' },
  sources: { buildingArea: 'DERIVED' } });
const option = (id, group, availability = 'AVAILABLE') => ({ optionId: id, code: 'OPTION_' + id, label: '选项' + id, selectionGroup: group, availability });
const item = (id, code, options, source = 'STANDARD', category = 'EXTERIOR') => ({ itemId: id, code, category, name: code, source, options });
const fixture = () => ({ items: [
  item('1', 'FOUNDATION', [option('9007199254741001', 'FOUNDATION'), option('9007199254741002', 'FOUNDATION')], 'STANDARD', 'BODY'),
  item('2', 'DOORS_WINDOWS', [option('21', 'DOOR'), option('22', 'WINDOW'), option('23', 'WINDOW', 'MISSING_PRICE')]),
  item('3', 'LIGHTING', [option('31', 'WASHER'), option('32', 'STRIP'), option('33', 'WALL_LAMP')]),
  item('4', 'WATERPROOF_LIGHTNING', [option('41', 'WATERPROOF'), option('42', 'LIGHTNING')]),
  item('5', 'CUSTOM_DRAIN', [option('51', 'DEFAULT')], 'CUSTOM_TEMPLATE'),
] });

function runtime(changes = {}) {
  const storage = {};
  const calls = [];
  const modules = new Map();
  let token = 'account-A-session';
  let definition;
  let storageUnavailable = false;
  const api = Object.assign({
    getBudgetPointQuote: async () => ({ product: 'BUDGET_ESTIMATE', ruleId: '9007199254741998', ruleVersion: 1, pointCost: 3 }),
    getProject: async () => ({ projectId, resultVersionId: versionId }),
    getBudgetInputs: async () => inputs(),
    getBudgetRegions: async () => [{ code: 'QZ', name: '泉州' }, { code: 'XM', name: '厦门' }],
    getBudgetOptions: async region => { calls.push(['catalog', region]); return fixture(); },
  }, changes);
  const wx = {
    getStorageSync: key => storage[key],
    setStorageSync(key, value) { if (storageUnavailable) throw Error('storage full'); storage[key] = plain(value); },
    showToast: value => calls.push(['toast', value]), showModal: value => { calls.push(['modal', value]); if (value.title === '确认预算测算') value.success({ confirm: true }); },
    navigateTo: value => calls.push(['navigateTo', value]), redirectTo: value => calls.push(['redirectTo', value]),
    navigateBack: value => calls.push(['navigateBack', value]),
  };
  function load(filename) {
    const full = path.resolve(root, filename);
    if (modules.has(full)) return modules.get(full).exports;
    const module = { exports: {} };
    modules.set(full, module);
    vm.runInNewContext(fs.readFileSync(full, 'utf8'), { module, wx, console, getApp: () => ({ globalData: {} }),
      require(specifier) {
        if (specifier.endsWith('/access')) return { protectedPage(page) { definition = page; } };
        if (specifier.endsWith('/api')) return api;
        if (specifier.endsWith('/request')) return { getToken: () => token };
        return load(path.resolve(path.dirname(full), specifier + '.js'));
      },
    }, { filename: full });
    return module.exports;
  }
  return { calls, storage, api, draft: load('utils/budget-draft.js'), selection: load('utils/budget-selection.js'),
    setToken(value) { token = value; delete storage['zs_draft_scope']; }, failStorage() { storageUnavailable = true; },
    page(name, query = { projectId, resultVersionId: versionId }) {
      load('pages/budget/' + name + '.js');
      const page = { ...definition, data: plain(definition.data), setData(values) { Object.assign(this.data, values); } };
      page.onLoad(query); return page;
    } };
}

test('空目录仍保留3+7标准行且没有假选项，公共自定义独立补充且无内部字段', () => {
  const env = runtime();
  const draft = env.draft.create(inputs());
  assert.equal(env.selection.summary([], draft, 'BODY').standardRows.length, 3);
  assert.equal(env.selection.summary([], draft, 'EXTERIOR').standardRows.length, 7);
  assert.equal(env.selection.summary([], draft, 'BODY').completeCount, 0);
  assert.equal(env.selection.summary([], draft, 'BODY').standardRows[0].options.length, 0);
  const response = fixture(); response.items[0].options[0].unitPriceCents = '52000';
  response.items[0].internalNote = 'private';
  const catalog = env.selection.catalog(response);
  assert.doesNotMatch(JSON.stringify(catalog), /unitPrice|internalNote|private/);
  const exterior = env.selection.summary(catalog, draft, 'EXTERIOR');
  assert.equal(exterior.standardRows.length, 7);
  assert.equal(exterior.customRows.length, 1);
});

test('同项同选择组互斥，门窗双组齐全才完成，缺价与选择完成分离', () => {
  const env = runtime(); const catalog = env.selection.catalog(fixture());
  let draft = env.draft.create(inputs());
  draft = env.selection.choose(draft, catalog, '2', 'DOOR', '21');
  assert.equal(env.selection.summary(catalog, draft, 'EXTERIOR').completeCount, 0);
  draft = env.selection.choose(draft, catalog, '2', 'WINDOW', '22');
  draft = env.selection.choose(draft, catalog, '2', 'WINDOW', '23');
  assert.deepEqual(plain(draft.selections), ['21', '23']);
  const summary = env.selection.summary(catalog, draft, 'EXTERIOR');
  assert.equal(summary.completeCount, 1);
  assert.equal(summary.pendingPriceCount, 1);
  assert.throws(() => env.selection.choose(draft, catalog, '2', 'WINDOW', '31'));
});

test('灯具可多组组合且任选一组完成；防水防雷必须两组，取消不会误删另一组', () => {
  const env = runtime(); const catalog = env.selection.catalog(fixture());
  let draft = env.draft.create(inputs());
  draft = env.selection.choose(draft, catalog, '3', 'WASHER', '31');
  draft = env.selection.choose(draft, catalog, '3', 'STRIP', '32');
  draft = env.selection.choose(draft, catalog, '4', 'WATERPROOF', '41');
  assert.equal(env.selection.summary(catalog, draft, 'EXTERIOR').completeCount, 1);
  draft = env.selection.choose(draft, catalog, '4', 'LIGHTNING', '42');
  assert.equal(env.selection.summary(catalog, draft, 'EXTERIOR').completeCount, 2);
  draft = env.selection.choose(draft, catalog, '3', 'WASHER', null);
  assert.deepEqual(plain(draft.selections), ['32', '41', '42']);
});

test('地区变更清空配置并提示；返回重载保留选择，失效选项明确移除', () => {
  const env = runtime(); const catalog = env.selection.catalog(fixture());
  let draft = env.selection.choose(env.draft.create(inputs()), catalog, '1', 'FOUNDATION', '9007199254741001');
  env.draft.write(draft);
  assert.deepEqual(plain(env.draft.read(projectId, versionId).selections), ['9007199254741001']);
  const editedArea = env.draft.edit(draft, 'footprintArea', '130');
  assert.deepEqual(plain(env.draft.restore(editedArea).selections), ['9007199254741001']);
  const changed = env.draft.edit(draft, 'regionCode', 'XM');
  assert.deepEqual(plain(changed.selections), []);
  assert.match(env.selection.selectionNotice(changed), /地区已修改/);
  draft = env.selection.reconcile(draft, []);
  assert.deepEqual(plain(draft.selections), []);
  assert.match(env.selection.selectionNotice(draft), /失效选择/);
});

test('生成请求白名单、字符串ID及持久幂等键：同体复用，参数或选择改变换键', () => {
  const env = runtime();
  let draft = env.draft.create(inputs());
  draft.selections = ['9007199254741001'];
  draft = env.draft.edit(draft, 'footprintArea', '130');
  draft.overrides.unitPriceCents = '1';
  const first = env.draft.prepareGeneration(draft);
  env.draft.write(first.draft);
  const restored = env.draft.read(projectId, versionId);
  assert.equal(env.draft.prepareGeneration(restored).key, first.key);
  assert.deepEqual(plain(first.body), { resultVersionId: versionId, inputOverrides: { footprintArea: '130' },
    optionIds: ['9007199254741001'], requirementSnapshotIds: ['9007199254740997'] });
  assert.notEqual(env.draft.prepareGeneration(env.draft.edit(restored, 'footprintArea', '140')).key, first.key);
  assert.notEqual(env.draft.prepareGeneration({ ...restored, selections: ['21'] }).key, first.key);
  env.draft.write({ ...restored, generationAttempt: { ...restored.generationAttempt, key: 'k'.repeat(64) } });
  assert.equal(env.draft.read(projectId, versionId).generationAttempt.key.length, 64);
  env.draft.write({ ...restored, generationAttempt: { ...restored.generationAttempt, key: 'k'.repeat(65) } });
  assert.equal(env.draft.read(projectId, versionId).generationAttempt, null);
});

test('主体/外装页动态选项可编辑保存返回，缺价可选，禁用标准项不伪造', async () => {
  const env = runtime();
  const page = env.page('exterior'); await flush();
  assert.equal(page.data.standardRows.length, 7);
  assert.equal(page.data.customRows.length, 1);
  page.openItem({ currentTarget: { dataset: { id: '2' } } });
  page.chooseOption({ currentTarget: { dataset: { group: 'DOOR', optionId: '21' } } });
  page.chooseOption({ currentTarget: { dataset: { group: 'WINDOW', optionId: '23' } } });
  assert.equal(page.data.completeCount, 1);
  assert.equal(page.data.pendingPriceCount, 1);
  assert.equal(page.data.activeItem.itemId, '2');
  assert.equal(page.data.activeGroups[1].options[1].selected, true);
  page.save();
  assert.equal(env.calls.at(-1)[0], 'navigateBack');
  assert.deepEqual(plain(env.draft.read(projectId, versionId).selections), ['21', '23']);
  const body = env.page('body'); await flush();
  assert.equal(body.data.standardRows.length, 3);
  assert.equal(body.data.standardRows.find(row => row.code === 'ROOF').selectedLabel, '待配置');
});

test('配置页无效路由重试仍拒绝，断网可重试，换账号旧回包不显示或写入', async () => {
  for (const name of ['body', 'exterior']) {
    const bad = runtime(); const badPage = bad.page(name, { projectId, resultVersionId: 'bad' });
    badPage.load(); await flush(); assert.equal(bad.calls.length, 0);
    let resolveCatalog;
    const env = runtime({ getBudgetOptions: () => new Promise(resolve => { resolveCatalog = resolve; }) });
    const page = env.page(name); await flush();
    env.setToken('account-B-session');
    resolveCatalog(fixture()); await flush();
    assert.equal(page.data.draft, null);
    assert.deepEqual(Object.keys(env.storage).filter(k => k !== 'zs_draft_scope'), []);
  }
  const offline = runtime({ getBudgetOptions: async () => { throw { msg: 'network timeout' }; } });
  const failed = offline.page('body'); await flush();
  assert.equal(failed.data.error, 'network timeout');
  offline.api.getBudgetOptions = async () => fixture();
  failed.load(); await flush();
  assert.equal(failed.data.error, '');
  assert.equal(failed.data.standardRows.length, 3);
});

test('配置直接点选无空选项，单组保存即关闭，重选保持互斥及缺价提示', async () => {
  const env = runtime(); const page = env.page('body'); await flush();
  page.openItem({ currentTarget: { dataset: { id: '1' } } });
  assert.equal(page.data.activeGroups[0].options.length, 2);
  assert.equal(page.data.activeGroups[0].options.some(option => option.selected), false);
  assert.doesNotMatch(JSON.stringify(page.data.activeGroups), /暂不选择|selectedIndex|labels/);
  const click = id => page.chooseOption({ currentTarget: { dataset: { group: 'FOUNDATION', optionId: id } } });
  click('9007199254741001');
  assert.equal(page.data.activeItem, null);
  assert.deepEqual(plain(env.draft.read(projectId, versionId).selections), ['9007199254741001']);
  page.openItem({ currentTarget: { dataset: { id: '1' } } });
  assert.equal(page.data.activeGroups[0].options[0].selected, true);
  click('9007199254741002');
  assert.deepEqual(plain(page.data.draft.selections), ['9007199254741002']);
  const groups = env.selection.groupChoices(env.selection.catalog(fixture())[1], page.data.draft);
  assert.match(groups[1].options[1].displayLabel, /待补价/);
  for (const name of ['body', 'exterior']) {
    const template = fs.readFileSync(path.join(root, 'pages/budget/' + name + '.wxml'), 'utf8');
    assert.doesNotMatch(template, /<picker\s|暂不选择/);
    assert.match(template, /data-option-id="\{\{option.optionId\}\}" bindtap="chooseOption"/);
  }
});

test('直接点选拒绝空ID及错组，存储失败不关闭，换会话不写旧选择', async () => {
  const env = runtime(); const page = env.page('body'); await flush();
  const open = () => page.openItem({ currentTarget: { dataset: { id: '1' } } });
  const click = (group, optionId) => page.chooseOption({ currentTarget: { dataset: { group, optionId } } });
  open();
  click('FOUNDATION', null); click('FOUNDATION', '21'); click('DOOR', '9007199254741001');
  assert.deepEqual(plain(page.data.draft.selections), []);
  const before = plain(env.storage);
  env.failStorage(); click('FOUNDATION', '9007199254741001');
  assert.ok(page.data.activeItem);
  assert.match(page.data.storageError, /未保存/);
  assert.deepEqual(plain(env.storage), before);
  env.setToken('account-B'); // 等效 clearTokens：清 zs_draft_scope
  click('FOUNDATION', '9007199254741002');
  assert.equal(page.data.activeItem, null);
  assert.equal(page.data.draft, null);
  const draftKeysBefore = Object.keys(before).filter(k => k.startsWith('zs_budget_draft')).sort();
  assert.deepEqual(Object.keys(env.storage).filter(k => k.startsWith('zs_budget_draft')).sort(), draftKeysBefore);
});

test('真实生成动作同体失败重试复用键、防双击，成功只凭budgetId跳结果', async () => {
  const requests = []; let rejectFirst;
  const env = runtime({ createItemizedBudget(project, body, key) {
    requests.push({ project, body: plain(body), key });
    if (requests.length === 1) return new Promise((resolve, reject) => { rejectFirst = reject; });
    return Promise.resolve({ budgetId: '9007199254742001', completeness: 'INCOMPLETE' });
  } });
  const page = env.page('input'); await flush();
  assert.equal(page.data.canGenerate, true);
  assert.equal(page.data.bodyCount, 0);
  page.generate(); page.generate(); await flush();
  assert.equal(requests.length, 1);
  rejectFirst({ msg: 'network timeout' }); await flush();
  assert.match(page.data.generateError, /timeout/);
  const retryDraft = env.draft.read(projectId, versionId);
  assert.ok(retryDraft.generationAttempt, JSON.stringify(env.storage));
  assert.equal(JSON.stringify(env.draft.requestBody(retryDraft)), retryDraft.generationAttempt.signature);
  page.generate(); await flush();
  assert.equal(requests[0].key, requests[1].key);
  assert.equal(requests[0].project, projectId);
  assert.deepEqual(requests[0].body.optionIds, []);
  assert.equal(requests[0].body.resultVersionId, versionId);
  assert.deepEqual(requests[0].body.usageConfirmation, { product: 'BUDGET_ESTIMATE', ruleId: '9007199254741998', ruleVersion: 1 });
  assert.equal(env.calls.at(-1)[1].url, '/pages/budget/result?budgetId=9007199254742001');
  env.calls.at(-1)[1].success();
  assert.equal(env.draft.read(projectId, versionId).generationAttempt, null);
  page.generate(); await flush();
  assert.notEqual(requests[2].key, requests[1].key);
});

test('生成失败后改参数换键，换会话丢弃迟到生成响应；无地区禁止生成', async () => {
  const requests = []; let resolveResult;
  const env = runtime({ createItemizedBudget(project, body, key) {
    requests.push(key);
    if (requests.length === 1) return Promise.reject({ msg: 'timeout' });
    return new Promise(resolve => { resolveResult = resolve; });
  } });
  const page = env.page('input'); await flush(); page.generate(); await flush();
  env.draft.write(env.draft.edit(page.data.draft, 'footprintArea', '130'));
  page.generate(); assert.notEqual(requests[0], requests[1]);
  await flush(); env.setToken('account-B-session'); resolveResult({ budgetId: '999' }); await flush();
  assert.equal(page.data.draft, null);
  assert.equal(env.calls.filter(call => call[0] === 'navigateTo').length, 0);
  const missing = runtime({ getBudgetRegions: async () => [] });
  const emptyPage = missing.page('input'); await flush(); emptyPage.generate();
  assert.equal(emptyPage.data.canGenerate, false);
});

test('目录停用移除选择后不重复计费，私有或错误目录不能产生公开选项', () => {
  const env = runtime();
  let response = fixture(); response.items.push(item('6', 'PRIVATE', [option('61', 'DEFAULT')], 'PROJECT_CUSTOM'));
  assert.equal(env.selection.catalog(response).some(item => item.code === 'PRIVATE'), false);
  response = fixture(); response.items[0].options[0].optionId = 9007199254741000;
  assert.throws(() => env.selection.catalog(response), /选项无效/);
  const draft = env.draft.create(inputs()); draft.selections = ['21', '22', '23', 'removed'];
  const valid = env.selection.reconcile(draft, env.selection.catalog(fixture()));
  assert.deepEqual(plain(valid.selections), ['21', '22']);
  assert.equal(valid.selectionResetReason, 'OPTION_UNAVAILABLE');
});

test('参数版本冲突后确认重导入，仅重建当前草稿并使用新快照成功生成，方案版本不漂移', async () => {
  const requests = []; const reads = [];
  let serverInputs = inputs(); let latestVersion = versionId;
  const env = runtime({
    getProject: async () => ({ projectId, resultVersionId: latestVersion }),
    getBudgetInputs: async (project, version) => { reads.push({ project, version }); return plain(serverInputs); },
    createItemizedBudget: async (project, body, key) => {
      requests.push({ project, body: plain(body), key });
      if (body.requirementSnapshotIds[0] !== '9007199254740999') throw { code: 1099000001, msg: '参数版本已变化' };
      return { budgetId: '9007199254742001' };
    },
  });
  const page = env.page('input', { projectId }); await flush();
  let edited = env.draft.edit(page.data.draft, 'footprintArea', '130');
  edited.selections = ['9007199254741001']; env.draft.write(edited); page.onShow(); await flush();
  env.draft.write(env.draft.create({ ...inputs(), projectId: '100' }));
  const otherKey = Object.keys(env.storage).find(key => key.includes(':100:'));
  const otherDraft = plain(env.storage[otherKey]);
  latestVersion = '200'; serverInputs = { ...inputs(), requirementSnapshotIds: ['9007199254740999'],
    importedValues: { ...inputs().importedValues, footprintArea: '140', buildingArea: '280' } };
  page.generate(); await flush(); assert.equal(page.data.importConflict, true);
  page.load(); await flush();
  assert.equal(page.data.values.footprintArea, '130');
  assert.deepEqual(plain(page.data.draft.requirementSnapshotIds), ['9007199254740997']);
  page.confirmReimport(); page.confirmReimport();
  const importModals = env.calls.filter(call => call[0] === 'modal' && call[1].title === '重新导入当前参数？');
  assert.equal(importModals.length, 1);
  const modal = importModals.at(-1)[1];
  assert.match(modal.content, /清空参数修改和预算配置/);
  assert.match(modal.content, /不会修改设计项目、已存预算或其他草稿/);
  modal.success({ confirm: true }); await flush();
  assert.equal(page.data.importConflict, false);
  assert.equal(page.data.values.footprintArea, '140');
  assert.equal(page.data.draft.resultVersionId, versionId);
  assert.deepEqual(plain(page.data.draft.overrides), {});
  assert.deepEqual(plain(page.data.draft.selections), []);
  assert.equal(page.data.draft.generationAttempt, null);
  assert.deepEqual(plain(env.storage[otherKey]), otherDraft);
  assert.equal(reads.every(read => read.project === projectId && read.version === versionId), true);
  page.generate(); await flush();
  assert.deepEqual(requests[1].body, { resultVersionId: versionId, inputOverrides: {}, optionIds: [], requirementSnapshotIds: ['9007199254740999'],
    usageConfirmation: { product: 'BUDGET_ESTIMATE', ruleId: '9007199254741998', ruleVersion: 1 } });
  assert.notEqual(requests[0].key, requests[1].key);
  assert.equal(env.calls.at(-1)[1].url, '/pages/budget/result?budgetId=9007199254742001');
});

test('主动重导入取消不请求；断网、无权限、错误上下文或存储失败均保留原草稿及修改', async () => {
  for (const failure of ['cancel', 'network', 'permission', 'context', 'storage']) {
    const env = runtime(); const page = env.page('input'); await flush();
    const edited = env.draft.edit(page.data.draft, 'footprintArea', '130');
    edited.selections = ['9007199254741001']; env.draft.write(edited); page.onShow(); await flush();
    const before = plain(env.storage); const beforeDraft = plain(page.data.draft);
    let reads = 0;
    env.api.getBudgetInputs = async () => {
      reads++;
      if (failure === 'network') throw { msg: 'network timeout' };
      if (failure === 'permission') throw { code: 403, msg: '无权限访问项目' };
      return { ...inputs(), resultVersionId: failure === 'context' ? '200' : versionId, requirementSnapshotIds: ['101'] };
    };
    if (failure === 'storage') env.failStorage();
    page.confirmReimport(); env.calls.at(-1)[1].success({ confirm: failure !== 'cancel' }); await flush();
    assert.equal(reads, failure === 'cancel' ? 0 : 1, failure);
    assert.deepEqual(plain(env.storage), before, failure);
    assert.deepEqual(plain(page.data.draft), beforeDraft, failure);
    assert.equal(page.data.reimporting, false, failure);
    if (failure !== 'cancel') assert.match(page.data.importError, /原草稿未替换/, failure);
    assert.equal(env.calls.filter(call => call[0] === 'toast').length, 0, failure);
  }
});

test('重导入确认和异步回包均校验会话及页面生命周期，处理中阻止并发生成与草稿改写', async () => {
  for (const boundary of ['modal-session', 'response-session', 'unload']) {
    const env = runtime(); const page = env.page('input'); await flush();
    const before = plain(env.storage);
    let reads = 0; let resolveInputs;
    env.api.getBudgetInputs = () => { reads++; return new Promise(resolve => { resolveInputs = resolve; }); };
    page.confirmReimport(); const modal = env.calls.at(-1)[1];
    if (boundary === 'modal-session') env.setToken('account-B-session');
    modal.success({ confirm: true });
    if (boundary === 'modal-session') {
      assert.equal(reads, 0); assert.equal(page.data.draft, null);
    } else {
      assert.equal(page.data.reimporting, true);
      page.generate(); page.modify(); page.load(); page.loadCatalog(); page.configuration({ currentTarget: { dataset: { category: 'BODY' } } });
      assert.equal(reads, 1);
      assert.equal(env.calls.filter(call => call[0] === 'navigateTo').length, 0);
      if (boundary === 'response-session') env.setToken('account-B-session');
      else page.onUnload();
      resolveInputs({ ...inputs(), requirementSnapshotIds: ['101'] }); await flush();
      if (boundary === 'response-session') assert.equal(page.data.draft, null);
    }
    const stripped = obj => JSON.stringify(Object.entries(obj).filter(([k]) => k !== 'zs_draft_scope'));
    assert.equal(stripped(env.storage), stripped(before), boundary);
    assert.equal(env.calls.filter(call => call[0] === 'toast').length, 0, boundary);
  }
});
