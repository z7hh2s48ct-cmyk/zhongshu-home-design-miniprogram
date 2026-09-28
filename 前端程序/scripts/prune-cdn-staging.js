#!/usr/bin/env node
'use strict';

/**
 * 删除已上传并**公开校验通过**的 CDN 暂存副本（`release/<name>/cdn/**`）。
 *
 * 定位：`build:release` 会把待传素材落到 `release/<name>/cdn/**`；`upload:cos` 上传后这些文件
 * 就是**纯重复副本**，可安全清理。本脚本只做这一件事，**绝不触碰 `miniprogram/assets/**`**
 * ——那里是构建的输入源图，删掉会导致重新构建时发布副本留下死链。
 *
 * 为什么需要它而不是手删：删除是不可逆动作，必须**先证明线上真的能取到**。本脚本内置三道闸，
 * 任一不满足即拒绝执行：
 *   ① 清单 `cdnUploaded === true`（确认已上传）；
 *   ② `cdn-verification.json` 存在且 `allReachable === true`（确认终端用户匿名可取、内容一致）；
 *   ③ 报告的 `manifestFingerprint` 与当前清单一致（确认报告不是旧构建留下的陈旧结论）。
 * 另外：暂存目录若存在清单未登记的文件，一律拒绝删除（避免误伤未知内容）。
 *
 * 用法：
 *   node scripts/prune-cdn-staging.js <release 名称>          # 预演，只列清单，不删
 *   node scripts/prune-cdn-staging.js <release 名称> --yes    # 三闸通过后真正删除
 */

const fs = require('node:fs');
const path = require('node:path');

const { REPORT_NAME, manifestFingerprint } = require('./verify-cdn');

const FRONTEND_ROOT = path.resolve(__dirname, '..');
const RECORD_NAME = 'cdn-prune-record.json';
const RELEASE_NAME_PATTERN = /^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$/;

function fail(message) {
  throw new Error(message);
}

/** 断言目标位于允许的父目录之内，杜绝路径穿越导致误删。 */
function ensureInside(parent, child) {
  const relative = path.relative(path.resolve(parent), path.resolve(child));
  if (relative === '' || relative.startsWith('..') || path.isAbsolute(relative)) {
    fail(`拒绝操作发布目录之外的路径：${child}`);
  }
}

function walkFiles(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const target = path.join(directory, entry.name);
    if (entry.isSymbolicLink()) fail(`暂存目录禁止符号链接，拒绝删除：${target}`);
    return entry.isDirectory() ? walkFiles(target) : [target];
  });
}

/** 自底向上清理空目录（含暂存根目录本身），保留非空目录。 */
function removeEmptyDirectories(directory) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    if (entry.isDirectory()) removeEmptyDirectories(path.join(directory, entry.name));
  }
  if (fs.readdirSync(directory).length === 0) fs.rmdirSync(directory);
}

