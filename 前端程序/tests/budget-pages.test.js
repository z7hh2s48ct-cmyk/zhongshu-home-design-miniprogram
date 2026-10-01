const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../miniprogram');
const projectId = '9007199254740993';
const resultVersionId = '9007199254740995';
const regions = [{ code: 'FJ_QZ', name: '福建 · 泉州' }];
const fixture = () => ({ projectId, resultVersionId, requirementSnapshotIds: ['9007199254740997'],
  importedValues: { footprintArea: '120', floorCount: 2, buildingArea: '240', roofArea: '112', regionCode: 'FJ_QZ' },
  sources: { footprintArea: 'PROJECT', floorCount: 'PROJECT', buildingArea: 'DERIVED', roofArea: 'PROJECT', regionCode: 'PROJECT' } });
const plain = value => JSON.parse(JSON.stringify(value));
const flush = () => new Promise(resolve => setImmediate(resolve));

test('budget buttons override native default width without changing global buttons', () => {
  const styles = fs.readFileSync(path.join(root, 'pages/budget/shared.scss'), 'utf8');
  assert.match(styles, /\.budget-page \.budget-primary\s*\{[^}]*width:\s*100%[^}]*margin:\s*0/);
  assert.match(styles, /\.budget-page \.text-action\s*\{[^}]*width:\s*auto[^}]*margin:\s*0/);
  assert.doesNotMatch(styles, /^button\s*\{/m);
});

// Same isolated Page / wx / API pattern as activation-gate.test.js.
function runtime(apiChanges = {}, globalData = {}, actualGuard = false) {
  const storage = actualGuard ? { v12Authorized: true } : {};
  const calls = [];
  const modules = new Map();
  let definition;
  let failStorage = false;
  let token = 'session-owner-A';
  const api = Object.assign({
    getProject: async value => { calls.push(['project', value]); return { projectId: value, resultVersionId }; },
    getBudgetInputs: async (project, version) => { calls.push(['inputs', project, version]); return fixture(); },
    getBudgetRegions: async () => regions,
    getBudgetOptions: async () => ({ items: [] }),
  }, apiChanges);
  const wx = {
    getStorageSync: key => storage[key],
    setStorageSync(key, value) { if (failStorage) throw Error('full'); storage[key] = plain(value); },
    navigateTo: options => calls.push(['navigateTo', options]),
    redirectTo: options => calls.push(['redirectTo', options]),
    switchTab: options => calls.push(['switchTab', options]),
    navigateBack(options) { calls.push(['navigateBack', options]); options.complete?.(); },
    showToast: options => calls.push(['toast', options]),
    showModal: options => calls.push(['modal', options]),
  };
  function load(relative) {
    const filename = path.resolve(root, relative);
    if (modules.has(filename)) return modules.get(filename).exports;
    const module = { exports: {} };
    modules.set(filename, module);
    vm.runInNewContext(fs.readFileSync(filename, 'utf8'), {
      module, wx, console, getApp: () => ({ globalData, homeSeen: true }), getCurrentPages: () => [],
      Page(value) { definition = value; },
      require(specifier) {
        if (!actualGuard && specifier.endsWith('/access')) return { protectedPage(value) { definition = value; } };
        if (specifier.endsWith('/api')) return api;
        if (specifier.endsWith('/request')) return { getToken: () => token };
        return load(path.resolve(path.dirname(filename), specifier + '.js'));
      },
    }, { filename });
    return module.exports;
  }
  const draft = load('utils/budget-draft.js');
  return { calls, storage, draft, api, failStorage() { failStorage = true; }, setToken(value) { token = value; delete storage['zs_draft_scope']; },
    page(name, options = {}) {
      load('pages/budget/' + name + '.js');
      const page = Object.assign({}, definition, { data: plain(definition.data), setData(values) { Object.assign(this.data, values); } });
      page.onLoad(options);
      return page;
    } };
}

