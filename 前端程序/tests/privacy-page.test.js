const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
function setup(overrides = {}) {
  let definition, token = 'session-a'; const calls = [];
  const api = { getPrivacyConsents: async () => [], listPrivacyRequests: async () => [], ...overrides };
  const http = { getToken: () => token, isSameSession: value => value === token };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../miniprogram/pages/profile/privacy.js'), 'utf8'), {
    Page: value => { definition = value; }, clearTimeout,
    require: name => name.endsWith('/api') ? api : name.endsWith('/request') ? http : name.endsWith('/config') ? { apiBase: 'https://api.fixture.invalid', tenantId: 1 } : { id: String, errorText: e => e.message },
    wx: Object.fromEntries(['showModal', 'downloadFile', 'shareFileMessage'].map(key => [key, value => calls.push({ key, value })]))
  });
  definition.data = { ...definition.data }; definition.setData = value => Object.assign(definition.data, value);
  return { page: definition, calls, switchSession: () => { token = 'session-b'; } };
}
test('privacy remains available to logged in restricted accounts and never renders stale owner records', async () => {
  const response = deferred(); const f = setup({ listPrivacyRequests: () => response.promise });
  const loading = f.page.onShow(); f.switchSession(); response.resolve([{ requestId: '100', requestType: 'EXPORT', status: 'COMPLETED' }]); await loading;
  assert.equal(f.page.data.requests.length, 0); assert.match(f.page.data.error, /登录状态已变化/);
});
test('privacy submission prevents double requests and closure waits for explicit confirmation', async () => {
  const response = deferred(); let exports = 0, closures = 0;
  const f = setup({ createDataExportRequest: () => { exports++; return response.promise; }, createAccountClosureRequest: async () => { closures++; } });
  await f.page.onShow(); const first = f.page.createExport(); f.page.createExport(); assert.equal(exports, 1);
  response.resolve({}); await first; assert.equal(f.page.data.busy, false);
  f.page.requestClosure(); assert.equal(closures, 0); f.calls[0].value.success({ confirm: false }); assert.equal(closures, 0);
  f.calls[0].value.success({ confirm: true }); await new Promise(setImmediate); assert.equal(closures, 1);
});
test('download uses original request and authenticated file transfer, abandoning response on session change', async () => {
  const f = setup({ createPrivacyDownloadTicket: async id => ({ ticket: 'one-time-' + id }) }); await f.page.onShow();
  const download = f.page.download({ currentTarget: { dataset: { id: '9007199254740999' } } }); await new Promise(setImmediate);
  const request = f.calls[0].value; assert.match(request.url, /9007199254740999\/content\?ticket=one-time-9007199254740999$/);
  assert.equal(request.header.Authorization, 'Bearer session-a'); f.switchSession(); request.success({ statusCode: 200, tempFilePath: 'local.json' }); await download;
  assert.equal(f.calls.filter(c => c.key === 'shareFileMessage').length, 0);
});