async function pruneCdnStaging({
  releaseName,
  root = FRONTEND_ROOT,
  yes = false,
  keepRecord = true,
  now = () => new Date().toISOString(),
} = {}) {
  if (!releaseName || !RELEASE_NAME_PATTERN.test(releaseName)) {
    fail('必须提供合法的发布名称（字母、数字、下划线、短横线）');
  }
  const releaseDirectory = path.join(root, 'release', releaseName);
  const manifestPath = path.join(releaseDirectory, 'release-manifest.json');
  if (!fs.existsSync(manifestPath)) {
    fail(`未找到发布清单：${manifestPath}（请先执行 node scripts/build-release.js <name>）`);
  }
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));

  if (manifest.cdnUploaded !== true) {
    fail('清单 cdnUploaded 非 true：素材尚未上传，暂存副本还不能删（请先执行 node scripts/upload-cos.js <name>）');
  }

  const reportPath = path.join(releaseDirectory, REPORT_NAME);
  if (!fs.existsSync(reportPath)) {
    fail(
      `缺少 CDN 公开校验报告：${reportPath}。` +
        `删除前必须先执行 node scripts/verify-cdn.js ${releaseName}，确认线上图片全部正常显示。`,
    );
  }
  const report = JSON.parse(fs.readFileSync(reportPath, 'utf8'));
  if (report.allReachable !== true) {
    fail(`校验报告 allReachable 非 true：仍有素材未公开可达，拒绝删除暂存副本（删了将无法重新上传）。`);
  }

  const assets = Array.isArray(manifest.assets) ? manifest.assets : [];
  const fingerprint = manifestFingerprint(assets);
  if (report.manifestFingerprint !== fingerprint) {
    fail(
      '校验报告与当前清单不一致（对象集已变化）：该报告可能来自上一次构建，结论已失效，拒绝删除。' +
        `请重新执行 node scripts/verify-cdn.js ${releaseName}。`,
    );
  }

  const releaseRoot = path.join(root, 'release');
  const cdnDirectory = path.join(releaseDirectory, 'cdn');
  ensureInside(releaseRoot, cdnDirectory);

  if (!fs.existsSync(cdnDirectory)) {
    return {
      releaseName,
      releaseDirectory,
      cdnDirectory,
      dryRun: !yes,
      alreadyPruned: true,
      targets: [],
      deleted: [],
      bytes: 0,
      recordPath: null,
    };
  }

  const files = walkFiles(cdnDirectory);
  const expected = new Set(
    assets.map((asset) => path.join(cdnDirectory, ...String(asset.objectKey).split('/'))),
  );
  const unexpected = files.filter((file) => !expected.has(file));
  if (unexpected.length) {
    fail(
      `暂存目录存在 ${unexpected.length} 个清单未登记的文件，拒绝删除以免误伤：\n - ` +
        unexpected.map((file) => path.relative(cdnDirectory, file).split(path.sep).join('/')).join('\n - '),
    );
  }

  const targets = files.map((file) => ({
    file,
    relative: path.relative(releaseDirectory, file).split(path.sep).join('/'),
    bytes: fs.statSync(file).size,
  }));
  const totalBytes = targets.reduce((sum, item) => sum + item.bytes, 0);

  if (!yes) {
    return {
      releaseName,
      releaseDirectory,
      cdnDirectory,
      dryRun: true,
      alreadyPruned: false,
      targets,
      deleted: [],
      bytes: totalBytes,
      recordPath: null,
    };
  }

  for (const target of targets) fs.unlinkSync(target.file);
  removeEmptyDirectories(cdnDirectory);

  let recordPath = null;
  if (keepRecord) {
    recordPath = path.join(releaseDirectory, RECORD_NAME);
    fs.writeFileSync(
      recordPath,
      JSON.stringify(
        {
          schemaVersion: 1,
          prunedAt: now(),
          releaseName,
          scope: 'release/<name>/cdn/**',
          manifestFingerprint: fingerprint,
          verifiedAt: report.verifiedAt,
          assetBase: manifest.assetBase,
          fileCount: targets.length,
          bytes: totalBytes,
          files: targets.map((item) => item.relative),
        },
        null,
        2,
      ) + '\n',
    );
  }

  return {
    releaseName,
    releaseDirectory,
    cdnDirectory,
    dryRun: false,
    alreadyPruned: false,
    targets,
    deleted: targets.map((item) => item.relative),
    bytes: totalBytes,
    recordPath,
  };
}

if (require.main === module) {
  const args = process.argv.slice(2);
  const yes = args.includes('--yes');
  const releaseName = args.find((arg) => !arg.startsWith('-'));
  pruneCdnStaging({ releaseName, yes })
    .then((result) => {
      if (result.alreadyPruned) {
        console.log(`[prune] 暂存目录已不存在（此前已清理）：${path.relative(process.cwd(), result.releaseDirectory)}/cdn`);
        return;
      }
      if (result.dryRun) {
        console.warn(
          `[prune][预演] 将删除 ${result.targets.length} 个暂存副本（${result.bytes} B），` +
            `仅限 release/${releaseName}/cdn/**，不触碰 miniprogram/assets/**。\n` +
            result.targets.map((item) => `  ${String(item.bytes).padStart(9)} B  ${item.relative}`).join('\n') +
            `\n确认无误后加 --yes 执行。`,
        );
        return;
      }
      console.log(
        `[prune] 已删除 ${result.deleted.length} 个暂存副本（${result.bytes} B）。` +
          (result.recordPath ? `审计记录：${path.relative(process.cwd(), result.recordPath)}` : ''),
      );
    })
    .catch((error) => {
      console.error(error.message);
      process.exitCode = 1;
    });
}

module.exports = { RECORD_NAME, pruneCdnStaging };