test('预算草稿只接纳公开字段及字符串ID，不带内部工程量、单价或备注', () => {
  const { draft } = runtime();
  const response = fixture();
  response.importedValues.quantities = { DOOR_HOUSEHOLDS: '1' };
  response.importedValues.unitPrice = '100';
  response.internalNote = 'private';
  const created = draft.create(response);
  assert.equal(created.projectId, projectId);
  assert.equal(created.resultVersionId, resultVersionId);
  assert.doesNotMatch(JSON.stringify(created), /quantities|unitPrice|internalNote|private/);
  assert.equal(draft.id(9007199254740992), null);
  assert.equal(draft.id('9223372036854775808'), null);
  assert.equal(draft.id('1/2'), null);
});

test('面积按四位定点数精确推导，独立建筑面积保留并提示复核，不猜屋顶', () => {
  const { draft } = runtime();
  let imported = fixture();
  imported.importedValues.footprintArea = '0.1001';
  imported.importedValues.floorCount = 3;
  imported.importedValues.roofArea = null;
  let created = draft.create(imported);
  assert.equal(draft.values(created).buildingArea, '0.3003');
  assert.equal(draft.values(created).roofArea, null);
  imported = fixture();
  imported.importedValues.buildingArea = '251.2';
  imported.sources.buildingArea = 'PROJECT';
  created = draft.edit(draft.create(imported), 'footprintArea', '130');
  assert.equal(draft.values(created).buildingArea, '251.2');
  assert.equal(draft.validate(created, regions).needsReview, true);
  assert.throws(() => draft.edit(created, 'buildingArea', '260'));
});

test('返回与重进保留修改，恢复的是原导入快照，并按项目和方案隔离', () => {
  const { draft } = runtime();
  const original = draft.create(fixture());
  const changed = draft.edit(original, 'footprintArea', '130');
  draft.write(changed);
  const restoredCache = draft.read(projectId, resultVersionId);
  assert.equal(draft.values(restoredCache).footprintArea, '130');
  assert.equal(draft.values(restoredCache).buildingArea, '260');
  assert.equal(draft.values(draft.restore(restoredCache)).footprintArea, '120');
  assert.equal(draft.values(original).footprintArea, '120');
  assert.equal(draft.read(projectId, '100'), null);
  assert.equal(draft.read('100', resultVersionId), null);
});

test('拒绝负数、科学计数、超精度、越界及失效地区，零不代替缺失面积', () => {
  const { draft } = runtime();
  for (const value of ['0', '-1', '1e2', '12.34567', '1000000.0001', '', ' 120', '01', 120]) assert.equal(draft.area(value), null);
  let created = draft.create(fixture());
  created = draft.edit(created, 'roofArea', '');
  created = draft.edit(created, 'floorCount', 21);
  assert.equal(draft.validate(created, []).valid, false);
  assert.deepEqual(Object.keys(draft.validate(created, []).errors).sort(), ['floorCount', 'regionCode', 'roofArea']);
  assert.equal(draft.values(created).roofArea, null);
});

test('无项目不请求参数、不伪造方案B、不自动发起AI任务', async () => {
  const env = runtime();
  const page = env.page('input');
  await flush();
  assert.equal(page.data.noProject, true);
  assert.equal(page.data.draft, null);
  assert.equal(env.calls.length, 0);
  page.selectProject();
  assert.deepEqual(env.calls.map(call => call[0]), ['switchTab']);
});

test('快速页按真实项目和方案请求参数；打开编辑页保持大整数上下文', async () => {
  const env = runtime();
  const page = env.page('input', { projectId, resultVersionId });
  await flush();
  assert.equal(page.data.loading, false);
  assert.equal(page.data.values.footprintArea, '120');
  assert.equal(page.data.regionName, '福建 · 泉州');
  assert.deepEqual(env.calls.slice(0, 2), [['project', projectId], ['inputs', projectId, resultVersionId]]);
  page.modify();
  assert.equal(env.calls.at(-1)[1].url, '/pages/budget/parameters?projectId=' + projectId + '&resultVersionId=' + resultVersionId);
  page.configuration({ currentTarget: { dataset: { category: 'BODY' } } });
  assert.equal(env.calls.at(-1)[0], 'navigateTo');
  assert.match(env.calls.at(-1)[1].url, /\/budget\/body\?projectId=/);
  env.calls.at(-1)[1].fail();
  assert.equal(env.calls.at(-1)[0], 'toast');
  page.configuration({ currentTarget: { dataset: { category: 'EXTERIOR' } } });
  assert.equal(env.calls.at(-1)[1].url, '/pages/budget/exterior?projectId=' + projectId + '&resultVersionId=' + resultVersionId);
});

