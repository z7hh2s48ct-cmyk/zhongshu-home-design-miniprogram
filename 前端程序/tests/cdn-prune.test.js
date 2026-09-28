const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

const { manifestFingerprint } = require('../scripts/verify-cdn');
const { pruneCdnStaging, RECORD_NAME } = require('../scripts/prune-cdn-staging');

// 构造发布目录：manifest + cdn/** 暂存副本 + 可选的校验报告；另置一个 miniprogram 源图哨兵，
// 用于断言删除动作绝不越界。
function fixture({ upload = true, report = 'green', assets = [{ name: 'a', bytes: 2048 }, { name: 'b', bytes: 4096 }] } = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zs-prune-'));
  const release = path.join(root, 'release', 'fixture');
  fs.mkdirSync(path.join(release, 'cdn', 'assets/v12'), { recursive: true });
  const sentinel = path.join(root, 'miniprogram', 'assets', 'v12', 'source.png');
  fs.mkdirSync(path.dirname(sentinel), { recursive: true });
  fs.writeFileSync(sentinel, Buffer.alloc(4096, 3));

  const manifestAssets = assets.map(({ name, bytes }) => {
    const buffer = Buffer.alloc(bytes, 7);
    const sha256 = crypto.createHash('sha256').update(buffer).digest('hex');
    const objectKey = `assets/v12/${name}.${sha256.slice(0, 16)}.png`;
    fs.writeFileSync(path.join(release, 'cdn', objectKey), buffer);
    return { source: `assets/v12/${name}.png`, objectKey, url: `https://cdn.example.cn/${objectKey}`, sha256, bytes };
  });
  const manifest = {
    schemaVersion: 1,
    assetBase: 'https://cdn.example.cn',
    cdnUploaded: upload,
    assets: manifestAssets,
  };
  fs.writeFileSync(path.join(release, 'release-manifest.json'), JSON.stringify(manifest, null, 2));

  if (report !== 'missing') {
    fs.writeFileSync(
      path.join(release, 'cdn-verification.json'),
      JSON.stringify(
        {
          schemaVersion: 1,
          verifiedAt: '2026-09-28T00:00:00.000Z',
          releaseName: 'fixture',
          assetBase: manifest.assetBase,
          manifestFingerprint:
            report === 'stale' ? 'f'.repeat(64) : manifestFingerprint(manifestAssets),
          assetCount: manifestAssets.length,
          reachableCount: report === 'red' ? 1 : manifestAssets.length,
          allReachable: report !== 'red',
          results: [],
        },
        null,
        2,
      ),
    );
  }
  return { root, release, manifest, manifestAssets, sentinel };
}

test('未上传（cdnUploaded 非 true）时拒绝删除暂存副本', async () => {
  const { root } = fixture({ upload: false });
  await assert.rejects(() => pruneCdnStaging({ releaseName: 'fixture', root, yes: true }), /尚未上传/);
});

test('缺少公开校验报告时拒绝删除（删除前必须有正常显示的证明）', async () => {
  const { root } = fixture({ report: 'missing' });
  await assert.rejects(
    () => pruneCdnStaging({ releaseName: 'fixture', root, yes: true }),
    /缺少 CDN 公开校验报告/,
  );
});

test('校验报告 allReachable 为 false 时拒绝删除', async () => {
  const { root } = fixture({ report: 'red' });
  await assert.rejects(() => pruneCdnStaging({ releaseName: 'fixture', root, yes: true }), /allReachable 非 true/);
});

test('报告指纹与当前清单不一致（陈旧报告）时拒绝删除', async () => {
  const { root } = fixture({ report: 'stale' });
  await assert.rejects(
    () => pruneCdnStaging({ releaseName: 'fixture', root, yes: true }),
    /报告可能来自上一次构建/,
  );
});

test('默认预演：列出待删清单但不删除任何文件', async () => {
  const { root, release, sentinel } = fixture();
  const result = await pruneCdnStaging({ releaseName: 'fixture', root });
  assert.equal(result.dryRun, true);
  assert.equal(result.targets.length, 2);
  assert.equal(result.bytes, 2048 + 4096);
  assert.equal(result.deleted.length, 0);
  assert.ok(fs.existsSync(path.join(release, 'cdn')));
  assert.ok(fs.existsSync(sentinel));
});

test('三闸通过且显式 --yes 时删除暂存副本、移除空目录、写审计记录，且不动源图', async () => {
  const { root, release, sentinel } = fixture();
  const result = await pruneCdnStaging({ releaseName: 'fixture', root, yes: true, now: () => '2026-09-28T08:00:00.000Z' });
  assert.equal(result.dryRun, false);
  assert.equal(result.deleted.length, 2);
  assert.equal(result.bytes, 6144);
  assert.equal(fs.existsSync(path.join(release, 'cdn')), false);
  const record = JSON.parse(fs.readFileSync(path.join(release, RECORD_NAME), 'utf8'));
  assert.equal(record.fileCount, 2);
  assert.equal(record.bytes, 6144);
  assert.equal(record.prunedAt, '2026-09-28T08:00:00.000Z');
  assert.equal(record.scope, 'release/<name>/cdn/**');
  assert.equal(record.files.length, 2);
  assert.ok(fs.existsSync(sentinel));
  assert.ok(fs.existsSync(path.join(release, 'release-manifest.json')));
});

test('暂存目录含清单未登记的文件时拒绝删除（不静默误伤未知内容）', async () => {
  const { root, release } = fixture();
  fs.writeFileSync(path.join(release, 'cdn', 'assets', 'v12', 'stray.png'), Buffer.alloc(16, 1));
  await assert.rejects(
    () => pruneCdnStaging({ releaseName: 'fixture', root, yes: true }),
    /清单未登记的文件/,
  );
  assert.ok(fs.existsSync(path.join(release, 'cdn', 'assets', 'v12', 'stray.png')));
});

test('暂存目录此前已清理时返回 alreadyPruned，不报错', async () => {
  const { root, release } = fixture();
  fs.rmSync(path.join(release, 'cdn'), { recursive: true });
  const result = await pruneCdnStaging({ releaseName: 'fixture', root, yes: true });
  assert.equal(result.alreadyPruned, true);
  assert.equal(result.deleted.length, 0);
});

test('发布名称非法时拒绝执行', async () => {
  await assert.rejects(() => pruneCdnStaging({ releaseName: '../etc', yes: true }), /合法的发布名称/);
});
