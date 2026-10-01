const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function loadApi(http) {
  const filename = path.resolve(__dirname, '../miniprogram/utils/api.js');
  const module = { exports: {} };
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), {
    module,
    require(specifier) {
      assert.equal(specifier, './request');
      return http;
    },
  }, { filename });
  return module.exports;
}

test('预算参数导入保留大整数ID，并仅在指定方案时添加查询参数', async () => {
  const calls = [];
  const response = { importedValues: { buildingArea: '240' }, sources: { buildingArea: 'DERIVED' } };
  const api = loadApi({ get(url) { calls.push(url); return Promise.resolve(response); } });
  const projectId = '9007199254740993';
  const versionId = '9007199254740995';
  assert.equal(await api.getBudgetInputs(projectId, versionId), response);
  assert.equal(await api.getBudgetInputs(projectId), response);
  assert.equal(await api.getBudgetInputs(projectId, null), response);
  assert.deepEqual(calls, [
    `/app-api/design/v1/design-projects/${projectId}/budget-inputs?resultVersionId=${versionId}`,
    `/app-api/design/v1/design-projects/${projectId}/budget-inputs`,
    `/app-api/design/v1/design-projects/${projectId}/budget-inputs`,
  ]);
});

test('预算参数请求编码路径与查询值，失败向调用页面传播而不伪造默认数据', async () => {
  let url;
  const failure = new Error('network unavailable');
  const api = loadApi({ get(value) { url = value; return Promise.reject(failure); } });
  await assert.rejects(api.getBudgetInputs('1/2', '3&regionCode=demo'), error => error === failure);
  assert.equal(url, '/app-api/design/v1/design-projects/1%2F2/budget-inputs?resultVersionId=3%26regionCode%3Ddemo');
});

test('预算地区和选项读取复用请求层，地区编码不拼入其他查询条件', async () => {
  const calls = [];
  const api = loadApi({ get(url) { calls.push(url); return Promise.resolve([]); } });
  await api.getBudgetRegions();
  await api.getBudgetOptions('FJ_QZ&price=1');
  assert.deepEqual(calls, ['/app-api/design/v1/budget/regions', '/app-api/design/v1/budget/options?regionCode=FJ_QZ%26price%3D1']);
});

test('新预算创建与保存透传同一幂等键，不在客户端附加价格或重算', async () => {
  const calls = [], body = { resultVersionId: '9007199254740995', inputOverrides: { roofArea: null }, optionIds: ['9007199254740997'] };
  const api = loadApi({ post(url, data, headers) { calls.push({ url, data, headers: JSON.parse(JSON.stringify(headers)) }); return Promise.resolve({ budgetId: '9007199254740999' }); } });
  await api.createItemizedBudget('9007199254740993', body, 'create-key');
  await api.saveBudgetEstimate('9007199254740999', 'save-key');
  assert.equal(calls[0].data, body); assert.equal(calls[0].url, '/app-api/design/v1/design-projects/9007199254740993/budget-estimates/itemized');
  assert.deepEqual(calls[0].headers, { 'Idempotency-Key': 'create-key' });
  assert.equal(calls[1].url, '/app-api/design/v1/budget-estimates/9007199254740999/save');
  assert.equal(Object.keys(calls[1].data).length, 0); assert.deepEqual(calls[1].headers, { 'Idempotency-Key': 'save-key' });
});

test('新旧预算读取共用原路径，历史查询显式分页并编码全部ID', async () => {
  const calls = [], api = loadApi({ get(url) { calls.push(url); return Promise.resolve({}); } });
  await api.getBudgetEstimate('1/2'); await api.getBudgetEstimate('9007199254740995', '3&evil=true');
  await api.getBudgetHistory('9007199254740993'); await api.getBudgetHistory('1/2', { savedOnly: false, cursor: '3&evil=true', limit: 10 });
  assert.deepEqual(calls, ['/app-api/design/v1/budget-estimates/1%2F2', '/app-api/design/v1/budget-estimates/9007199254740995?revisionId=3%26evil%3Dtrue',
    '/app-api/design/v1/design-projects/9007199254740993/budget-estimates?savedOnly=true&limit=20', '/app-api/design/v1/design-projects/1%2F2/budget-estimates?savedOnly=false&limit=10&cursor=3%26evil%3Dtrue']);
});

test('正式报价列表按编码后的项目ID读取；单条详情无页面使用不封装', async () => {
  const calls = [], api = loadApi({ get(url) { calls.push(url); return Promise.resolve([]); } });
  await api.getBudgetQuotes('1/2');
  assert.deepEqual(calls, [
    '/app-api/design/v1/design-projects/1%2F2/budget-quotes'
  ]);
});

test('生成与保存失败原样传播，不返回假成功', async () => {
  const failure = Error('offline'), api = loadApi({ post() { return Promise.reject(failure); } });
  await assert.rejects(api.createItemizedBudget('1', {}, 'key'), error => error === failure);
  await assert.rejects(api.saveBudgetEstimate('1', 'key'), error => error === failure);
});

test('请求层和资产直传旁路统一携带租户标识', async () => {
  const calls = [], filename = path.resolve(__dirname, '../miniprogram/utils/request.js');
  const module = { exports: {} };
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), {
    module,
    getCurrentPages: () => [],
    wx: {
      getStorageSync: () => 'app-token',
      request(options) { calls.push(options); options.success({ statusCode: 200, data: { code: 0, data: { ready: true } } }); },
    },
    require(specifier) {
      assert.equal(specifier, './config');
      return { apiBase: 'http://localhost:48080', tenantId: 1 };
    },
  }, { filename });
  await module.exports.get('/app-api/design/v1/home');
  assert.equal(calls[0].header['tenant-id'], '1');
  assert.equal(calls[0].header.Authorization, 'Bearer app-token');
  assert.match(fs.readFileSync(path.resolve(__dirname, '../miniprogram/utils/assets.js'), 'utf8'), /'tenant-id': String\(config\.tenantId\)/);
  assert.match(fs.readFileSync(path.resolve(__dirname, '../miniprogram/pages/ai-design/index.js'), 'utf8'), /'tenant-id': String\(config\.tenantId\)/);
});