test('参数页编辑、保存和恢复不改原方案；保存失败不返回或误报成功', async () => {
  const env = runtime();
  const page = env.page('parameters', { projectId, resultVersionId });
  await flush();
  page.onAreaInput({ currentTarget: { dataset: { field: 'footprintArea' } }, detail: { value: '130' } });
  assert.equal(page.data.values.buildingArea, '260');
  assert.equal(env.draft.values(env.draft.read(projectId, resultVersionId)).footprintArea, '130');
  page.restore();
  assert.equal(page.data.values.footprintArea, '120');
  page.save();
  assert.equal(env.calls.at(-1)[0], 'navigateBack');
  const count = env.calls.length;
  env.failStorage();
  page.save();
  assert.match(page.data.storageError, /未保存/);
  assert.equal(env.calls.length, count);
  assert.equal(page.data.saving, false);
});

test('断网、地区空目录与跨项目拒绝均显示真实状态，不以草稿绕过服务端', async () => {
  const env = runtime({ getBudgetInputs: async () => { throw { msg: '无权访问此项目' }; } });
  env.draft.write(env.draft.create(fixture()));
  const page = env.page('input', { projectId });
  await flush();
  assert.equal(page.data.draft, null);
  assert.equal(page.data.error, '无权访问此项目');
  const empty = runtime({ getBudgetRegions: async () => [] });
  const parameters = empty.page('parameters', { projectId, resultVersionId });
  await flush();
  parameters.save();
  assert.match(parameters.data.errors.regionCode, /已启用/);
  assert.equal(empty.calls.filter(call => call[0] === 'navigateBack').length, 0);
});

test('新参数响应不覆盖已导入草稿，恢复值不会漂移到后来项目数据', async () => {
  const env = runtime({ getBudgetInputs: async () => {
    const changed = fixture(); changed.importedValues.footprintArea = '999'; return changed;
  } });
  env.draft.write(env.draft.edit(env.draft.create(fixture()), 'footprintArea', '130'));
  const page = env.page('parameters', { projectId, resultVersionId });
  await flush();
  assert.equal(page.data.values.footprintArea, '130');
  page.restore();
  assert.equal(page.data.values.footprintArea, '120');
});

test('无效方案编号不能通过重试降级为项目级预算参数', async () => {
  for (const name of ['input', 'parameters']) {
    const env = runtime();
    const page = env.page(name, { projectId, resultVersionId: 'invalid-version' });
    const originalError = page.data.error;
    assert.ok(originalError);
    page.load();
    await flush();
    assert.equal(page.data.error, originalError);
    assert.equal(page.data.draft, null);
    assert.equal(env.calls.length, 0);
  }
});

test('重试固定首次方案上下文，包括项目级null，并保留用户编辑', async () => {
  for (const initialVersion of [resultVersionId, null]) {
    let latestVersion = initialVersion;
    const inputs = [];
    const env = runtime({
      getProject: async () => ({ projectId, resultVersionId: latestVersion }),
      getBudgetInputs: async (project, version) => {
        inputs.push(version);
        const response = fixture(); response.resultVersionId = version; return response;
      },
    });
    const page = env.page('input', { projectId });
    await flush();
    env.draft.write(env.draft.edit(page.data.draft, 'footprintArea', '130'));
    latestVersion = '100';
    page.load();
    await flush();
    assert.deepEqual(inputs, [initialVersion, initialVersion]);
    assert.equal(page.data.draft.resultVersionId, initialVersion);
    assert.equal(page.data.values.footprintArea, '130');
  }
});

test('预算草稿按凭证隔离且不复制原token，缺登录不读写、旧无归属草稿不迁移', () => {
  const env = runtime();
  const original = env.draft.edit(env.draft.create(fixture()), 'footprintArea', '130');
  env.draft.write(original);
  const oldScope = original.sessionScope;
  assert.doesNotMatch(JSON.stringify(env.storage), /session-owner-A/);
  env.setToken('session-owner-B');
  assert.notEqual(env.draft.sessionScope(), oldScope);
  assert.equal(env.draft.read(projectId, resultVersionId), null);
  assert.throws(() => env.draft.write(original), /登录身份/);
  env.storage['zs_budget_draft_v1:' + projectId + ':' + resultVersionId] = { ...original, schema: 1 };
  assert.equal(env.draft.read(projectId, resultVersionId), null);
  env.setToken('');
  assert.equal(env.draft.sessionScope(), null);
  assert.throws(() => env.draft.read(projectId, resultVersionId), /登录身份/);
});

