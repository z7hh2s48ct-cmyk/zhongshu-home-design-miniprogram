const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../miniprogram');
const flush = () => new Promise(resolve => setImmediate(resolve));

test('我的当地单价页属于激活门禁业务页并已登记页面清单', () => {
  const appConfig = JSON.parse(fs.readFileSync(path.resolve(root, 'app.json'), 'utf8'));
  assert.ok(appConfig.pages.includes('pages/budget/prices'), 'app.json 应登记 prices 页');
  const source = fs.readFileSync(path.resolve(root, 'utils/access.js'), 'utf8');
  assert.ok(source.includes("'/pages/budget/prices'"), 'access.js 业务页清单应包含 prices 页');
  for (const extension of ['js', 'json', 'wxml', 'scss']) {
    assert.ok(fs.existsSync(path.resolve(root, 'pages/budget/prices.' + extension)), 'prices.' + extension + ' 应存在');
  }
});

test('我的当地单价 API 拼接路径与查询参数，金额以分提交', async () => {
  const calls = [];
  const http = {
    get(url) { calls.push(['GET', url]); return Promise.resolve({}); },
    put(url, body) { calls.push(['PUT', url, { ...body }]); return Promise.resolve({}); },
    del(url) { calls.push(['DELETE', url]); return Promise.resolve({}); },
  };
  const filename = path.resolve(root, 'utils/api.js');
  const module = { exports: {} };
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), { module, require: () => http }, { filename });
  const api = module.exports;
  await api.getMyPrices('HUB_WH');
  await api.setMyPrice('208001', 'HUB_WH', 60000);
  await api.setMyPrice('208001', 'HUB_WH', 60000, '当地人工更贵');
  await api.resetMyPrice('208/1', 'HUB&WH');
  assert.deepEqual(calls, [
    ['GET', '/app-api/design/v1/budget/my-prices?regionCode=HUB_WH'],
    ['PUT', '/app-api/design/v1/budget/my-prices/208001?regionCode=HUB_WH', { unitPriceCents: 60000 }],
    ['PUT', '/app-api/design/v1/budget/my-prices/208001?regionCode=HUB_WH', { unitPriceCents: 60000, reason: '当地人工更贵' }],
    ['DELETE', '/app-api/design/v1/budget/my-prices/208%2F1?regionCode=HUB%26WH'],
  ]);
});

// Same isolated Page / wx / API pattern as budget-pages.test.js.
function pricesRuntime(apiChanges = {}) {
  const storage = { v12Authorized: true };
  const calls = [];
  const regions = [{ code: 'HUB_WH', name: '湖北 · 武汉' }];
  const catalog = { regionCode: 'HUB_WH', regionName: '湖北 · 武汉', groups: [
    { itemId: '1', itemCode: 'FOUNDATION', itemName: '地基基础', category: 'BODY', options: [
      { optionId: '208001', code: 'STRIP', label: '条形基础', selectionGroup: 'FOUNDATION', unit: 'SQM',
        baselinePriceCents: 52000, myPriceCents: null, priceSource: 'DEFAULT', reason: null, updatedAt: null },
      { optionId: '208002', code: 'PILE', label: '桩基础', selectionGroup: 'FOUNDATION', unit: 'SQM',
        baselinePriceCents: null, myPriceCents: null, priceSource: 'MISSING', reason: null, updatedAt: null },
    ] },
  ] };
  const api = Object.assign({
    getBudgetRegions: async () => { calls.push(['regions']); return regions; },
    getMyPrices: async () => { calls.push(['myPrices']); return catalog; },
    setMyPrice: async (optionId, regionCode, cents) => { calls.push(['set', optionId, regionCode, cents]); return {}; },
    resetMyPrice: async optionId => { calls.push(['reset', optionId]); return {}; },
  }, apiChanges);
  const wx = {
    getStorageSync: key => storage[key],
    setStorageSync(key, value) { storage[key] = value; },
    navigateTo: options => calls.push(['navigateTo', options.url]),
    showToast: options => calls.push(['toast', options.title]),
    showModal: options => { calls.push(['modal', options.title]); options.success({ confirm: true }); },
  };
  let definition;
  const filename = path.resolve(root, 'pages/budget/prices.js');
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), {
    module: { exports: {} },
    wx, console,
    Page(value) { definition = value; },
    require(specifier) {
      if (specifier.endsWith('/access')) return { protectedPage(value) { definition = value; } };
      if (specifier.endsWith('/api')) return api;
      throw new Error('unexpected require ' + specifier);
    },
  }, { filename });
  const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)),
    setData(values) { Object.assign(this.data, values); } });
  page.onLoad({});
  return { page, calls, catalog };
}

