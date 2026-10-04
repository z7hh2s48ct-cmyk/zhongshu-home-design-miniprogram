const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const view = require('../miniprogram/utils/record-view');
function load(file, deps, globals = {}) {
  const ctx = Object.assign({ module: { exports: {} }, require: name => {
    if (!(name in deps)) throw Error('Unexpected dependency ' + name);
    return deps[name];
  } }, globals);
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../miniprogram', file), 'utf8'), ctx);
  return ctx.module.exports;
}

test('生成确认读取最新价格，携带字符串规则 ID 和版本，取消不提交', async () => {
  let total = 26, confirm = true; const prompts = [];
  const price = load('utils/generation-price.js', { './request': { getToken: () => 'owner', isSameSession: () => true }, './api': { getGenerationQuote: async (stage, count, resolution) => ({
    stage, count, resolution, totalPointCost: total, unitPointCost: total / count, ruleId: '2100000000000000001', ruleVersion: 2,
    usageProduct: 'AI_PROMPT', usageRuleId: '2100000000000000002', usageRuleVersion: 5, usagePointCost: 4
  }) } }, { wx: { showModal: options => { prompts.push(options.content); options.success({ confirm }); } } });
  const result = await price.confirm('FLAT', 2);
  assert.equal(result.ruleId, '2100000000000000001'); assert.equal(result.ruleVersion, 2);
  assert.deepEqual(JSON.parse(JSON.stringify(result.usageConfirmation)), { product: 'AI_PROMPT', ruleId: '2100000000000000002', ruleVersion: 5 });
  assert.match(prompts[0], /26/);
  total = 30; confirm = false;
  await assert.rejects(price.confirm('FLAT', 2), error => error.cancelled === true);
  assert.match(prompts[1], /30/);
});

test('数量快速改变时旧报价不能覆盖新报价', async () => {
  const pending = [];
  const price = load('utils/generation-price.js', { './api': { getGenerationQuote: (stage, count, resolution) =>
    new Promise(resolve => pending.push({ resolve, count, stage, resolution })) } });
  const page = { data: { count: 1, resolution: '2K' }, setData(patch) { Object.assign(this.data, patch); } };
  // 2026-10-04 起 refresh 会并行拉取 2K/4K 档位单价：每次 refresh 产生 3 个调用（2K、4K、主报价），
  // 主报价是每组最后一个。先解最新主报价，再解旧主报价，断言旧报价不回写。
  const old = price.refresh(page, 'FLAT'); page.data.count = 4; const latest = price.refresh(page, 'FLAT');
  const resolve = i => pending[i].resolve({ count: pending[i].count, stage: 'FLAT', resolution: pending[i].resolution, totalPointCost: pending[i].count * 7, unitPointCost: 7, ruleId: '1',
    usageProduct: 'AI_PROMPT', usageRuleId: '2', usageRuleVersion: 1, usagePointCost: 1 });
  resolve(5); await latest; resolve(2); await old;
  assert.match(page.data.priceText, /28/);
});

test('恢复按服务端动作定位：选择态收敛到生成页内联，配置/结果各归其位', () => {
  const resume = load('utils/project-resume.js', { './api': {}, './record-view': view,
    './generation-options': require('../miniprogram/utils/generation-options') });
  const project = { projectId: '2100000000000000001', jobId: '2100000000000000003', stage: 'FLAT', requestedCount: 4 };
  assert.match(resume.target({ ...project, resumeAction: 'POLL_JOB' }), /stage=plane&count=4$/);
  // 2026-09-29 决策 2：SELECT_FLAT/SELECT_ELEVATION 与主路径同体验，收敛到 generating 内联候选
  assert.match(resume.target({ ...project, resumeAction: 'SELECT_FLAT' }), /ai-design\/generating\?stage=plane&count=4$/);
  assert.match(resume.target({ ...project, stage: 'ELEVATION', resumeAction: 'SELECT_ELEVATION' }), /ai-design\/generating\?stage=elevation&count=4$/);
  assert.match(resume.target({ ...project, resumeAction: 'CREATE_ELEVATION_JOB' }), /elevation-setup$/);
  assert.match(resume.target({ ...project, resumeAction: 'VIEW_RESULT', resultVersionId: '2100000000000000005' }), /resultVersionId=2100000000000000005$/);
  assert.throws(() => resume.target({ projectId: 2100000000000000001 }));
});

test('消息续页保留关联编号，去重，已读消息仍可打开业务', async () => {
  let page; const cursors = [], navigations = [], receipts = [];
  const message = id => ({ messageId: id, title: '已审核', read: true, bizType: 'case_submission', bizId: '2100000000000000001' });
  load('pages/messagecenter/messagecenter.js', {
    '../../utils/access': { protectedPage: value => { page = value; } },
    '../../utils/record-view': view, '../../utils/format': { shortTime: () => '' },
    '../../utils/unread-count': { setUnreadCount: v => v, decrementUnreadCount: () => 0 },
    '../../utils/api': { getUnreadCount: async () => 0, listMessages: async cursor => {
      cursors.push(cursor); return cursor ? { list: [message('2'), message('1')], nextCursor: null }
        : { list: [message('3'), message('2')], nextCursor: '2' };
    }, markMessageRead: async id => receipts.push(id) }
  }, { wx: { navigateTo: options => navigations.push(options.url) } });
  page.setData = patch => Object.assign(page.data, patch);
  await page.load(); await page.loadMore();
  assert.deepEqual(cursors, [null, '2']);
  assert.equal(page.data.messages.length, 3); assert.equal(page.data.nextCursor, null);
  page.onTap({ currentTarget: { dataset: { id: '1' } } });
  assert.match(navigations[0], /type=submissions&id=2100000000000000001$/); assert.equal(receipts.length, 0);
});
