const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../miniprogram');
const view = require('../miniprogram/utils/record-view');
const ID = '9007199254740993', VERSION = '9007199254740995';
const project = n => ({ projectId: n, status: 'ACTIVE', stage: 'ELEVATION', sourceType: 'SELF_UPLOAD' });
const tick = () => new Promise(resolve => setImmediate(resolve));

test('退款记录区分退款成功、设计点冲正和未支付关闭，显示真实金额原因', () => {
  const closed = { orderId: ID, paymentState: 'CLOSED', fulfillmentState: 'NOT_READY', amountCents: 1000 };
  assert.equal(view.payment(closed), '订单已关闭');
  const refunded = { ...closed, refund: { channelState: 'SUCCEEDED', pointReversalState: 'REVERSED', amountCents: 1000, reason: '误充' } };
  assert.equal(view.payment(refunded), '已退款 · 设计点已冲正');
  assert.equal(view.payment({ ...refunded, refund: { ...refunded.refund, pointReversalState: 'RESERVED' } }), '已退款 · 设计点冲正中');
  const e = environment('pages/payment/success.js');
  e.page.onLoad({ orderId: ID });
  assert.equal(e.page.applyOrder(refunded), true);
  assert.equal(e.page.data.paymentTitle, '已退款 · 设计点已冲正');
  assert.equal(e.page.data.paymentHint, '退款 ¥10.00 · 误充');
  assert.equal(e.page.data.total, '—');
});
function environment(file, api = {}) {
  if (file === 'pages/ai-design/publish.js') api.getResultVersions ||= async () => ({ list: [{ versionId: VERSION, version: 3, superseded: false, selectedFlatAssetId: '11', selectedElevationAssetId: '12' }] });
  let definition;
  const state = { token: 'A', calls: [], timers: [], globalData: { selectedElevationAssetId: '999', selectedFlatAssetId: '998' } };
  const deps = {
    '../../utils/access': { protectedPage: value => { definition = value; } },
    '../../utils/api': api,
    '../../utils/request': { getToken: () => state.token },
    '../../utils/record-view': view,
    '../../utils/format': require('../miniprogram/utils/format'),
    '../../utils/assets': { fetchProfileAvatar: async id => { state.calls.push(['asset', id]); return 'asset://' + id; } }
  };
  vm.runInNewContext(fs.readFileSync(path.join(root, file), 'utf8'), {
    require: name => { assert.ok(deps[name], name); return deps[name]; },
    getApp: () => ({ globalData: state.globalData }),
    wx: Object.fromEntries(['navigateTo', 'redirectTo', 'switchTab', 'navigateBack', 'showToast', 'showModal'].map(name => [name, args => state.calls.push([name, args])])),
    setTimeout: fn => { state.timers.push(fn); return state.timers.length; }, clearTimeout: () => {}
  });
  const page = { ...definition, data: structuredClone(definition.data), setData(value) { Object.assign(this.data, value); } };
  return { page, state, api };
}
function list(type, api) { const e = environment('pages/profile/records.js', api); e.page.onLoad({ type }); return e; }

test('四个记录入口保持首页分类，充值页也进入同一个订单列表', () => {
  const markup = fs.readFileSync(path.join(root, 'pages/profile/index.wxml'), 'utf8');
  for (const type of ['projects', 'submissions', 'favorites', 'orders']) assert.match(markup, new RegExp('bindtap="openRecords"[^>]*data-type="' + type + '"'));
  assert.ok(!markup.includes('showPlaceholder'));
  assert.match(fs.readFileSync(path.join(root, 'pages/wallet/recharge.js'), 'utf8'), /profile\/records\?type=orders/);
});

