import test from 'node:test';
import assert from 'node:assert/strict';
import { Provider, sha } from '../src/runtime.mjs';

const PNG = Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), Buffer.from('rest-of-image')]);

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

test('b64_json 内联回包路径保持原行为', async () => {
  const b64 = PNG.toString('base64');
  const provider = new Provider(settings(), journal, async () => jsonResponse({ data: [{ b64_json: b64 }] }));
  const result = await provider.generate(job, 1, 'p', [], new AbortController().signal);
  assert.equal(result.encoded, b64);
  assert.equal(result.sha256, sha(PNG));
});
