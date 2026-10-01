import test from 'node:test';
import assert from 'node:assert/strict';
import { validateConfig, diagnosticObjectKey, runLive } from './cos-storage-check.mjs';

const base = { ZS_COS_SECRET_ID: 'id', ZS_COS_SECRET_KEY: 'key', ZS_COS_BUCKET: 'demo-1250000000', ZS_COS_REGION: 'ap-guangzhou', ZS_COS_ENDPOINT: 'https://cos.ap-guangzhou.myqcloud.com', ZHONGSHU_DESIGN_ASSET_STORAGE_PROVIDER: 'cos', ZS_COS_PATH_STYLE_ACCESS: 'false' };

test('配置校验拒绝缺失项且不泄露密钥', () => {
  assert.throws(() => validateConfig({ ...base, ZS_COS_SECRET_KEY: '' }), /ZS_COS_SECRET_KEY/);
  try { validateConfig({ ...base, ZS_COS_SECRET_KEY: '' }); } catch (e) { assert.doesNotMatch(e.message, /key/); }
});

test('配置校验要求 HTTPS 虚拟主机 endpoint 与 bucket/region 一致', () => {
  assert.throws(() => validateConfig({ ...base, ZS_COS_ENDPOINT: 'http://cos.ap-guangzhou.myqcloud.com' }), /HTTPS/);
  assert.throws(() => validateConfig({ ...base, ZS_COS_ENDPOINT: 'https://demo-1250000000.cos.ap-guangzhou.myqcloud.com' }), /区域 endpoint/);
  assert.throws(() => validateConfig({ ...base, ZS_COS_ENDPOINT: 'https://demo-1250000000.cos.ap-shanghai.myqcloud.com' }), /区域 endpoint/);
});

test('配置校验要求 cos provider 与关闭 path style', () => {
  assert.throws(() => validateConfig({ ...base, ZHONGSHU_DESIGN_ASSET_STORAGE_PROVIDER: 'local' }), /provider/);
  assert.throws(() => validateConfig({ ...base, ZHONGSHU_DESIGN_ASSET_STORAGE_PROVIDER: undefined }), /provider/);
  assert.throws(() => validateConfig({ ...base, ZS_COS_PATH_STYLE_ACCESS: 'true' }), /path-style/);
});

test('配置拒绝错误桶名、端口和含凭据地址，错误不回显输入', () => {
  for (const patch of [{ ZS_COS_BUCKET: '../bad' }, { ZS_COS_ENDPOINT: 'https://cos.ap-guangzhou.myqcloud.com:444' }, { ZS_COS_ENDPOINT: 'https://private:secret@cos.ap-guangzhou.myqcloud.com' }]) {
    assert.throws(() => validateConfig({ ...base, ...patch }), error => !error.message.includes('secret'));
  }
});

function storageTransport({ corrupt = false, publicRead = false } = {}) {
  let stored;
  const calls = [];
  return { calls, transport: async ({ method, body, anonymous }) => {
    calls.push(method + (anonymous ? '-anon' : ''));
    if (method === 'PUT') stored = body;
    return { statusCode: anonymous && !publicRead ? 403 : 200,
      headers: { 'content-length': String(stored.length) }, body: corrupt ? Buffer.from('wrong') : stored };
  } };
}

test('真实验收按 PUT HEAD 签名GET 匿名GET 顺序校验，不删除对象', async () => {
  const fixture = storageTransport();
  assert.equal((await runLive({ env: base, transport: fixture.transport })).ok, true);
  assert.deepEqual(fixture.calls, ['PUT', 'HEAD', 'GET', 'GET-anon']);
});

test('摘要不符及匿名可读均不能通过验收', async () => {
  for (const options of [{ corrupt: true }, { publicRead: true }]) {
    const fixture = storageTransport(options);
    assert.equal((await runLive({ env: base, transport: fixture.transport })).ok, false);
  }
});

test('网络错误保留对象key与失败阶段，不回显原始异常', async () => {
  const result = await runLive({ env: base, transport: async () => { throw Error('secret credentials'); } });
  assert.equal(result.ok, false);
  assert.equal(result.failed, 'PUT');
  assert.ok(result.objectKey.startsWith('diagnostics/cos-check/'));
  assert.doesNotMatch(JSON.stringify(result), /secret credentials/);
});

test('实际传输使用桶域名、短期签名及有界超时，匿名请求不带凭据', async context => {
  let stored;
  const requests = [];
  context.mock.method(globalThis, 'fetch', async (url, options) => {
    requests.push({ url, options });
    assert.equal(new URL(url).hostname, 'demo-1250000000.cos.ap-guangzhou.myqcloud.com');
    assert.equal(options.redirect, 'error');
    assert.ok(options.signal instanceof AbortSignal);
    if (options.method === 'PUT') stored = options.body;
    if (!options.headers.authorization) return new Response('', { status: 403 });
    assert.match(options.headers.authorization, /q-sign-algorithm=sha1/);
    return new Response(options.method === 'HEAD' ? null : stored, {
      status: 200, headers: { 'content-length': String(stored.length) }
    });
  });
  assert.equal((await runLive({ env: base })).ok, true);
  assert.equal(requests.length, 4);
  assert.equal(requests[3].options.headers.authorization, undefined);
});

test('诊断 key 随机且固定 diagnostics 前缀', () => {
  const key = diagnosticObjectKey();
  assert.match(key, /^diagnostics\/cos-check\/\d+-[0-9a-f-]+\.txt$/);
});

test('live 失败时返回非零语义并报告 key，不执行删除', async () => {
  const calls = [];
  const result = await runLive({ env: base, transport: async ({ method, anonymous }) => { calls.push(method + (anonymous ? '-anon' : '')); return { statusCode: method === 'PUT' ? 200 : (method === 'HEAD' ? 200 : 403), headers: { 'content-length': '48' }, body: Buffer.from('wrong') }; } });
  assert.equal(result.ok, false);
  assert.ok(result.objectKey.startsWith('diagnostics/cos-check/'));
  assert.deepEqual(calls, ['PUT', 'HEAD']);
});