test('列表 API 复用既有接口，游标及大整数编号按字符串传递', async () => {
  const calls = [], module = { exports: {} };
  vm.runInNewContext(fs.readFileSync(path.join(root, 'utils/api.js'), 'utf8'), { module, require: () => ({ get: async url => calls.push(url) }) });
  await module.exports.listProjects(ID, 20); await module.exports.listSubmissions('1&x=1', 20);
  await module.exports.getSubmission(ID); await module.exports.listRechargeOrders(2, 20);
  assert.deepEqual(calls, ['/app-api/design/v1/design-projects?limit=20&cursor=' + ID,
    '/app-api/design/v1/submissions?limit=20&cursor=1%26x%3D1', '/app-api/design/v1/submissions/' + ID, '/app-api/design/v1/recharge-orders?pageNo=2&pageSize=20']);
});

test('方案游标翻页去重，翻页失败保留已读记录，重试不跳页', async () => {
  let fail = true; const calls = [];
  const e = list('projects', { listProjects: async cursor => { calls.push(cursor); if (!cursor) return { list: [project(ID)], nextCursor: '50' }; if (fail) throw Error('断网'); return { list: [project(ID), project('49')], nextCursor: null }; } });
  await e.page.reload(); await e.page.more();
  assert.equal(e.page.data.rows.length, 1); assert.equal(e.page.data.error, '断网');
  fail = false; await e.page.retry();
  assert.equal(e.page.data.rows.length, 2); assert.equal(e.page.data.hasMore, false);
  assert.deepEqual(calls, [null, '50', '50']);
  e.page.open({ currentTarget: { dataset: { id: ID } } });
  assert.equal(e.state.calls.at(-1)[1].url, '/pages/profile/record?type=projects&id=' + ID);
});

test('空列表与接口失败分开，异常类型、数字 ID 与重复游标拒绝', async () => {
  const empty = list('favorites', { listFavorites: async () => ({ list: [], nextCursor: null }) }); await empty.page.reload();
  assert.equal(empty.page.data.error, ''); assert.equal(empty.page.data.rows.length, 0);
  const invalid = list('__proto__', {}); assert.ok(invalid.page.data.error);
  const bad = list('projects', { listProjects: async () => ({ list: [project(9007199254740992)], nextCursor: null }) }); await bad.page.reload(); assert.match(bad.page.data.error, /编号无效/);
  bad.api.listProjects = async () => ({ list: [project(ID)], nextCursor: '50' }); await bad.page.reload(); await bad.page.more(); assert.match(bad.page.data.error, /分页/);
});

test('订单使用页码分页且防重复加载，下一页不携带金额到详情', async () => {
  let resolve; const pages = [];
  const e = list('orders', { listRechargeOrders: page => { pages.push(page); return new Promise(r => { resolve = r; }); } });
  const pending = e.page.reload(); e.page.load(); assert.deepEqual(pages, [1]);
  resolve({ total: 21, list: [{ orderId: ID, amountCents: 100, paymentState: 'PENDING' }] }); await pending;
  assert.equal(e.page.data.hasMore, true); assert.equal(e.page.data.rows[0].url, '/pages/payment/success?orderId=' + ID);
  const next = e.page.more(); resolve({ total: 21, list: [{ orderId: '1', amountCents: 200, paymentState: 'CLOSED' }] }); await next;
  assert.deepEqual(pages, [1, 2]); assert.equal(e.page.data.hasMore, false);
});

test('切换账号丢弃迟到响应并清空旧记录，退出页面后不更新', async () => {
  let resolve; const e = list('projects', { listProjects: () => new Promise(r => { resolve = r; }) });
  let pending = e.page.reload(); e.page.data.rows = [{ id: 'old' }]; e.state.token = 'B'; resolve({ list: [project(ID)] }); await pending;
  assert.equal(e.page.data.rows.length, 0); assert.equal(e.page.data.loading, false);
  pending = e.page.reload(); e.page.onUnload(); resolve({ list: [project(ID)] }); await pending; assert.equal(e.page.data.rows.length, 0);
});

