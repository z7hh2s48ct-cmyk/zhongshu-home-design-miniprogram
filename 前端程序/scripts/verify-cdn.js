#!/usr/bin/env node
'use strict';

/**
 * CDN 公开可达性校验（迁移前置门禁）。
 *
 * 为什么需要它：`upload-cos.js` 用的是**带签名的 HEAD**，只能证明「对象确实进了桶」，
 * 不能证明「终端用户能匿名取到」。桶若为私有读、或 CDN 域名未绑定/未回源，签名校验照样
 * 全绿，但小程序里图片会全部 404。因此**任何删除本地素材的动作之前**，必须先通过本脚本
 * 的匿名公开校验：真实 GET assetBase 地址，逐一比对字节数与 sha256，并识别图片魔数，
 * 防止「HTTP 200 但返回的是错误页/占位图」这类假成功。
 *
 * 用法：
 *   node scripts/verify-cdn.js <release 名称>
 *   例如 node scripts/verify-cdn.js 20260928-01
 *
 * 契约：
 *  1. 必须 `cdnUploaded === true`（未经 upload-cos.js 上传的产物拒绝校验，避免自欺）；
 *  2. 只用匿名 GET，不携带任何凭据——校验的就是「终端用户视角」；
 *  3. 逐对象比对：HTTP 200 + 字节数 + sha256 + 图片魔数 + Content-Type；
 *  4. 结果落盘 `release/<name>/cdn-verification.json`，内含清单指纹（manifestFingerprint），
 *     供删除守卫比对——清单一旦变化（重新构建/换对象），旧报告自动失效；
 *  5. 任一对象不通过则整体退出码非 0，且报告 `allReachable=false`。
 */

const crypto = require('node:crypto');
const fs = require('node:fs');
const https = require('node:https');
const path = require('node:path');

const FRONTEND_ROOT = path.resolve(__dirname, '..');
const REPORT_NAME = 'cdn-verification.json';

/** 扩展名 → 期望的图片 MIME。非图片扩展名不在此表内，只做可达性/字节校验。 */
const IMAGE_TYPES = {
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
  '.gif': 'image/gif',
  '.svg': 'image/svg+xml',
};

function fail(message) {
  throw new Error(message);
}

/**
 * 清单指纹：把「对象集」的性质（键 / 内容哈希 / 字节数）固化成可比较摘要。
 * 删除守卫用它拒绝陈旧报告，避免「先校验通过、后又重新构建」导致的错删。
 */
function manifestFingerprint(assets) {
  const rows = (assets || [])
    .map((asset) => `${asset.objectKey}\t${asset.sha256}\t${asset.bytes}`)
    .sort();
  return crypto.createHash('sha256').update(rows.join('\n')).digest('hex');
}

/**
 * 图片魔数识别：用于识破「返回 200 但内容是 HTML 错误页 / 占位图 / 登录跳转页」。
 * 只按文件头判断，不解析整图，足够作为「是否为该格式」的最低门槛。
 */
function sniffImageType(buffer) {
  if (!buffer || buffer.length < 3) return null;
  if (buffer.length >= 8 && buffer[0] === 0x89 && buffer[1] === 0x50 && buffer[2] === 0x4e && buffer[3] === 0x47) {
    return 'image/png';
  }
  if (buffer[0] === 0xff && buffer[1] === 0xd8 && buffer[2] === 0xff) return 'image/jpeg';
  if (buffer.length >= 6) {
    const six = buffer.toString('ascii', 0, 6);
    if (six === 'GIF87a' || six === 'GIF89a') return 'image/gif';
  }
  if (buffer.length >= 12 && buffer.toString('ascii', 0, 4) === 'RIFF' && buffer.toString('ascii', 8, 12) === 'WEBP') {
    return 'image/webp';
  }
  const head = buffer.toString('utf8', 0, Math.min(buffer.length, 256)).replace(/^\uFEFF/, '').trimStart();
  if (head.startsWith('<?xml') || head.startsWith('<svg')) return 'image/svg+xml';
  return null;
}

/**
 * 匿名 GET，流式计算 sha256，避免把大图整体读进内存。
 * 显式声明 `accept-encoding: identity`——否则 CDN 可能回 gzip/br，字节比对与哈希都会失真。
 */
function fetchObject({ url, maxRedirects = 3, timeoutMs = 30000 }) {
  return new Promise((resolve, reject) => {
    const target = new URL(url);
    const handle = https.get(
      {
        hostname: target.hostname,
        path: target.pathname + target.search,
        headers: { accept: '*/*', 'accept-encoding': 'identity' },
      },
      (response) => {
        const status = response.statusCode;
        if (status >= 300 && status < 400 && response.headers.location && maxRedirects > 0) {
          response.resume();
          const next = new URL(response.headers.location, url).toString();
          resolve(fetchObject({ url: next, maxRedirects: maxRedirects - 1, timeoutMs }));
          return;
        }
        const hash = crypto.createHash('sha256');
        let size = 0;
        let head = Buffer.alloc(0);
        response.on('data', (chunk) => {
          hash.update(chunk);
          size += chunk.length;
          if (head.length < 64) head = Buffer.concat([head, chunk.slice(0, 64 - head.length)]);
        });
        response.on('end', () =>
          resolve({ statusCode: status, headers: response.headers, size, sha256: hash.digest('hex'), head }),
        );
      },
    );
    handle.on('error', reject);
    handle.setTimeout(timeoutMs, () => handle.destroy(new Error(`CDN 请求超时（${timeoutMs / 1000}s）`)));
  });
}

