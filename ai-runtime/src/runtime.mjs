import { createHash, createHmac, randomUUID } from 'node:crypto';
import { readFileSync, writeFileSync, mkdirSync, renameSync, openSync, fsyncSync, closeSync, existsSync } from 'node:fs';
import { join, dirname, resolve as resolvePath, sep as pathSep } from 'node:path';
export const sha = bytes => createHash('sha256').update(bytes).digest('hex');
export const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
// 图片字节上限与后端隔离区 OUTPUT_POLICY（20MB）对齐；4K UHD PNG（渠道像素上限 8294400）可能达到十余 MB
const MAX_IMAGE = 20 * 1024 * 1024;
const MAX_IMAGE_BASE64 = Math.ceil(MAX_IMAGE / 3) * 4;
const MAX_IMAGE_JSON = MAX_IMAGE_BASE64 + 64 * 1024;
const IMAGE_OPTION_SIZES = Object.freeze({
  '2K:LANDSCAPE': '2048x1152',
  '2K:PORTRAIT': '1152x2048',
  '4K:LANDSCAPE': '3840x2160',
  '4K:PORTRAIT': '2160x3840'
});
const JPEG_SOF_MARKERS = new Set([0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf]);

function imageOptions(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)
      || !['2K', '4K'].includes(value.resolution)
      || !['LANDSCAPE', 'PORTRAIT'].includes(value.orientation)
      || Object.keys(value).some(key => !['resolution', 'orientation'].includes(key))) throw Error('INPUT_IMAGE_OPTIONS');
  return Object.freeze({ resolution: value.resolution, orientation: value.orientation });
}

function requestedImageSize(value, legacySize) {
  if (value === undefined) return legacySize;
  const selected = imageOptions(value);
  return IMAGE_OPTION_SIZES[`${selected.resolution}:${selected.orientation}`];
}

function imageDimensions(bytes, mimeType) {
  if (mimeType === 'image/png') {
    if (bytes.length < 33 || bytes.readUInt32BE(8) !== 13 || bytes.toString('ascii', 12, 16) !== 'IHDR') return null;
    const width = bytes.readUInt32BE(16), height = bytes.readUInt32BE(20);
    return width && height ? { width, height } : null;
  }
  if (mimeType !== 'image/jpeg' || bytes.length < 4) return null;
  let offset = 2;
  while (offset < bytes.length) {
    if (bytes[offset] !== 0xff) return null;
    while (offset < bytes.length && bytes[offset] === 0xff) offset += 1;
    if (offset >= bytes.length) return null;
    const marker = bytes[offset++];
    if (marker === 0xd9 || marker === 0xda) return null;
    if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd8)) continue;
    if (offset + 2 > bytes.length) return null;
    const length = bytes.readUInt16BE(offset);
    if (length < 2 || offset + length > bytes.length) return null;
    if (JPEG_SOF_MARKERS.has(marker)) {
      if (length < 8) return null;
      const height = bytes.readUInt16BE(offset + 3), width = bytes.readUInt16BE(offset + 5);
      return width && height ? { width, height } : null;
    }
    offset += length;
  }
  return null;
}

// 供应商（gpt-image 系列 /images/edits）实测不承诺精确输出尺寸（会按参考图比例自由发挥），
// 而计费与展示契约要求 2K/4K 像素口径真实：这里把返回图等比缩放 + 居中裁切归一到请求尺寸。
async function normalizeImageToSize(image, expectedSize) {
  const [width, height] = expectedSize.split('x').map(Number);
  if (!width || !height) throw Error('PROVIDER_IMAGE_INVALID');
  const bytes = Buffer.from(image.encoded, 'base64');
  const dimensions = imageDimensions(bytes, image.mimeType);
  // 解析不出尺寸（截断/伪造头）的字节不在此处处理——交由 verifyGeneratedImage 按原契约拒绝；
  // 只对「真实可解析且尺寸与请求不符」的图（供应商 edits 不守尺寸）做归一化。
  if (!dimensions || (dimensions.width === width && dimensions.height === height)) return image;
  const { default: sharp } = await import('sharp');
  let output;
  try {
    output = await sharp(bytes).resize(width, height, { fit: 'cover', position: 'centre' }).png().toBuffer();
  } catch {
    // 头部声称可解析、实体却是坏字节（截断/伪造）：统一按供应商图无效处理。
    throw Error('PROVIDER_IMAGE_INVALID');
  }
  if (output.length > MAX_IMAGE) throw Error('PROVIDER_IMAGE_TOO_LARGE');
  return { encoded: output.toString('base64'), mimeType: 'image/png', sha256: sha(output), sizeBytes: output.length };
}

