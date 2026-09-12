const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../miniprogram');
const plain = value => JSON.parse(JSON.stringify(value));

// 隔离加载 pages/ai-design/index.js，只为直接驱动 putObject 的 COS 分流契约：
// protectedPage 捕获页面定义；wx.request / wx.uploadFile / config / api / request / sha256 全部由桩顶替，
// 使测试聚焦「预签名 PUT vs 开发期 multipart POST」的分流与请求头契约，不触网、不读真实 config。
function runtime() {
  const calls = [];
  const modules = new Map();
  let definition;
  let nextStatus = 200;
  const config = { apiBase: 'https://api.example.com', tenantId: 7 };
  const api = { assetContentUrl: id => '/app-api/design/v1/assets/' + encodeURIComponent(id) + '/content' };
  const http = { getToken: () => 'tok-abc' };
  const wx = {
    request: options => { calls.push(['request', options]); options.success && options.success({ statusCode: nextStatus }); },
    uploadFile: options => { calls.push(['uploadFile', options]); options.success && options.success({ statusCode: nextStatus }); },
  };
  function load(relative) {
    const filename = path.resolve(root, relative);
    if (modules.has(filename)) return modules.get(filename).exports;
    const module = { exports: {} };
    modules.set(filename, module);
    vm.runInNewContext(fs.readFileSync(filename, 'utf8'), {
      module, wx, console, getApp: () => ({ globalData: {} }), getCurrentPages: () => [],
      Page(value) { definition = value; },
      require(specifier) {
        if (specifier.endsWith('/access')) return { protectedPage(value) { definition = value; } };
        if (specifier.endsWith('/api')) return api;
        if (specifier.endsWith('/request')) return http;
        if (specifier.endsWith('/config')) return config;
        if (specifier.endsWith('/sha256')) return { sha256Hex: () => 'deadbeef' };
        return load(path.resolve(path.dirname(filename), specifier + '.js'));
      },
    }, { filename });
    return module.exports;
  }
  load('pages/ai-design/index.js');
  const page = Object.assign({}, definition, { data: plain(definition.data), setData(v) { Object.assign(this.data, v); } });
  return { calls, page, config, setStatus(s) { nextStatus = s; } };
}

const bytes = () => new Uint8Array([137, 80, 78, 71]).buffer;

test('COS 预签名地址走 wx.request PUT：Content-Type 透传申报 mime，且不带应用 Bearer / tenant-id', async () => {
  const { calls, page } = runtime();
  const body = bytes();
  await page.putObject(
    { assetId: '9007199254740993', uploadUrl: 'https://cos.example.com/obj?sign=abc' },
    'wxfile://tmp/sketch.png', body, 'image/png',
  );
  const req = calls.filter(c => c[0] === 'request');
  assert.equal(req.length, 1);
  assert.equal(req[0][1].method, 'PUT');
  assert.equal(req[0][1].url, 'https://cos.example.com/obj?sign=abc');
  assert.equal(req[0][1].data, body);
  // 红线：签名对象 PUT 的请求头只允许 Content-Type，绝不能泄漏应用 Bearer / tenant-id（会破坏签名或越权）
  assert.deepEqual(Object.keys(req[0][1].header), ['Content-Type']);
  assert.equal(req[0][1].header['Content-Type'], 'image/png');
  assert.equal(calls.filter(c => c[0] === 'uploadFile').length, 0);
});

test('开发期 local:// 地址走 wx.uploadFile multipart POST，带 Bearer + tenant-id 到字节端点', async () => {
  const { calls, page, config } = runtime();
  await page.putObject(
    { assetId: '9007199254740993', uploadUrl: 'local://assets/9007199254740993' },
    'wxfile://tmp/sketch.png', bytes(), 'image/png',
  );
  const up = calls.filter(c => c[0] === 'uploadFile');
  assert.equal(up.length, 1);
  assert.equal(up[0][1].url, config.apiBase + '/app-api/design/v1/assets/9007199254740993/content');
  assert.equal(up[0][1].name, 'file');
  assert.equal(up[0][1].header['Authorization'], 'Bearer tok-abc');
  assert.equal(up[0][1].header['tenant-id'], '7');
  assert.equal(calls.filter(c => c[0] === 'request').length, 0);
});

test('预签名 PUT 的 Content-Type 精确等于申报 mime（jpeg 场景），确保与后端签名一致', async () => {
  const { calls, page } = runtime();
  await page.putObject(
    { assetId: '2', uploadUrl: 'https://cos.example.com/o?sign=y' },
    'wxfile://tmp/sketch.jpg', bytes(), 'image/jpeg',
  );
  assert.equal(calls.filter(c => c[0] === 'request')[0][1].header['Content-Type'], 'image/jpeg');
});

test('uploadUrl 协议未知时拒绝，且不触碰任何上传 API', async () => {
  const { calls, page } = runtime();
  await assert.rejects(
    () => page.putObject({ assetId: '1', uploadUrl: 'ftp://host/o' }, 'wxfile://t.png', bytes(), 'image/png'),
    err => { assert.equal(err.msg, '上传地址不可用'); return true; },
  );
  assert.equal(calls.length, 0);
});

test('预签名 PUT 返回非 2xx 时按上传失败拒绝（携带状态码）', async () => {
  const env = runtime();
  env.setStatus(403);
  await assert.rejects(
    () => env.page.putObject({ assetId: '1', uploadUrl: 'https://cos.example.com/o?sign=x' }, 'wxfile://t.png', bytes(), 'image/png'),
    err => { assert.match(err.msg, /上传失败\(403\)/); return true; },
  );
});
