const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

const {
  buildAuthorization,
  cosEncode,
  guessContentType,
  readCredentials,
  uploadRelease,
} = require('../scripts/upload-cos');

// 构造一个最小可用的发布目录：manifest + cdn/<objectKey>，
// 使上传器可以在不触网、不依赖真实构建的前提下被完整驱动。
function fixture(assets) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zs-cos-'));
  const release = path.join(root, 'release', 'fixture');
  fs.mkdirSync(path.join(release, 'cdn', 'assets/v12'), { recursive: true });
  const manifestAssets = assets.map(({ name, bytes }) => {
    const buffer = Buffer.alloc(bytes, 7);
    const sha256 = crypto.createHash('sha256').update(buffer).digest('hex');
    const objectKey = `assets/v12/${name}.${sha256.slice(0, 16)}.png`;
    fs.writeFileSync(path.join(release, 'cdn', objectKey), buffer);
    return { source: `assets/v12/${name}.png`, objectKey, url: `https://cdn.example.cn/${objectKey}`, sha256, bytes };
  });
  const manifest = { schemaVersion: 1, assetBase: 'https://cdn.example.cn', cdnUploaded: false, assets: manifestAssets };
  fs.writeFileSync(path.join(release, 'release-manifest.json'), JSON.stringify(manifest, null, 2));
  return { root, release };
}

const CREDENTIALS = {
  ZS_COS_SECRET_ID: 'AKIDEXAMPLE',
  ZS_COS_SECRET_KEY: 'secret-example',
  ZS_COS_BUCKET: 'zhongshu-1250000000',
  ZS_COS_REGION: 'ap-guangzhou',
};

const okTransport = () => ({
  putObject: async () => ({ statusCode: 200, headers: {}, body: '' }),
  headObject: async ({ item }) => ({
    statusCode: 200,
    headers: { 'content-length': String(item.bytes.length), etag: '"etag-value"' },
  }),
});

test('缺少凭据时立即报错，且不发起任何传输', async () => {
  const { root } = fixture([{ name: 'a', bytes: 2048 }]);
  let called = 0;
  const transport = {
    putObject: async () => { called += 1; return { statusCode: 200, headers: {}, body: '' }; },
    headObject: async () => { called += 1; return { statusCode: 200, headers: {}, body: '' }; },
  };
  await assert.rejects(
    () => uploadRelease({ releaseName: 'fixture', env: {}, root, transport }),
    /缺少 COS 凭据环境变量/,
  );
  assert.equal(called, 0);
});

test('--dry-run 无需凭据即可列出待传清单', async () => {
  const { root } = fixture([{ name: 'a', bytes: 1000 }, { name: 'b', bytes: 5000 }]);
  const result = await uploadRelease({ releaseName: 'fixture', dryRun: true, env: {}, root });
  assert.equal(result.dryRun, true);
  assert.equal(result.pending.length, 2);
  assert.ok(result.pending.every((item) => item.objectKey.startsWith('assets/v12/')));
});

test('全部对象上传且可达性校验通过后，回写 cdnUploaded=true 与每个对象的 etag', async () => {
  const { root, release } = fixture([{ name: 'a', bytes: 2048 }, { name: 'b', bytes: 4096 }]);
  const result = await uploadRelease({
    releaseName: 'fixture',
    env: CREDENTIALS,
    root,
    transport: okTransport(),
  });
  assert.equal(result.cdnUploaded, true);
  assert.equal(result.uploaded.length, 2);
  const written = JSON.parse(fs.readFileSync(path.join(release, 'release-manifest.json'), 'utf8'));
  assert.equal(written.cdnUploaded, true);
  assert.ok(written.cdnUploadedAt);
  assert.equal(written.cdnBucket, CREDENTIALS.ZS_COS_BUCKET);
  assert.ok(written.assets.every((asset) => asset.uploaded && asset.uploaded.etag === 'etag-value'));
});

test('远端字节数与本地不一致时判定失败，且不写 cdnUploaded=true（避免发布 404/截断图）', async () => {
  const { root, release } = fixture([{ name: 'a', bytes: 2048 }]);
  const transport = {
    putObject: async () => ({ statusCode: 200, headers: {}, body: '' }),
    headObject: async () => ({ statusCode: 200, headers: { 'content-length': '999' } }),
  };
  await assert.rejects(
    () => uploadRelease({ releaseName: 'fixture', env: CREDENTIALS, root, transport }),
    /字节数 999 与本地 2048 不一致/,
  );
  const written = JSON.parse(fs.readFileSync(path.join(release, 'release-manifest.json'), 'utf8'));
  assert.equal(written.cdnUploaded, false);
});

