import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import {
  MAX_OBJECT_BYTES,
  loadMigrationEnvironment,
  migrateAssets,
  parseArguments,
  scanAssets,
} from './migrate-local-assets.mjs';

async function fixture(t) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'zs-cos-migrate-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  await fs.mkdir(path.join(root, 'plans'), { recursive: true });
  await fs.writeFile(path.join(root, 'plans', 'a plan.png'), Buffer.from('plan'));
  await fs.writeFile(path.join(root, 'elevation.jpg'), Buffer.from('elevation'));
  return root;
}

test('参数默认 dry-run，只有 --apply 才启用上传', () => {
  assert.deepEqual(parseArguments(['--root', 'assets']), {
    root: path.resolve('assets'), apply: false, manifest: null,
  });
  assert.equal(parseArguments(['--root=assets', '--apply', '--manifest', 'result.json']).apply, true);
  assert.throws(() => parseArguments(['--apply']), /--root/);
  assert.throws(() => parseArguments(['--root', 'assets', '--unknown']), /未知参数/);
});

test('扫描保留相对 object key，逐文件计算 SHA-256 并忽略非资产文件', async t => {
  const root = await fixture(t);
  await fs.writeFile(path.join(root, 'notes.txt'), 'not an asset');
  const assets = await scanAssets(root);
  assert.deepEqual(assets.map(({ key, size }) => ({ key, size })), [
    { key: 'elevation.jpg', size: 9 },
    { key: 'plans/a plan.png', size: 4 },
  ]);
  assert.match(assets[0].sha256, /^[a-f0-9]{64}$/);
  assert.equal(assets.every(item => item.outcome === 'pending'), true);
});

test('扫描拒绝符号链接、越界根目录和超过 20 MiB 的对象', async t => {
  const root = await fixture(t);
  const outside = path.join(path.dirname(root), 'outside.png');
  await fs.writeFile(outside, 'outside');
  t.after(() => fs.rm(outside, { force: true }));
  try {
    await fs.symlink(outside, path.join(root, 'linked.png'));
    await assert.rejects(scanAssets(root), /符号链接/);
    await fs.rm(path.join(root, 'linked.png'));
  } catch (error) {
    if (error?.code !== 'EPERM') throw error;
  }
  await fs.writeFile(path.join(root, 'large.png'), Buffer.alloc(MAX_OBJECT_BYTES + 1));
  await assert.rejects(scanAssets(root), /20 MiB/);
  await assert.rejects(scanAssets(path.join(root, '..', path.basename(root), '..', 'missing')), /根目录/);
});

test('dry-run 不读取配置也不调用网络，返回可持久化 manifest', async t => {
  const root = await fixture(t);
  let calls = 0;
  const result = await migrateAssets({ root, apply: false, env: {}, transport: async () => { calls += 1; } });
  assert.equal(calls, 0);
  assert.equal(result.mode, 'dry-run');
  assert.deepEqual(result.assets.map(item => item.outcome), ['pending', 'pending']);
});

test('远端不存在时禁止覆盖上传，随后 GET 摘要验证', async t => {
  const root = await fixture(t);
  const calls = [];
  const result = await migrateAssets({
    root, apply: true, env: validEnvironment(),
    transport: async request => {
      calls.push({ method: request.method, key: request.key, headers: request.headers });
      if (request.method === 'HEAD') return { statusCode: 404, headers: {} };
      if (request.method === 'PUT') return { statusCode: 200, headers: {} };
      return { statusCode: 200, headers: {}, body: await fs.readFile(path.join(root, ...request.key.split('/'))) };
    },
  });
  assert.equal(result.assets.every(item => item.outcome === 'uploaded'), true);
  assert.deepEqual(calls.map(call => call.method), ['HEAD', 'PUT', 'GET', 'HEAD', 'PUT', 'GET']);
  assert.equal(calls.filter(call => call.method === 'PUT').every(call => call.headers['x-cos-forbid-overwrite'] === 'true'), true);
});

test('远端同摘要跳过，不同摘要拒绝覆盖，403 直接失败', async t => {
  const root = await fixture(t);
  const local = await scanAssets(root);
  const same = await migrateAssets({
    root, apply: true, env: validEnvironment(),
    transport: async request => request.method === 'HEAD'
      ? { statusCode: 200, headers: {} }
      : { statusCode: 200, headers: {}, body: await fs.readFile(path.join(root, ...request.key.split('/'))) },
  });
  assert.equal(same.assets.every(item => item.outcome === 'skipped'), true);
  await assert.rejects(migrateAssets({
    root, apply: true, env: validEnvironment(),
    transport: async request => request.method === 'HEAD'
      ? { statusCode: 200, headers: {} }
      : { statusCode: 200, headers: {}, body: Buffer.from('different') },
  }), /拒绝覆盖/);
  await assert.rejects(migrateAssets({
    root, apply: true, env: validEnvironment(),
    transport: async () => ({ statusCode: 403, headers: {} }),
  }), /403/);
  assert.equal(local.length, 2);
});

test('整体 deadline 到期即停止，错误不泄露凭据', async t => {
  const root = await fixture(t);
  await assert.rejects(migrateAssets({
    root, apply: true, env: validEnvironment(), deadlineMs: 1,
    transport: async () => {
      await new Promise(resolve => setTimeout(resolve, 5));
      return { statusCode: 404, headers: {} };
    },
  }), error => /超时/.test(error.message) && !/secret-key/.test(error.message));
});

test('响应头返回后慢速响应体仍受整体 deadline 限制，错误包含 key 且不泄密', async t => {
  const root = await fixture(t);
  async function* slowBody() {
    await new Promise(resolve => setTimeout(resolve, 30));
    throw new Error('secret-key in response failure');
  }
  await assert.rejects(migrateAssets({
    root, apply: true, env: validEnvironment(), deadlineMs: 10,
    transport: async request => request.method === 'HEAD'
      ? { statusCode: 200, headers: {} }
      : { statusCode: 200, headers: {}, body: slowBody() },
  }), error => /elevation\.jpg/.test(error.message)
    && /超时/.test(error.message)
    && !/secret-key/.test(error.message));
});

test('.env 先加载，process env 覆盖且解析不回显凭据', async t => {
  const root = await fixture(t);
  const dotenv = path.join(root, '.env');
  await fs.writeFile(dotenv, 'ZS_COS_SECRET_ID=file-id\nZS_COS_SECRET_KEY=file-key\nZS_COS_BUCKET=demo-1250000000\n');
  const env = await loadMigrationEnvironment({ dotenvPath: dotenv, env: { ZS_COS_SECRET_ID: 'process-id' } });
  assert.equal(env.ZS_COS_SECRET_ID, 'process-id');
  assert.equal(env.ZS_COS_SECRET_KEY, 'file-key');
});

function validEnvironment() {
  return {
    ZS_COS_SECRET_ID: 'secret-id',
    ZS_COS_SECRET_KEY: 'secret-key',
    ZS_COS_BUCKET: 'demo-1250000000',
    ZS_COS_REGION: 'ap-shanghai',
    ZS_COS_ENDPOINT: 'https://cos.ap-shanghai.myqcloud.com',
    ZHONGSHU_DESIGN_ASSET_STORAGE_PROVIDER: 'cos',
    ZS_COS_PATH_STYLE_ACCESS: 'false',
  };
}
