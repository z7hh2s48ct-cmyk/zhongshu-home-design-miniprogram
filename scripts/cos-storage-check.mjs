#!/usr/bin/env node
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { buildAuthorization } = require('../前端程序/scripts/upload-cos.js');
const REQUIRED = ['ZS_COS_SECRET_ID', 'ZS_COS_SECRET_KEY', 'ZS_COS_BUCKET', 'ZS_COS_REGION'];

function environmentWithBackendDotenv(env) {
  const merged = { ...env };
  const file = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '后端程序', '.env');
  if (!fs.existsSync(file)) return merged;
  let content;
  try { content = fs.readFileSync(file, 'utf8'); }
  catch { fail('读取后端 .env 失败'); }
  for (const line of content.split(/\r?\n/)) {
    const match = line.match(/^\s*([A-Z][A-Z0-9_]*)\s*=\s*(.*?)\s*$/);
    if (match && merged[match[1]] == null) merged[match[1]] = match[2].replace(/^['"]|['"]$/g, '');
  }
  return merged;
}

function fail(message) { throw new Error(message); }

export function validateConfig(env = process.env) {
  const missing = REQUIRED.filter(key => !String(env[key] ?? '').trim());
  if (missing.length) fail(`缺少 COS 配置：${missing.join('、')}`);
  const bucket = String(env.ZS_COS_BUCKET).trim();
  const region = String(env.ZS_COS_REGION).trim();
  const provider = env.ZHONGSHU_DESIGN_ASSET_STORAGE_PROVIDER;
  if (provider !== 'cos') fail('对象存储 provider 必须显式配置 ZHONGSHU_DESIGN_ASSET_STORAGE_PROVIDER=cos');
  if (!/^[a-z0-9][a-z0-9-]*-\d+$/.test(bucket)) fail('COS bucket 格式错误');
  if (!/^[a-z]+-[a-z]+(?:-\d+)?$/.test(region)) fail('COS region 格式错误');
  if (String(env.ZS_COS_PATH_STYLE_ACCESS || '').toLowerCase() === 'true') fail('COS path-style 必须关闭');
  const raw = String(env.ZS_COS_ENDPOINT || '').trim();
  if (!raw) fail('缺少 COS 配置：ZS_COS_ENDPOINT');
  let url;
  try { url = new URL(raw); } catch { fail('COS endpoint 不是有效 URL'); }
  if (url.protocol !== 'https:') fail('COS endpoint 必须使用 HTTPS');
  if (url.username || url.password || url.port || url.search || url.hash || url.pathname !== '/') fail('COS endpoint 只允许 HTTPS 主机名');
  const expected = `cos.${region}.myqcloud.com`;
  if (url.hostname !== expected) fail(`COS endpoint 必须是区域 endpoint ${expected}`);
  return { secretId: String(env.ZS_COS_SECRET_ID), secretKey: String(env.ZS_COS_SECRET_KEY), bucket, region, endpoint: url.hostname, requestHost: `${bucket}.${url.hostname}` };
}

export function diagnosticObjectKey(now = Date.now()) {
  return `diagnostics/cos-check/${now}-${crypto.randomUUID()}.txt`;
}

async function request({ endpoint, method, key, headers = {}, body, timeoutMs = 10000 }) {
  try {
    const response = await fetch(`https://${endpoint}/${key}`, {
      method, headers, body, redirect: 'error', signal: AbortSignal.timeout(timeoutMs)
    });
    const chunks = [];
    let total = 0;
    if (response.body) for await (const chunk of response.body) {
      total += chunk.length;
      if (total > 65536) throw Error('响应超限');
      chunks.push(chunk);
    }
    return { statusCode: response.status, headers: Object.fromEntries(response.headers), body: Buffer.concat(chunks) };
  } catch { throw Error('COS 请求失败（网络、超时或响应超限）'); }
}

async function signedRequest({ credentials, method, key, body, contentType, timeoutMs, anonymous = false }) {
  if (anonymous) return request({ endpoint: credentials.requestHost, method, key, timeoutMs });
  const now = Math.floor(Date.now() / 1000);
  const signTime = `${now};${now + 600}`;
  const headers = { host: credentials.requestHost };
  if (contentType) headers['content-type'] = contentType;
  headers.authorization = buildAuthorization({ secretId: credentials.secretId, secretKey: credentials.secretKey, method: method.toLowerCase(), pathname: `/${key}`, headers, signTime });
  if (body) headers['content-length'] = body.length;
  return request({ endpoint: credentials.requestHost, method, key, headers, body, timeoutMs });
}

export async function runLive({ env = process.env, transport = null, timeoutMs = 10000 } = {}) {
  const credentials = validateConfig(env);
  const objectKey = diagnosticObjectKey();
  const body = Buffer.from(`cos-storage-check ${new Date().toISOString()}\n`);
  const transportCall = transport || (args => signedRequest({ credentials, timeoutMs, ...args }));
  const call = async args => {
    try { return await transportCall(args); }
    catch { return { statusCode: 0 }; }
  };
  const put = await call({ method: 'PUT', key: objectKey, body, contentType: 'text/plain' });
  if (put.statusCode !== 200) return { ok: false, objectKey, failed: 'PUT', statusCode: put.statusCode };
  const head = await call({ method: 'HEAD', key: objectKey });
  if (head.statusCode !== 200 || Number(head.headers?.['content-length']) !== body.length) return { ok: false, objectKey, failed: 'HEAD', statusCode: head.statusCode };
  const signedGet = await call({ method: 'GET', key: objectKey });
  if (signedGet.statusCode !== 200 || !signedGet.body || !crypto.timingSafeEqual(crypto.createHash('sha256').update(signedGet.body).digest(), crypto.createHash('sha256').update(body).digest())) return { ok: false, objectKey, failed: 'signed GET digest', statusCode: signedGet.statusCode };
  const get = await call({ method: 'GET', key: objectKey, anonymous: true });
  if (get.statusCode !== 403) return { ok: false, objectKey, failed: 'anonymous GET', statusCode: get.statusCode };
  return { ok: true, objectKey, bytes: body.length };
}

if (process.argv[1] && process.argv[1].endsWith('cos-storage-check.mjs')) {
  const live = process.argv.includes('--live');
  try {
    if (!live) { validateConfig(environmentWithBackendDotenv(process.env)); process.stdout.write('COS 配置校验通过（未执行网络请求；如需连接验收请显式使用 --live）\n'); }
    else {
      const result = await runLive({ env: environmentWithBackendDotenv(process.env) });
      process.stdout.write(JSON.stringify(result) + '\n');
      if (!result.ok) process.exitCode = 1;
    }
  } catch (error) {
    process.stderr.write(String(error.message).replace(/[?&](?:q-signature|q-ak|q-sign-time|q-key-time)=[^&]*/gi, '') + '\n');
    process.exitCode = 1;
  }
}
