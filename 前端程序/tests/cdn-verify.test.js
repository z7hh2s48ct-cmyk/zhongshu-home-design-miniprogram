const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

const { manifestFingerprint, sniffImageType, verifyCdn } = require('../scripts/verify-cdn');

const PNG_HEAD = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
const JPEG_HEAD = Buffer.from([0xff, 0xd8, 0xff, 0xe0]);

function imageBytes(size, head = PNG_HEAD) {
  return Buffer.concat([head, Buffer.alloc(size - head.length, 9)]);
}

// 构造最小发布目录：manifest（可选 cdnUploaded）+ 内容映射，使校验器可离线被完整驱动。
function fixture(specs, { cdnUploaded = true } = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zs-verify-'));
  const release = path.join(root, 'release', 'fixture');
  fs.mkdirSync(release, { recursive: true });
  const contents = new Map();
  const assets = specs.map(({ name, size, ext = 'png', head }) => {
    const buffer = imageBytes(size, head || (ext === 'jpg' ? JPEG_HEAD : PNG_HEAD));
    const sha256 = crypto.createHash('sha256').update(buffer).digest('hex');
    const objectKey = `assets/v12/${name}.${sha256.slice(0, 16)}.${ext}`;
    const url = `https://cdn.example.cn/${objectKey}`;
    contents.set(url, buffer);
    return { source: `assets/v12/${name}.${ext}`, objectKey, url, sha256, bytes: buffer.length };
  });
  const manifest = { schemaVersion: 1, assetBase: 'https://cdn.example.cn', cdnUploaded, assets };
  fs.writeFileSync(path.join(release, 'release-manifest.json'), JSON.stringify(manifest, null, 2));
  return { root, release, manifest, contents };
}

// 以内容映射为后端的匿名 GET 桩：默认「完全一致的成功响应」，overrides 用于制造各类偏差。
function fetchFrom(contents, overrides = {}) {
  const types = { '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp', '.gif': 'image/gif' };
  return async ({ url }) => {
    const buffer = contents.get(url);
    if (!buffer) return { statusCode: 404, headers: {}, size: 0, sha256: '', head: Buffer.alloc(0) };
    const ext = path.extname(new URL(url).pathname).toLowerCase();
    return {
      statusCode: 200,
      headers: { 'content-type': types[ext] || 'application/octet-stream' },
      size: buffer.length,
      sha256: crypto.createHash('sha256').update(buffer).digest('hex'),
      head: buffer.slice(0, 64),
      ...overrides,
    };
  };
}

test('cdnUploaded 非 true 时拒绝校验，避免未上传就自证可达', async () => {
  const { root } = fixture([{ name: 'a', size: 2048 }], { cdnUploaded: false });
  await assert.rejects(() => verifyCdn({ releaseName: 'fixture', root }), /cdnUploaded 非 true/);
});

test('全部素材匿名公开可达且字节与哈希一致时，报告 allReachable 为 true', async () => {
  const { root, release, manifest, contents } = fixture([{ name: 'a', size: 2048 }, { name: 'b', size: 4096, ext: 'jpg' }]);
  const result = await verifyCdn({ releaseName: 'fixture', root, fetchImpl: fetchFrom(contents) });
  assert.equal(result.report.allReachable, true);
  assert.equal(result.report.reachableCount, 2);
  assert.equal(result.report.assetCount, 2);
  assert.equal(result.failures.length, 0);
  assert.equal(result.report.assetBase, 'https://cdn.example.cn');
  assert.equal(result.reportPath, path.join(release, 'cdn-verification.json'));
  assert.equal(result.report.manifestFingerprint, manifestFingerprint(manifest.assets));
  assert.match(result.report.manifestFingerprint, /^[0-9a-f]{64}$/);
});

test('对象缺失返回 404 时判定失败并列出该对象', async () => {
  const { root, contents } = fixture([{ name: 'a', size: 2048 }, { name: 'b', size: 4096 }]);
  const missingUrl = [...contents.keys()][1];
  const base = fetchFrom(contents);
  const result = await verifyCdn({
    releaseName: 'fixture',
    root,
    fetchImpl: async (input) =>
      input.url === missingUrl
        ? { statusCode: 404, headers: {}, size: 0, sha256: '', head: Buffer.alloc(0) }
        : base(input),
  });
  assert.equal(result.report.allReachable, false);
  assert.equal(result.report.reachableCount, 1);
  assert.match(result.failures.join('\n'), /HTTP 404/);
});