function verifyGeneratedImage(image, expectedSize) {  if (typeof image?.encoded !== 'string') throw Error('PROVIDER_IMAGE_INVALID');
  if (image.encoded.length > MAX_IMAGE_BASE64) throw Error('PROVIDER_IMAGE_TOO_LARGE');
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(image.encoded)) throw Error('PROVIDER_IMAGE_INVALID');
  const bytes = Buffer.from(image.encoded, 'base64');
  if (bytes.length > MAX_IMAGE || image.sizeBytes > MAX_IMAGE) throw Error('PROVIDER_IMAGE_TOO_LARGE');
  if (bytes.length !== image.sizeBytes || sha(bytes) !== image.sha256) throw Error('PROVIDER_IMAGE_INVALID');
  const mimeType = bytes.subarray(0, 8).equals(Buffer.from([137,80,78,71,13,10,26,10])) ? 'image/png'
    : bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255 ? 'image/jpeg' : null;
  if (!mimeType || mimeType !== image.mimeType) throw Error('PROVIDER_IMAGE_INVALID');
  const dimensions = imageDimensions(bytes, mimeType);
  if (!dimensions) throw Error('PROVIDER_IMAGE_INVALID');
  const [width, height] = expectedSize.split('x').map(Number);
  if (dimensions.width !== width || dimensions.height !== height) throw Error('PROVIDER_IMAGE_SIZE_MISMATCH');
}

// dev 专用：后端 LocalObjectStorageAdapter 以 local://<objectKey> 签发内网对象地址。
// local-fs 模式下引擎与后端共享同一资产根目录（ZS_AI_STORAGE_ROOT），直接读写文件；
// 该模式绝不用于生产（生产走 COS https 源白名单），路径解析含越界防护。
export function localObjectPath(root, url) {
  const parsed = new URL(url);
  if (parsed.protocol !== 'local:') throw Error('STORAGE_ORIGIN');
  const relative = decodeURIComponent(`${parsed.host}${parsed.pathname}`).replace(/\\/g, '/');
  if (!relative || relative.includes('../') || relative.includes('/..') || relative.startsWith('/')) throw Error('STORAGE_ORIGIN');
  const base = resolvePath(root);
  const path = resolvePath(base, relative);
  if (path !== base && !path.startsWith(base + pathSep)) throw Error('STORAGE_ORIGIN');
  return path;
}

// T15：把后端需求快照的受控键格式化为中文需求描述，提升生图提示词质量；
// 未知键原样 JSON 附带（兼容旧快照与后续扩展），budgetInputs 属预算模块不进提示词。
const STYLE_LABELS = { NEW_CHINESE: '新中式', MODERN: '现代简约', CHINESE: '中式', EUROPEAN: '欧式' };
const ROOF_LABELS = { GABLE_ROOF: '坡屋顶', FLAT_ROOF: '平屋顶', GABLE: '坡屋顶' };
const MATERIAL_LABELS = { WHITE_STUCCO: '白色真石漆', GREY_STONE: '灰色石材', WHITE_COAT: '白色涂料', STONE: '石材' };
const COLOR_LABELS = { DEEP_WOOD: '深木色', WARM_GREY: '暖灰色', BLACK: '黑色', WARM_WHITE: '暖白色' };
const ROOM_LABELS = { bedroom: '室', living: '厅', bath: '卫', kitchen: '厨' };
const KNOWN_REQUIREMENT_KEYS = new Set(['faceWidthM', 'depthM', 'floor', 'floorCount', 'family', 'rooms',
  'prompt', 'note', 'styleCode', 'roofType', 'material', 'color', 'count']);
const label = (map, value) => (Object.hasOwn(map, value) ? map[value] : value);

