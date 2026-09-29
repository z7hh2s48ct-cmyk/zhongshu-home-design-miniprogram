#!/usr/bin/env node
'use strict';

/**
 * 将发布构建的 CDN 素材上传到腾讯云 COS，并回写发布清单的可达性结论。
 *
 * 背景：`build-release.js` 已能把 >64KiB 且被引用的素材改写为 `assetBase/<objectKey>` 并落到
 * `release/<name>/cdn/**`，但清单中 `cdnUploaded` 恒为 false——即产物引用的地址尚未真正存在，
 * 直接发布会导致图片全部 404。本脚本专门闭环这一步。
 *
 * 用法：
 *   node scripts/upload-cos.js <release 名称> [--dry-run]
 *   例如 node scripts/upload-cos.js 20260928-01
 *
 * 凭据（仅从环境变量读取，绝不入库、绝不打印）：
 *   ZS_COS_SECRET_ID / ZS_COS_SECRET_KEY / ZS_COS_BUCKET / ZS_COS_REGION
 *   ZS_COS_ENDPOINT（可选；默认 <bucket>.cos.<region>.myqcloud.com）
 *
 * 契约：
 *  1. 素材以内容哈希命名（objectKey 内含 sha256 前 16 位），故可设置一年期 immutable 长缓存；
 *  2. 每个对象上传后必须通过 HEAD 校验可达性（状态码 + Content-Length 与源文件一致），
 *     任一对象不可达则整体失败并列出失败清单，绝不写 cdnUploaded=true；
 *  3. `--dry-run` 无需凭据，仅输出待传清单，便于 CI 与人工核对；
 *  4. 缺任一凭据时立刻报错退出，不发起任何网络请求。
 */

const crypto = require('node:crypto');
const fs = require('node:fs');
const fsp = require('node:fs/promises');
const https = require('node:https');
const path = require('node:path');

const FRONTEND_ROOT = path.resolve(__dirname, '..');

const REQUIRED_ENV = ['ZS_COS_SECRET_ID', 'ZS_COS_SECRET_KEY', 'ZS_COS_BUCKET', 'ZS_COS_REGION'];

/** 内容哈希寻址 → 可安全长缓存。 */
const CACHE_CONTROL = 'public, max-age=31536000, immutable';

function fail(message) {
  throw new Error(message);
}

function readManifest(releaseDirectory) {
  const manifestPath = path.join(releaseDirectory, 'release-manifest.json');
  if (!fs.existsSync(manifestPath)) {
    fail(`未找到发布清单：${manifestPath}（请先执行 node scripts/build-release.js <name>）`);
  }
  return { manifestPath, manifest: JSON.parse(fs.readFileSync(manifestPath, 'utf8')) };
}

function collectUploads(releaseDirectory, manifest) {
  const assets = Array.isArray(manifest.assets) ? manifest.assets : [];
  return assets.map((asset) => {
    const localPath = path.join(releaseDirectory, 'cdn', asset.objectKey);
    if (!fs.existsSync(localPath)) {
      fail(`清单登记的对象缺少本地文件：cdn/${asset.objectKey}（发布目录可能被清理，请重新构建）`);
    }
    const bytes = fs.readFileSync(localPath);
    if (bytes.length !== asset.bytes) {
      fail(`对象 ${asset.objectKey} 字节数与清单不一致：本地 ${bytes.length}，清单 ${asset.bytes}`);
    }
    return { ...asset, localPath, bytes };
  });
}

