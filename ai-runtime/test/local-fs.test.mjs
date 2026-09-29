import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, readFileSync, rmSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { config, localObjectPath, Provider, sha } from '../src/runtime.mjs';

function envWith(overrides = {}) {
  return {
    ZS_AI_CORE_URL: 'http://127.0.0.1:48080',
    ZS_INTERNAL_SECRET: 'dev-secret',
    ZS_AI_API_KEY: 'key',
    ZS_AI_IMAGE_MODEL: 'img-model',
    ZS_AI_DAILY_CALL_LIMIT: '10',
    ZS_AI_JOURNAL_DIR: join(tmpdir(), 'zs-ai-journal-test'),
    ...overrides,
  };
}

test('local-fs 模式免 https 源白名单，要求资产根目录', () => {
  const settings = config(envWith({ ZS_AI_STORAGE_MODE: 'local-fs', ZS_AI_STORAGE_ROOT: join(tmpdir(), 'zs-assets-test') }));
  assert.equal(settings.storageMode, 'local-fs');
  assert.deepEqual(settings.storageOrigins, []);
  assert.throws(() => config(envWith({ ZS_AI_STORAGE_MODE: 'local-fs' })), /MISSING_ZS_AI_STORAGE_ROOT/);
  const https = config(envWith({ ZS_AI_STORAGE_ORIGINS: 'https://cos.example.com' }));
  assert.equal(https.storageMode, 'https');
});

test('localObjectPath 解析 local:// 并拦截越界', () => {
  const root = tmpdir();
  assert.equal(localObjectPath(root, 'local://ai-quarantine/9007/f.png?expires=123'),
    join(root, 'ai-quarantine', '9007', 'f.png'));
  assert.throws(() => localObjectPath(root, 'https://cos.example.com/a.png'), /STORAGE_ORIGIN/);
  assert.throws(() => localObjectPath(root, 'local://../escape.png'), /STORAGE_ORIGIN/);
  // URL 规范化会先吃掉点段：a/../../escape 解析为 a/escape，仍在根内，不构成逃逸
  assert.equal(localObjectPath(root, 'local://a/../../escape.png'), join(root, 'a', 'escape.png'));
});

test('local-fs 读写回环：imageInput 校验摘要，upload 原子落盘', async () => {
  const root = mkdtempSync(join(tmpdir(), 'zs-local-fs-'));
  try {
    const settings = { storageMode: 'local-fs', storageRoot: root, storageOrigins: [] };
    const provider = new Provider(settings, { once: async (_k, op) => op() });
    const bytes = Buffer.from('png-bytes');
    mkdirSync(join(root, 'inputs'), { recursive: true });
    writeFileSync(join(root, 'inputs', 'a.png'), bytes);
    const input = { url: 'local://inputs/a.png?expires=1', sizeBytes: bytes.length, sha256: sha(bytes), mimeType: 'image/png' };
    const image = await provider.imageInput(input, undefined);
    assert.equal(image.mimeType, 'image/png');
    await assert.rejects(() => provider.imageInput({ ...input, sha256: sha(Buffer.from('other')) }, undefined),
      /INPUT_DIGEST/);

    const encoded = Buffer.from('gen-image').toString('base64');
    await provider.upload('local://ai-quarantine/9007/slot-1.png', { encoded, mimeType: 'image/png' }, undefined);
    assert.equal(readFileSync(join(root, 'ai-quarantine', '9007', 'slot-1.png')).toString(), 'gen-image');
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
