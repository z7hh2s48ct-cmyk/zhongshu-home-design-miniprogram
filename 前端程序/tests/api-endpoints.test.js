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

test('客服入口走 GET /support-entry（C06）', async () => {
  const { calls, http } = recorder();
  const api = loadApi(http);
  await api.getSupportEntry();
  assert.deepEqual(calls.map((c) => [c.method, c.url]), [
    ['GET', '/app-api/design/v1/support-entry'],
  ]);
});

test('数据主体请求（M10）：导出/关闭 的发起为无体 POST；进度查询端点暂无页面使用不封装', async () => {
  const { calls, http } = recorder();
  const api = loadApi(http);
  await api.createDataExportRequest();
  await api.createAccountClosureRequest();
  assert.deepEqual(calls.map((c) => [c.method, c.url]), [
    ['POST', '/app-api/design/v1/data-export-requests'],
    ['POST', '/app-api/design/v1/account-closure-requests'],
  ]);
  // 发起类端点不得携带 body（后端只认 Authorization 头解析身份）
  assert.equal(calls[0].data, undefined);
  assert.equal(calls[1].data, undefined);
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