test('可达性校验返回非 200 时整体失败并列出该对象', async () => {
  const { root } = fixture([{ name: 'a', bytes: 2048 }]);
  const transport = {
    putObject: async () => ({ statusCode: 200, headers: {}, body: '' }),
    headObject: async () => ({ statusCode: 403, headers: {} }),
  };
  await assert.rejects(
    () => uploadRelease({ releaseName: 'fixture', env: CREDENTIALS, root, transport }),
    /可达性校验返回 403/,
  );
});

test('上传阶段抛错被聚合为该对象的失败项，不阻断其余对象', async () => {
  const { root } = fixture([{ name: 'a', bytes: 2048 }, { name: 'b', bytes: 4096 }]);
  let index = 0;
  const transport = {
    putObject: async () => {
      index += 1;
      if (index === 1) throw new Error('网络中断');
      return { statusCode: 200, headers: {}, body: '' };
    },
    headObject: async ({ item }) => ({
      statusCode: 200,
      headers: { 'content-length': String(item.bytes.length) },
    }),
  };
  await assert.rejects(
    () => uploadRelease({ releaseName: 'fixture', env: CREDENTIALS, root, transport }),
    /网络中断/,
  );
});

test('清单登记的对象缺少本地文件时给出可操作错误', async () => {
  const { root, release } = fixture([{ name: 'a', bytes: 2048 }]);
  const manifest = JSON.parse(fs.readFileSync(path.join(release, 'release-manifest.json'), 'utf8'));
  fs.rmSync(path.join(release, 'cdn', manifest.assets[0].objectKey));
  await assert.rejects(
    () => uploadRelease({ releaseName: 'fixture', env: CREDENTIALS, root, transport: okTransport() }),
    /缺少本地文件/,
  );
});

test('发布名称非法时拒绝执行', async () => {
  await assert.rejects(() => uploadRelease({ releaseName: '../etc', env: CREDENTIALS }), /合法的发布名称/);
});

test('凭据读取：默认按 bucket+region 推导 endpoint，可用 ZS_COS_ENDPOINT 覆盖', () => {
  assert.equal(readCredentials(CREDENTIALS).endpoint, 'zhongshu-1250000000.cos.ap-guangzhou.myqcloud.com');
  assert.equal(
    readCredentials({ ...CREDENTIALS, ZS_COS_ENDPOINT: 'cos.accelerate.example.cn' }).endpoint,
    'cos.accelerate.example.cn',
  );
});

test('签名包含规定字段，header-list 为小写且已排序', () => {
  const authorization = buildAuthorization({
    secretId: 'AKIDEXAMPLE',
    secretKey: 'secret-example',
    method: 'put',
    pathname: '/assets/v12/a.png',
    headers: { 'Content-Type': 'image/png', CacheControl: 'public', host: 'example.cn' },
    signTime: '1700000000;1700000600',
  });
  const params = Object.fromEntries(authorization.split('&').map((pair) => pair.split('=')));
  assert.equal(params['q-sign-algorithm'], 'sha1');
  assert.equal(params['q-ak'], 'AKIDEXAMPLE');
  assert.equal(params['q-sign-time'], '1700000000;1700000600');
  assert.equal(params['q-header-list'], 'cachecontrol;content-type;host');
  assert.match(params['q-signature'], /^[0-9a-f]{40}$/);
});

test('签名对相同输入稳定、对签名时间变化敏感', () => {
  const base = {
    secretId: 'AKIDEXAMPLE',
    secretKey: 'secret-example',
    method: 'head',
    pathname: '/assets/v12/a.png',
    headers: { host: 'example.cn' },
  };
  const first = buildAuthorization({ ...base, signTime: '1700000000;1700000600' });
  const same = buildAuthorization({ ...base, signTime: '1700000000;1700000600' });
  const later = buildAuthorization({ ...base, signTime: '1700000100;1700000700' });
  assert.equal(first, same);
  assert.notEqual(first, later);
});

test('URL 编码覆盖 COS 要求的额外安全字符', () => {
  assert.equal(cosEncode('a b/c'), 'a%20b%2Fc');
  assert.equal(cosEncode("!'()*"), '%21%27%28%29%2A');
});

test('内容类型按扩展名推导，未知扩展名回退二进制流', () => {
  assert.equal(guessContentType('assets/v12/a.png'), 'image/png');
  assert.equal(guessContentType('assets/v12/a.jpg'), 'image/jpeg');
  assert.equal(guessContentType('assets/v12/a.webp'), 'image/webp');
  assert.equal(guessContentType('assets/v12/a.bin'), 'application/octet-stream');
});
