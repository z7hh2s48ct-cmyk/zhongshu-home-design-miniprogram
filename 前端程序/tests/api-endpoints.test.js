const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

// 复用 budget-api.test.js 的加载口径：把 utils/api.js 装进 vm，唯一依赖 ./request 由桩 http 顶替。
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

// 记录型 http：捕获每次调用的 method/url/data/headers，供 URL 契约断言。
function recorder() {
  const calls = [];
  const mk = (method) => (url, data, headers) => {
    calls.push({ method, url, data, headers });
    return Promise.resolve({ ok: true });
  };
  return {
    calls,
    http: { get: mk('GET'), post: mk('POST'), put: mk('PUT'), patch: mk('PATCH'), del: mk('DELETE') },
  };
}

test('用户偏好走 PATCH /profile/preferences；未传偏好发空对象而非 undefined', async () => {
  const { calls, http } = recorder();
  const api = loadApi(http);
  await api.updatePreferences({ theme: 'dark', notify: false });
  await api.updatePreferences();
  // 未传偏好时的空对象 {} 由 api.js 在 vm realm 内创建，其 Object.prototype 与测试 realm 不同，
  // 直接 deepStrictEqual 会因原型不等而失败；用 JSON 往返在当前 realm 重建，保留 {} 与 undefined 的区别。
  const normalize = (d) => (d === undefined ? undefined : JSON.parse(JSON.stringify(d)));
  assert.deepEqual(calls.map((c) => [c.method, c.url, normalize(c.data)]), [
    ['PATCH', '/app-api/design/v1/profile/preferences', { theme: 'dark', notify: false }],
    ['PATCH', '/app-api/design/v1/profile/preferences', {}],
  ]);
});

test('客服入口走 GET /support-entry（C06）', async () => {
  const { calls, http } = recorder();
  const api = loadApi(http);
  await api.getSupportEntry();
  assert.deepEqual(calls.map((c) => [c.method, c.url]), [
    ['GET', '/app-api/design/v1/support-entry'],
  ]);
});

test('数据主体请求（M10）：导出/关闭 的发起为无体 POST，查询保留大整数 requestId 不丢精度', async () => {
  const { calls, http } = recorder();
  const api = loadApi(http);
  await api.createDataExportRequest();
  await api.getDataExportRequest('9007199254740993');
  await api.createAccountClosureRequest();
  await api.getAccountClosureRequest('42');
  assert.deepEqual(calls.map((c) => [c.method, c.url]), [
    ['POST', '/app-api/design/v1/data-export-requests'],
    ['GET', '/app-api/design/v1/data-export-requests/9007199254740993'],
    ['POST', '/app-api/design/v1/account-closure-requests'],
    ['GET', '/app-api/design/v1/account-closure-requests/42'],
  ]);
  // 发起类端点不得携带 body（后端只认 Authorization 头解析身份）
  assert.equal(calls[0].data, undefined);
  assert.equal(calls[2].data, undefined);
});

test('assetContentUrl 构造开发期字节端点路径并转义 assetId', () => {
  const { http } = recorder();
  const api = loadApi(http);
  assert.equal(api.assetContentUrl('123'), '/app-api/design/v1/assets/123/content');
  assert.equal(api.assetContentUrl('a/b'), '/app-api/design/v1/assets/a%2Fb/content');
});

test('三处上传/下载入口已收敛到 assetContentUrl，无硬编码 assets content 路径残留', () => {
  const files = ['utils/assets.js', 'utils/avatar-upload.js', 'pages/ai-design/index.js'];
  for (const rel of files) {
    const src = fs.readFileSync(path.resolve(__dirname, '../miniprogram', rel), 'utf8');
    assert.ok(src.includes('assetContentUrl('), `${rel} 应改用 api.assetContentUrl 构造字节端点`);
    assert.ok(
      !src.includes('/app-api/design/v1/assets/'),
      `${rel} 不应再硬编码 /app-api/design/v1/assets/ 路径`,
    );
  }
});
