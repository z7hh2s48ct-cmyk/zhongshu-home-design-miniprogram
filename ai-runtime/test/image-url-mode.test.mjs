import test from 'node:test';
import assert from 'node:assert/strict';
import { Provider, sha } from '../src/runtime.mjs';

const PNG = Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), Buffer.from('rest-of-image')]);

function pngHeader(width, height) {
  const bytes = Buffer.alloc(33);
  Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]).copy(bytes);
  bytes.writeUInt32BE(13, 8);
  bytes.write('IHDR', 12, 'ascii');
  bytes.writeUInt32BE(width, 16);
  bytes.writeUInt32BE(height, 20);
  bytes[24] = 8; bytes[25] = 2;
  return bytes;
}

function jpegHeader(width, height) {
  return Buffer.from([
    0xff, 0xd8,
    0xff, 0xe0, 0x00, 0x04, 0x4a, 0x46,
    0xff, 0xc0, 0x00, 0x0b, 0x08,
    height >> 8, height & 0xff, width >> 8, width & 0xff,
    0x01, 0x01, 0x11, 0x00, 0xff, 0xd9,
  ]);
}

function settings(overrides = {}) {
  return {
    base: 'https://api.apilio.ai/v1', key: 'k', imageModel: 'gpt-image-2',
    imageUrlOrigins: ['https://cdn.example.com'],
    imageSizeFlat: '1024x1536', imageSizeElevation: '1536x1024', imageQuality: 'high',
    ...overrides,
  };
}
const journal = { once: async (_key, op) => op() };
const job = { jobId: '9007199254740993', phase: 'FLAT', payload: { requestedCount: 1 } };

function jsonResponse(body) {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } });
}

test('按阶段选择比例并携带 quality 参数', async () => {
  const bodies = [];
  const provider = new Provider(settings(), journal, async (input, init) => {
    bodies.push(init?.body ? JSON.parse(init.body) : null);
    return jsonResponse({ data: [{ b64_json: PNG.toString('base64') }] });
  });
  await provider.generate({ ...job, phase: 'FLAT' }, 1, 'p', [], new AbortController().signal);
  await provider.generate({ ...job, phase: 'ELEVATION' }, 1, 'p', [], new AbortController().signal);
  assert.equal(bodies[0].size, '1024x1536');
  assert.equal(bodies[0].quality, 'high');
  assert.equal(bodies[1].size, '1536x1024');
});

test('冻结的 2K/4K 横竖选项映射到 generation 请求尺寸', async () => {
  const cases = [
    [{ resolution: '2K', orientation: 'LANDSCAPE' }, '2048x1152'],
    [{ resolution: '2K', orientation: 'PORTRAIT' }, '1152x2048'],
    [{ resolution: '4K', orientation: 'LANDSCAPE' }, '3840x2160'],
    [{ resolution: '4K', orientation: 'PORTRAIT' }, '2160x3840'],
  ];
  const sizes = [];
  const provider = new Provider(settings(), journal, async (_input, init) => {
    const size = JSON.parse(init.body).size;
    sizes.push(size);
    const [width, height] = size.split('x').map(Number);
    return jsonResponse({ data: [{ b64_json: pngHeader(width, height).toString('base64') }] });
  });
  for (const [imageOptions] of cases) {
    await provider.generate(job, 1, 'p', [], new AbortController().signal, imageOptions);
  }
  assert.deepEqual(sizes, cases.map(([, size]) => size));
});

test('edit 请求使用同一尺寸映射，无选项时保持历史配置尺寸', async () => {
  const sizes = [];
  const provider = new Provider(settings(), journal, async (_input, init) => {
    const size = init.body instanceof FormData ? init.body.get('size') : JSON.parse(init.body).size;
    sizes.push(size);
    const [width, height] = size.split('x').map(Number);
    const bytes = size === '2160x3840' ? jpegHeader(width, height) : PNG;
    return jsonResponse({ data: [{ b64_json: bytes.toString('base64') }] });
  });
  const reference = [{ bytes: PNG, mimeType: 'image/png' }];
  await provider.generate(job, 1, 'p', reference, new AbortController().signal,
    { resolution: '4K', orientation: 'PORTRAIT' });
  await provider.generate(job, 1, 'p', [], new AbortController().signal);
  assert.deepEqual(sizes, ['2160x3840', '1024x1536']);
});

