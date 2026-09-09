// 离线复现：加载当前小程序源码，wx/API 均使用内存替身；不访问网络、数据库或用户会话。
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
const out = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(out, '../../前端程序/miniprogram');
const localRequire = createRequire(import.meta.url);
function loadPage(relative) {
  let page;
  vm.runInNewContext(fs.readFileSync(path.join(root, relative), 'utf8'), {
    require: name => name.endsWith('/access') ? { protectedPage: p => { page = p; } }
      : name.endsWith('/record-view') ? localRequire(path.join(root, 'utils/record-view.js')) : {},
    getApp: () => ({ globalData: {} }), wx: {},
  }, { timeout: 1000 });
  const instance = { ...page, data: { ...page.data }, setData(patch) { Object.assign(this.data, patch); }, loadCandidates() {} };
  return instance;
}
const results = [];
const inputId = '2100000000000000001';
for (const relative of ['pages/ai-design/plane-select.js', 'pages/ai-design/elevation-select.js']) {
  const page = loadPage(relative);
  page.onLoad({ projectId: inputId });
  results.push({ check: '大整数项目 ID 完整性', source: relative, inputId,
    actualId: String(page.data.projectId), defectObserved: String(page.data.projectId) !== inputId });
}
for (const statusCode of [200, 403]) {
  const storage = new Map([['zs_access_token', 'offline-test-token'], ['zs_refresh_token', 'offline-test-refresh'], ['v12Authorized', true]]);
  const redirects = [];
  const context = {
    module: { exports: {} }, require: () => ({ apiBase: '', tenantId: 1 }),
    getCurrentPages: () => [{ route: 'pages/library/index' }],
    wx: { getStorageSync: k => storage.get(k), setStorageSync: (k, v) => storage.set(k, v),
      removeStorageSync: k => storage.delete(k), redirectTo: p => redirects.push(p.url),
      request: p => p.success({ statusCode, data: { code: 403, msg: '没有该资源权限' } }) },
  };
  vm.runInNewContext(fs.readFileSync(path.join(root, 'utils/request.js'), 'utf8'), context, { timeout: 1000 });
  let rejection;
  try { await context.module.exports.get('/app-api/design/v1/design-projects/1'); } catch (e) { rejection = e; }
  results.push({ check: '同一业务 403 在不同 HTTP 状态下的处理', statusCode, bodyCode: 403,
    tokenRetained: storage.has('zs_access_token'), authorized: storage.get('v12Authorized'), redirects, rejection });
}
const payment = loadPage('pages/payment/success.js');
payment.setData({ orderId: '123', price: 999, base: 999, bonus: 999, total: 1998 });
payment.applyOrder({ orderId: '123', amountCents: 1000, basePoints: 100, bonusPoints: 10, paymentState: 'SUCCEEDED', fulfillmentState: 'CREDITED' });
const actualPrice = payment.data.price, actualPoints = payment.data.total;
const closedTerminal = payment.applyOrder({ orderId: '123', amountCents: 1000, basePoints: 100, bonusPoints: 10, paymentState: 'CLOSED', fulfillmentState: 'NOT_READY' });
results.push({ check: '查单响应校准显示金额/点数及关闭状态', expectedPrice: 10,
  actualPrice, expectedPoints: 110, actualPoints, closedTerminal,
  statusText: payment.data.paymentStateText,
  defectObserved: Number(actualPrice) !== 10 || actualPoints !== 110 || !closedTerminal || payment.data.paymentStateText === '处理中' });
fs.writeFileSync(path.join(out, '离线复现结果.json'), JSON.stringify({ ranAt: new Date().toISOString(), mode: 'isolated-vm-no-network', results }, null, 2) + '\n');
console.log(JSON.stringify(results, null, 2));