export function formatRequirements(requirements) {
  if (!requirements || typeof requirements !== 'object') return JSON.stringify(requirements ?? null);
  const parts = [];
  if (requirements.faceWidthM != null && requirements.depthM != null) {
    parts.push(`宅基地面宽${requirements.faceWidthM}米、进深${requirements.depthM}米`);
  } else if (requirements.faceWidthM != null) parts.push(`宅基地面宽${requirements.faceWidthM}米`);
  else if (requirements.depthM != null) parts.push(`宅基地进深${requirements.depthM}米`);
  if (requirements.floor) parts.push(String(requirements.floor));
  else if (requirements.floorCount != null) parts.push(`${requirements.floorCount}层`);
  if (requirements.family) parts.push(String(requirements.family));
  else if (requirements.rooms && typeof requirements.rooms === 'object') {
    const rooms = Object.entries(requirements.rooms)
      .filter(([, count]) => count != null && count > 0)
      .map(([key, count]) => `${count}${ROOM_LABELS[key] ?? key}`)
      .join('');
    if (rooms) parts.push(rooms);
  }
  if (requirements.styleCode) parts.push(`${label(STYLE_LABELS, requirements.styleCode)}风格`);
  if (requirements.roofType) parts.push(label(ROOF_LABELS, requirements.roofType));
  if (requirements.material) parts.push(`${label(MATERIAL_LABELS, requirements.material)}外墙`);
  if (requirements.color) parts.push(`${label(COLOR_LABELS, requirements.color)}点缀`);
  if (requirements.prompt) parts.push(`自主需求：${requirements.prompt}`);
  if (requirements.note) parts.push(`补充需求：${requirements.note}`);
  const extras = Object.keys(requirements).filter(key => !KNOWN_REQUIREMENT_KEYS.has(key) && key !== 'budgetInputs');
  let text = parts.join('；');
  if (extras.length) text += `${text ? '；' : ''}其他需求数据：${JSON.stringify(Object.fromEntries(extras.map(key => [key, requirements[key]])))}`;
  return text || JSON.stringify(requirements);
}

export function config(env = process.env) {
  const required = name => { if (!env[name]?.trim()) throw Error(`MISSING_${name}`); return env[name].trim(); };
  const origin = (name, value, internal = false) => {
    const url = new URL(value);
    if (url.username || url.password || url.search || url.hash || (url.protocol !== 'https:' && !(internal && url.protocol === 'http:')))
      throw Error(`INVALID_${name}`);
    return url.href.replace(/\/$/, '');
  };
  const limit = Number(required('ZS_AI_DAILY_CALL_LIMIT'));
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > 10000) throw Error('INVALID_DAILY_CALL_LIMIT');
  const storageMode = env.ZS_AI_STORAGE_MODE?.trim() === 'local-fs' ? 'local-fs' : 'https';
  const storageRoot = storageMode === 'local-fs' ? required('ZS_AI_STORAGE_ROOT') : null;
  const storageOrigins = storageMode === 'local-fs' ? []
    : required('ZS_AI_STORAGE_ORIGINS').split(',').map(x => origin('STORAGE_ORIGIN', x.trim()));
  if (storageOrigins.some(x => new URL(x).origin !== x)) throw Error('INVALID_STORAGE_ORIGIN');
  const temperature = Number(env.ZS_AI_TEMPERATURE || '0.7');
  if (!Number.isFinite(temperature) || temperature < 0 || temperature > 2) throw Error('INVALID_TEMPERATURE');
  const aiBase = origin('AI_BASE_URL', env.ZS_AI_BASE_URL || 'https://api.apilio.ai/v1');
  const imageSize = (name, value) => {
    if (!/^\d{3,4}x\d{3,4}$/.test(value)) throw Error(`INVALID_${name}`);
    return value;
  };
  const rawQuality = env.ZS_AI_IMAGE_QUALITY?.trim();
  if (rawQuality && !['low', 'medium', 'high', 'auto'].includes(rawQuality)) throw Error('INVALID_IMAGE_QUALITY');
  // 供应商调用超时可配置：4K/高质量档出图常见 2~4 分钟，默认 180s 不够
  const requestTimeoutMs = Number(env.ZS_AI_REQUEST_TIMEOUT_MS || '180000');
  if (!Number.isSafeInteger(requestTimeoutMs) || requestTimeoutMs < 30000 || requestTimeoutMs > 600000) throw Error('INVALID_REQUEST_TIMEOUT');
  return {
    core: origin('CORE_URL', required('ZS_AI_CORE_URL'), true),
    secret: required('ZS_INTERNAL_SECRET'), key: required('ZS_AI_API_KEY'),
    base: aiBase,
    model: env.ZS_AI_MODEL || 'gpt-5.6-sol', imageModel: required('ZS_AI_IMAGE_MODEL'),
    temperature, storageMode, storageRoot, storageOrigins, dailyLimit: limit,
    imageSizeFlat: imageSize('IMAGE_SIZE_FLAT', env.ZS_AI_IMAGE_SIZE_FLAT || '1024x1536'),
    imageSizeElevation: imageSize('IMAGE_SIZE_ELEVATION', env.ZS_AI_IMAGE_SIZE_ELEVATION || '1536x1024'),
    imageQuality: rawQuality || '',
    // 图片回包为 URL 模式时，仅允许下载白名单 origin（默认与供应商 API 同源）；渠道 CDN 不同源时显式配置
    imageUrlOrigins: env.ZS_AI_IMAGE_URL_ORIGINS?.trim()
      ? env.ZS_AI_IMAGE_URL_ORIGINS.split(',').map(x => origin('IMAGE_URL_ORIGIN', x.trim()))
      : [new URL(aiBase).origin],
    requestTimeoutMs,
    journalPath: required('ZS_AI_JOURNAL_DIR'), providerCode: 'apilio', workerId: `runtime-${randomUUID()}`
  };
}