test('两页返回时换账号先清除旧参数，项目归属拒绝后不恢复另一账号缓存', async () => {
  for (const name of ['input', 'parameters']) {
    const env = runtime({}, {}, true);
    const page = env.page(name, { projectId, resultVersionId });
    await flush();
    assert.equal(page.data.values.footprintArea, '120');
    env.setToken('session-owner-B');
    env.api.getBudgetInputs = async () => { throw { msg: '无权访问此项目' }; };
    page.onShow();
    assert.equal(page.data.draft, null);
    assert.deepEqual(plain(page.data.values), {});
    await flush();
    assert.equal(page.data.error, '无权访问此项目');
    assert.equal(page.data.draft, null);
  }
});

test('切换会话后的旧响应不写草稿、不覆盖新请求状态，旧页点击也不能保存', async () => {
  for (const name of ['input', 'parameters']) {
    let resolveOld;
    const env = runtime({ getBudgetInputs: () => new Promise(resolve => { resolveOld = resolve; }) });
    const page = env.page(name, { projectId, resultVersionId });
    await flush();
    env.setToken('session-owner-B');
    env.api.getBudgetInputs = async () => { throw { msg: '无权访问此项目' }; };
    page.onShow();
    await flush();
    resolveOld(fixture());
    await flush();
    assert.equal(page.data.error, '无权访问此项目');
    assert.equal(page.data.draft, null);
    // P2-C：换号后新 scope 的草稿键不得被旧响应写入；旧 scope 残留键不可见且无害
    const newScope = env.draft.sessionScope();
    assert.deepEqual(Object.keys(env.storage).filter(k => k.startsWith('zs_budget_draft') && k.includes(newScope)), []);
  }
  const env = runtime({}, {}, true);
  const page = env.page('parameters', { projectId, resultVersionId });
  await flush();
  const before = JSON.parse(JSON.stringify(env.storage));
  env.setToken('session-owner-B'); // 等效 clearTokens：清 zs_draft_scope
  page.save();
  assert.equal(page.data.draft, null);
  // 除会话 scope 标识本身外，存储不得有任何变化（脏写防护）
  const stripped = obj => JSON.stringify(Object.entries(obj).filter(([k]) => k !== 'zs_draft_scope'));
  assert.equal(stripped(env.storage), stripped(before));
  assert.equal(env.calls.filter(call => call[0] === 'navigateBack').length, 0);
});

test('两页不显示内部工程量或默认金额，生成绑定真实动作；界面不再出现旧版测算入口与字样', async () => {
  const input = fs.readFileSync(path.join(root, 'pages/budget/input.wxml'), 'utf8');
  const params = fs.readFileSync(path.join(root, 'pages/budget/parameters.wxml'), 'utf8');
  // T14 豁免：parameters 页的“我的当地单价”入口是用户自己的调价功能导航，不是内部计价数据；
  // 其余“单价”字样与内部工程量、默认金额仍一律禁止出现在这两页。
  const protectedText = (input + params).replaceAll('我的当地单价', '').replaceAll('按当地成本调价', '');
  assert.doesNotMatch(protectedText, /方案B|52\.86|单价|灯具米数|12\.6|13\.8/);
  assert.match(input, /bindtap="generate"[^>]*disabled="{{!canGenerate/);
  assert.match(input, /待补充预算，不视为完整总价/);
  assert.match(params, /本机草稿/);
  // 2026-09-29 决策 4：入口下线、页面删除，历史记录仅以“区间测算”中性称呼展示
  assert.doesNotMatch(input, /旧版/);
  assert.ok(!fs.existsSync(path.join(root, 'pages/budget/legacy.js')), 'legacy 页面应已删除');
  const history = fs.readFileSync(path.join(root, 'pages/budget/history.wxml'), 'utf8');
  assert.doesNotMatch(history, /旧版/);
});
