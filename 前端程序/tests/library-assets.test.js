const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const tick = () => new Promise(resolve => setImmediate(resolve));
function page(detail, fetch = async id => 'image://' + id) {
  let definition;
  const state = { token: 'A', routes: [], data: {} };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../miniprogram/pages/library/detail.js'), 'utf8'), {
    require: name => ({
      '../../utils/access': { protectedPage: value => { definition = value; } },
      '../../utils/request': { getToken: () => state.token },
      '../../utils/format': { styleLabel: () => '现代' },
      '../../utils/api': { getCaseDetail: async () => detail, listFavorites: async () => ({ list: [] }) },
      '../../utils/assets': { fetchAssetDataUrl: fetch }
    })[name],
    getApp: () => ({ globalData: state.data }),
    wx: { showToast() {}, switchTab: route => state.routes.push(route) }
  });
  const p = { ...definition, data: structuredClone(definition.data), setData(patch) {
    for (const [key, value] of Object.entries(patch)) {
      const fields = key.replace(/\[(\d+)\]/g, '.$1').split('.');
      let target = this.data;
      for (const field of fields.slice(0, -1)) target = target[field];
      target[fields.at(-1)] = value;
    }
  } };
  p._token = 'A'; p._closed = false;
  return { p, state };
}
test('真实封面、立面及三层图纸完整展示，不截断或错标楼层', async () => {
  const { p } = page({ caseId: '1', sourceType: 'COMPANY', coverAssetId: 'cover', elevationAssetId: 'elevation',
    floorPlans: [1, 2, 3].map(floorNo => ({ floorNo, assetId: 'floor' + floorNo })), allowedActions: ['DESIGN_WITH'] });
  await p.loadDetail('1'); await tick();
  assert.equal(p.data.hero.url, 'image://cover');
  assert.deepEqual(Array.from(p.data.drawings, d => d.label), ['立面效果', '1层平面', '2层平面', '3层平面']);
  assert.equal(p.data.drawings[3].url, 'image://floor3');
  assert.equal(p.data.caseDetail.canDesign, true);
});
test('缺图保持缺失，AI整张方案不伪装一层；无授权不产生设计跳转', async () => {
  const { p, state } = page({ caseId: '1', sourceType: 'AI', floorPlans: [{ assetId: 'plan', floorNo: 1 }], allowedActions: ['FAVORITE'] });
  await p.loadDetail('1'); await tick(); p.useHouse();
  assert.equal(p.data.hero.status, 'missing');
  assert.equal(p.data.drawings[0].status, 'missing');
  assert.equal(p.data.drawings[1].label, '平面方案');
  assert.equal(state.routes.length, 0); assert.equal(state.data.refCase, undefined);
});
test('取图失败可重试并恢复，未分层旧数据不臆造楼层', async () => {
  let fail = true;
  const { p } = page({ caseId: '1', coverAssetId: 'c', floorPlanAssetIds: ['p'] }, async id => { if (fail) throw Error('断网'); return 'image://' + id; });
  await p.loadDetail('1'); await tick();
  assert.equal(p.data.hero.status, 'error'); assert.equal(p.data.drawings[1].label, '平面方案');
  fail = false; await p.retryImage({ currentTarget: { dataset: { index: 'hero' } } });
  assert.equal(p.data.hero.status, 'ready'); assert.equal(p.data.hero.url, 'image://c');
});
test('读取期间换账号或卸载，不回填旧图片', async () => {
  let finish;
  const { p, state } = page({ caseId: '1', coverAssetId: 'c' }, () => new Promise(resolve => { finish = resolve; }));
  await p.loadDetail('1'); state.token = 'B'; finish('image://private'); await tick();
  assert.equal(p.data.hero.url, '');
});

function assetHelper(env, response) {
  const module = { exports: {} }, calls = [];
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../miniprogram/utils/assets.js'), 'utf8'), {
    module,
    require: name => ({ './config': { env, apiBase: 'http://local', tenantId: 1 }, './request': { getToken: () => 'test' },
      './api': { createDownloadTicket: async id => { calls.push(['ticket', id]); return { ticketId: 'ticket' }; },
        resolveDownload: async (id, ticket) => { calls.push(['resolve', id, ticket]); return response; } } })[name],
    wx: { request: options => { calls.push(['bytes']); options.success(response); }, arrayBufferToBase64: () => 'image' }
  });
  return { helper: module.exports, calls };
}
test('正式环境图片复用票据签名地址，不调用开发专用内容端点或永久缓存URL', async () => {
  const { helper, calls } = assetHelper('prod', { downloadUrl: 'https://storage.example/image?signature=test' });
  assert.match(await helper.fetchAssetDataUrl('1'), /^https:/);
  await helper.fetchAssetDataUrl('1');
  assert.equal(calls.filter(c => c[0] === 'ticket').length, 2);
  assert.equal(calls.filter(c => c[0] === 'bytes').length, 0);
});
test('200业务错误JSON不被当作图片缓存，重试重新申请票据', async () => {
  const { helper, calls } = assetHelper('dev', { statusCode: 200, header: { 'Content-Type': 'application/json' }, data: {} });
  await assert.rejects(helper.fetchAssetDataUrl('1'));
  await assert.rejects(helper.fetchAssetDataUrl('1'));
  assert.equal(calls.filter(c => c[0] === 'ticket').length, 2);
});
