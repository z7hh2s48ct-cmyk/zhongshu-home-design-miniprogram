const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../miniprogram');
const view = require('../miniprogram/utils/record-view');

// UX 2026-10：我的方案滑动/长按删除 + 下拉刷新对标 + 冗余占位文案清理
function environment(api) {
  let definition;
  const state = { token: 'A', calls: [] };
  const deps = {
    '../../utils/access': { protectedPage: value => { definition = value; } },
    '../../utils/api': api,
    '../../utils/request': { getToken: () => state.token },
    '../../utils/record-view': view,
    '../../utils/assets': { fetchAssetDataUrl: async () => '', previewImages: () => {} }
  };
  vm.runInNewContext(fs.readFileSync(path.join(root, 'pages/profile/records.js'), 'utf8'), {
    require: name => { assert.ok(deps[name], name); return deps[name]; },
    wx: new Proxy({}, { get: (target, name) => (args) => {
      if (name === 'showModal' && args && args.success) args.success({ confirm: true });
      state.calls.push([String(name), args]);
    } })
  });
  const page = { ...definition, data: structuredClone(definition.data), setData(value) { Object.assign(this.data, value); } };
  return { page, state };
}

function list(type, api) { const e = environment(api); e.page.onLoad({ type }); return e; }

test('删除手势只挂在「我的方案」上：projects 可删，充值记录等类型不可删', () => {
  const projects = list('projects', {});
  assert.equal(projects.page.data.canDelete, true);
  const orders = list('orders', {});
  assert.equal(orders.page.data.canDelete, false);
  orders.page.confirmDelete({ currentTarget: { dataset: { id: '1' } } });
  assert.equal(orders.state.calls.length, 0, '不可删除类型的确认入口必须直接返回');
});

test('长按/点删除按钮弹确认，确认后调用 DELETE 接口并从列表移除该行', async () => {
  const tick = () => new Promise(resolve => setImmediate(resolve));
  const calls = [];
  const e = list('projects', { listProjects: async () => ({ list: [], nextCursor: null }), deleteProject: async (id) => { calls.push(['delete', id]); return true; } });
  e.page.setData({ rows: [{ id: 'P1', title: '设计方案 · 000001', status: '生成完成', jobStatus: '', url: '' }] });
  e.page.confirmDelete({ currentTarget: { dataset: { id: 'P1' } } });
  await tick();
  assert.deepEqual(calls, [['delete', 'P1']]);
  assert.deepEqual(e.page.data.rows, [], '删除成功后行应从列表移除');
  assert.equal(e.page.data.swipedId, '');
});

test('生成进行中的方案不允许删除，前端直接拦截并提示', () => {
  const calls = [];
  const e = list('projects', { deleteProject: async () => { calls.push('delete'); return true; } });
  e.page.setData({ rows: [{ id: 'P1', title: '设计方案 · 000001', status: 'AI 正在绘制', jobStatus: 'RUNNING', url: '' }] });
  e.page.confirmDelete({ currentTarget: { dataset: { id: 'P1' } } });
  assert.deepEqual(calls, [], '进行中任务不得发起删除请求');
  assert.equal(e.state.calls[0][0], 'showToast');
});

test('横向滑动露出/收起删除按钮；已滑开的行第一次点击收起而不是打开详情', () => {
  const e = list('projects', {});
  const id = 'P1';
  e.page.swipeStart({ touches: [{ clientX: 200, clientY: 300 }], currentTarget: { dataset: { id } } });
  e.page.swipeEnd({ changedTouches: [{ clientX: 120, clientY: 302 }], currentTarget: { dataset: { id } } });
  assert.equal(e.page.data.swipedId, id, '横向位移 80px 应滑开');
  e.page.swipeStart({ touches: [{ clientX: 120, clientY: 300 }], currentTarget: { dataset: { id } } });
  e.page.swipeEnd({ changedTouches: [{ clientX: 190, clientY: 300 }], currentTarget: { dataset: { id } } });
  assert.equal(e.page.data.swipedId, '', '反向滑动应收起');
  // 已滑开状态点击该行：收起并阻止导航
  e.page.setData({ swipedId: id, rows: [{ id, title: 't', status: 's', url: '/x' }] });
  e.page.open({ currentTarget: { dataset: { id } } });
  assert.equal(e.page.data.swipedId, '');
  assert.ok(!e.state.calls.some(([name]) => name === 'navigateTo'), '滑开行的首次点击不应导航');
  // 纵向滑动不触发
  e.page.swipeStart({ touches: [{ clientX: 200, clientY: 300 }], currentTarget: { dataset: { id } } });
  e.page.swipeEnd({ changedTouches: [{ clientX: 196, clientY: 380 }], currentTarget: { dataset: { id } } });
  assert.equal(e.page.data.swipedId, '');
});

test('四页开启下拉刷新，页面内不再保留通用刷新按钮与填充文案', () => {
  for (const p of ['pages/profile/records', 'pages/profile/record', 'pages/messagecenter/messagecenter', 'pages/ai-design/result']) {
    const json = JSON.parse(fs.readFileSync(path.join(root, p + '.json'), 'utf8'));
    assert.equal(json.enablePullDownRefresh, true, p + ' 应开启 enablePullDownRefresh');
  }
  const records = fs.readFileSync(path.join(root, 'pages/profile/records.wxml'), 'utf8');
  assert.ok(!records.includes('记录会自动保存到当前账号'), '列表头部填充文案应删除');
  assert.ok(!records.includes('>刷新<'), '页内刷新按钮应删除（下拉刷新替代）');
  assert.match(records, /bindlongpress="confirmDelete"/);
  assert.match(records, /class="record-delete"/);
  const record = fs.readFileSync(path.join(root, 'pages/profile/record.wxml'), 'utf8');
  assert.ok(!record.includes('刷新状态'), '详情页刷新按钮应删除（下拉刷新替代）');
  const result = fs.readFileSync(path.join(root, 'pages/ai-design/result.wxml'), 'utf8');
  assert.ok(!result.includes('发布前将校核户型库必填参数'), '与投稿页重复的提示应删除');
  const profile = fs.readFileSync(path.join(root, 'pages/profile/index.wxml'), 'utf8');
  for (const filler of ['收藏灵感', '生成记录', '分享作品']) {
    assert.ok(!profile.includes(filler), '我的页占位填充文案应删除：' + filler);
  }
});
