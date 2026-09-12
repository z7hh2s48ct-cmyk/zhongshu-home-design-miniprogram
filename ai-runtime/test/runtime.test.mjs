import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { config, bounded, Core, Journal, Provider, runJob, sha } from '../src/runtime.mjs';
import { createHmac } from 'node:crypto';

const job = { jobId: '9007199254740993', attemptNo: 1, fencingToken: 2, phase: 'FLAT', payload: { requestedCount: 2 } };
function scenario(failure) {
  const calls = [];
  const core = { async job(task, endpoint, payload) {
    calls.push([endpoint, payload]);
    if (endpoint === 'inputs') return { schemaVersion: 1, phase: task.phase, images: [], requirements: { width: 10 } };
    if (endpoint === 'output-tickets') return { objectKey: 'quarantine/' + payload.slot, uploadUrl: 'https://storage.example/upload' };
    if (endpoint === 'result-events') {
      if (failure === 'response-lost' && calls.filter(x => x[0] === endpoint).length === 1) throw Error('network');
      return 'QUARANTINED';
    }
    if (endpoint === 'lease-renewals' && failure === 'lease') return false;
    return true;
  } };
  const provider = { imageInput() { throw Error('unexpected'); }, plan: async () => 'plan', upload: async () => {},
    generate: async (task, slot, prompt, images, signal) => {
      if (failure === 'second' && slot === 2) throw Error('timeout');
      if (failure === 'lease') await new Promise((resolve, reject) => signal.addEventListener('abort', () => reject(Error('abort')), { once: true }));
      return { sha256: 'digest', sizeBytes: 10, mimeType: 'image/png' };
    } };
  return { core, provider, calls };
}

test('completion follows all slots; replay uses identical event ID and payload', async () => {
  const s = scenario('response-lost'); await runJob(job, s.core, s.provider, { providerCode: 'apilio' });
  const events = s.calls.filter(x => x[0] === 'result-events');
  assert.deepEqual(events[0], events[1]); assert.equal(events.length, 3);
  assert.equal(s.calls.at(-1)[0], 'completion-events');
});
test('partial provider failure sends a completion barrier without regenerating a paid slot', async () => {
  const s = scenario('second'); await assert.rejects(runJob(job, s.core, s.provider, { providerCode: 'apilio' }), /INCOMPLETE/);
  assert.equal(s.calls.filter(x => x[0] === 'result-events').length, 1);
  assert.equal(s.calls.at(-1)[0], 'completion-events');
});
test('lost lease aborts provider and prevents result or completion writes', async () => {
  const s = scenario('lease'); await assert.rejects(runJob(job, s.core, s.provider, { providerCode: 'apilio' }, { heartbeatMs: 5 }), /LEASE_LOST/);
  assert.equal(s.calls.filter(x => ['result-events', 'completion-events'].includes(x[0])).length, 0);
});
test('elevation without selected plane fails before a provider request', async () => {
  const s = scenario(); let called = false; s.provider.plan = async () => { called = true; };
  await assert.rejects(runJob({ ...job, phase: 'ELEVATION' }, s.core, s.provider, { providerCode: 'apilio' })); assert.equal(called, false);
});
test('journal restart reuses completed work; uncertain paid call never repeats; shared quota cannot race', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'zs-runtime-'));
  try {
    let calls = 0; const a = new Journal(dir, 2), b = new Journal(dir, 2);
    assert.equal(await a.once('one', async () => { calls++; return 'image'; }), 'image');
    assert.equal(await b.once('one', async () => { calls++; }), 'image');
    await assert.rejects(a.once('two', async () => { calls++; throw Error('timeout'); }));
    await assert.rejects(b.once('two', async () => { calls++; }), /UNCERTAIN/);
    assert.throws(() => b.reserve(), /DAILY_CALL_LIMIT/); assert.equal(calls, 2);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
test('streaming body cap works with missing content length and cancels the stream', async () => {
  await assert.rejects(bounded(new Response(new Uint8Array(32)), 8), /BODY_LIMIT/);
  assert.equal((await bounded(new Response('ok'), 8)).toString(), 'ok');
});
test('internal signature covers exact UTF-8 body and request path, no provider credential sent', async () => {
  const core = new Core({ core: 'http://core', secret: 'test-secret' }, async (url, request) => {
    assert.equal(url, 'http://core/internal-api/design/v1/ai-jobs/claims');
    const canonical = `${request.headers['X-ZS-Timestamp']}\nPOST\n/internal-api/design/v1/ai-jobs/claims\n${sha(request.body)}`;
    assert.equal(request.headers['X-ZS-Signature'], createHmac('sha256', 'test-secret').update(canonical).digest('hex'));
    assert.equal(request.headers.Authorization, undefined); return Response.json({ code: 0, data: [] });
  });
  await core.post('/internal-api/design/v1/ai-jobs/claims', { workerId: '中文测试', maxJobs: 1 });
});
test('provider URL responses and unapproved storage origins are rejected without a fetch', async () => {
  const provider = new Provider({ storageOrigins: ['https://storage.example'], base: 'https://provider.example', key: 'test-key', imageModel: 'fixture' },
    { once: (key, operation) => operation() }, async () => Response.json({ data: [{ url: 'http://127.0.0.1/secret' }] }));
  await assert.rejects(provider.generate(job, 1, 'plan', [], new AbortController().signal), /IMAGE_INVALID/);
  await assert.rejects(provider.imageInput({ url: 'https://evil.example/img' }, new AbortController().signal), /STORAGE_ORIGIN/);
});
test('live config requires image model, spend limit and explicit trusted storage origins', () => {
  assert.throws(() => config({}), /MISSING/);
  const env = { ZS_AI_DAILY_CALL_LIMIT: '10', ZS_AI_STORAGE_ORIGINS: 'https://storage.example', ZS_AI_CORE_URL: 'http://core:48080',
    ZS_INTERNAL_SECRET: 'test-secret', ZS_AI_API_KEY: 'test-key', ZS_AI_IMAGE_MODEL: 'reviewed-image-model', ZS_AI_JOURNAL_DIR: '/data' };
  assert.equal(config(env).base, 'https://api.apilio.ai/v1');
  assert.throws(() => config({ ...env, ZS_AI_BASE_URL: 'http://provider.example' }), /INVALID/);
  assert.throws(() => config({ ...env, ZS_AI_DAILY_CALL_LIMIT: '0' }), /INVALID/);
});