function readManifest(releaseDirectory) {
  const manifestPath = path.join(releaseDirectory, 'release-manifest.json');
  if (!fs.existsSync(manifestPath)) {
    fail(`未找到发布清单：${manifestPath}（请先执行 node scripts/build-release.js <name>）`);
  }
  return { manifestPath, manifest: JSON.parse(fs.readFileSync(manifestPath, 'utf8')) };
}

async function verifyCdn({
  releaseName,
  root = FRONTEND_ROOT,
  fetchImpl = fetchObject,
  now = () => new Date().toISOString(),
} = {}) {
  if (!releaseName || !/^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$/.test(releaseName)) {
    fail('必须提供合法的发布名称（字母、数字、下划线、短横线）');
  }
  const releaseDirectory = path.join(root, 'release', releaseName);
  const { manifest } = readManifest(releaseDirectory);

  if (manifest.cdnUploaded !== true) {
    fail(
      '清单 cdnUploaded 非 true：素材尚未上传到 CDN，拒绝以「已迁移」口径校验。' +
        '请先执行 node scripts/upload-cos.js <name>。',
    );
  }
  const assets = Array.isArray(manifest.assets) ? manifest.assets : [];
  if (!assets.length) fail('清单未登记任何 CDN 素材，无可校验对象');

  const results = [];
  const failures = [];

  for (const asset of assets) {
    const url = `${manifest.assetBase}/${asset.objectKey}`;
    const expectedExt = path.extname(asset.objectKey).toLowerCase();
    const expectedType = IMAGE_TYPES[expectedExt] || null;
    try {
      const response = await fetchImpl({ url });
      const problems = [];
      if (response.statusCode !== 200) {
        problems.push(`HTTP ${response.statusCode}`);
      } else {
        if (response.size !== asset.bytes) problems.push(`字节数 ${response.size} ≠ 清单 ${asset.bytes}`);
        if (response.sha256 !== asset.sha256) problems.push('sha256 与清单不一致（内容被改动或截断）');
        const sniffed = sniffImageType(response.head);
        if (expectedType && !sniffed) problems.push('响应体不是可识别的图片（疑似错误页/占位内容）');
        else if (expectedType && sniffed !== expectedType) {
          problems.push(`图片类型 ${sniffed} 与扩展名推导的 ${expectedType} 不一致`);
        }
        const declared = String(response.headers['content-type'] || '')
          .split(';')[0]
          .trim()
          .toLowerCase();
        if (expectedType && declared && declared !== 'application/octet-stream' && declared !== expectedType) {
          problems.push(`Content-Type ${declared} ≠ ${expectedType}`);
        }
      }
      results.push({
        objectKey: asset.objectKey,
        url,
        statusCode: response.statusCode,
        bytes: response.size,
        expectedBytes: asset.bytes,
        sha256: response.sha256,
        ok: problems.length === 0,
        problems,
      });
      if (problems.length) failures.push(`${asset.objectKey}：${problems.join('；')}`);
    } catch (error) {
      results.push({ objectKey: asset.objectKey, url, ok: false, problems: [error.message] });
      failures.push(`${asset.objectKey}：${error.message}`);
    }
  }

  const report = {
    schemaVersion: 1,
    verifiedAt: now(),
    releaseName,
    assetBase: manifest.assetBase,
    manifestFingerprint: manifestFingerprint(assets),
    assetCount: assets.length,
    reachableCount: results.filter((item) => item.ok).length,
    allReachable: failures.length === 0,
    results,
  };
  return {
    releaseDirectory,
    reportPath: path.join(releaseDirectory, REPORT_NAME),
    report,
    failures,
  };
}

if (require.main === module) {
  const releaseName = process.argv.slice(2).find((arg) => !arg.startsWith('-'));
  verifyCdn({ releaseName })
    .then(async (result) => {
      await require('node:fs/promises').writeFile(
        result.reportPath,
        JSON.stringify(result.report, null, 2) + '\n',
      );
      const relative = path.relative(process.cwd(), result.reportPath);
      if (result.report.allReachable) {
        console.log(
          `[verify-cdn] ${result.report.reachableCount}/${result.report.assetCount} 个素材匿名公开可达，` +
            `字节数与 sha256 全部一致 → ${relative}`,
        );
        return;
      }
      console.error(
        `[verify-cdn] ${result.failures.length}/${result.report.assetCount} 个素材未通过公开校验：\n - ` +
          result.failures.join('\n - ') +
          `\n报告：${relative}`,
      );
      process.exitCode = 1;
    })
    .catch((error) => {
      console.error(error.message);
      process.exitCode = 1;
    });
}

module.exports = { IMAGE_TYPES, REPORT_NAME, fetchObject, manifestFingerprint, sniffImageType, verifyCdn };
