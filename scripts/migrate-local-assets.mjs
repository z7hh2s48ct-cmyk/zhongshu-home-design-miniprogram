#!/usr/bin/env node
import crypto from 'node:crypto';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import https from 'node:https';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

import { validateConfig } from './cos-storage-check.mjs';

const require = createRequire(import.meta.url);
const { buildAuthorization, cosEncode } = require('../前端程序/scripts/upload-cos.js');

export const MAX_OBJECT_BYTES = 20 * 1024 * 1024;
const DEFAULT_DEADLINE_MS = 60_000;
const ALLOWED_EXTENSIONS = new Set(['.csv', '.gif', '.jpeg', '.jpg', '.pdf', '.png', '.webp']);
const CONTENT_TYPES = {
  '.csv': 'text/csv',
  '.gif': 'image/gif',
  '.jpeg': 'image/jpeg',
  '.jpg': 'image/jpeg',
  '.pdf': 'application/pdf',
  '.png': 'image/png',
  '.webp': 'image/webp',
};

function fail(message) {
  throw new Error(message);
}

export function parseArguments(args) {
  let root = null;
  let manifest = null;
  let apply = false;
  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index];
    if (argument === '--apply') apply = true;
    else if (argument === '--root' || argument === '--manifest') {
      const value = args[index + 1];
      if (!value || value.startsWith('--')) fail(`${argument} 必须提供路径`);
      if (argument === '--root') root = path.resolve(value);
      else manifest = path.resolve(value);
      index += 1;
    } else if (argument.startsWith('--root=')) root = path.resolve(argument.slice(7));
    else if (argument.startsWith('--manifest=')) manifest = path.resolve(argument.slice(11));
    else fail(`未知参数：${argument}`);
  }
  if (!root) fail('必须提供 --root PATH');
  return { root, apply, manifest };
}

