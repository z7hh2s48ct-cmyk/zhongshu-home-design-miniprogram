'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { scanTree } = require('./check-release');
const FRONTEND_ROOT = path.resolve(__dirname, '..');
const TEXT = new Set(['.js', '.json', '.wxml', '.wxss', '.scss', '.ts', '.wxs']);
const digest = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
function origin(value) {
  let url; try { url = new URL(value); } catch { throw Error('必须提供正式 HTTPS 域名'); }
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash || url.pathname !== '/' || (url.port && url.port !== '443')
      || !url.hostname.includes('.') || /^(localhost|127\.|0\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/i.test(url.hostname)
      || /[\[\]:]/.test(url.hostname)) throw Error('必须提供正式 HTTPS 域名，不允许本机或私网地址');
  return url.origin;
}
function walk(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const file = path.join(directory, entry.name);
    if (entry.isSymbolicLink()) throw Error('发布目录禁止符号链接');
    return entry.isDirectory() ? walk(file) : [file];
  });
}
function buildRelease({ apiBase, assetBase, appid, outName, root = FRONTEND_ROOT }) {
  apiBase = origin(apiBase); assetBase = origin(assetBase);
  if (!/^wx[0-9a-f]{16}$/.test(appid || '')) throw Error('必须提供正式小程序 AppID');
  if (!/^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$/.test(outName || '')) throw Error('构建名称只允许字母、数字、下划线和短横线');
  root = path.resolve(root);
  const source = path.join(root, 'miniprogram'), destination = path.join(root, 'release', outName);
  if (fs.existsSync(destination)) throw Error('构建目录已存在，请使用新的名称；不会覆盖既有制品');
  const files = walk(source).filter(file => !path.basename(file).startsWith('.') && !['project.private.config.json', 'project.config.json'].includes(path.basename(file)));
  const text = files.filter(file => TEXT.has(path.extname(file))).map(file => fs.readFileSync(file, 'utf8')).join('\n');
  const substitutions = new Map(), assets = [];
  const write = (relative, bytes) => { const target = path.join(destination, relative); fs.mkdirSync(path.dirname(target), { recursive: true }); fs.writeFileSync(target, bytes); };
  for (const file of files) {
    const relative = path.relative(source, file).split(path.sep).join('/');
    if (!relative.startsWith('assets/')) continue;
    if (!text.includes('/' + relative)) continue;
    const bytes = fs.readFileSync(file);
    if (bytes.length <= 64 * 1024) continue;
    const hash = digest(bytes), ext = path.extname(relative);
    const objectKey = relative.slice(0, -ext.length) + '.' + hash.slice(0, 16) + ext;
    const url = assetBase + '/' + objectKey;
    substitutions.set('/' + relative, url);
    assets.push({ source: relative, objectKey, url, sha256: hash, bytes: bytes.length });
    write('cdn/' + objectKey, bytes);
  }
  for (const file of files) {
    const relative = path.relative(source, file).split(path.sep).join('/');
    if (relative === 'utils/config.js') continue;
    if (relative.startsWith('assets/') && (!text.includes('/' + relative) || substitutions.has('/' + relative))) continue;
    let bytes = fs.readFileSync(file);
    if (TEXT.has(path.extname(file))) {
      let sourceText = bytes.toString('utf8');
      for (const [previous, next] of substitutions) sourceText = sourceText.split(previous).join(next);
      bytes = Buffer.from(sourceText);
    }
    write('miniprogram/' + relative, bytes);
  }
  write('miniprogram/utils/config.js', "'use strict';\nmodule.exports = " + JSON.stringify({ apiBase, env: 'prod', tenantId: 1 }, null, 2) + ';\n');
  const project = JSON.parse(fs.readFileSync(path.join(root, 'project.config.json'), 'utf8'));
  project.appid = appid; project.miniprogramRoot = 'miniprogram/';
  project.setting = { ...project.setting, urlCheck: true, uploadWithSourceMap: false, ignoreUploadUnusedFiles: true };
  write('project.config.json', JSON.stringify(project, null, 2) + '\n');
  const violations = scanTree(destination);
  if (violations.length) throw Error('发布门禁失败：' + violations.map(item => item.path + ':' + item.ruleId).join(', '));
  const inventory = walk(path.join(destination, 'miniprogram')).map(file => {
    const bytes = fs.readFileSync(file);
    return { path: path.relative(destination, file).split(path.sep).join('/'), bytes: bytes.length, sha256: digest(bytes) };
  });
  const sourceBytes = inventory.reduce((sum, file) => sum + file.bytes, 0);
  // Conservative source budget with headroom; official compiled package size still requires DevTools.
  if (sourceBytes > 1.8 * 1024 * 1024) throw Error('主包源码超过 1.8 MiB 内部门禁，需继续减少资源或拆包');
  const manifest = { schemaVersion: 1, appid, apiBase, assetBase, env: 'prod', sourceBytes, compiledPackageVerified: false, cdnUploaded: false, assets, files: inventory };
  write('release-manifest.json', JSON.stringify(manifest, null, 2) + '\n');
  return { destination, manifest };
}
if (require.main === module) {
  try {
    const result = buildRelease({ apiBase: process.env.ZS_RELEASE_API_BASE, assetBase: process.env.ZS_RELEASE_ASSET_BASE, appid: process.env.ZS_RELEASE_APPID, outName: process.argv[2] });
    console.log(JSON.stringify({ directory: result.destination, sourceBytes: result.manifest.sourceBytes, cdnAssets: result.manifest.assets.length, pending: ['CDN 上传及可达性验证', '微信开发者工具编译包大小及真机验收'] }, null, 2));
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
module.exports = { buildRelease, origin };