/** COS 要求的 URL 编码：encodeURIComponent 基础上补上 COS 特有的安全字符集。 */
function cosEncode(value) {
  return encodeURIComponent(String(value)).replace(/[!'()*]/g, (char) =>
    '%' + char.charCodeAt(0).toString(16).toUpperCase(),
  );
}

/**
 * 生成 COS v5 签名（q-sign-algorithm=sha1）。
 * 算法：SignKey = HMAC-SHA1(SecretKey, keyTime)
 *       HttpString = method\npath\nparams\nheaders\n
 *       StringToSign = sha1\nsignTime\nSHA1(HttpString)\n
 *       Signature = HMAC-SHA1(SignKey, StringToSign)
 */
function buildAuthorization({ secretId, secretKey, method, pathname, headers, signTime }) {
  const headerKeys = Object.keys(headers)
    .map((key) => key.toLowerCase())
    .sort();
  const signedHeaders = headerKeys.map((key) => `${key}=${cosEncode(headers[key])}`).join('&');
  const headerList = headerKeys.join(';');

  const httpString = [
    method.toLowerCase(),
    pathname,
    '',
    signedHeaders,
    '',
  ].join('\n');
  const httpStringSha1 = crypto.createHash('sha1').update(httpString).digest('hex');

  const signKey = crypto.createHmac('sha1', secretKey).update(signTime).digest('hex');
  const stringToSign = ['sha1', signTime, httpStringSha1, ''].join('\n');
  const signature = crypto.createHmac('sha1', signKey).update(stringToSign).digest('hex');

  return [
    'q-sign-algorithm=sha1',
    `q-ak=${secretId}`,
    `q-sign-time=${signTime}`,
    `q-key-time=${signTime}`,
    `q-header-list=${headerList}`,
    'q-url-param-list=',
    `q-signature=${signature}`,
  ].join('&');
}

function request({ hostname, method, pathname, headers, body }) {
  return new Promise((resolve, reject) => {
    const requestHandle = https.request({ hostname, method, path: pathname, headers }, (response) => {
      const chunks = [];
      response.on('data', (chunk) => chunks.push(chunk));
      response.on('end', () =>
        resolve({
          statusCode: response.statusCode,
          headers: response.headers,
          body: Buffer.concat(chunks).toString('utf8'),
        }),
      );
    });
    requestHandle.on('error', reject);
    requestHandle.setTimeout(30000, () => requestHandle.destroy(new Error('COS 请求超时（30s）')));
    if (body) requestHandle.write(body);
    requestHandle.end();
  });
}

async function putObject({ endpoint, credentials, item }) {
  const pathname = '/' + item.objectKey;
  const now = Math.floor(Date.now() / 1000);
  const signTime = `${now};${now + 600}`;
  const contentType = item.contentType || guessContentType(item.objectKey);
  const headers = {
    'content-type': contentType,
    'cache-control': CACHE_CONTROL,
    host: endpoint,
  };
  headers.authorization = buildAuthorization({
    secretId: credentials.secretId,
    secretKey: credentials.secretKey,
    method: 'put',
    pathname,
    headers,
    signTime,
  });

  return request({
    hostname: endpoint,
    method: 'PUT',
    pathname,
    headers: { ...headers, 'content-length': item.bytes.length },
    body: item.bytes,
  });
}

async function headObject({ endpoint, credentials, item }) {
  const pathname = '/' + item.objectKey;
  const now = Math.floor(Date.now() / 1000);
  const signTime = `${now};${now + 600}`;
  const headers = { host: endpoint };
  headers.authorization = buildAuthorization({
    secretId: credentials.secretId,
    secretKey: credentials.secretKey,
    method: 'head',
    pathname,
    headers,
    signTime,
  });
  return request({ hostname: endpoint, method: 'HEAD', pathname, headers });
}

function guessContentType(objectKey) {
  const extension = path.extname(objectKey).toLowerCase();
  return (
    {
      '.png': 'image/png',
      '.jpg': 'image/jpeg',
      '.jpeg': 'image/jpeg',
      '.webp': 'image/webp',
      '.svg': 'image/svg+xml',
      '.gif': 'image/gif',
      '.json': 'application/json',
    }[extension] || 'application/octet-stream'
  );
}

function readCredentials(env) {
  const missing = REQUIRED_ENV.filter((name) => !env[name]);
  if (missing.length) {
    fail(
      `缺少 COS 凭据环境变量：${missing.join('、')}。` +
        '凭据只从环境变量读取（严禁写入仓库）；如仅需核对清单请使用 --dry-run。',
    );
  }
  return {
    secretId: env.ZS_COS_SECRET_ID,
    secretKey: env.ZS_COS_SECRET_KEY,
    bucket: env.ZS_COS_BUCKET,
    region: env.ZS_COS_REGION,
    endpoint: env.ZS_COS_ENDPOINT || `${env.ZS_COS_BUCKET}.cos.${env.ZS_COS_REGION}.myqcloud.com`,
  };
}

async function uploadRelease({
  releaseName,
  dryRun = false,
  env = process.env,
  root = FRONTEND_ROOT,
  transport = null,
} = {}) {
  if (!releaseName || !/^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$/.test(releaseName)) {
    fail('必须提供合法的发布名称（字母、数字、下划线、短横线）');
  }
  const releaseDirectory = path.join(root, 'release', releaseName);
  const { manifestPath, manifest } = readManifest(releaseDirectory);
  const uploads = collectUploads(releaseDirectory, manifest);

  if (dryRun) {
    return {
      dryRun: true,
      releaseDirectory,
      manifestPath,
      assetBase: manifest.assetBase,
      pending: uploads.map((item) => ({ objectKey: item.objectKey, bytes: item.bytes.length, sha256: item.sha256 })),
    };
  }

  const credentials = readCredentials(env);
  // 传输层可注入：便于以桩覆盖上传/校验/失败聚合逻辑，不必触网。
  const put = transport?.putObject || putObject;
  const head = transport?.headObject || headObject;
  const uploaded = [];
  const failures = [];

  for (const item of uploads) {
    try {
      const putResult = await put({ endpoint: credentials.endpoint, credentials, item });
      if (putResult.statusCode !== 200) {
        failures.push(`${item.objectKey}：上传返回 ${putResult.statusCode} ${String(putResult.body).slice(0, 200)}`);
        continue;
      }
      const headResult = await head({ endpoint: credentials.endpoint, credentials, item });
      const length = Number(headResult.headers['content-length']);
      if (headResult.statusCode !== 200) {
        failures.push(`${item.objectKey}：可达性校验返回 ${headResult.statusCode}`);
        continue;
      }
      if (length !== item.bytes.length) {
        failures.push(`${item.objectKey}：远端字节数 ${length} 与本地 ${item.bytes.length} 不一致`);
        continue;
      }
      uploaded.push({
        objectKey: item.objectKey,
        url: `${manifest.assetBase}/${item.objectKey}`,
        bytes: item.bytes.length,
        sha256: item.sha256,
        etag: String(headResult.headers.etag || '').replace(/"/g, '') || null,
      });
    } catch (error) {
      failures.push(`${item.objectKey}：${error.message}`);
    }
  }

  if (failures.length) {
    fail(`COS 上传未全部闭环，共 ${failures.length} 项失败：\n - ${failures.join('\n - ')}`);
  }

  const nextManifest = {
    ...manifest,
    cdnUploaded: true,
    cdnUploadedAt: new Date().toISOString(),
    cdnBucket: credentials.bucket,
    cdnRegion: credentials.region,
    assets: (manifest.assets || []).map((asset) => {
      const record = uploaded.find((entry) => entry.objectKey === asset.objectKey);
      return record ? { ...asset, uploaded: { url: record.url, etag: record.etag, bytes: record.bytes } } : asset;
    }),
  };
  await fsp.writeFile(manifestPath, JSON.stringify(nextManifest, null, 2) + '\n');

  return { dryRun: false, releaseDirectory, manifestPath, uploaded, cdnUploaded: true };
}

if (require.main === module) {
  const args = process.argv.slice(2);
  const dryRun = args.includes('--dry-run');
  const releaseName = args.find((arg) => !arg.startsWith('-'));
  uploadRelease({ releaseName, dryRun })
    .then((result) => {
      if (result.dryRun) {
        console.log(
          `[dry-run] 待上传 ${result.pending.length} 个对象 → ${result.assetBase}\n` +
            result.pending.map((item) => `  ${item.bytes.toString().padStart(9)} B  ${item.objectKey}`).join('\n'),
        );
        return;
      }
      console.log(
        `已上传并校验 ${result.uploaded.length} 个对象，cdnUploaded=true 已回写：${path.relative(process.cwd(), result.manifestPath)}`,
      );
    })
    .catch((error) => {
      console.error(error.message);
      process.exitCode = 1;
    });
}

module.exports = {
  CACHE_CONTROL,
  buildAuthorization,
  collectUploads,
  cosEncode,
  guessContentType,
  readCredentials,
  uploadRelease,
};
