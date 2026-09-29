const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const view = require('../miniprogram/utils/record-view');

function page(file, api, price) {
  let definition; const state = { token: 'A', calls: [], global: { jobId: '71', projectId: '91' }, timers: [] };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../miniprogram/pages/ai-design', file), 'utf8'), {
    require(name) { return {
      '../../utils/access': { protectedPage: value => { definition = value; } }, '../../utils/api': api,
      '../../utils/request': { getToken: () => state.token, isSameSession: token => token === state.token },
      '../../utils/record-view': view, '../../utils/assets': { fetchProfileAvatar: async id => 'asset://' + id },
      '../../utils/generation-price': price
    }[name]; }, getApp: () => ({ globalData: state.global }),
    wx: Object.fromEntries(['redirectTo', 'navigateBack', 'navigateTo', 'showToast', 'showModal'].map(key => [key, value => state.calls.push([key, value])])),
    setTimeout: callback => { state.timers.push(callback); return state.timers.length; }, clearTimeout() {}, setInterval: () => 0, clearInterval() {}
  });
  return { state, page: { ...definition, data: structuredClone(definition.data), setData(patch) { Object.assign(this.data, patch); } } };
}
const tick = () => new Promise(resolve => setImmediate(resolve));

test('生成页面显示服务端阶段与数量；已有一张结果仍等待结算', async () => {
  const e = page('generating.js', { getAiJob: async () => ({ jobId: '71', projectId: '91', phase: 'ELEVATION', status: 'RUNNING', requestedCount: 4, acceptedCount: 1, progress: 23, allowedActions: ['CANCEL'] }) });
  e.page.onLoad({ stage: 'plane', count: '2' }); await tick();
  assert.equal(e.page.data.stage, 'elevation'); assert.equal(e.page.data.count, 4); assert.equal(e.page.data.acceptedCount, 1);
  assert.equal(e.page.data.finished, false); assert.equal(e.state.calls.length, 0); e.page.onUnload();
});
test('生成轮询迟到响应不能跨账号写入或跳转', async () => {
  let resolve; const e = page('generating.js', { getAiJob: () => new Promise(r => { resolve = r; }) });
  e.page.onLoad({}); e.state.token = 'B'; resolve({ jobId: '71', status: 'SUCCEEDED', projectId: '91', requestedCount: 1 }); await tick();
  assert.equal(e.page.data.finished, false); assert.equal(e.state.calls.length, 0); e.page.onUnload();
});
test('重新生成先确认准确报价，失败重试保持请求与幂等键；取消不建单', async () => {
  const requests = []; let fail = true, confirm = true;
  const e = page('result.js', { getResultVersions: async () => ({ list: [{ versionId: '19', version: 1, superseded: false }] }),
    createRevisionRequest: async (...args) => { requests.push(args); if (fail) throw Error('network'); return { jobId: '72' }; }
  }, { confirm: async (stage, count, description) => { assert.equal(stage, 'ELEVATION'); assert.equal(count, 2); assert.match(description, /原方案保留/);
    if (!confirm) throw { cancelled: true }; return { ruleId: '123', ruleVersion: 2 }; } });
  e.page.onLoad({ projectId: '91' }); await tick();
  await e.page.regenerate(); fail = false; await e.page.regenerate();
  assert.deepEqual(requests[0], requests[1]); assert.equal(requests[0][1].priceConfirmation.ruleVersion, 2);
  confirm = false; await e.page.regenerate(); assert.equal(requests.length, 2);
});