test('有 imageOptions 时拒绝非法枚举，不回退历史尺寸或请求供应商', async () => {
  let called = false;
  const provider = new Provider(settings(), journal, async () => { called = true; });
  await assert.rejects(provider.generate(job, 1, 'p', [], new AbortController().signal,
    { resolution: '4096', orientation: 'LANDSCAPE' }), /IMAGE_OPTIONS/);
  await assert.rejects(provider.generate(job, 1, 'p', [], new AbortController().signal,
    { resolution: '4K', orientation: 'SQUARE' }), /IMAGE_OPTIONS/);
  assert.equal(called, false);
});

test('新任务拒绝截断 PNG/JPEG 和与请求档位不符的原生尺寸', async () => {
  const options = { resolution: '2K', orientation: 'LANDSCAPE' };
  for (const bytes of [PNG, Buffer.from([0xff, 0xd8, 0xff, 0xc0, 0x00, 0x0b, 0x08])]) {
    const provider = new Provider(settings(), journal,
      async () => jsonResponse({ data: [{ b64_json: bytes.toString('base64') }] }));
    await assert.rejects(provider.generate(job, 1, 'p', [], new AbortController().signal, options),
      /PROVIDER_IMAGE_INVALID/);
  }
  let mismatchCalls = 0;
  const mismatch = new Provider(settings(), journal, async () => {
    mismatchCalls += 1;
    return jsonResponse({ data: [{ b64_json: pngHeader(2048, 2048).toString('base64') }] });
  });
  await assert.rejects(mismatch.generate(job, 1, 'p', [], new AbortController().signal, options),
    /PROVIDER_IMAGE_SIZE_MISMATCH/);
  assert.equal(mismatchCalls, 1);
});

test('历史任务无 imageOptions 时不新增像素头要求', async () => {
  const provider = new Provider(settings(), journal,
    async () => jsonResponse({ data: [{ b64_json: PNG.toString('base64') }] }));
  assert.equal((await provider.generate(job, 1, 'p', [], new AbortController().signal)).mimeType, 'image/png');
});

test('带 imageOptions 的 completed journal 结果仍复核尺寸且不重复调用供应商', async () => {
  let providerCalls = 0;
  const cached = pngHeader(2048, 2048);
  const provider = new Provider(settings(), { once: async () => ({
    encoded: cached.toString('base64'), mimeType: 'image/png', sha256: sha(cached), sizeBytes: cached.length,
  }) }, async () => { providerCalls += 1; });
  await assert.rejects(provider.generate(job, 1, 'p', [], new AbortController().signal,
    { resolution: '2K', orientation: 'LANDSCAPE' }), /PROVIDER_IMAGE_SIZE_MISMATCH/);
  assert.equal(providerCalls, 0);
});

test('带 imageOptions 的 completed journal 结果继续受 20MB 限制', async () => {
  const oversizedBase64 = 'A'.repeat(Math.ceil((20 * 1024 * 1024) / 3) * 4 + 4);
  const provider = new Provider(settings(), { once: async () => ({
    encoded: oversizedBase64, mimeType: 'image/png', sha256: 'unused', sizeBytes: 20 * 1024 * 1024 + 1,
  }) }, async () => { throw Error('不应调用供应商'); });
  await assert.rejects(provider.generate(job, 1, 'p', [], new AbortController().signal,
    { resolution: '4K', orientation: 'LANDSCAPE' }), /PROVIDER_IMAGE_TOO_LARGE/);
});