test('返回收藏列表重新读服务器，已取消的收藏不会残留', async () => {
  let saved = true; const e = list('favorites', { listFavorites: async () => ({ list: saved ? [{ caseId: ID, title: '院落' }] : [] }) });
  e.page.onShow(); await tick(); assert.equal(e.page.data.rows.length, 1);
  saved = false; e.page.onShow(); await tick(); assert.equal(e.page.data.rows.length, 0);
});

test('方案详情显示真实版本并准确链接历史结果和预算，不依赖全局上下文', async () => {
  const e = environment('pages/profile/record.js', { getProject: async () => project(ID), getResultVersions: async () => ({ list: [{ versionId: VERSION, version: 1, superseded: true }] }) });
  e.page.onLoad({ type: 'projects', id: ID }); await e.page.load();
  assert.equal(e.page.data.versions[0].hint, '历史版本 · 只读回看');
  e.page.openVersion({ currentTarget: { dataset: { id: VERSION } } });
  assert.equal(e.state.calls.at(-1)[1].url, '/pages/ai-design/result?projectId=' + ID + '&resultVersionId=' + VERSION);
  e.page.budgetHistory(); assert.equal(e.state.calls.at(-1)[1].url, '/pages/budget/history?projectId=' + ID);
});

test('投稿详情分别展示审核、发布与意见，审核通过不误报已发布', async () => {
  const e = environment('pages/profile/record.js', { getSubmission: async () => ({ submissionId: '3', projectId: ID, resultVersionId: VERSION, status: 'APPROVED', publicationStatus: 'UNPUBLISHED', reviewComment: '请核对尺寸' }) });
  e.page.onLoad({ type: 'submissions', id: '3' }); await e.page.load();
  assert.equal(e.page.data.fields.find(x => x.label === '审核状态').value, '审核通过');
  assert.match(e.page.data.fields.find(x => x.label === '发布状态').value, /尚未发布/);
  assert.equal(e.page.data.fields.find(x => x.label === '审核意见').value, '请核对尺寸');
  e.page.openVersion(); assert.ok(e.state.calls.at(-1)[1].url.includes(VERSION));
});

test('退修说明重提失败保留草稿和幂等键，成功后回读第二轮', async () => {
  let fail = true, state = 'CHANGES_REQUESTED'; const calls = [];
  const e = environment('pages/profile/record.js', {
    getSubmission: async () => ({ submissionId: '3', projectId: ID, resultVersionId: VERSION, status: state, allowedActions: ['RESUBMIT'], currentRound: state === 'RESUBMITTED' ? 2 : 1, note: '原说明' }),
    resubmitSubmission: async (...args) => { calls.push(args); if (fail) throw Error('响应丢失'); state = 'RESUBMITTED'; return { submissionId: '3' }; }
  });
  e.page.onLoad({ type: 'submissions', id: '3' }); await e.page.load();
  assert.equal(e.page.data.canResubmit, true);
  e.page.editResubmitNote({ detail: { value: '补充说明' } }); await e.page.resubmit();
  assert.equal(e.page.data.resubmitNote, '补充说明'); assert.equal(e.page.data.submitError, '响应丢失');
  fail = false; await e.page.resubmit(); assert.deepEqual(calls[0], calls[1]);
  assert.equal(e.page.data.canResubmit, false);
  assert.equal(e.page.data.fields.find(x => x.label === '审核轮次').value, '2');
});

test('非退修状态、空说明与重复点击不发起重提，换号不回填', async () => {
  let resolve, calls = 0;
  const e = environment('pages/profile/record.js', {
    getSubmission: async () => ({ submissionId: '3', projectId: ID, resultVersionId: VERSION, status: 'CHANGES_REQUESTED', allowedActions: ['RESUBMIT'] }),
    resubmitSubmission: () => { calls++; return new Promise(r => { resolve = r; }); }
  });
  e.page.onLoad({ type: 'submissions', id: '3' }); await e.page.load(); await e.page.resubmit(); assert.equal(calls, 0);
  e.page.editResubmitNote({ detail: { value: '修改说明' } });
  const pending = e.page.resubmit(); e.page.resubmit(); assert.equal(calls, 1);
  e.state.token = 'B'; resolve({ submissionId: '3' }); await pending;
  assert.equal(e.page.data.loaded, false); assert.equal(e.page.data.canResubmit, false);
  assert.equal(e.page.data.resubmitNote, '');
});