export async function bounded(response, max, signal) {
  if (!response.ok) { await response.body?.cancel(); throw Error(`HTTP_${response.status}`); }
  const length = Number(response.headers.get('content-length'));
  if (length > max) { await response.body?.cancel(); throw Error('BODY_LIMIT'); }
  const reader = response.body.getReader(), parts = []; let size = 0;
  try {
    while (true) {
      signal?.throwIfAborted();
      const { value, done } = await reader.read(); if (done) break;
      size += value.length; if (size > max) throw Error('BODY_LIMIT'); parts.push(value);
    }
    return Buffer.concat(parts, size);
  } finally { await reader.cancel().catch(() => {}); }
}

export class Core {
  constructor(settings, fetcher = fetch) { this.settings = settings; this.fetch = fetcher; }
  async post(path, payload, signal) {
    const body = JSON.stringify(payload), timestamp = String(Math.floor(Date.now() / 1000));
    const signature = createHmac('sha256', this.settings.secret).update(`${timestamp}\nPOST\n${path}\n${sha(body)}`).digest('hex');
    const controller = AbortSignal.any([AbortSignal.timeout(15000), ...(signal ? [signal] : [])]);
    const response = await this.fetch(this.settings.core + path, { method: 'POST', body, redirect: 'error', signal: controller,
      headers: { 'Content-Type': 'application/json', 'X-ZS-Timestamp': timestamp, 'X-ZS-Signature': signature } });
    const value = JSON.parse(await bounded(response, 1024 * 1024, controller));
    if (value.code !== 0) throw Error(`CORE_${Number(value.code) || 'REJECTED'}`);
    return value.data;
  }
  job(job, endpoint, values = {}, signal) {
    return this.post(`/internal-api/design/v1/ai-jobs/${job.jobId}/${endpoint}`,
      { attemptNo: job.attemptNo, fencingToken: job.fencingToken, ...values }, signal);
  }
}

/** Synchronous atomic journal. Reservations survive crashes; uncertain paid requests are never blindly repeated. */
export class Journal {
  constructor(directory, dailyLimit) { this.directory = directory; this.limit = dailyLimit; mkdirSync(directory, { recursive: true, mode: 0o700 }); }
  path(key) { if (!/^[a-zA-Z0-9_-]+$/.test(key)) throw Error('JOURNAL_KEY'); return join(this.directory, key + '.json'); }
  read(key) { const path = this.path(key); return existsSync(path) ? JSON.parse(readFileSync(path, 'utf8')) : null; }
  write(key, value) {
    const path = this.path(key), temp = path + '.' + randomUUID() + '.tmp';
    const fd = openSync(temp, 'wx', 0o600);
    try { writeFileSync(fd, JSON.stringify(value)); fsyncSync(fd); } finally { closeSync(fd); }
    renameSync(temp, path);
  }
  reserve() {
    const day = new Date().toISOString().slice(0, 10);
    for (let index = 1; index <= this.limit; index++) {
      try { const fd = openSync(join(this.directory, `quota-${day}-${index}.reserved`), 'wx', 0o600); fsyncSync(fd); closeSync(fd); return; }
      catch (error) { if (error.code !== 'EEXIST') throw error; }
    }
    throw Error('DAILY_CALL_LIMIT');
  }
  async once(key, operation) {
    const previous = this.read(key);
    if (previous?.state === 'completed') return previous.value;
    if (previous) throw Error('PROVIDER_OUTCOME_UNCERTAIN');
    try { const fd = openSync(this.path(key) + '.started', 'wx', 0o600); fsyncSync(fd); closeSync(fd); }
    catch (error) { if (error.code !== 'EEXIST') throw error; throw Error('PROVIDER_OUTCOME_UNCERTAIN'); }
    this.reserve(); this.write(key, { state: 'started', at: new Date().toISOString() });
    const value = await operation(); this.write(key, { state: 'completed', value }); return value;
  }
}