test('图片 JSON 上限覆盖 20MB base64 膨胀和信封，越界错误不泄露密钥', async () => {
  const base64Limit = Math.ceil((20 * 1024 * 1024) / 3) * 4;
  const signal = new AbortController().signal;
  for (const [path, body, json] of [
    ['/images/generations', {}, true],
    ['/images/edits', new FormData(), false],
  ]) {
    const accepted = new Provider(settings(), journal, async () => new Response('{}', {
      status: 200, headers: { 'content-length': String(base64Limit + 64 * 1024) },
    }));
    assert.deepEqual(await accepted.request(path, body, json, signal), {});
  }
  const rejected = new Provider(settings(), journal, async () => new Response('{}', {
    status: 200, headers: { 'content-length': String(base64Limit + 64 * 1024 + 1) },
  }));
  await assert.rejects(rejected.request('/images/generations', {}, true, signal),
    error => /PROVIDER_IMAGE_RESPONSE_TOO_LARGE/.test(error.message) && !/test-key/.test(error.message));
});

test('URL 回包模式：白名单内下载图片、重算魔数与摘要', async () => {
  const calls = [];
  const fetcher = async input => {
    calls.push(String(input));
    if (calls.length === 1) return jsonResponse({ data: [{ url: 'https://cdn.example.com/x.png' }] });
    return new Response(PNG, { status: 200, headers: { 'content-length': String(PNG.length) } });
  };
  const provider = new Provider(settings(), journal, fetcher);
  const result = await provider.generate(job, 1, 'prompt', [], new AbortController().signal);
  assert.equal(result.mimeType, 'image/png');
  assert.equal(result.sha256, sha(PNG));
  assert.equal(result.sizeBytes, PNG.length);
  assert.equal(calls[1], 'https://cdn.example.com/x.png');
});

test('URL 回包白名单外、b64 与 url 双缺、非图片字节均拒绝', async () => {
  const offOrigin = new Provider(settings({ imageUrlOrigins: ['https://other.example.com'] }), journal,
    async () => jsonResponse({ data: [{ url: 'https://cdn.example.com/x.png' }] }));
  await assert.rejects(() => offOrigin.generate(job, 1, 'p', [], new AbortController().signal), /PROVIDER_IMAGE_URL_ORIGIN/);

  const noPayload = new Provider(settings(), journal, async () => jsonResponse({ data: [{}] }));
  await assert.rejects(() => noPayload.generate(job, 1, 'p', [], new AbortController().signal), /PROVIDER_IMAGE_INVALID/);

  const notImage = new Provider(settings(), journal, async input => {
    if (String(input).endsWith('/images/generations')) return jsonResponse({ data: [{ url: 'https://cdn.example.com/x.png' }] });
    return new Response(Buffer.from('not-an-image'), { status: 200, headers: { 'content-length': '12' } });
  });
  await assert.rejects(() => notImage.generate(job, 1, 'p', [], new AbortController().signal), /PROVIDER_IMAGE_INVALID/);
});

test('4K 回包超过既有 20MB 上限时显式失败', async () => {
  let calls = 0;
  const provider = new Provider(settings(), journal, async () => {
    calls += 1;
    if (calls === 1) return jsonResponse({ data: [{ url: 'https://cdn.example.com/large.png' }] });
    return new Response(null, { status: 200, headers: { 'content-length': String(20 * 1024 * 1024 + 1) } });
  });
  await assert.rejects(provider.generate(job, 1, 'p', [], new AbortController().signal,
    { resolution: '4K', orientation: 'LANDSCAPE' }), /PROVIDER_IMAGE_TOO_LARGE/);
});

test('b64_json 内联回包路径保持原行为', async () => {
  const b64 = PNG.toString('base64');
  const provider = new Provider(settings(), journal, async () => jsonResponse({ data: [{ b64_json: b64 }] }));
  const result = await provider.generate(job, 1, 'p', [], new AbortController().signal);
  assert.equal(result.encoded, b64);
  assert.equal(result.sha256, sha(PNG));
});