test('冷启动历史方案从冻结版本读取图片，禁止投稿和重新计费', async () => {
  const e = environment('pages/ai-design/result.js', { getResultVersions: async () => ({ list: [{ versionId: VERSION, version: 1, superseded: true, selectedFlatAssetId: '11', selectedElevationAssetId: '12' }] }) });
  e.page.onLoad({ projectId: ID, resultVersionId: VERSION }); await tick();
  assert.equal(e.page.data.flatImageUrl, 'asset://11'); assert.equal(e.page.data.imageUrl, 'asset://12');
  assert.equal(e.page.data.readOnly, true); e.page.regenerate(); e.page.publish();
  assert.deepEqual(e.state.calls.map(c => c[0]), ['asset', 'asset']);
});

test('指定版本不存在不偷偷显示最新版本，读取图片期间换号不回填', async () => {
  const e = environment('pages/ai-design/result.js', { getResultVersions: async () => ({ list: [{ versionId: '99', version: 2 }] }) });
  e.page.onLoad({ projectId: ID, resultVersionId: VERSION }); await tick(); assert.match(e.page.data.error, /不可用/); assert.equal(e.page.data.latest, null);
  e.state.token = ''; await e.page.loadVersions(); assert.equal(e.page.data.latest, null);
  for (const filename of ['plane-select', 'elevation-select']) {
    const f = environment('pages/ai-design/' + filename + '.js', { getProject: async projectId => { assert.equal(projectId, ID); return { candidates: [] }; } });
    f.page.onLoad({ projectId: ID }); await tick(); assert.equal(f.page.data.projectId, ID);
  }
});

test('充值详情忽略 URL 金额，已支付但未入账时不显示充值成功', async () => {
  const order = { orderId: ID, amountCents: 9900, basePoints: 100, bonusPoints: 5, paymentState: 'SUCCEEDED', fulfillmentState: 'PENDING' };
  const e = environment('pages/payment/success.js', { getRechargeOrder: async () => order });
  e.page.onLoad({ orderId: ID, price: '0.01', total: '999999' }); await e.page.refresh();
  assert.equal(e.page.data.price, '99.00'); assert.equal(e.page.data.total, '—'); assert.match(e.page.data.paymentTitle, /到账确认中/);
  order.fulfillmentState = 'CREDITED'; await e.page.refresh(); assert.equal(e.page.data.total, 105); assert.match(e.page.data.paymentTitle, /已到账/); assert.equal(e.page.data.polling, false);
});

test('关闭或读取失败订单不报成功，隐藏后迟到响应无效，换号清除金额', async () => {
  let resolve; const e = environment('pages/payment/success.js', { getRechargeOrder: async () => ({ orderId: ID, paymentState: 'CLOSED', amountCents: 100 }) });
  e.page.onLoad({ orderId: ID }); await e.page.refresh(); assert.equal(e.page.data.paymentTitle, '订单已关闭'); assert.equal(e.page.data.total, '—');
  e.api.getRechargeOrder = () => new Promise(r => { resolve = r; });
  let pending = e.page.refresh(); e.page.onHide(); resolve({ orderId: ID, paymentState: 'SUCCEEDED', fulfillmentState: 'CREDITED' }); await pending; assert.equal(e.page.data.total, '—');
  pending = e.page.refresh(); e.state.token = 'B'; resolve({ orderId: ID, amountCents: 900 }); await pending; assert.equal(e.page.data.price, '—'); assert.ok(e.page.data.error);
});