export class Provider {
  constructor(settings, journal, fetcher = fetch) { this.settings = settings; this.journal = journal; this.fetch = fetcher; }
  async imageInput(input, signal) {
    let bytes;
    if (this.settings.storageMode === 'local-fs') {
      bytes = readFileSync(localObjectPath(this.settings.storageRoot, input.url));
    } else {
      const url = new URL(input.url);
      if (url.username || url.password || !this.settings.storageOrigins.includes(url.origin)) throw Error('STORAGE_ORIGIN');
      const timeout = AbortSignal.any([signal, AbortSignal.timeout(30000)]);
      bytes = await bounded(await this.fetch(url, { redirect: 'error', signal: timeout }), MAX_IMAGE, timeout);
    }
    if (!['image/png', 'image/jpeg'].includes(input.mimeType) || input.sizeBytes > MAX_IMAGE) throw Error('INPUT_POLICY');
    if (bytes.length !== input.sizeBytes || sha(bytes) !== input.sha256) throw Error('INPUT_DIGEST');
    return { bytes, mimeType: input.mimeType };
  }
  async request(path, body, json, signal) {
    const timeout = AbortSignal.any([signal, AbortSignal.timeout(this.settings.requestTimeoutMs || 180000)]);
    const response = await this.fetch(this.settings.base + path, { method: 'POST', body: json ? JSON.stringify(body) : body,
      redirect: 'error', signal: timeout, headers: { Authorization: `Bearer ${this.settings.key}`, ...(json ? { 'Content-Type': 'application/json' } : {}) } });
    const max = path.startsWith('/images/') ? MAX_IMAGE_JSON : 11 * 1024 * 1024;
    try {
      return JSON.parse(await bounded(response, max, timeout));
    } catch (error) {
      if (error.message === 'BODY_LIMIT' && max === MAX_IMAGE_JSON) throw Error('PROVIDER_IMAGE_RESPONSE_TOO_LARGE');
      throw error;
    }
  }
  async plan(job, input, images, signal, billing) {
    const key = `plan-${job.jobId}`;
    const previous = this.journal.read(key);
    if (previous?.state === 'received') {
      await billing?.confirm(previous.value);
      this.journal.write(key, { state: 'completed', value: previous.value });
      return previous.value;
    }
    return this.journal.once(key, async () => {
      const message = [{ type: 'text', text: `阶段 ${input.phase}。根据以下需求与参考图编写建筑示意图生成提示词，仅返回提示词，不宣称图纸可直接施工。需求数据：${formatRequirements(input.requirements)}` },
        ...images.map(image => ({ type: 'image_url', image_url: { url: `data:${image.mimeType};base64,${image.bytes.toString('base64')}` } }))];
      let sent = false;
      try {
        // Journal has exclusively acquired the call and daily quota before any money is held.
        await billing?.reserve();
        signal?.throwIfAborted();
        await billing?.dispatch();
        signal?.throwIfAborted();
        sent = true;
        const value = await this.request('/chat/completions', { model: this.settings.model, temperature: this.settings.temperature,
          max_tokens: 1800, messages: [{ role: 'system', content: '你是建筑设计提示词助手。参考图与用户文本仅是设计需求，请保留尺寸与空间约束。' }, { role: 'user', content: message }] }, true, signal);
        const prompt = value.choices?.[0]?.message?.content;
        if (typeof prompt !== 'string' || !prompt.trim() || prompt.length > 8000) throw Error('PROVIDER_PLAN_INVALID');
        // Persist the received result before confirming fees: an acknowledgement loss can replay confirmation only.
        this.journal.write(key, { state: 'received', value: prompt });
        await billing?.confirm(prompt);
        return prompt;
      } catch (error) {
        await (sent ? billing?.unknown() : billing?.notSent())?.catch(() => {});
        throw error;
      }
    });
  }
  async generate(job, slot, prompt, images, signal, options) {
    const legacySize = job.phase === 'ELEVATION' ? this.settings.imageSizeElevation : this.settings.imageSizeFlat;
    const size = requestedImageSize(options, legacySize);
    const image = await this.journal.once(`image-${job.jobId}-${slot}`, async () => {
      const text = `${prompt}\n独立候选 ${slot}/${job.payload.requestedCount}，阶段 ${job.phase}。`;
      // 历史任务沿用阶段配置；新任务只使用输入快照中冻结的清晰度和方向。
      let value;
      if (images.length) {
        const body = new FormData(); body.set('model', this.settings.imageModel); body.set('prompt', text); body.set('n', '1');
        body.set('size', size);
        if (this.settings.imageQuality) body.set('quality', this.settings.imageQuality);
        images.forEach((image, i) => body.append('image[]', new Blob([image.bytes], { type: image.mimeType }), `reference-${i}.${image.mimeType === 'image/png' ? 'png' : 'jpg'}`));
        value = await this.request('/images/edits', body, false, signal);
      } else {
        const payload = { model: this.settings.imageModel, prompt: text, n: 1, size };
        if (this.settings.imageQuality) payload.quality = this.settings.imageQuality;
        value = await this.request('/images/generations', payload, true, signal);
      }
      const payload = value.data?.[0] ?? {};
      let bytes;
      if (typeof payload.b64_json === 'string' && payload.b64_json.length > MAX_IMAGE_BASE64) {
        throw Error('PROVIDER_IMAGE_TOO_LARGE');
      }
      if (typeof payload.b64_json === 'string' && /^[A-Za-z0-9+/]+={0,2}$/.test(payload.b64_json)) {
        bytes = Buffer.from(payload.b64_json, 'base64');
      } else if (typeof payload.url === 'string') {
        // URL 回包模式（如 gpt-image-2 经 apilio 代理）：仅下载白名单 origin，禁止重定向，
        // 下载后重算魔数与摘要——不信任 URL 内容声明，摘要以后续下载字节为准。
        bytes = await this.fetchImageUrl(payload.url, signal);
      } else throw Error('PROVIDER_IMAGE_INVALID');
      const mimeType = bytes.subarray(0, 8).equals(Buffer.from([137,80,78,71,13,10,26,10])) ? 'image/png'
        : bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255 ? 'image/jpeg' : null;
      if (bytes.length > MAX_IMAGE) throw Error('PROVIDER_IMAGE_TOO_LARGE');
      if (!mimeType) throw Error('PROVIDER_IMAGE_INVALID');
      return normalizeImageToSize({ encoded: bytes.toString('base64'), mimeType, sha256: sha(bytes), sizeBytes: bytes.length }, size);
    });
    if (options !== undefined) verifyGeneratedImage(image, size);
    return image;
  }