export async function loadMigrationEnvironment({
  dotenvPath = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '后端程序', '.env'),
  env = process.env,
} = {}) {
  const fromFile = {};
  try {
    const content = await fsp.readFile(dotenvPath, 'utf8');
    for (const line of content.split(/\r?\n/)) {
      const match = line.match(/^\s*([A-Z][A-Z0-9_]*)\s*=\s*(.*?)\s*$/);
      if (match) fromFile[match[1]] = match[2].replace(/^(['"])(.*)\1$/, '$2');
    }
  } catch (error) {
    if (error.code !== 'ENOENT') fail('读取后端 .env 失败');
  }
  return { ...fromFile, ...env };
}

function safeObjectKey(root, file) {
  const relative = path.relative(root, file);
  const parts = relative.split(path.sep);
  if (!relative || path.isAbsolute(relative) || parts.some(part => !part || part === '.' || part === '..')) {
    fail('资产路径越界或包含非法路径段');
  }
  return parts.join('/');
}

async function sha256File(file) {
  const digest = crypto.createHash('sha256');
  for await (const chunk of fs.createReadStream(file)) digest.update(chunk);
  return digest.digest('hex');
}

export async function scanAssets(rootPath) {
  const root = path.resolve(rootPath);
  let rootStat;
  try { rootStat = await fsp.lstat(root); }
  catch { fail('资产根目录不存在或不可读'); }
  if (!rootStat.isDirectory() || rootStat.isSymbolicLink()) fail('资产根目录必须是真实目录，不能是符号链接');

  const assets = [];
  async function visit(directory) {
    const entries = await fsp.readdir(directory, { withFileTypes: true });
    entries.sort((left, right) => left.name.localeCompare(right.name, 'en'));
    for (const entry of entries) {
      const file = path.join(directory, entry.name);
      const stat = await fsp.lstat(file);
      if (stat.isSymbolicLink()) fail(`拒绝符号链接：${safeObjectKey(root, file)}`);
      if (stat.isDirectory()) {
        await visit(file);
        continue;
      }
      if (!stat.isFile() || !ALLOWED_EXTENSIONS.has(path.extname(entry.name).toLowerCase())) continue;
      const key = safeObjectKey(root, file);
      if (stat.size > MAX_OBJECT_BYTES) fail(`${key} 超过单对象 20 MiB 限制`);
      assets.push({ key, size: stat.size, sha256: await sha256File(file), filePath: file, outcome: 'pending' });
    }
  }
  await visit(root);
  return assets;
}

function encodedPath(key) {
  return '/' + key.split('/').map(cosEncode).join('/');
}

function remaining(deadlineAt) {
  const value = deadlineAt - Date.now();
  if (value <= 0) fail('COS 迁移超时（整体 60 秒时限）');
  return value;
}

async function withDeadline(operation, deadlineAt, onTimeout = null) {
  const timeoutMs = remaining(deadlineAt);
  let timer;
  try {
    return await Promise.race([
      operation(timeoutMs),
      new Promise((_, reject) => {
        timer = setTimeout(() => {
          reject(new Error('COS 迁移超时（整体 60 秒时限）'));
          onTimeout?.();
        }, timeoutMs);
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
}

function signedTransport(credentials) {
  return ({ method, key, filePath, headers = {}, timeoutMs }) => new Promise((resolve, reject) => {
    const pathname = encodedPath(key);
    const now = Math.floor(Date.now() / 1000);
    const signTime = `${now};${now + 600}`;
    const signedHeaders = { host: credentials.requestHost, ...headers };
    const authorization = buildAuthorization({
      secretId: credentials.secretId,
      secretKey: credentials.secretKey,
      method: method.toLowerCase(),
      pathname,
      headers: signedHeaders,
      signTime,
    });
    const requestHeaders = { ...signedHeaders, authorization };
    if (filePath) requestHeaders['content-length'] = fs.statSync(filePath).size;
    const request = https.request({
      hostname: credentials.requestHost,
      method,
      path: pathname,
      headers: requestHeaders,
      signal: AbortSignal.timeout(timeoutMs),
    }, response => resolve({ statusCode: response.statusCode, headers: response.headers, body: response }));
    request.once('error', () => reject(new Error('COS 请求失败（网络或超时）')));
    request.setTimeout(timeoutMs, () => request.destroy(new Error('COS 请求失败（网络或超时）')));
    if (!filePath) {
      request.end();
      return;
    }
    const input = fs.createReadStream(filePath);
    input.once('error', () => request.destroy(new Error('读取本地资产失败')));
    input.pipe(request);
  });
}

async function responseSha256(response, expectedSize) {
  if (response.statusCode !== 200) fail(`COS GET 返回 ${response.statusCode}`);
  const digest = crypto.createHash('sha256');
  let size = 0;
  const body = response.body ?? Buffer.alloc(0);
  const chunks = Buffer.isBuffer(body) || body instanceof Uint8Array ? [body] : body;
  for await (const chunk of chunks) {
    size += chunk.length;
    if (size > MAX_OBJECT_BYTES) fail('COS GET 响应超过 20 MiB 限制');
    digest.update(chunk);
  }
  if (size !== expectedSize) return null;
  return digest.digest('hex');
}

async function drainResponse(response) {
  if (!response.body || Buffer.isBuffer(response.body) || response.body instanceof Uint8Array) return;
  let size = 0;
  for await (const chunk of response.body) {
    size += chunk.length;
    if (size > 65_536) fail('COS 响应超过 64 KiB 限制');
  }
}

async function consumeWithDeadline(response, deadlineAt, key, consumer) {
  try {
    return await withDeadline(
      () => consumer(response),
      deadlineAt,
      () => response.body?.destroy?.(),
    );
  } catch (error) {
    if (error.message === 'COS 迁移超时（整体 60 秒时限）') fail(`${key}：${error.message}`);
    if (/^COS (?:GET|响应)/.test(error.message)) fail(`${key}：${error.message}`);
    fail(`${key}：COS 响应读取失败（网络或超时）`);
  }
}

export async function migrateAssets({
  root,
  apply = false,
  env = null,
  transport = null,
  deadlineMs = DEFAULT_DEADLINE_MS,
} = {}) {
  if (!root) fail('必须提供资产根目录');
  const deadlineAt = Date.now() + deadlineMs;
  const assets = apply
    ? await withDeadline(() => scanAssets(root), deadlineAt)
    : await scanAssets(root);
  if (!apply) return manifestResult('dry-run', assets);

  const effectiveEnv = env ?? await loadMigrationEnvironment();
  const credentials = validateConfig(effectiveEnv);
  const request = transport || signedTransport(credentials);

  for (const asset of assets) {
    const call = async (method, options = {}) => {
      try {
        return await withDeadline(timeoutMs => request({
          method,
          key: asset.key,
          filePath: asset.filePath,
          timeoutMs,
          ...options,
        }), deadlineAt);
      } catch (error) {
        if (error.message === 'COS 迁移超时（整体 60 秒时限）') fail(`${asset.key}：${error.message}`);
        fail(`${asset.key}：COS ${method} 请求失败（网络或超时）`);
      }
    };
    const head = await call('HEAD', { filePath: null });
    await consumeWithDeadline(head, deadlineAt, asset.key, drainResponse);
    if (head.statusCode === 200) {
      const get = await call('GET', { filePath: null });
      const remoteHash = await consumeWithDeadline(
        get,
        deadlineAt,
        asset.key,
        response => responseSha256(response, asset.size),
      );
      if (remoteHash !== asset.sha256) fail(`${asset.key} 已存在但摘要不同，拒绝覆盖`);
      asset.outcome = 'skipped';
      continue;
    }
    if (head.statusCode !== 404) fail(`${asset.key} 的 COS HEAD 返回 ${head.statusCode}`);

    const put = await call('PUT', {
      headers: {
        'content-type': CONTENT_TYPES[path.extname(asset.key).toLowerCase()],
        'x-cos-forbid-overwrite': 'true',
      },
    });
    await consumeWithDeadline(put, deadlineAt, asset.key, drainResponse);
    if (put.statusCode !== 200) fail(`${asset.key} 的 COS PUT 返回 ${put.statusCode}`);
    const get = await call('GET', { filePath: null });
    const uploadedHash = await consumeWithDeadline(
      get,
      deadlineAt,
      asset.key,
      response => responseSha256(response, asset.size),
    );
    if (uploadedHash !== asset.sha256) fail(`${asset.key} 上传后摘要校验失败`);
    asset.outcome = 'uploaded';
  }
  return manifestResult('apply', assets);
}

function manifestResult(mode, assets) {
  return {
    version: 1,
    mode,
    assets: assets.map(({ key, size, sha256, outcome }) => ({ key, size, sha256, outcome })),
  };
}

async function main() {
  const options = parseArguments(process.argv.slice(2));
  const result = await migrateAssets(options);
  const output = JSON.stringify(result, null, 2) + '\n';
  if (options.manifest) await fsp.writeFile(options.manifest, output, { flag: 'wx' });
  process.stdout.write(output);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  });
}