test('prices 页加载地区与单价并渲染基准价、覆盖价与缺价三种状态', async () => {
  const { page, calls } = pricesRuntime();
  await flush(); await flush();
  assert.equal(page.data.regionCode, 'HUB_WH');
  assert.equal(page.data.regionNames[0], '湖北 · 武汉');
  const options = page.data.groups[0].options;
  assert.equal(options[0].baselineText, '¥520.00');
  assert.equal(options[0].overridden, false);
  assert.equal(options[1].baselineText, '待补价');
  assert.equal(options[1].missing, true);
  assert.deepEqual(calls[0], ['regions']);
  assert.deepEqual(calls[1], ['myPrices']);
});

test('prices 页保存输入校验两位小数并按分提交，成功后重载', async () => {
  const { page, calls } = pricesRuntime();
  await flush(); await flush();
  calls.length = 0;
  page.startEdit({ currentTarget: { dataset: { optionId: '208001' } } });
  assert.equal(page.data.editOptionId, '208001');
  assert.equal(page.data.inputValue, '520.00');
  for (const bad of ['0', '-5', '12.345', 'abc', '', '1000001']) {
    page.setData({ inputValue: bad });
    await page.confirmEdit({ currentTarget: { dataset: { optionId: '208001' } } });
    assert.equal(calls.filter(call => call[0] === 'set').length, 0, bad + ' 不应提交');
  }
  page.setData({ inputValue: '600' });
  await page.confirmEdit({ currentTarget: { dataset: { optionId: '208001' } } });
  await flush();
  assert.deepEqual(calls.find(call => call[0] === 'set'), ['set', '208001', 'HUB_WH', 60000]);
  assert.equal(page.data.editOptionId, null);
});

test('prices 页恢复默认需确认，按当前地区提交', async () => {
  const { page, calls, catalog } = pricesRuntime();
  await flush(); await flush();
  catalog.groups[0].options[0].myPriceCents = 60000;
  catalog.groups[0].options[0].priceSource = 'ACCOUNT_OVERRIDE';
  page.setData({ regionCode: 'HUB_WH', groups: page.data.groups });
  calls.length = 0;
  await page.reset({ currentTarget: { dataset: { optionId: '208001' } } });
  await flush(); await flush();
  assert.deepEqual(calls.find(call => call[0] === 'modal'), ['modal', '恢复默认价']);
  assert.deepEqual(calls.find(call => call[0] === 'reset'), ['reset', '208001']);
});

test('基础参数页登记我的当地单价入口并携带当前地区跳转', () => {
  const wxml = fs.readFileSync(path.resolve(root, 'pages/budget/parameters.wxml'), 'utf8');
  assert.match(wxml, /bindtap="openMyPrices"/, '参数页应提供我的当地单价入口');
  const js = fs.readFileSync(path.resolve(root, 'pages/budget/parameters.js'), 'utf8');
  assert.match(js, /openMyPrices\(\)\s*\{[\s\S]*?regionCode[\s\S]*?pages\/budget\/prices/, '入口应携带当前地区跳转 prices 页');
});