  async fetchImageUrl(url, signal) {
    const parsed = new URL(url);
    if (parsed.protocol !== 'https:' || parsed.username || parsed.password
        || !this.settings.imageUrlOrigins.includes(parsed.origin)) throw Error('PROVIDER_IMAGE_URL_ORIGIN');
    const timeout = AbortSignal.any([signal, AbortSignal.timeout(this.settings.requestTimeoutMs || 180000)]);
    try {
      return await bounded(await this.fetch(parsed, { redirect: 'error', signal: timeout }), MAX_IMAGE, timeout);
    } catch (error) {
      if (error.message === 'BODY_LIMIT') throw Error('PROVIDER_IMAGE_TOO_LARGE');
      throw error;
    }
  }
  async upload(url, image, signal) {
    if (this.settings.storageMode === 'local-fs') {
      const path = localObjectPath(this.settings.storageRoot, url);
      mkdirSync(dirname(path), { recursive: true });
      const bytes = Buffer.from(image.encoded, 'base64');
      const temp = path + '.' + randomUUID() + '.tmp';
      writeFileSync(temp, bytes);
      renameSync(temp, path);
      return;
    }
    const parsed = new URL(url);
    if (parsed.username || parsed.password || !this.settings.storageOrigins.includes(parsed.origin)) throw Error('STORAGE_ORIGIN');
    const timeout = AbortSignal.any([signal, AbortSignal.timeout(30000)]);
    await bounded(await this.fetch(url, { method: 'PUT', body: Buffer.from(image.encoded, 'base64'), redirect: 'error',
      headers: { 'Content-Type': image.mimeType }, signal: timeout }), 64 * 1024, timeout);
  }
}

