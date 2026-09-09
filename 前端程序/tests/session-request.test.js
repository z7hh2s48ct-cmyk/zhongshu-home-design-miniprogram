const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function harness() {
  const storage = new Map([['zs_access_token', 'old-access'], ['zs_refresh_token', 'old-refresh'], ['v12Authorized', true]]);
  const calls = [], redirects = [], app = { globalData: { projectId: '123' } };
  const context = { module: { exports: {} }, require: () => ({ apiBase: '', tenantId: 1 }),
    getApp: () => app, getCurrentPages: () => [{ route: 'pages/library/index' }],
    wx: { getStorageSync: key => storage.get(key), setStorageSync: (key, value) => storage.set(key, value),
      removeStorageSync: key => storage.delete(key), redirectTo: options => redirects.push(options.url),
      request: options => calls.push(options) } };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../miniprogram/utils/request.js'), 'utf8'), context);
  const respond = (index, code, data, statusCode = 200) => calls[index].success({ statusCode, data: { code, data, msg: 'test response' } });
  return { http: context.module.exports, calls, storage, redirects, app, respond };
}
const tick = () => new Promise(resolve => setImmediate(resolve));
const tokens = restricted => ({ accessToken: 'new-access', refreshToken: 'new-refresh', restricted });

test('退出后到达的成功响应不会回填旧用户数据', async () => {
  const h = harness(); const request = h.http.get('/profile');
  h.http.clearTokens(); h.respond(0, 0, { nickname: 'old user' });
  await assert.rejects(request, e => e.code === 'SESSION_CHANGED');
});

test('并发 401 共用一次刷新，原写请求只重放一次并保留幂等键', async () => {
  const h = harness();
  const a = h.http.post('/first', { count: 2 }, { 'Idempotency-Key': 'same-command' });
  const b = h.http.get('/second');
  h.respond(0, 401); h.respond(1, 401, null, 401); await tick();
  assert.equal(h.calls.length, 3);
  assert.match(h.calls[2].url, /token-refresh$/);
  h.respond(2, 0, tokens(false)); await tick();
  assert.equal(h.calls.length, 5);
  assert.equal(h.calls[3].header['Idempotency-Key'], 'same-command');
  assert.equal(h.calls[3].header.Authorization, 'Bearer new-access');
  h.respond(3, 0, 'a'); h.respond(4, 0, 'b');
  assert.deepEqual(await Promise.all([a, b]), ['a', 'b']);
  assert.equal(h.storage.get('zs_refresh_token'), 'new-refresh');
  assert.equal(h.http.isSameSession('old-access'), true);
  h.http.clearTokens();
  assert.equal(h.http.isSameSession('old-access'), false);
});

test('刷新完成后才到达的旧请求 401 不启动第二次刷新', async () => {
  const h = harness(); const a = h.http.get('/a'), b = h.http.get('/b');
  h.respond(0, 401); await tick(); h.respond(2, 0, tokens(false)); await tick();
  h.respond(3, 0, 'a'); await a;
  h.respond(1, 401); await tick();
  assert.equal(h.calls.filter(c => c.url.endsWith('/token-refresh')).length, 1);
  h.respond(4, 0, 'b'); assert.equal(await b, 'b');
});

for (const statusCode of [200, 403]) test(`资源 403 保留令牌和授权状态，HTTP ${statusCode}`, async () => {
  const h = harness(); const request = h.http.get('/other-users-project');
  h.respond(0, 403, null, statusCode);
  await assert.rejects(request, e => e.code === 403);
  assert.equal(h.storage.get('zs_access_token'), 'old-access');
  assert.equal(h.storage.get('v12Authorized'), true);
  assert.equal(h.redirects.length, 0);
});

test('未激活业务码降级授权并保留受限会话用于兑换', async () => {
  const h = harness(); const request = h.http.get('/project'); h.respond(0, 1070001001);
  await assert.rejects(request, e => e.code === 1070001001);
  assert.equal(h.storage.get('zs_access_token'), 'old-access');
  assert.equal(h.storage.get('v12Authorized'), false);
  assert.equal(h.redirects.length, 1);
});

test('刷新仍为受限账号时不会写成已激活', async () => {
  const h = harness(); const request = h.http.get('/access-grant');
  h.respond(0, 401); await tick(); h.respond(1, 0, tokens(true)); await tick();
  h.respond(2, 0, { status: 'NONE' }); await request;
  assert.equal(h.storage.get('v12Authorized'), false);
});

test('刷新令牌无效时清会话且只跳转一次', async () => {
  const h = harness(); const request = h.http.get('/project');
  const rejected = assert.rejects(request, e => e.code === 401);
  h.respond(0, 401); await tick(); h.respond(1, 401); await rejected;
  assert.equal(h.storage.has('zs_access_token'), false);
  assert.equal(h.storage.has('zs_refresh_token'), false);
  assert.equal(Object.keys(h.app.globalData).length, 0);
  assert.equal(h.redirects.length, 1);
});

test('重放后再 401 时结束，不无限刷新', async () => {
  const h = harness(); const request = h.http.get('/project');
  const rejected = assert.rejects(request, e => e.code === 401);
  h.respond(0, 401); await tick(); h.respond(1, 0, tokens(false)); await tick();
  h.respond(2, 401); await rejected;
  assert.equal(h.calls.length, 3); assert.equal(h.redirects.length, 1);
});

test('刷新过程中退出或切换账号，不写入旧账号令牌或重放旧写操作', async () => {
  const h = harness(); const request = h.http.post('/project', { count: 2 });
  const rejected = assert.rejects(request, e => e.code === 'SESSION_CHANGED');
  h.respond(0, 401); await tick(); h.http.clearTokens(); h.http.setTokens('other-access', 'other-refresh');
  h.respond(1, 0, tokens(false)); await rejected;
  assert.equal(h.storage.get('zs_access_token'), 'other-access');
  assert.equal(h.calls.length, 2); assert.equal(h.redirects.length, 0);
});

test('刷新网络错误允许稍后重试，不清除有效刷新令牌', async () => {
  const h = harness(); const request = h.http.get('/project');
  const rejected = assert.rejects(request, e => e.errMsg === 'offline');
  h.respond(0, 401); await tick(); h.calls[1].fail({ errMsg: 'offline' }); await rejected;
  assert.equal(h.storage.get('zs_refresh_token'), 'old-refresh');
  assert.equal(h.redirects.length, 0);
});