test('收藏状态可跨越首 50 条，取消必须服务端确认，不接受迟到账号操作', async () => {
  const cursors = []; const e = environment('pages/library/detail.js', { listFavorites: async cursor => { cursors.push(cursor); return cursor ? { list: [{ caseId: ID }], nextCursor: null } : { list: [], nextCursor: '50' }; }, unfavorite: async () => false });
  e.page._token = 'A'; await e.page.loadFavoriteState(ID); assert.deepEqual(cursors, [null, '50']); assert.equal(e.page.data.favorite, true);
  e.page.data.caseDetail = { id: ID }; await e.page.toggleFavorite(); assert.equal(e.page.data.favorite, true); assert.match(e.state.calls.at(-1)[1].title, /未保存/);
  e.api.unfavorite = async () => true; await e.page.toggleFavorite(); assert.equal(e.page.data.favorite, false);
  e.state.token = 'B'; e.page.toggleFavorite(); assert.equal(e.page.data.favorite, false);
});

test('收藏分页循环或网络失败保留未知状态，防止误把取消操作变成收藏', async () => {
  const e = environment('pages/library/detail.js', { listFavorites: async () => ({ list: [], nextCursor: '50' }) });
  e.page._token = 'A'; await e.page.loadFavoriteState(ID); assert.equal(e.page.data.favoriteError, true); assert.equal(e.page.data.favoriteLoading, false);
});

test('投稿提交成功直接进入保存的投稿记录，重试复用同一幂等键', async () => {
  const calls = []; let fail = true;
  const e = environment('pages/ai-design/publish.js', { validatePublication: async () => ({ valid: true }), submitForPublication: async (...args) => { calls.push(args); if (fail) throw Error('断网'); return { submissionId: '123' }; } });
  Object.assign(e.state.globalData, { projectId: ID, resultVersionId: VERSION }); e.page.onLoad(); await tick();
  assert.equal(e.page.data.materialCount, 2); assert.equal(e.page.data.versionNumber, 3);
  await e.page.submit(); assert.equal(calls.length, 0);
  await e.page.togglePublicDisplay({ detail: { value: true } });
  await e.page.submit(); assert.equal(e.page.data.submitting, false);
  fail = false; await e.page.submit(); assert.equal(calls[0][2], calls[1][2]);
  assert.equal(e.state.calls.at(-1)[1].url, '/pages/profile/record?type=submissions&id=123');
});

test('投稿不信任空成功响应，身份变化后不跳转到旧账号记录', async () => {
  let resolve; const e = environment('pages/ai-design/publish.js', { validatePublication: async () => ({ valid: true }), submitForPublication: async () => null });
  Object.assign(e.state.globalData, { projectId: ID, resultVersionId: VERSION }); e.page.onLoad(); await tick(); await e.page.togglePublicDisplay({ detail: { value: true } }); await e.page.submit();
  assert.match(e.state.calls.at(-1)[1].title, /未确认保存/);
  e.api.submitForPublication = () => new Promise(r => { resolve = r; }); const pending = e.page.submit();
  e.state.token = 'B'; resolve({ submissionId: '123' }); await pending;
  assert.equal(e.state.calls.filter(c => c[0] === 'redirectTo').length, 0);
});

test('项目行状态：进行中任务优先，其次阶段/结果（UX 整改）', () => {
  const base = { projectId: '123', sourceType: 'SELF_UPLOAD', createdAt: '2026-09-29T05:00:00Z' };
  assert.equal(view.row('projects', { ...base, jobStatus: 'RUNNING' }).status, '生成中');
  assert.equal(view.row('projects', { ...base, jobStatus: 'QUEUED' }).status, '排队中');
  assert.equal(view.row('projects', { ...base, stage: 'FLAT' }).status, '待选择平面方案');
  assert.equal(view.row('projects', { ...base, stage: 'ELEVATION' }).status, '待选择立面方案');
  assert.equal(view.row('projects', { ...base, hasResult: true }).status, '方案已完成');
  const row = view.row('projects', { ...base, coverAssetId: '456' });
  assert.equal(row.coverAssetId, '456'); assert.equal(row.coverUrl, '');
});