export async function runJob(job, core, provider, settings, options = {}) {
  if (!/^[1-9][0-9]{0,18}$/.test(job.jobId) || !['FLAT', 'ELEVATION'].includes(job.phase)
      || !Number.isSafeInteger(job.payload?.requestedCount) || job.payload.requestedCount < 1 || job.payload.requestedCount > 4) throw Error('JOB_CONTRACT');
  const controller = new AbortController(), signal = controller.signal;
  const shutdown = () => controller.abort();
  options.signal?.addEventListener('abort', shutdown, { once: true });
  if (options.signal?.aborted) controller.abort();
  let renewing = false;
  const timer = setInterval(async () => {
    if (renewing) return; renewing = true;
    try { if (!await core.job(job, 'lease-renewals', {}, signal)) controller.abort(); }
    catch { controller.abort(); } finally { renewing = false; }
  }, options.heartbeatMs || 15000);
  try {
    const input = await core.job(job, 'inputs', {}, signal);
    if (input.schemaVersion !== 1 || input.phase !== job.phase || !Array.isArray(input.images) || input.images.length > 8
        || (job.phase === 'ELEVATION' && input.images.length === 0)) throw Error('INPUT_CONTRACT');
    const generationOptions = Object.hasOwn(input, 'imageOptions') ? imageOptions(input.imageOptions) : undefined;
    const images = [];
    for (const image of input.images) images.push(await provider.imageInput(image, signal));
    const billingEvent = async (outcome, prompt) => {
      let last;
      for (let attempt = 0; attempt < 3; attempt++) {
        try { return await core.job(job, 'prompt-call-events', { outcome, callId: `plan-${job.jobId}`, ...(outcome === 'SUCCEEDED' ? { responseHash: sha(prompt) } : {}) }); }
        catch (error) { last = error; }
      }
      throw last;
    };
    const prompt = await provider.plan(job, input, images, signal, {
      reserve: () => core.job(job, 'prompt-calls', {}, signal),
      dispatch: () => billingEvent('DISPATCHED'),
      confirm: prompt => billingEvent('SUCCEEDED', prompt),
      unknown: () => billingEvent('UNKNOWN'),
      notSent: () => billingEvent('NOT_SENT')
    });
    for (let slot = 1; slot <= job.payload.requestedCount; slot++) {
      signal.throwIfAborted();
      const image = await provider.generate(job, slot, prompt, images, signal, generationOptions);
      const ticket = await core.job(job, 'output-tickets', { slot, mimeType: image.mimeType }, signal);
      await provider.upload(ticket.uploadUrl, image, signal);
      const event = { providerCode: settings.providerCode, sourceEventId: `result-${job.jobId}-${job.fencingToken}-${slot}`,
        candidateSlotNo: slot, objectKey: ticket.objectKey, sha256: image.sha256, mimeType: image.mimeType, sizeBytes: image.sizeBytes };
      let outcome;
      for (let attempt = 0; attempt < 3; attempt++) {
        try { outcome = await core.job(job, 'result-events', event, signal); break; }
        catch (error) { if (attempt === 2) throw error; await sleep(500); }
      }
      if (!['QUARANTINED', 'DUPLICATE_EVENT'].includes(outcome)) throw Error('RESULT_NOT_ACCEPTED');
      await core.job(job, 'progress-events', { progress: Math.floor(slot * 90 / job.payload.requestedCount), stage: 'GENERATING' }, signal);
    }
    if (!await core.job(job, 'completion-events', {}, signal)) throw Error('COMPLETION_NOT_ACCEPTED');
    return 'COMPLETED';
  } catch (error) {
    if (!signal.aborted) {
      // Paid outcome may be unknown. Complete this attempt and settle actual received outputs, never auto-repeat a charge.
      await core.job(job, 'completion-events', {}).catch(() => {});
    }
    // 失败原因必须可见（静默吞错会让 4K 超时这类问题无从排障）；消息不含密钥
    console.error(JSON.stringify({ event: 'job_attempt_failed', jobId: job.jobId,
      reason: signal.aborted ? 'LEASE_LOST' : String(error && error.message || 'unknown').slice(0, 300) }));
    throw new Error(signal.aborted ? 'LEASE_LOST' : 'JOB_ATTEMPT_INCOMPLETE');
  } finally { clearInterval(timer); controller.abort(); options.signal?.removeEventListener('abort', shutdown); }
}
