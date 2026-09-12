const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { buildRelease, origin } = require('../scripts/build-release');
const { scanTree } = require('../scripts/check-release');
test('release staging freezes production config, moves large referenced assets and preserves source', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zs-release-test-'));
  try {
    fs.mkdirSync(path.join(root, 'miniprogram/utils'), { recursive: true }); fs.mkdirSync(path.join(root, 'miniprogram/assets/v12'), { recursive: true });
    fs.writeFileSync(path.join(root, 'project.config.json'), JSON.stringify({ appid: 'touristappid', setting: {} }));
    const config = "module.exports={env:'dev',apiBase:'http://localhost:48080'}";
    fs.writeFileSync(path.join(root, 'miniprogram/utils/config.js'), config);
    fs.writeFileSync(path.join(root, 'miniprogram/app.wxml'), '<image src="/assets/v12/hero.png"/>');
    fs.writeFileSync(path.join(root, 'miniprogram/assets/v12/hero.png'), Buffer.alloc(70000));
    fs.writeFileSync(path.join(root, 'miniprogram/assets/v12/unused.png'), Buffer.alloc(100));
    const result = buildRelease({ root, apiBase: 'https://api.demo-company.cn', assetBase: 'https://cdn.demo-company.cn', appid: 'wx1234567890abcdef', outName: 'fixture' });
    assert.equal(result.manifest.env, 'prod'); assert.equal(result.manifest.assets.length, 1); assert.equal(result.manifest.cdnUploaded, false);
    assert.match(fs.readFileSync(path.join(result.destination, 'miniprogram/app.wxml'), 'utf8'), /https:\/\/cdn\.demo-company\.cn\/assets\/v12\/hero\.[0-9a-f]+\.png/);
    assert.equal(fs.existsSync(path.join(result.destination, 'miniprogram/assets/v12/unused.png')), false);
    assert.equal(fs.readFileSync(path.join(root, 'miniprogram/utils/config.js'), 'utf8'), config);
    assert.equal(scanTree(result.destination).length, 0);
    fs.writeFileSync(path.join(result.destination, 'miniprogram/utils/config.js'), "module.exports={env:'dev',apiBase:'https://api.demo-company.cn'}");
    assert.ok(scanTree(result.destination).some(item => item.ruleId === 'dev-env'));
  } finally { if (root.startsWith(path.join(os.tmpdir(), 'zs-release-test-'))) fs.rmSync(root, { recursive: true, force: true }); }
});
test('release builder rejects missing, insecure and private endpoints', () => {
  for (const value of ['', 'http://public.cn', 'https://127.0.0.1', 'https://localhost', 'https://10.0.0.1', 'https://user:pass@public.cn']) assert.throws(() => origin(value));
  assert.equal(origin('https://api.demo-company.cn'), 'https://api.demo-company.cn');
});