test('远端字节数与清单不一致时判定失败（防截断图）', async () => {
  const { root, contents } = fixture([{ name: 'a', size: 2048 }]);
  const result = await verifyCdn({
    releaseName: 'fixture',
    root,
    fetchImpl: fetchFrom(contents, { size: 999 }),
  });
  assert.equal(result.report.allReachable, false);
  assert.match(result.failures.join('\n'), /字节数 999 ≠ 清单 2048/);
});

test('远端 sha256 与清单不一致时判定失败（防同尺寸内容被替换）', async () => {
  const { root, contents } = fixture([{ name: 'a', size: 2048 }]);
  const result = await verifyCdn({
    releaseName: 'fixture',
    root,
    fetchImpl: fetchFrom(contents, { sha256: 'f'.repeat(64) }),
  });
  assert.equal(result.report.allReachable, false);
  assert.match(result.failures.join('\n'), /sha256 与清单不一致/);
});

test('HTTP 200 但响应体是 HTML 错误页时判定失败（识破假成功）', async () => {
  const { root, contents } = fixture([{ name: 'a', size: 2048 }]);
  const result = await verifyCdn({
    releaseName: 'fixture',
    root,
    fetchImpl: fetchFrom(contents, { head: Buffer.from('<!DOCTYPE html><html><body>404 Not Found') }),
  });
  assert.equal(result.report.allReachable, false);
  assert.match(result.failures.join('\n'), /不是可识别的图片/);
});

test('图片魔数与扩展名不符时判定失败（防错图/占位图）', async () => {
  const { root, contents } = fixture([{ name: 'a', size: 2048, ext: 'png' }]);
  const result = await verifyCdn({
    releaseName: 'fixture',
    root,
    fetchImpl: fetchFrom(contents, { head: Buffer.concat([JPEG_HEAD, Buffer.alloc(60, 1)]) }),
  });
  assert.equal(result.report.allReachable, false);
  assert.match(result.failures.join('\n'), /image\/jpeg 与扩展名推导的 image\/png 不一致/);
});

test('响应 Content-Type 与图片实际类型不符时判定失败（小程序将无法按图渲染）', async () => {
  const { root, contents } = fixture([{ name: 'a', size: 2048 }]);
  const result = await verifyCdn({
    releaseName: 'fixture',
    root,
    fetchImpl: fetchFrom(contents, { headers: { 'content-type': 'text/html; charset=utf-8' } }),
  });
  assert.equal(result.report.allReachable, false);
  assert.match(result.failures.join('\n'), /Content-Type text\/html ≠ image\/png/);
});

test('请求地址为 assetBase 与 objectKey 拼接，且只做匿名 GET（不携带凭据）', async () => {
  const { root, manifest, contents } = fixture([{ name: 'a', size: 2048 }, { name: 'b', size: 4096 }]);
  const seen = [];
  await verifyCdn({
    releaseName: 'fixture',
    root,
    fetchImpl: async (input) => {
      seen.push(input);
      return fetchFrom(contents)(input);
    },
  });
  assert.deepEqual(
    seen.map((item) => item.url),
    manifest.assets.map((asset) => `${manifest.assetBase}/${asset.objectKey}`),
  );
  assert.ok(seen.every((item) => item.authorization === undefined && Object.keys(item).length === 1));
});

test('清单指纹随对象集变化，供删除守卫拒绝陈旧报告', () => {
  const before = [{ objectKey: 'a.png', sha256: 'x', bytes: 1 }];
  const same = [{ objectKey: 'a.png', sha256: 'x', bytes: 1 }];
  const rebuilt = [{ objectKey: 'a.' + 'y'.repeat(16) + '.png', sha256: 'x', bytes: 1 }];
  assert.equal(manifestFingerprint(before), manifestFingerprint(same));
  assert.notEqual(manifestFingerprint(before), manifestFingerprint(rebuilt));
});

test('图片魔数识别覆盖常见格式并拒绝 HTML', () => {
  assert.equal(sniffImageType(PNG_HEAD), 'image/png');
  assert.equal(sniffImageType(JPEG_HEAD), 'image/jpeg');
  assert.equal(sniffImageType(Buffer.from('GIF89a' + 'x'.repeat(20))), 'image/gif');
  assert.equal(sniffImageType(Buffer.concat([Buffer.from('RIFF'), Buffer.alloc(4), Buffer.from('WEBP')])), 'image/webp');
  assert.equal(sniffImageType(Buffer.from('<svg xmlns="http://www.w3.org/2000/svg"></svg>')), 'image/svg+xml');
  assert.equal(sniffImageType(Buffer.from('<!DOCTYPE html><html>')), null);
  assert.equal(sniffImageType(Buffer.alloc(0)), null);
});
